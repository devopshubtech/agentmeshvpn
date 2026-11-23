package sessions

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/enfec/agentmesh/backend/internal/audit"
	"github.com/enfec/agentmesh/backend/internal/devices"
	"github.com/enfec/agentmesh/backend/internal/platform/bus"
	"github.com/enfec/agentmesh/backend/internal/platform/db"
	"github.com/enfec/agentmesh/backend/internal/platform/httpx"
	"github.com/enfec/agentmesh/backend/internal/platform/textutil"
)

// Access control for phones that use a connect key or pairing code.
//
// Every phone is registered the first time it connects (by a stable client id
// the app generates). With approval required, it waits as "pending" and the
// admin gets an alert; once approved it may connect as many times as the
// admin allowed (nil = unlimited). A "connect" is one user-started
// connection; automatic reconnects (Wi-Fi to mobile data, switching to
// another exit node) do not use up the allowance.

const (
	AccessPending  = "pending"
	AccessApproved = "approved"
	AccessDenied   = "denied"
	AccessBlocked  = "blocked"

	// EventAccessRequest is sent when a new phone asks to connect;
	// EventAccessUpdated when an admin changes a phone's access.
	EventAccessRequest = "access.request"
	EventAccessUpdated = "access.updated"
	// EventAccessMore is sent when an approved phone asks for more connections.
	EventAccessMore = "access.more"

	// reconnectGrace: a reconnect this soon after the last connect is free.
	reconnectGrace = 15 * time.Minute
	maxConnectsCap = 100000
)

var (
	// ErrClientIDRequired: an old app that cannot take part in approval.
	ErrClientIDRequired = errors.New("client id required")
	ErrAccessDenied     = errors.New("access denied")
	ErrAccessExpired    = errors.New("access expired")
	ErrLimitReached     = errors.New("connect limit reached")
	ErrUnknownClient    = errors.New("unknown client")
	// ErrNoExitNode: every exit node this phone may use is offline right now.
	ErrNoExitNode = errors.New("no exit node online")
)

// Rule is the default treatment of new phones on a link or pairing code.
type Rule struct {
	// RequireApproval: new phones wait for an admin (default true).
	RequireApproval *bool `json:"require_approval"`
	// MaxConnects is the starting allowance per phone; nil = unlimited.
	MaxConnects *int `json:"default_max_connects"`
	// Pool lets the phone move to any other online exit node when its own
	// is offline (default true).
	Pool *bool `json:"pool"`
}

func (r Rule) approval() bool { return r.RequireApproval == nil || *r.RequireApproval }
func (r Rule) pool() bool     { return r.Pool == nil || *r.Pool }
func (r Rule) validate() error {
	if r.MaxConnects != nil && (*r.MaxConnects < 1 || *r.MaxConnects > maxConnectsCap) {
		return httpx.Validation{"default_max_connects": "must be between 1 and 100000, or empty for unlimited"}.Err()
	}
	return nil
}

// ClientDevice is a registered phone (API representation).
type ClientDevice struct {
	ID             uuid.UUID  `json:"id"`
	ConnectKeyID   uuid.UUID  `json:"connect_key_id"`
	DeviceID       uuid.UUID  `json:"device_id"`
	DeviceName     string     `json:"device_name"`
	KeyLabel       string     `json:"key_label"`
	Name           string     `json:"name"`
	IP             string     `json:"ip"`
	Status         string     `json:"status"`
	MaxConnects    *int       `json:"max_connects"`
	ConnectsUsed   int        `json:"connects_used"`
	ConnectsLeft   *int       `json:"connects_left"`
	ExpiresAt      *time.Time `json:"expires_at"`
	CreatedAt      time.Time  `json:"created_at"`
	LastSeenAt     *time.Time `json:"last_seen_at"`
	LastConnectAt  *time.Time `json:"last_connect_at"`
	DecidedAt      *time.Time `json:"decided_at"`
	ActiveSessions int        `json:"active_sessions"`
	// MoreRequestedAt is set while the phone is asking for more connections.
	MoreRequestedAt *time.Time `json:"more_requested_at"`
	MoreMessage     string     `json:"more_message"`
	// PersonName is the name the person typed when they first opened the app.
	PersonName string `json:"person_name"`
	// OnlineSince is when the phone's current connection started (nil = not connected now);
	// OfflineSince is when its last connection ended (nil while connected or never connected).
	OnlineSince  *time.Time `json:"online_since"`
	OfflineSince *time.Time `json:"offline_since"`
}

