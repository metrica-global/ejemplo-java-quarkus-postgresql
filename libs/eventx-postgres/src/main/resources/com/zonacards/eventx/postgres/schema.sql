-- Canonical schema of the eventx tables. Every service copies it into its own Flyway
-- migrations (each service has its own schema, so each has its own outbox and inbox).

CREATE TABLE outbox (
  id               uuid        PRIMARY KEY,
  destination_kind text        NOT NULL CHECK (destination_kind IN ('TOPIC', 'QUEUE')),
  destination      text        NOT NULL,
  message_type     text        NOT NULL,
  tenant_id        text        NOT NULL,
  payload          text        NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  available_at     timestamptz NOT NULL DEFAULT now(),
  claimed_until    timestamptz,
  attempts         integer     NOT NULL DEFAULT 0,
  last_error       text,
  published_at     timestamptz,
  parked_at        timestamptz
);

-- The relay only looks at unpublished rows. A partial index keeps that lookup small and fast
-- no matter how many published rows are waiting to be purged.
CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX outbox_published_idx ON outbox (published_at) WHERE published_at IS NOT NULL;

CREATE TABLE inbox (
  consumer    text        NOT NULL,
  message_id  text        NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, message_id)
);

CREATE INDEX inbox_received_idx ON inbox (received_at);
