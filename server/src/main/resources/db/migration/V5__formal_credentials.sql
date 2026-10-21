-- Credentials are deliberately separate from public/profile account queries.
CREATE TABLE account_credential (
  user_id uuid PRIMARY KEY REFERENCES user_account(id),
  username varchar(32) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9_]{3,32}$'),
  password_hash varchar(512) NOT NULL,
  password_change_required boolean NOT NULL DEFAULT false,
  session_version bigint NOT NULL DEFAULT 1 CHECK (session_version > 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE auth_attempt_window (
  bucket_key char(64) PRIMARY KEY,
  expires_at timestamptz NOT NULL,
  attempts integer NOT NULL CHECK (attempts > 0)
);
CREATE INDEX auth_attempt_expiry ON auth_attempt_window(expires_at);
