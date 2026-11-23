package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"

	"github.com/enfec/agentmesh/backend/internal/auth"
	"github.com/enfec/agentmesh/backend/internal/platform/httpx"
	"github.com/enfec/agentmesh/backend/internal/sessions"
)

// GET /v1/access?status=pending — phones that asked to connect (admin app).
func (s *Server) listAccess(w http.ResponseWriter, r *http.Request) error {
	p, err := s.require(r, auth.PermDevicesManage)
	if err != nil {
		return err
	}
	status := r.URL.Query().Get("status")
	switch status {
	case "", "requests", sessions.AccessPending, sessions.AccessApproved, sessions.AccessDenied, sessions.AccessBlocked:
	default:
		return httpx.Validation{"status": "must be requests, pending, approved, denied or blocked"}.Err()
	}
	items, err := s.RemoteSessions.ListClients(r.Context(), p.OrgID, status)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, httpx.List[sessions.ClientDevice]{Items: items})
	return nil
}

// POST /v1/access/{id}/approve|add|dismiss|deny|block
// add body: {"add_connects": 5} gives that many more connections (keeps the count used so far).
// dismiss clears a phone's request for more without changing its allowance.
// approve body: {"max_connects": 5 | null (unlimited), "expires_in_s": 0, "name": "Ravi's phone"}
func (s *Server) decideAccess(w http.ResponseWriter, r *http.Request) error {
	p, err := s.require(r, auth.PermDevicesManage)
	if err != nil {
		return err
	}
	id, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	var in struct {
		MaxConnects *int    `json:"max_connects"`
		AddConnects *int    `json:"add_connects"`
		ExpiresInS  int     `json:"expires_in_s"`
		Name        *string `json:"name"`
	}
	if r.ContentLength != 0 {
		if err := httpx.DecodeJSON(w, r, &in); err != nil {
			return err
		}
	}
	c, err := s.RemoteSessions.Decide(r.Context(), s.actor(r, p), id, sessions.Decision{
		Action: chi.URLParam(r, "op"), MaxConnects: in.MaxConnects, AddConnects: in.AddConnects, ExpiresInS: in.ExpiresInS, Name: in.Name,
	})
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, c)
	return nil
}

// DELETE /v1/access/{id} — forget the phone; it must ask again.
func (s *Server) deleteAccess(w http.ResponseWriter, r *http.Request) error {
	p, err := s.require(r, auth.PermDevicesManage)
	if err != nil {
		return err
	}
	id, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	if err := s.RemoteSessions.DeleteClient(r.Context(), s.actor(r, p), id); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
