CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL REFERENCES restaurant(id),
    email TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    token_version BIGINT NOT NULL DEFAULT 0 CHECK (token_version >= 0),
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    email_verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_app_user_email ON app_user (lower(email));
CREATE INDEX idx_app_user_restaurant ON app_user (restaurant_id);

CREATE TABLE user_role (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_code TEXT NOT NULL REFERENCES role(code),
    PRIMARY KEY (user_id, role_code)
);

CREATE TABLE user_station (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    station_id UUID NOT NULL REFERENCES station(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, station_id)
);

CREATE TABLE refresh_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);

CREATE TABLE auth_login_attempt (
    attempt_key CHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    failure_count INTEGER NOT NULL CHECK (failure_count > 0),
    locked_until TIMESTAMP WITH TIME ZONE
);
