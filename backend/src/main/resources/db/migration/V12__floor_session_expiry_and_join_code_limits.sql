CREATE INDEX idx_open_session_last_activity
    ON table_session (last_activity_at)
    WHERE status = 'OPEN';

CREATE TABLE join_code_attempt (
    table_id UUID NOT NULL REFERENCES dining_table(id) ON DELETE CASCADE,
    client_key CHAR(64) NOT NULL,
    failure_count SMALLINT NOT NULL DEFAULT 0 CHECK (failure_count BETWEEN 0 AND 5),
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    blocked_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (table_id, client_key)
);
