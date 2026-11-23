-- +goose Up
-- +goose StatementBegin
-- Approval and per-phone limits. Each phone that uses a connect key / pairing
-- code is registered here the first time it connects. An admin approves it
-- (or not) and sets how many times it may connect (NULL = unlimited).
ALTER TABLE connect_keys
    ADD COLUMN require_approval     boolean NOT NULL DEFAULT true,
    ADD COLUMN default_max_connects int,
    ADD COLUMN pool                 boolean NOT NULL DEFAULT true;
-- Links that already exist keep working exactly as before.
UPDATE connect_keys SET require_approval = false, pool = false;

ALTER TABLE pair_codes
    ADD COLUMN require_approval     boolean NOT NULL DEFAULT true,
    ADD COLUMN default_max_connects int,
    ADD COLUMN pool                 boolean NOT NULL DEFAULT true;

CREATE TABLE client_devices (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL REFERENCES organizations(id),
    connect_key_id  uuid NOT NULL REFERENCES connect_keys(id) ON DELETE CASCADE,
    client_id       text NOT NULL,
    name            text NOT NULL DEFAULT '',
    ip              text NOT NULL DEFAULT '',
    status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'denied', 'blocked')),
    max_connects    int,
    connects_used   int NOT NULL DEFAULT 0,
    expires_at      timestamptz,
    decided_by      uuid REFERENCES users(id),
    decided_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz,
    last_connect_at timestamptz,
    UNIQUE (connect_key_id, client_id)
);
CREATE INDEX client_devices_org_status_idx ON client_devices (org_id, status, created_at DESC);

ALTER TABLE remote_sessions ADD COLUMN client_device_id uuid REFERENCES client_devices(id) ON DELETE SET NULL;
CREATE INDEX remote_sessions_client_open_idx ON remote_sessions (client_device_id) WHERE status <> 'ended';
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
ALTER TABLE remote_sessions DROP COLUMN IF EXISTS client_device_id;
DROP TABLE IF EXISTS client_devices;
ALTER TABLE pair_codes DROP COLUMN IF EXISTS require_approval, DROP COLUMN IF EXISTS default_max_connects, DROP COLUMN IF EXISTS pool;
ALTER TABLE connect_keys DROP COLUMN IF EXISTS require_approval, DROP COLUMN IF EXISTS default_max_connects, DROP COLUMN IF EXISTS pool;
-- +goose StatementEnd
