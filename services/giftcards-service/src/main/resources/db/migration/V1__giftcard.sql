-- Schema of the giftcards domain. Table names are unqualified: the roles of this service have
-- search_path = giftcards (see deploy/db/bootstrap-service.sql).

CREATE TABLE giftcard (
  id             uuid          PRIMARY KEY,
  tenant_id      text          NOT NULL,
  lote_id        uuid,
  secuencia      integer,
  pais           text          NOT NULL CHECK (pais ~ '^[0-9]{3}$'),
  moneda         text          NOT NULL CHECK (moneda ~ '^[A-Z]{3}$'),
  monto          numeric(12,2) NOT NULL CHECK (monto > 0),
  vigencia_hasta date          NOT NULL,
  estado         text          NOT NULL CHECK (estado IN ('GENERADA', 'ACTIVA', 'ANULADA')),
  -- The code is never stored in the clear: ciphertext, blind index and mask.
  codigo_cifrado bytea         NOT NULL,
  codigo_indice  bytea         NOT NULL,
  codigo_mascara text          NOT NULL,
  cvv_hash       text          NOT NULL,
  motivo         text,
  activada_en    timestamptz,
  anulada_en     timestamptz,
  creado_en      timestamptz   NOT NULL,
  actualizado_en timestamptz   NOT NULL,
  version        integer       NOT NULL DEFAULT 0,
  -- No two cards share a code, across every tenant and lote. The database enforces it on the
  -- blind index, without ever seeing a code.
  CONSTRAINT giftcard_codigo_uk UNIQUE (codigo_indice),
  -- One card per position of a lote: a fragment stored twice would violate this.
  CONSTRAINT giftcard_lote_secuencia_uk UNIQUE (lote_id, secuencia),
  CONSTRAINT giftcard_lote_ck CHECK ((lote_id IS NULL) = (secuencia IS NULL))
);

CREATE INDEX giftcard_tenant_lote_idx ON giftcard (tenant_id, lote_id, secuencia) WHERE lote_id IS NOT NULL;
CREATE INDEX giftcard_tenant_creado_idx ON giftcard (tenant_id, creado_en DESC, id DESC);

-- What this domain knows about a lote it generates. The descriptive columns are null for a
-- tombstone: an annulment that arrived before the request.
CREATE TABLE plan_generacion (
  lote_id          uuid          PRIMARY KEY,
  tenant_id        text          NOT NULL,
  pais             text,
  moneda           text,
  monto            numeric(12,2),
  cantidad         integer       NOT NULL DEFAULT 0,
  vigencia_hasta   date,
  estado           text          NOT NULL CHECK (estado IN ('EN_CURSO', 'ANULADO', 'FALLIDO')),
  total_fragmentos integer       NOT NULL DEFAULT 0,
  creado_en        timestamptz   NOT NULL DEFAULT now(),
  actualizado_en   timestamptz   NOT NULL DEFAULT now(),
  anulado_en       timestamptz
);

CREATE TABLE plan_fragmento (
  lote_id         uuid        NOT NULL REFERENCES plan_generacion (lote_id) ON DELETE CASCADE,
  numero          integer     NOT NULL,
  desde_secuencia integer     NOT NULL,
  cantidad        integer     NOT NULL CHECK (cantidad > 0),
  estado          text        NOT NULL DEFAULT 'PENDIENTE' CHECK (estado IN ('PENDIENTE', 'GENERADO', 'FALLIDO')),
  motivo          text,
  terminado_en    timestamptz,
  PRIMARY KEY (lote_id, numero)
);

-- For the operator: which lotes still have work to do, and since when.
CREATE INDEX plan_fragmento_pendiente_idx ON plan_fragmento (lote_id) WHERE estado = 'PENDIENTE';
