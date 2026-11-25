-- Id source. Starts at 62^5 so the first short code is 6 characters long ("100000").
-- CACHE 50: each app instance grabs 50 ids at a time, so nextval is rarely a network round trip.
-- Gaps after a restart are harmless.
CREATE SEQUENCE IF NOT EXISTS url_seq START WITH 916132832 INCREMENT BY 1 CACHE 50;

CREATE TABLE IF NOT EXISTS url_mapping (
    id           BIGINT        PRIMARY KEY,
    short_code   VARCHAR(11)   NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL,
    expires_at   TIMESTAMPTZ
);

-- The hot read path: redirect looks up by short_code.
CREATE UNIQUE INDEX IF NOT EXISTS idx_url_mapping_short_code ON url_mapping (short_code);
