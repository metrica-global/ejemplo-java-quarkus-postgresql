-- Schema of the lotes domain. Table names are unqualified: the roles of this service have
-- search_path = lotes (see deploy/db/bootstrap-service.sql).

CREATE TABLE lote (
  id                     uuid          PRIMARY KEY,
  tenant_id              text          NOT NULL,
  descripcion            text          NOT NULL,
  referencia_externa     text,
  pais                   text          NOT NULL CHECK (pais ~ '^[0-9]{3}$'),
  moneda                 text          NOT NULL CHECK (moneda ~ '^[A-Z]{3}$'),
  monto                  numeric(12,2) NOT NULL CHECK (monto > 0),
  cantidad               integer       NOT NULL CHECK (cantidad BETWEEN 1 AND 200000),
  vigencia_hasta         date          NOT NULL,
  estado                 text          NOT NULL
                         CHECK (estado IN ('SOLICITADO', 'EN_GENERACION', 'GENERADO', 'ANULADO', 'FALLIDO')),
  fragmentos_totales     integer       NOT NULL DEFAULT 0 CHECK (fragmentos_totales >= 0),
  fragmentos_completados integer       NOT NULL DEFAULT 0 CHECK (fragmentos_completados >= 0),
  generadas              integer       NOT NULL DEFAULT 0 CHECK (generadas >= 0),
  motivo                 text,
  anulacion_aplicada_en  timestamptz,
  giftcards_anuladas     integer,
  idempotency_key        text          NOT NULL,
  request_hash           text          NOT NULL,
  creado_por             text          NOT NULL,
  creado_en              timestamptz   NOT NULL,
  actualizado_en         timestamptz   NOT NULL,
  version                integer       NOT NULL DEFAULT 0,
  -- A retried POST with the same key must find the first lote instead of creating a second one.
  CONSTRAINT lote_idempotency_uk UNIQUE (tenant_id, idempotency_key),
  -- The database, not only the code, refuses to count more cards than were requested.
  CONSTRAINT lote_generadas_ck CHECK (generadas <= cantidad)
);

CREATE INDEX lote_tenant_creado_idx ON lote (tenant_id, creado_en DESC, id DESC);

-- Progress is a set of fragment numbers. The primary key is what makes a redelivered
-- "fragment generated" event count once.
CREATE TABLE lote_fragmento (
  lote_id     uuid        NOT NULL REFERENCES lote (id) ON DELETE CASCADE,
  fragmento   integer     NOT NULL,
  cantidad    integer     NOT NULL,
  recibido_en timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (lote_id, fragmento)
);
