//go:build integration

package sessions

import (
	"context"
	"crypto/ed25519"
	"errors"
	"log/slog"
	"os"
	"testing"

	"github.com/google/uuid"

	"github.com/enfec/agentmesh/backend/internal/auth"
	"github.com/enfec/agentmesh/backend/internal/devices"
	"github.com/enfec/agentmesh/backend/internal/platform/bus"
	"github.com/enfec/agentmesh/backend/internal/platform/db"
	"github.com/enfec/agentmesh/backend/migrations"
)

// Run with a throwaway Postgres and NATS:
//
//	AM_TEST_DB=postgres://postgres:x@localhost:55432/postgres AM_TEST_NATS=nats://localhost:54222 \
//	  go test -tags integration ./backend/internal/sessions -run TestAccessFlow -v
func TestAccessFlow(t *testing.T) {
	dbURL, natsURL := os.Getenv("AM_TEST_DB"), os.Getenv("AM_TEST_NATS")
	if dbURL == "" || natsURL == "" {
		t.Skip("AM_TEST_DB / AM_TEST_NATS not set")
	}
	ctx := context.Background()
	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	pool, err := db.Connect(ctx, dbURL)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if err := migrations.Up(ctx, pool, log); err != nil {
		t.Fatal(err)
	}
	b, err := bus.Connect(natsURL, "test", log)
	if err != nil {
		t.Fatal(err)
	}
	_, priv, _ := ed25519.GenerateKey(nil)
	svc := NewService(pool, b, devices.NewService(pool, b), priv)

	org := uuid.MustParse("00000000-0000-7000-8000-000000000001")
	user := uuid.Must(uuid.NewV7())
	mustExec := func(q string, args ...any) {
		t.Helper()
		if _, err := pool.Exec(ctx, q, args...); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	mustExec(`INSERT INTO users (id, org_id, email, password_hash) VALUES ($1, $2, $3, 'x')`, user, org, "t-"+user.String()+"@example.com")
	newNode := func(name string, online bool) uuid.UUID {
		id := uuid.Must(uuid.NewV7())
		conn := "offline"
		if online {
			conn = "online"
		}
		mustExec(`INSERT INTO devices (id, org_id, name, status, connectivity, capabilities) VALUES ($1, $2, $3, 'active', $4, '{exit_node}')`,
			id, org, name+"-"+id.String()[:8], conn)
		return id
	}
	nodeA, nodeB := newNode("mac-mini", true), newNode("windows", true)

	// A link that needs approval and gives each phone 2 connects.
	key, _ := newConnectSecret()
	keyID := uuid.Must(uuid.NewV7())
	mustExec(`INSERT INTO connect_keys (id, org_id, device_id, key_hash, label, created_by, require_approval, default_max_connects, pool)
		VALUES ($1, $2, $3, $4, 'test', $5, true, 2, true)`, keyID, org, nodeA, HashTicket(key), user)

	open := func(clientID string, reconnect bool) (*OpenResult, error) {
		return svc.OpenWithKey(ctx, OpenInput{Key: key, ClientID: clientID, ClientLabel: "Pixel", ClientName: "Ravi", IP: "203.0.113.9", RelayURL: "wss://x/v1/relay", Reconnect: reconnect})
	}
	admin := devices.Actor{P: &auth.Principal{UserID: user, OrgID: org, Email: "admin@example.com", SessionID: uuid.New()}}

	// 1. An unknown phone waits for approval.
	r, err := open("phone-1", false)
	if err != nil || r.Pending == nil {
		t.Fatalf("want pending, got %+v err=%v", r, err)
	}
	pendingID := r.Pending.ID
	if r.Pending.PersonName != "Ravi" || r.Pending.Name != "Pixel" {
		t.Fatalf("want person Ravi on device Pixel, got %q on %q", r.Pending.PersonName, r.Pending.Name)
	}
	// Asking again does not create a second request.
	if r, err = open("phone-1", false); err != nil || r.Pending == nil || r.Pending.ID != pendingID {
		t.Fatalf("want same pending request, got %+v err=%v", r, err)
	}
	list, _ := svc.ListClients(ctx, org, AccessPending)
	if len(list) != 1 {
		t.Fatalf("want 1 pending, got %d", len(list))
	}

	// 2. Approved with 2 connects: first connect goes through on the key's own node.
	two := 2
	if _, err := svc.Decide(ctx, admin, pendingID, Decision{Action: "approve", MaxConnects: &two}); err != nil {
		t.Fatal(err)
	}
	r, err = open("phone-1", false)
	if err != nil || r.Created == nil || r.Created.Session.DeviceID != nodeA {
		t.Fatalf("want session on node A, got %+v err=%v", r, err)
	}
	if r.ConnectsLeft == nil || *r.ConnectsLeft != 1 {
		t.Fatalf("want 1 connect left, got %v", r.ConnectsLeft)
	}

	// 3. Node A goes off: the phone's automatic reconnect moves to node B and is free.
	mustExec(`UPDATE devices SET connectivity = 'offline' WHERE id = $1`, nodeA)
	r, err = open("phone-1", true)
	if err != nil || r.Created == nil || r.Created.Session.DeviceID != nodeB {
		t.Fatalf("want failover to node B, got %+v err=%v", r, err)
	}
	if r.ConnectsLeft == nil || *r.ConnectsLeft != 1 {
		t.Fatalf("a reconnect must not use a connect; left=%v", r.ConnectsLeft)
	}

	// 4. A new user-started connect uses the last one; the next is refused.
	if r, err = open("phone-1", false); err != nil || r.Created == nil || *r.ConnectsLeft != 0 {
		t.Fatalf("want last connect, got %+v err=%v", r, err)
	}
	if _, err = open("phone-1", false); !errors.Is(err, ErrLimitReached) {
		t.Fatalf("want ErrLimitReached, got %v", err)
	}

	// 5. If every node is off, the connect is refused and not counted.
	mustExec(`UPDATE devices SET connectivity = 'offline' WHERE id = $1`, nodeB)
	three := 3
	if _, err := svc.Decide(ctx, admin, pendingID, Decision{Action: "approve", MaxConnects: &three}); err != nil {
		t.Fatal(err)
	}
	if _, err = open("phone-1", false); !errors.Is(err, ErrNoExitNode) {
		t.Fatalf("want ErrNoExitNode when all nodes are offline, got %v", err)
	}
	var used int
	if err := pool.QueryRow(ctx, `SELECT connects_used FROM client_devices WHERE id = $1`, pendingID).Scan(&used); err != nil || used != 0 {
		t.Fatalf("a failed connect must not use the allowance; used=%d err=%v", used, err)
	}

	// 6. Blocked phones are refused; unlimited approval has no limit.
	if _, err := svc.Decide(ctx, admin, pendingID, Decision{Action: "block"}); err != nil {
		t.Fatal(err)
	}
	if _, err = open("phone-1", false); !errors.Is(err, ErrAccessDenied) {
		t.Fatalf("want ErrAccessDenied, got %v", err)
	}
	mustExec(`UPDATE devices SET connectivity = 'online' WHERE id = $1`, nodeB)
	if _, err := svc.Decide(ctx, admin, pendingID, Decision{Action: "approve"}); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 5; i++ {
		if r, err = open("phone-1", false); err != nil || r.Created == nil || r.ConnectsLeft != nil {
			t.Fatalf("unlimited connect %d failed: %+v err=%v", i, r, err)
		}
	}

	// 7. An old app (no client id) cannot use a link that needs approval.
	if _, err = open("", false); !errors.Is(err, ErrClientIDRequired) {
		t.Fatalf("want ErrClientIDRequired, got %v", err)
	}

	// 8. "4 of 5 left" and asking for more. A fresh phone with an allowance of 2.
	two2 := 2
	other := "phone-2"
	r2, err := open(other, false)
	if err != nil || r2.Pending == nil {
		t.Fatalf("phone-2 want pending, got %+v err=%v", r2, err)
	}
	phone2 := r2.Pending.ID
	if _, err := svc.Decide(ctx, admin, phone2, Decision{Action: "approve", MaxConnects: &two2}); err != nil {
		t.Fatal(err)
	}
	if r2, err = open(other, false); err != nil || r2.ConnectsMax == nil || *r2.ConnectsMax != 2 || *r2.ConnectsLeft != 1 {
		t.Fatalf("want 1 of 2 left, got %+v err=%v", r2, err)
	}
	st, err := svc.StatusWithKey(ctx, key, other)
	if err != nil || st.Status != AccessApproved || *st.ConnectsLeft != 1 || *st.MaxConnects != 2 {
		t.Fatalf("status want approved 1 of 2, got %+v err=%v", st, err)
	}
	if st, err = svc.StatusWithKey(ctx, key, "never-seen"); err != nil || st.Status != "new" {
		t.Fatalf("unknown phone want status new, got %+v err=%v", st, err)
	}
	if st, err = svc.RequestMore(ctx, key, other, "please, trip next week", "", "203.0.113.9"); err != nil || !st.MoreRequested {
		t.Fatalf("request more: %+v err=%v", st, err)
	}
	reqs, _ := svc.ListClients(ctx, org, "requests")
	found := false
	for _, c := range reqs {
		if c.ID == phone2 && c.MoreRequestedAt != nil && c.MoreMessage == "please, trip next week" {
			found = true
		}
	}
	if !found {
		t.Fatalf("the more-request should be listed under requests, got %+v", reqs)
	}
	three2 := 3
	if _, err := svc.Decide(ctx, admin, phone2, Decision{Action: "add", AddConnects: &three2}); err != nil {
		t.Fatal(err)
	}
	if st, err = svc.StatusWithKey(ctx, key, other); err != nil || *st.MaxConnects != 5 || *st.ConnectsLeft != 4 || st.MoreRequested {
		t.Fatalf("after adding 3: want 4 of 5 left and no pending request, got %+v err=%v", st, err)
	}
	// A phone that was never approved cannot ask for more.
	if _, err := svc.RequestMore(ctx, key, "never-seen", "", "", ""); !errors.Is(err, ErrUnknownClient) {
		t.Fatalf("want ErrUnknownClient, got %v", err)
	}
}