const selectClient = `
	SELECT c.id, c.connect_key_id, k.device_id, d.name, k.label, c.name, c.ip, c.status, c.max_connects, c.connects_used,
	       c.expires_at, c.created_at, c.last_seen_at, c.last_connect_at, c.decided_at,
	       (SELECT count(*) FROM remote_sessions s WHERE s.client_device_id = c.id AND s.status = 'active'),
	       c.more_requested_at, c.more_message, c.person_name,
	       (SELECT min(s.started_at) FROM remote_sessions s WHERE s.client_device_id = c.id AND s.status = 'active'),
	       (SELECT max(s.ended_at) FROM remote_sessions s WHERE s.client_device_id = c.id AND s.ended_at IS NOT NULL
	          AND NOT EXISTS (SELECT 1 FROM remote_sessions a WHERE a.client_device_id = c.id AND a.status = 'active'))
	FROM client_devices c
	JOIN connect_keys k ON k.id = c.connect_key_id
	JOIN devices d ON d.id = k.device_id`

func scanClient(row pgx.Row) (*ClientDevice, error) {
	var c ClientDevice
	err := row.Scan(&c.ID, &c.ConnectKeyID, &c.DeviceID, &c.DeviceName, &c.KeyLabel, &c.Name, &c.IP, &c.Status, &c.MaxConnects,
		&c.ConnectsUsed, &c.ExpiresAt, &c.CreatedAt, &c.LastSeenAt, &c.LastConnectAt, &c.DecidedAt, &c.ActiveSessions,
		&c.MoreRequestedAt, &c.MoreMessage, &c.PersonName, &c.OnlineSince, &c.OfflineSince)
	if c.MaxConnects != nil {
		left := max(*c.MaxConnects-c.ConnectsUsed, 0)
		c.ConnectsLeft = &left
	}
	return &c, err
}

// OpenInput is what a phone sends to open a session.
type OpenInput struct {
	Key         string
	ClientID    string
	ClientLabel string
	// ClientName is the person's own name (asked once when the app is first opened).
	ClientName string
	IP         string
	RequestID  string
	RelayURL   string
	// Reconnect is true when the app is re-opening a connection it already
	// had (network change, switching nodes); it does not use up the allowance.
	Reconnect bool
}

// OpenResult is either a session (Created) or a request still waiting for
// the admin (Pending).
type OpenResult struct {
	Created *Created
	Pending *ClientDevice
	// ConnectsLeft is what remains of the phone's allowance (nil = unlimited).
	ConnectsLeft *int
	// ConnectsMax is the phone's whole allowance (nil = unlimited).
	ConnectsMax *int
}

