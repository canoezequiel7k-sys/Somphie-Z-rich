-- Mantiene el ETag del menú entre reinicios; el valor inicial conserva la versión publicada hasta ahora.
ALTER TABLE restaurant
    ADD COLUMN catalog_version BIGINT NOT NULL DEFAULT 2 CHECK (catalog_version >= 1);
