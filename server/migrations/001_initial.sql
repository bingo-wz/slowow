BEGIN;

CREATE TABLE manman_user_state (
    owner_id text PRIMARY KEY,
    last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= 0)
);

CREATE TABLE manman_records (
    owner_id text NOT NULL,
    kind text NOT NULL,
    record_id text NOT NULL,
    payload jsonb NOT NULL,
    deleted boolean NOT NULL DEFAULT false,
    version bigint NOT NULL CHECK (version > 0),
    sequence bigint NOT NULL CHECK (sequence > 0),
    updated_at_ms bigint NOT NULL,
    PRIMARY KEY (owner_id, kind, record_id),
    UNIQUE (owner_id, sequence)
);

CREATE INDEX manman_records_sync_idx ON manman_records(owner_id, sequence);

CREATE TABLE manman_operations (
    owner_id text NOT NULL,
    operation_id text NOT NULL,
    request_hash text NOT NULL,
    response jsonb NOT NULL,
    PRIMARY KEY (owner_id, operation_id)
);

ALTER TABLE manman_user_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE manman_user_state FORCE ROW LEVEL SECURITY;
ALTER TABLE manman_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE manman_records FORCE ROW LEVEL SECURITY;
ALTER TABLE manman_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE manman_operations FORCE ROW LEVEL SECURITY;

CREATE POLICY manman_user_state_owner ON manman_user_state
    USING (owner_id = current_setting('app.user_id', true))
    WITH CHECK (owner_id = current_setting('app.user_id', true));
CREATE POLICY manman_records_owner ON manman_records
    USING (owner_id = current_setting('app.user_id', true))
    WITH CHECK (owner_id = current_setting('app.user_id', true));
CREATE POLICY manman_operations_owner ON manman_operations
    USING (owner_id = current_setting('app.user_id', true))
    WITH CHECK (owner_id = current_setting('app.user_id', true));

REVOKE ALL ON manman_user_state, manman_records, manman_operations FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO manman_app;
GRANT SELECT, INSERT, UPDATE ON manman_user_state, manman_records, manman_operations TO manman_app;

COMMIT;