// OpenWithKey opens an exit-node session using a connect key (no login),
// applying approval, the connect allowance and exit-node failover.
func (s *Service) OpenWithKey(ctx context.Context, in OpenInput) (*OpenResult, error) {
	if !strings.HasPrefix(in.Key, connectKeyPrefix) || len(in.Key) > 128 {
		return nil, ErrInvalidConnectKey
	}
	var (
		id, orgID, deviceID, creator uuid.UUID
		label, creatorStatus         string
		expires, revoked             *time.Time
		requireApproval, pool        bool
		defaultMax                   *int
	)
	err := s.pool.QueryRow(ctx, `
		SELECT k.id, k.org_id, k.device_id, k.created_by, k.label, k.expires_at, k.revoked_at, u.status,
		       k.require_approval, k.default_max_connects, k.pool
		FROM connect_keys k JOIN users u ON u.id = k.created_by WHERE k.key_hash = $1`, HashTicket(in.Key)).
		Scan(&id, &orgID, &deviceID, &creator, &label, &expires, &revoked, &creatorStatus, &requireApproval, &defaultMax, &pool)
	if db.IsNoRows(err) {
		return nil, ErrInvalidConnectKey
	}
	if err != nil {
		return nil, err
	}
	if revoked != nil || (expires != nil && time.Now().After(*expires)) || creatorStatus != "active" {
		return nil, ErrInvalidConnectKey
	}

	clientID := textutil.Clean(in.ClientID, 80)
	var (
		client  *ClientDevice
		counted bool
	)
	if clientID == "" {
		if requireApproval {
			return nil, ErrClientIDRequired
		}
	} else {
		var inserted bool
		client, inserted, err = s.registerClient(ctx, orgID, id, clientID, textutil.Clean(in.ClientLabel, 100), textutil.Clean(in.ClientName, 60), in.IP, requireApproval, defaultMax)
		if err != nil {
			return nil, err
		}
		if inserted && client.Status == AccessPending {
			_ = db.InTx(ctx, s.pool, func(tx pgx.Tx) error {
				return audit.Record(ctx, tx, audit.Event{
					OrgID: orgID, RequestID: in.RequestID, ActorType: audit.ActorSystem, ActorLabel: "connect-key:" + label,
					ActorIP: in.IP, Action: "access.request", TargetType: "device", TargetID: &deviceID,
					Details: map[string]any{"client_device_id": client.ID, "name": client.Name},
				})
			})
			s.bus.PublishEvent(orgID, EventAccessRequest, client)
		}
		switch client.Status {
		case AccessDenied, AccessBlocked:
			return nil, ErrAccessDenied
		case AccessPending:
			return &OpenResult{Pending: client}, nil
		}
		if client.ExpiresAt != nil && time.Now().After(*client.ExpiresAt) {
			return nil, ErrAccessExpired
		}
		free := in.Reconnect && client.LastConnectAt != nil && time.Since(*client.LastConnectAt) < reconnectGrace
		if !free {
			counted = true
			var used int
			err := s.pool.QueryRow(ctx, `
				UPDATE client_devices SET connects_used = connects_used + 1
				WHERE id = $1 AND status = 'approved' AND (max_connects IS NULL OR connects_used < max_connects)
				RETURNING connects_used`, client.ID).Scan(&used)
			if db.IsNoRows(err) {
				return nil, ErrLimitReached
			}
			if err != nil {
				return nil, err
			}
			client.ConnectsUsed = used
		}
	}

	// The key's own exit node first, then (if allowed) the least busy other
	// online ones, so the phone keeps working when one node is off.
	cands := []uuid.UUID{deviceID}
	if pool {
		rows, err := s.pool.Query(ctx, `
			SELECT d.id FROM devices d WHERE d.org_id = $1 AND d.status = 'active' AND d.id <> $2
			ORDER BY (SELECT count(*) FROM remote_sessions s WHERE s.device_id = d.id AND s.status <> 'ended'), d.name`, orgID, deviceID)
		if err == nil {
			others, cerr := pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
			if cerr == nil {
				cands = append(cands, others...)
			}
		}
	}
	req := requester{
		OrgID: orgID, UserID: creator, ConnectKeyID: &id, ActorType: audit.ActorSystem,
		ActorLabel: "connect-key:" + label, RequestID: in.RequestID, IP: in.IP, ClientLabel: in.ClientLabel,
	}
	if client != nil {
		req.ClientDeviceID = &client.ID
	}
	var (
		c        *Created
		firstErr error
	)
	for _, did := range cands {
		if c, err = s.create(ctx, req, did, in.RelayURL); err == nil {
			break
		}
		if firstErr == nil {
			firstErr = err
		}
	}
	if c == nil {
		if counted {
			_, _ = s.pool.Exec(ctx, `UPDATE client_devices SET connects_used = greatest(connects_used - 1, 0) WHERE id = $1`, client.ID)
		}
		// Every node was tried and none could take the phone because they are offline
		// (or do not allow exit sessions): tell the phone to wait, not that it failed.
		var he *httpx.Error
		if errors.As(firstErr, &he) && he.Code == "invalid_state" && !strings.HasPrefix(he.Message, "too many") {
			return nil, ErrNoExitNode
		}
		return nil, firstErr
	}
	_, _ = s.pool.Exec(ctx, `UPDATE connect_keys SET uses = uses + 1, last_used_at = now() WHERE id = $1`, id)
	res := &OpenResult{Created: c}
	if client != nil {
		_, _ = s.pool.Exec(ctx, `UPDATE client_devices SET last_connect_at = now() WHERE id = $1`, client.ID)
		if client.MaxConnects != nil {
			left := max(*client.MaxConnects-client.ConnectsUsed, 0)
			res.ConnectsLeft = &left
			res.ConnectsMax = client.MaxConnects
		}
	}
	return res, nil
}

