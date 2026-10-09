-- Evenly V2. Only what a person chose to share lives here: no bank, card, account or text.
CREATE TABLE IF NOT EXISTS users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text UNIQUE NOT NULL,
  name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash text PRIMARY KEY,           -- sha256 of the token; the token itself is never stored
  user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS groups (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  emoji text NOT NULL DEFAULT '👥',
  code text UNIQUE NOT NULL,             -- invite code
  created_by uuid NOT NULL REFERENCES users,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS members (
  group_id uuid NOT NULL REFERENCES groups ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  joined_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, user_id)
);
-- A spend someone shared. id comes from the phone, so sending twice (offline retry) is harmless.
CREATE TABLE IF NOT EXISTS shares (
  id uuid PRIMARY KEY,
  group_id uuid NOT NULL REFERENCES groups ON DELETE CASCADE,
  payer_id uuid NOT NULL REFERENCES users,
  amount_paise bigint NOT NULL CHECK (amount_paise > 0),
  title text NOT NULL,
  note text,
  spent_at bigint NOT NULL,              -- ms since epoch, when it was paid
  deleted boolean NOT NULL DEFAULT false,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS share_parts (
  share_id uuid NOT NULL REFERENCES shares ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users,
  paise bigint NOT NULL CHECK (paise > 0),
  PRIMARY KEY (share_id, user_id)
);
CREATE TABLE IF NOT EXISTS settlements (
  id uuid PRIMARY KEY,
  group_id uuid NOT NULL REFERENCES groups ON DELETE CASCADE,
  from_id uuid NOT NULL REFERENCES users,  -- paid back
  to_id uuid NOT NULL REFERENCES users,
  paise bigint NOT NULL CHECK (paise > 0),
  created_by uuid NOT NULL REFERENCES users,
  deleted boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS shares_group ON shares (group_id);
CREATE INDEX IF NOT EXISTS parts_user ON share_parts (user_id);
CREATE INDEX IF NOT EXISTS settle_group ON settlements (group_id);
-- "Let me in" from someone with the code. The admin (groups.created_by) approves or denies.
CREATE TABLE IF NOT EXISTS join_requests (
  group_id uuid NOT NULL REFERENCES groups ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (group_id, user_id)
);
