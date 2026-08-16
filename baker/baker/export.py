"""Scrittura del `.qzd`.

Uno zip con dentro `manifest.json` e `content.sqlite`. Il `content.sqlite`
non e' il `work.db`: e' la sua proiezione su cio' che il telefono usa
davvero. I campi grezzi, le colonne di debug e la cronologia degli stadi
restano sul desktop.
"""

from __future__ import annotations

import json
import sqlite3
import time
import zipfile
from collections import Counter
from pathlib import Path

from . import db as dbmod
from .rules import COVERED_TYPES, GEN_VERSION
from .tmpdir import ScratchDirectory

SCHEMA_VERSION = 1
BAKER_VERSION = "0.1.0"

_CONTENT_SCHEMA = """
CREATE TABLE card (
  uid         TEXT PRIMARY KEY,
  front       TEXT NOT NULL,
  back        TEXT NOT NULL,
  answer_core TEXT NOT NULL,
  answer_note TEXT,
  core_norm   TEXT NOT NULL,
  answer_type TEXT NOT NULL,
  blanks      INTEGER DEFAULT 0,
  source_ord  INTEGER
);
CREATE TABLE sezione (
  uid TEXT PRIMARY KEY, name TEXT NOT NULL, src_name TEXT,
  imported_at INTEGER, card_count INTEGER
);
CREATE TABLE card_sezione (
  card_uid TEXT NOT NULL, sezione_uid TEXT NOT NULL,
  PRIMARY KEY (card_uid, sezione_uid)
);
CREATE TABLE distractor (
  card_uid TEXT NOT NULL, text TEXT NOT NULL, origin TEXT NOT NULL,
  quality REAL, gen_version TEXT NOT NULL,
  PRIMARY KEY (card_uid, text)
);
CREATE TABLE neighbor (
  card_uid TEXT NOT NULL, other_uid TEXT NOT NULL,
  rank INTEGER NOT NULL, sim REAL NOT NULL,
  PRIMARY KEY (card_uid, rank)
);
CREATE TABLE exclusion (
  card_uid TEXT NOT NULL, other_uid TEXT NOT NULL, reason TEXT,
  PRIMARY KEY (card_uid, other_uid)
);
CREATE INDEX idx_distractor_card ON distractor(card_uid);
CREATE INDEX idx_neighbor_card   ON neighbor(card_uid);
CREATE INDEX idx_exclusion_card  ON exclusion(card_uid);
CREATE INDEX idx_card_sezione    ON card_sezione(sezione_uid);
"""


def coverage(con: sqlite3.Connection) -> dict[str, int]:
    """Come sono coperte le card: da regole, da LLM, o solo dal campionamento.

    E' il numero da guardare per capire se la pipeline sta lavorando. Molte
    card in `sibling_only` non sono un errore -- i termini stanno li' per
    scelta -- ma se ci finiscono numeri e date, una regola non ha funzionato.
    """
    counts = Counter()
    rows = con.execute(
        "SELECT c.uid,"
        "       EXISTS(SELECT 1 FROM distractor d WHERE d.card_uid = c.uid) AS has_pool,"
        "       EXISTS(SELECT 1 FROM distractor d"
        "                WHERE d.card_uid = c.uid AND d.origin = 'llm') AS has_llm"
        "  FROM card c"
    )
    for row in rows:
        if not row["has_pool"]:
            counts["sibling_only"] += 1
        elif row["has_llm"]:
            counts["llm"] += 1
        else:
            counts["rule"] += 1
    return dict(counts)


def uncovered_by_rules(con: sqlite3.Connection) -> list[sqlite3.Row]:
    """Card di un tipo che una regola dovrebbe coprire, ma senza distrattori.

    Ogni riga qui e' un difetto del motore a regole, non una scelta.
    """
    placeholders = ",".join("?" * len(COVERED_TYPES))
    return con.execute(
        f"SELECT uid, answer_type, answer_core FROM card"
        f" WHERE answer_type IN ({placeholders})"
        f"   AND uid NOT IN (SELECT card_uid FROM distractor WHERE origin = 'rule')",
        tuple(sorted(COVERED_TYPES)),
    ).fetchall()


def write_qzd(work_db: str | Path, out_path: str | Path, lang: str = "") -> dict:
    src = dbmod.open_db(work_db)
    out_path = Path(out_path)
    llm_model = dbmod.get_meta(src, "llm_model")
    llm = None
    if llm_model:
        llm = {
            "model": llm_model,
            "temperature": float(dbmod.get_meta(src, "llm_temperature", "0.8")),
            "seed": int(dbmod.get_meta(src, "llm_seed", "42")),
        }

    manifest = {
        "schema_version": SCHEMA_VERSION,
        "raccolta": {
            "uid": dbmod.get_meta(src, "raccolta_uid", ""),
            "name": dbmod.get_meta(src, "raccolta_name", ""),
        },
        "sezioni": [
            {
                "uid": r["uid"],
                "name": r["name"],
                "cards": r["card_count"],
                "src": r["src_name"],
            }
            for r in src.execute(
                "SELECT uid, name, card_count, src_name FROM sezione ORDER BY imported_at"
            )
        ],
        "lang": lang,
        "card_count": src.execute("SELECT COUNT(*) FROM card").fetchone()[0],
        "baked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "baker_version": BAKER_VERSION,
        "gen_version": GEN_VERSION,
        "llm": llm,
        "coverage": coverage(src),
    }

    with ScratchDirectory() as tmp:
        content_path = Path(tmp) / "content.sqlite"
        dst = sqlite3.connect(str(content_path))
        dst.executescript(_CONTENT_SCHEMA)

        for table, columns in (
            ("card", "uid, front, back, answer_core, answer_note, core_norm,"
                     " answer_type, blanks, source_ord"),
            ("sezione", "uid, name, src_name, imported_at, card_count"),
            ("card_sezione", "card_uid, sezione_uid"),
            ("distractor", "card_uid, text, origin, quality, gen_version"),
            ("neighbor", "card_uid, other_uid, rank, sim"),
            ("exclusion", "card_uid, other_uid, reason"),
        ):
            rows = src.execute(f"SELECT {columns} FROM {table}").fetchall()
            if not rows:
                continue
            marks = ",".join("?" * len(columns.split(",")))
            dst.executemany(
                f"INSERT INTO {table} ({columns}) VALUES ({marks})",
                [tuple(r) for r in rows],
            )

        dst.commit()
        dst.execute("VACUUM")
        dst.close()
        src.close()

        out_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False))
            zf.write(content_path, "content.sqlite")

    manifest["_size"] = out_path.stat().st_size
    return manifest
