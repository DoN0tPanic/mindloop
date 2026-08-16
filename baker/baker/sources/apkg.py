"""Lettura di un export Anki `.apkg`.

Un `.apkg` e' uno zip che contiene un database SQLite. Quale, dipende dalla
versione dell'export:

    collection.anki21b   v3   SQLite compresso con zstd      <- il piu' recente
    collection.anki21    v2   SQLite non compresso
    collection.anki2     v1   SQLite non compresso

**Attenzione al v3.** Un export v3 contiene *anche* un `collection.anki2`, ma
e' uno stub di compatibilita' per i client vecchi e contiene una singola card
fittizia. Aprire quello significa importare un mazzo vuoto senza che nulla
segnali un errore. L'ordine di preferenza qui sotto non e' negoziabile.
"""

from __future__ import annotations

import sqlite3
import zipfile
from pathlib import Path

from .base import RawNote, SourceRead
from ..tmpdir import ScratchDirectory

FIELD_SEP = "\x1f"

# In ordine di preferenza: il primo che c'e', vince.
_DB_CANDIDATES = (
    ("collection.anki21b", True),
    ("collection.anki21", False),
    ("collection.anki2", False),
)


class ApkgError(Exception):
    pass


def _decompress_zstd(data: bytes) -> bytes:
    try:  # Python >= 3.14
        from compression import zstd  # type: ignore

        return zstd.decompress(data)
    except ImportError:
        pass
    try:
        import zstandard  # type: ignore
    except ImportError as exc:  # pragma: no cover
        raise ApkgError(
            "Questo .apkg e' in formato Anki v3 (zstd). Serve Python 3.14+ "
            "oppure il pacchetto `zstandard`."
        ) from exc
    return zstandard.ZstdDecompressor().decompress(data, max_output_size=1 << 30)


def _extract_collection(apkg: Path, workdir: Path) -> Path:
    with zipfile.ZipFile(apkg) as zf:
        names = set(zf.namelist())
        for candidate, compressed in _DB_CANDIDATES:
            if candidate not in names:
                continue
            data = zf.read(candidate)
            if compressed:
                data = _decompress_zstd(data)
            out = workdir / "collection.sqlite"
            out.write_bytes(data)
            return out
    raise ApkgError(f"{apkg.name}: nessun database di collezione nello zip")


def _deck_names(con: sqlite3.Connection) -> dict[int, str]:
    """I nomi dei mazzi, con `\\x1f` come separatore di gerarchia.

    Schema nuovo (Anki 2.1.28+): tabella `decks`. Schema vecchio: un blob
    JSON nella colonna `col.decks`.
    """
    tables = {
        r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }
    if "decks" in tables:
        return {
            r["id"]: r["name"].replace(FIELD_SEP, "::")
            for r in con.execute("SELECT id, name FROM decks")
        }
    import json

    row = con.execute("SELECT decks FROM col LIMIT 1").fetchone()
    if not row or not row[0]:
        return {}
    return {int(k): v.get("name", "") for k, v in json.loads(row[0]).items()}


def read(apkg: str | Path) -> SourceRead:
    """Legge le note di un `.apkg`.

    Il nome del mazzo prevalente diventa il nome proposto per la sezione.

    Assunzione di M0: il campo 0 e' il fronte e il campo 1 il retro. Vale per
    i notetype Basic e derivati, che sono la quasi totalita' dei mazzi
    condivisi. I cloze e i notetype con ordinamento diverso arriveranno con
    il supporto ai cloze; per ora una nota con meno di due campi viene
    saltata invece di produrre una card monca.
    """
    apkg = Path(apkg)
    with ScratchDirectory() as tmp:
        db = _extract_collection(apkg, Path(tmp))
        con = sqlite3.connect(str(db))
        con.row_factory = sqlite3.Row
        try:
            decks = _deck_names(con)
            rows = con.execute(
                "SELECT n.id AS nid, n.flds AS flds,"
                "       (SELECT c.did FROM cards c WHERE c.nid = n.id"
                "        ORDER BY c.ord LIMIT 1) AS did"
                "  FROM notes n ORDER BY n.id"
            ).fetchall()
        finally:
            con.close()

    notes: list[RawNote] = []
    deck_tally: dict[str, int] = {}
    for i, row in enumerate(rows):
        fields = (row["flds"] or "").split(FIELD_SEP)
        if len(fields) < 2:
            continue
        front, back = fields[0], fields[1]
        if not front.strip() or not back.strip():
            continue
        deck = decks.get(row["did"], "")
        deck_tally[deck] = deck_tally.get(deck, 0) + 1
        notes.append(RawNote(front=front, back=back, deck=deck, ord=i))

    if not notes:
        raise ApkgError(
            f"{apkg.name}: nessuna nota utilizzabile. Se l'export e' in "
            "formato v3, controlla che sia stato letto collection.anki21b."
        )

    main_deck = max(deck_tally, key=deck_tally.__getitem__) if deck_tally else ""
    suggested = main_deck.split("::")[-1] or apkg.stem

    warnings: list[str] = []
    skipped = len(rows) - len(notes)
    if skipped:
        warnings.append(
            f"{skipped} note saltate: meno di due campi, oppure fronte o retro "
            f"vuoto. I notetype con ordinamento dei campi diverso da "
            f"Fronte/Retro non sono ancora supportati."
        )
    return SourceRead(notes=notes, suggested_name=suggested, warnings=warnings)
