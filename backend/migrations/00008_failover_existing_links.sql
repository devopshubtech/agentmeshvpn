-- +goose Up
-- +goose StatementBegin
-- Links and pairing codes made before approval and failover existed should also
-- move to another online exit node when their own is offline. Approval stays as
-- it was (off for those links), so nobody is asked to re-approve anything.
UPDATE connect_keys SET pool = true WHERE pool = false;
UPDATE pair_codes SET pool = true WHERE pool = false;
-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
SELECT 1; -- nothing to undo: pool only widens where a link may connect
-- +goose StatementEnd
