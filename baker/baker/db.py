"""Il database di lavoro del baker.

Un `work.db` = una raccolta (C9). Gli stadi della pipeline lo attraversano in
ordine, ciascuno scrivendo le proprie colonne: cosi' un errore in `classify`
non costringe a rifare l'ingest, e ogni stadio e' ispezionabile con qualunque
client SQLite.

Lo schema e' un soprainsieme di quello che finira' in `content.sqlite`
(PLAN.md 4.1): qui ci sono anche i campi grezzi, che servono a rieseguire la
normalizzazione senza tornare al file sorgente e non hanno motivo di
viaggiare fino al telefono.
"""

from __future__ import annotations

import sqlite3
import time
from pathlib import Path

SCHEMA_VERSION = 1

_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT
);

CREATE TABLE IF NOT EXISTS sezione (
  uid         TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  src_name    TEXT,
  imported_at INTEGER,
  card_count  INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS card (
  uid         TEXT PRIMARY KEY,
  front_raw   TEXT NOT NULL,
  back_raw    TEXT NOT NULL,
  front       TEXT,
  back        TEXT,
  answer_core TEXT,
  answer_note TEXT,
  core_norm   TEXT,
  answer_type TEXT,
  blanks      INTEGER DEFAULT 0,
  source_ord  INTEGER
);

CREATE TABLE IF NOT EXISTS card_sezione (
  card_uid    TEXT NOT NULL REFERENCES card(uid) ON DELETE CASCADE,
  sezione_uid TEXT NOT NULL REFERENCES sezione(uid) ON DELETE CASCADE,
  PRIMARY KEY (card_uid, sezione_uid)
);

CREATE INDEX IF NOT EXISTS idx_card_sezione_sez ON card_sezione(sezione_uid);

-- I distrattori cotti. La provenienza `sibling` prevista da C5 non compare
-- mai qui: quei distrattori nascono a runtime sul telefono pescando dai
-- vicini, e non hanno motivo di essere materializzati.
CREATE TABLE IF NOT EXISTS distractor (
  card_uid    TEXT NOT NULL REFERENCES card(uid) ON DELETE CASCADE,
  text        TEXT NOT NULL,
  origin      TEXT NOT NULL,          -- rule | llm
  rule        TEXT,                   -- quale regola l'ha prodotto
  quality     REAL,                   -- 0..1
  gen_version TEXT NOT NULL,
  PRIMARY KEY (card_uid, text)
);

-- Vicini semantici precalcolati, in classifica.
CREATE TABLE IF NOT EXISTS neighbor (
  card_uid  TEXT NOT NULL REFERENCES card(uid) ON DELETE CASCADE,
  other_uid TEXT NOT NULL REFERENCES card(uid) ON DELETE CASCADE,
  rank      INTEGER NOT NULL,
  sim       REAL NOT NULL,
  PRIMARY KEY (card_uid, rank)
);

-- "La risposta di other_uid NON puo' essere mostrata come opzione sbagliata
--  per card_uid, perche' e' anch'essa corretta." Vedi C10.
CREATE TABLE IF NOT EXISTS exclusion (
  card_uid  TEXT NOT NULL REFERENCES card(uid) ON DELETE CASCADE,
  other_uid TEXT NOT NULL REFERENCES card(uid) ON DELETE CASCADE,
  reason    TEXT,                     -- same_core | contained | overlap | user_burned
  PRIMARY KEY (card_uid, other_uid)
);
"""


def open_db(path: str | Path) -> sqlite3.Connection:
    con = sqlite3.connect(str(path))
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys = ON")
    con.executescript(_SCHEMA)
    return con


def set_meta(con: sqlite3.Connection, key: str, value: str) -> None:
    con.execute(
        "INSERT INTO meta(key, value) VALUES(?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (key, value),
    )


def get_meta(con: sqlite3.Connection, key: str, default: str | None = None):
    row = con.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
    return row["value"] if row else default


def delete_meta(con: sqlite3.Connection, key: str) -> None:
    con.execute("DELETE FROM meta WHERE key = ?", (key,))


def ensure_raccolta(con: sqlite3.Connection, name: str | None, uid: str) -> str:
    """Crea la raccolta se il work.db e' nuovo, altrimenti la lascia stare.

    Il nome si aggiorna solo se esplicitamente fornito: reimportare una
    sezione non deve rinominare la raccolta di destinazione.
    """
    existing = get_meta(con, "raccolta_uid")
    if existing is None:
        set_meta(con, "raccolta_uid", uid)
        set_meta(con, "raccolta_name", name or "Senza nome")
        set_meta(con, "schema_version", str(SCHEMA_VERSION))
        set_meta(con, "created_at", str(int(time.time())))
        return uid
    if name:
        set_meta(con, "raccolta_name", name)
    return existing


def add_sezione(
    con: sqlite3.Connection, uid: str, name: str, src_name: str | None
) -> None:
    con.execute(
        "INSERT INTO sezione(uid, name, src_name, imported_at, card_count) "
        "VALUES(?, ?, ?, ?, 0)",
        (uid, name, src_name, int(time.time())),
    )


def refresh_card_counts(con: sqlite3.Connection) -> None:
    con.execute(
        "UPDATE sezione SET card_count = ("
        "  SELECT COUNT(*) FROM card_sezione WHERE sezione_uid = sezione.uid)"
    )
