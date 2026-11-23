-- +goose Up
-- +goose StatementBegin
-- The name the person typed when they first opened the app (shown to the admin).
ALTER TABLE client_devices ADD COLUMN person_name text NOT NULL DEFAULT '';
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
ALTER TABLE client_devices DROP COLUMN IF EXISTS person_name;
-- +goose StatementEnd