// registerClient finds or creates the phone's record for this key.
func (s *Service) registerClient(ctx context.Context, orgID, keyID uuid.UUID, clientID, name, person, ip string, requireApproval bool, defaultMax *int) (*ClientDevice, bool, error) {
	status := AccessApproved
	if requireApproval {
		status = AccessPending
	}
	var (
		id       uuid.UUID
		inserted bool
	)
	err := s.pool.QueryRow(ctx, `
		INSERT INTO client_devices (id, org_id, connect_key_id, client_id, name, person_name, ip, status, max_connects, last_seen_at, decided_at)
		VALUES ($1, $2, $3, $4, $5, $9, $6, $7, $8, now(), CASE WHEN $7 = 'approved' THEN now() END)
		ON CONFLICT (connect_key_id, client_id) DO UPDATE
		   SET last_seen_at = now(), ip = EXCLUDED.ip,
		       name = CASE WHEN client_devices.name = '' THEN EXCLUDED.name ELSE client_devices.name END,
		       person_name = CASE WHEN EXCLUDED.person_name <> '' THEN EXCLUDED.person_name ELSE client_devices.person_name END
		RETURNING id, (xmax = 0)`,
		uuid.Must(uuid.NewV7()), orgID, keyID, clientID, name, ip, status, defaultMax, person).Scan(&id, &inserted)
	if err != nil {
		return nil, false, err
	}
	c, err := scanClient(s.pool.QueryRow(ctx, selectClient+` WHERE c.id = $1`, id))
	return c, inserted, err
}

// ListClients returns registered phones, newest first. status "" = all.
func (s *Service) ListClients(ctx context.Context, orgID uuid.UUID, status string) ([]ClientDevice, error) {
	rows, err := s.pool.Query(ctx, selectClient+`
		WHERE c.org_id = $1 AND (
		      $2 = ''
		   OR ($2 = 'requests' AND (c.status = 'pending' OR c.more_requested_at IS NOT NULL))
		   OR ($2 <> 'requests' AND c.status = $2))
		ORDER BY coalesce(c.more_requested_at, c.created_at) DESC LIMIT 500`, orgID, status)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ClientDevice{}
	for rows.Next() {
		c, err := scanClient(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *c)
	}
	return out, rows.Err()
}

// Decision is an admin's answer for a phone.
type Decision struct {
	Action      string // approve | deny | block | add | dismiss
	MaxConnects *int   // approve: allowance from now on; nil = unlimited
	AddConnects *int   // add: connections to add to the phone's allowance
	ExpiresInS  int    // approve: access ends after this many seconds; 0 = never
	Name        *string
}

