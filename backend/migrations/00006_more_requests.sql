-- +goose Up
-- +goose StatementBegin
-- A phone that used its connections can ask the owner for more.
ALTER TABLE client_devices
    ADD COLUMN more_requested_at timestamptz,
    ADD COLUMN more_message      text NOT NULL DEFAULT '';
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
ALTER TABLE client_devices DROP COLUMN IF EXISTS more_requested_at, DROP COLUMN IF EXISTS more_message;
-- +goose StatementEnd
