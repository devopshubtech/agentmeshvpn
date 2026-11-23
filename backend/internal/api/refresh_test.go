package api

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/enfec/agentmesh/backend/internal/platform/httpx"
	"github.com/enfec/agentmesh/backend/internal/platform/ratelimit"
)

// reader that hides its length, like a proxied request (ContentLength == -1).
type unknownLen struct{ io.Reader }

func refreshStatus(t *testing.T, mutate func(*http.Request)) int {
	t.Helper()
	s := &Server{refreshIP: ratelimit.New(60, 30)}
	req := httptest.NewRequest(http.MethodPost, "/v1/auth/refresh", nil)
	mutate(req)
	rec := httptest.NewRecorder()
	httpx.H(s.refresh).ServeHTTP(rec, req)
	return rec.Code
}

// A session-less refresh must be 401 ("no session"), never 400 "request body is empty",
// however the proxy in front reports the body length.
func TestRefreshWithoutTokenIs401(t *testing.T) {
	cases := map[string]func(*http.Request){
		"no body":              func(r *http.Request) {},
		"unknown length empty": func(r *http.Request) { r.Body = io.NopCloser(unknownLen{strings.NewReader("")}); r.ContentLength = -1 },
		"empty json header": func(r *http.Request) {
			r.Header.Set("Content-Type", "application/json")
			r.Body = io.NopCloser(unknownLen{strings.NewReader("")})
			r.ContentLength = -1
		},
		"whitespace body":    func(r *http.Request) { r.Body = io.NopCloser(strings.NewReader(" \n")); r.ContentLength = 2 },
		"json without token": func(r *http.Request) { r.Body = io.NopCloser(strings.NewReader(`{}`)); r.ContentLength = 2 },
		"empty cookie":       func(r *http.Request) { r.AddCookie(&http.Cookie{Name: refreshCookie, Value: ""}) },
	}
	for name, m := range cases {
		if got := refreshStatus(t, m); got != http.StatusUnauthorized {
			t.Errorf("%s: status %d, want 401", name, got)
		}
	}
}

func TestRefreshInvalidInput(t *testing.T) {
	// cookie without the CSRF header: forbidden
	if got := refreshStatus(t, func(r *http.Request) { r.AddCookie(&http.Cookie{Name: refreshCookie, Value: "x"}) }); got != http.StatusForbidden {
		t.Errorf("cookie w/o csrf: %d, want 403", got)
	}
	// garbage JSON and unknown fields stay 400
	for name, body := range map[string]string{"garbage": `{nope`, "unknown field": `{"x":1}`} {
		got := refreshStatus(t, func(r *http.Request) {
			r.Body = io.NopCloser(strings.NewReader(body))
			r.ContentLength = int64(len(body))
		})
		if got != http.StatusBadRequest {
			t.Errorf("%s: %d, want 400", name, got)
		}
	}
}