// Decide approves, denies or blocks a phone. Approving (again) starts a fresh
// allowance, so it is also how an admin gives a phone more connects.
func (s *Service) Decide(ctx context.Context, a devices.Actor, id uuid.UUID, d Decision) (*ClientDevice, error) {
	if d.MaxConnects != nil && (*d.MaxConnects < 1 || *d.MaxConnects > maxConnectsCap) {
		return nil, httpx.Validation{"max_connects": "must be between 1 and 100000, or empty for unlimited"}.Err()
	}
	if d.ExpiresInS < 0 || d.ExpiresInS > 3650*86400 {
		return nil, httpx.Validation{"expires_in_s": "must be between 0 (never) and 10 years"}.Err()
	}
	var name *string
	if d.Name != nil {
		n := textutil.Clean(*d.Name, 100)
		name = &n
	}
	var (
		out  *ClientDevice
		live []uuid.UUID
	)
	err := db.InTx(ctx, s.pool, func(tx pgx.Tx) error {
		var tag string
		switch d.Action {
		case "approve":
			var exp *time.Time
			if d.ExpiresInS > 0 {
				t := time.Now().Add(time.Duration(d.ExpiresInS) * time.Second)
				exp = &t
			}
			err := tx.QueryRow(ctx, `
				UPDATE client_devices SET status = 'approved', max_connects = $3, connects_used = 0, expires_at = $4,
				       name = coalesce($5, name), decided_by = $6, decided_at = now(), more_requested_at = NULL, more_message = ''
				WHERE id = $1 AND org_id = $2 RETURNING id::text`, id, a.P.OrgID, d.MaxConnects, exp, name, a.P.UserID).Scan(&tag)
			if db.IsNoRows(err) {
				return httpx.NotFound("device")
			}
			if err != nil {
				return err
			}
		case "add":
			if d.AddConnects == nil || *d.AddConnects < 1 || *d.AddConnects > maxConnectsCap {
				return httpx.Validation{"add_connects": "must be between 1 and 100000"}.Err()
			}
			err := tx.QueryRow(ctx, `
				UPDATE client_devices SET max_connects = CASE WHEN max_connects IS NULL THEN NULL ELSE max_connects + $3 END,
				       decided_by = $4, decided_at = now(), more_requested_at = NULL, more_message = ''
				WHERE id = $1 AND org_id = $2 AND status = 'approved' RETURNING id::text`, id, a.P.OrgID, *d.AddConnects, a.P.UserID).Scan(&tag)
			if db.IsNoRows(err) {
				return httpx.NotFound("approved device")
			}
			if err != nil {
				return err
			}
		case "dismiss":
			err := tx.QueryRow(ctx, `
				UPDATE client_devices SET more_requested_at = NULL, more_message = ''
				WHERE id = $1 AND org_id = $2 RETURNING id::text`, id, a.P.OrgID).Scan(&tag)
			if db.IsNoRows(err) {
				return httpx.NotFound("device")
			}
			if err != nil {
				return err
			}
		case "deny", "block":
			status := AccessDenied
			if d.Action == "block" {
				status = AccessBlocked
			}
			err := tx.QueryRow(ctx, `
				UPDATE client_devices SET status = $3, name = coalesce($4, name), decided_by = $5, decided_at = now()
				WHERE id = $1 AND org_id = $2 RETURNING id::text`, id, a.P.OrgID, status, name, a.P.UserID).Scan(&tag)
			if db.IsNoRows(err) {
				return httpx.NotFound("device")
			}
			if err != nil {
				return err
			}
			rows, err := tx.Query(ctx, `SELECT id FROM remote_sessions WHERE client_device_id = $1 AND status <> 'ended'`, id)
			if err != nil {
				return err
			}
			if live, err = pgx.CollectRows(rows, pgx.RowTo[uuid.UUID]); err != nil {
				return err
			}
		default:
			return httpx.Validation{"action": "must be approve, add, dismiss, deny or block"}.Err()
		}
		c, err := scanClient(tx.QueryRow(ctx, selectClient+` WHERE c.id = $1`, id))
		if err != nil {
			return err
		}
		out = c
		details := map[string]any{"client_device_id": id, "name": c.Name, "key_label": c.KeyLabel}
		if d.Action == "approve" {
			details["max_connects"] = d.MaxConnects
			details["expires_in_s"] = d.ExpiresInS
		}
		if d.Action == "add" {
			details["add_connects"] = d.AddConnects
		}
		return audit.Record(ctx, tx, audit.Event{
			OrgID: a.P.OrgID, RequestID: a.RequestID, ActorType: audit.ActorUser, ActorID: &a.P.UserID, ActorLabel: a.P.Email,
			ActorIP: a.IP, SessionID: &a.P.SessionID, Action: "access." + d.Action, TargetType: "device", TargetID: &c.DeviceID,
			Details: details,
		})
	})
	if err != nil {
		return nil, err
	}
	for _, sid := range live {
		s.bus.PublishJSON(bus.RelayKillSubject(sid), map[string]string{"reason": "terminated"})
	}
	s.bus.PublishEvent(a.P.OrgID, EventAccessUpdated, out)
	return out, nil
}

