CREATE UNIQUE INDEX ux_session_participant_session_id
    ON session_participant(session_id, id);

CREATE TABLE waiter_call (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES table_session(id) ON DELETE CASCADE,
    participant_id UUID NOT NULL,
    reason TEXT NOT NULL CHECK (reason IN ('BILL', 'WATER', 'HELP', 'OTHER')),
    text TEXT CHECK (text IS NULL OR length(text) <= 80),
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'EXPIRED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    resolved_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_waiter_call_lifecycle CHECK (
        (status = 'OPEN' AND acknowledged_at IS NULL AND resolved_at IS NULL)
        OR (status = 'ACKNOWLEDGED' AND acknowledged_at IS NOT NULL AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND acknowledged_at IS NOT NULL AND resolved_at IS NOT NULL)
        OR (status = 'EXPIRED' AND resolved_at IS NULL)
    ),
    CONSTRAINT fk_waiter_call_participant_session
        FOREIGN KEY (session_id, participant_id)
        REFERENCES session_participant(session_id, id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_waiter_call_active_session_reason
    ON waiter_call(session_id, reason)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');

CREATE INDEX idx_waiter_call_session_created
    ON waiter_call(session_id, created_at DESC);

CREATE INDEX idx_waiter_call_expiry
    ON waiter_call(expires_at)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');

CREATE INDEX idx_waiter_call_resolved_cooldown
    ON waiter_call(session_id, reason, resolved_at DESC)
    WHERE status = 'RESOLVED';
