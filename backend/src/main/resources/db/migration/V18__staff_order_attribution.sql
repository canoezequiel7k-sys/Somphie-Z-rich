ALTER TABLE customer_order
    ALTER COLUMN participant_id DROP NOT NULL,
    ADD COLUMN created_by_user_id UUID REFERENCES app_user(id);

-- El participante asociado debe pertenecer a la misma sesión, incluso en pedidos creados por staff.
ALTER TABLE customer_order
    DROP CONSTRAINT customer_order_participant_id_fkey,
    ADD CONSTRAINT customer_order_participant_session_fkey
        FOREIGN KEY (session_id, participant_id)
        REFERENCES session_participant(session_id, id);

ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_attribution_check
        CHECK (participant_id IS NOT NULL OR created_by_user_id IS NOT NULL);