// DeleteClient forgets a phone (it must ask again the next time) and ends its sessions.
func (s *Service) DeleteClient(ctx context.Context, a devices.Actor, id uuid.UUID) error {
	var live []uuid.UUID
	err := db.InTx(ctx, s.pool, func(tx pgx.Tx) error {
		c, err := scanClient(tx.QueryRow(ctx, selectClient+` WHERE c.id = $1 AND c.org_id = $2`, id, a.P.OrgID))
		if db.IsNoRows(err) {
			return httpx.NotFound("device")
		}
		if err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT id FROM remote_sessions WHERE client_device_id = $1 AND status <> 'ended'`, id)
		if err != nil {
			return err
		}
		if live, err = pgx.CollectRows(rows, pgx.RowTo[uuid.UUID]); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM client_devices WHERE id = $1`, id); err != nil {
			return err
		}
		return audit.Record(ctx, tx, audit.Event{
			OrgID: a.P.OrgID, RequestID: a.RequestID, ActorType: audit.ActorUser, ActorID: &a.P.UserID, ActorLabel: a.P.Email,
			ActorIP: a.IP, SessionID: &a.P.SessionID, Action: "access.delete", TargetType: "device", TargetID: &c.DeviceID,
			Details: map[string]any{"client_device_id": id, "name": c.Name},
		})
	})
	if err != nil {
		return err
	}
	for _, sid := range live {
		s.bus.PublishJSON(bus.RelayKillSubject(sid), map[string]string{"reason": "terminated"})
	}
	return nil
}

// ClientStatus is what a phone may ask about itself.
type ClientStatus struct {
	// Status is new (never asked), pending, approved, denied or blocked.
	Status       string `json:"status"`
	DeviceName   string `json:"device_name"`
	MaxConnects  *int   `json:"max_connects"`
	ConnectsUsed int    `json:"connects_used"`
	ConnectsLeft *int   `json:"connects_left"`
	// MoreRequested is true while a request for more connections is waiting.
	MoreRequested bool       `json:"more_requested"`
	ExpiresAt     *time.Time `json:"expires_at"`
}

type keyInfo struct {
	ID, OrgID, DeviceID uuid.UUID
	DeviceName          string
	DefaultMax          *int
	RequireApproval     bool
}

// lookupKey validates a connect key (same rules as opening a session).
func (s *Service) lookupKey(ctx context.Context, key string) (*keyInfo, error) {
	if !strings.HasPrefix(key, connectKeyPrefix) || len(key) > 128 {
		return nil, ErrInvalidConnectKey
	}
	var (
		k             keyInfo
		expires, rev  *time.Time
		creatorStatus string
	)
	err := s.pool.QueryRow(ctx, `
		SELECT k.id, k.org_id, k.device_id, d.name, k.default_max_connects, k.require_approval, k.expires_at, k.revoked_at, u.status
		FROM connect_keys k JOIN users u ON u.id = k.created_by JOIN devices d ON d.id = k.device_id
		WHERE k.key_hash = $1`, HashTicket(key)).
		Scan(&k.ID, &k.OrgID, &k.DeviceID, &k.DeviceName, &k.DefaultMax, &k.RequireApproval, &expires, &rev, &creatorStatus)
	if db.IsNoRows(err) {
		return nil, ErrInvalidConnectKey
	}
	if err != nil {
		return nil, err
	}
	if rev != nil || (expires != nil && time.Now().After(*expires)) || creatorStatus != "active" {
		return nil, ErrInvalidConnectKey
	}
	return &k, nil
}

func (s *Service) clientByKey(ctx context.Context, keyID uuid.UUID, clientID string) (*ClientDevice, error) {
	c, err := scanClient(s.pool.QueryRow(ctx, selectClient+` WHERE c.connect_key_id = $1 AND c.client_id = $2`, keyID, textutil.Clean(clientID, 80)))
	if db.IsNoRows(err) {
		return nil, ErrUnknownClient
	}
	return c, err
}

func statusOf(k *keyInfo, c *ClientDevice) *ClientStatus {
	if c == nil {
		st := &ClientStatus{Status: "new", DeviceName: k.DeviceName}
		if !k.RequireApproval {
			st.MaxConnects = k.DefaultMax
			if k.DefaultMax != nil {
				left := *k.DefaultMax
				st.ConnectsLeft = &left
			}
		}
		return st
	}
	return &ClientStatus{Status: c.Status, DeviceName: k.DeviceName, MaxConnects: c.MaxConnects, ConnectsUsed: c.ConnectsUsed,
		ConnectsLeft: c.ConnectsLeft, MoreRequested: c.MoreRequestedAt != nil, ExpiresAt: c.ExpiresAt}
}

// StatusWithKey tells a phone where it stands (for "4 of 5 connections left").
// It never registers the phone and never counts as a connect.
func (s *Service) StatusWithKey(ctx context.Context, key, clientID string) (*ClientStatus, error) {
	k, err := s.lookupKey(ctx, key)
	if err != nil {
		return nil, err
	}
	c, err := s.clientByKey(ctx, k.ID, clientID)
	if errors.Is(err, ErrUnknownClient) {
		return statusOf(k, nil), nil
	}
	if err != nil {
		return nil, err
	}
	return statusOf(k, c), nil
}

// RequestMore lets an approved phone ask the owner for more connections.
func (s *Service) RequestMore(ctx context.Context, key, clientID, message, person, ip string) (*ClientStatus, error) {
	k, err := s.lookupKey(ctx, key)
	if err != nil {
		return nil, err
	}
	c, err := s.clientByKey(ctx, k.ID, clientID)
	if err != nil {
		return nil, err
	}
	if c.Status != AccessApproved {
		return nil, ErrAccessDenied
	}
	// Asking again within a minute does not alert the owner twice.
	fresh := c.MoreRequestedAt == nil || time.Since(*c.MoreRequestedAt) > time.Minute
	if _, err := s.pool.Exec(ctx, `UPDATE client_devices SET more_requested_at = CASE WHEN $3 THEN now() ELSE more_requested_at END,
		more_message = $2, person_name = CASE WHEN $4 <> '' THEN $4 ELSE person_name END WHERE id = $1`,
		c.ID, textutil.Clean(message, 200), fresh, textutil.Clean(person, 60)); err != nil {
		return nil, err
	}
	c, err = s.clientByKey(ctx, k.ID, clientID)
	if err != nil {
		return nil, err
	}
	if fresh {
		_ = db.InTx(ctx, s.pool, func(tx pgx.Tx) error {
			return audit.Record(ctx, tx, audit.Event{
				OrgID: k.OrgID, ActorType: audit.ActorSystem, ActorLabel: "connect-key:" + c.KeyLabel, ActorIP: ip,
				Action: "access.more_requested", TargetType: "device", TargetID: &k.DeviceID,
				Details: map[string]any{"client_device_id": c.ID, "name": c.Name, "used": c.ConnectsUsed, "max": c.MaxConnects},
			})
		})
		s.bus.PublishEvent(k.OrgID, EventAccessMore, c)
	}
	return statusOf(k, c), nil
}
