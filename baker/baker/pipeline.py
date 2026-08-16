"""Gli stadi della pipeline: ingest, normalize, classify.

Ogni stadio e' idempotente e rieseguibile: `normalize` ricalcola sempre tutte
le colonne derivate a partire dai campi grezzi, `classify` a partire dal
nucleo. Rieseguire un intero stadio dopo averne cambiato l'euristica e'
l'operazione normale, non un caso limite.
"""

from __future__ import annotations

import sqlite3
from collections import Counter
from pathlib import Path
from typing import NamedTuple

from . import classify as classify_mod
from . import db as dbmod
from . import llm as llm_mod
from . import relations
from . import rules
from . import validate as validate_mod
from .gloss import split_gloss
from .sources import apkg as apkg_src
from .sources import delimited as delimited_src
from .textnorm import cnorm, display_norm, norm_for_compare
from .uid import card_uid, container_uid

LLM_MAX_ATTEMPTS = 3


class IngestResult(NamedTuple):
    sezione_uid: str
    sezione_name: str
    read: int
    inserted: int
    shared: int          # card gia' presenti, provenienti da un'altra sezione
    duplicated_in_file: int
    warnings: list[str]


def _read_source(path: Path):
    suffix = path.suffix.lower()
    if suffix == ".apkg":
        return apkg_src.read(path)
    if suffix in (".csv", ".tsv", ".txt"):
        return delimited_src.read(path)
    raise ValueError(f"formato non riconosciuto: {suffix or path.name}")


def ingest(
    source: str | Path,
    work_db: str | Path,
    sezione_name: str | None = None,
    raccolta_name: str | None = None,
) -> IngestResult:
    """Importa un file come nuova sezione della raccolta in `work_db`."""
    source = Path(source)
    src = _read_source(source)
    notes = src.notes
    name = sezione_name or src.suggested_name

    con = dbmod.open_db(work_db)
    with con:
        dbmod.ensure_raccolta(con, raccolta_name, container_uid())
        sez_uid = container_uid()
        dbmod.add_sezione(con, sez_uid, name, source.name)

        inserted = shared = dup_in_file = 0
        seen: set[str] = set()
        for note in notes:
            uid = card_uid(note.front)
            if uid in seen:
                # Stessa domanda due volte nello stesso file: e' una sola
                # card. Vince la prima occorrenza.
                dup_in_file += 1
                continue
            seen.add(uid)

            cur = con.execute(
                "INSERT INTO card(uid, front_raw, back_raw, source_ord) "
                "VALUES(?, ?, ?, ?) ON CONFLICT(uid) DO NOTHING",
                (uid, note.front, note.back, note.ord),
            )
            if cur.rowcount:
                inserted += 1
            else:
                # La card esiste gia': appartiene a un'altra sezione della
                # stessa raccolta. Non si duplica e non si sovrascrive, si
                # aggiunge soltanto l'appartenenza (PLAN.md 4.1).
                shared += 1
            con.execute(
                "INSERT INTO card_sezione(card_uid, sezione_uid) VALUES(?, ?) "
                "ON CONFLICT DO NOTHING",
                (uid, sez_uid),
            )
        dbmod.refresh_card_counts(con)
    con.close()

    return IngestResult(
        sezione_uid=sez_uid,
        sezione_name=name,
        read=len(notes),
        inserted=inserted,
        shared=shared,
        duplicated_in_file=dup_in_file,
        warnings=list(src.warnings),
    )


def normalize(work_db: str | Path) -> Counter:
    """Ricalcola testo visualizzabile, nucleo, glossa e `core_norm`."""
    con = dbmod.open_db(work_db)
    stats: Counter = Counter()
    with con:
        rows = con.execute("SELECT uid, front_raw, back_raw FROM card").fetchall()
        for row in rows:
            front = display_norm(row["front_raw"])
            back = display_norm(row["back_raw"])
            core, note = split_gloss(back)
            con.execute(
                "UPDATE card SET front = ?, back = ?, answer_core = ?, "
                "answer_note = ?, core_norm = ? WHERE uid = ?",
                (front, back, core, note, cnorm(core), row["uid"]),
            )
            stats["cards"] += 1
            if note:
                stats["with_gloss"] += 1
            if not core:
                stats["empty_core"] += 1
    con.close()
    return stats


def classify(work_db: str | Path) -> Counter:
    """Assegna `answer_type` e conta i segnaposto nel fronte."""
    con = dbmod.open_db(work_db)
    stats: Counter = Counter()
    with con:
        rows = con.execute(
            "SELECT uid, front, answer_core FROM card"
        ).fetchall()
        for row in rows:
            front = row["front"] or ""
            core = row["answer_core"] or ""
            blanks = classify_mod.count_blanks(front)
            answer_type = classify_mod.classify(core, front)
            # `core_norm` si ricalcola qui e non in `normalize`: la
            # normalizzazione giusta dipende dal tipo, che prima non si
            # conosceva. `normalize` ne scrive una provvisoria da prosa.
            con.execute(
                "UPDATE card SET answer_type = ?, blanks = ?, core_norm = ? "
                "WHERE uid = ?",
                (answer_type, blanks, norm_for_compare(core, answer_type),
                 row["uid"]),
            )
            stats[answer_type] += 1
            if blanks:
                stats["_with_blanks"] += 1
    con.close()
    return stats


def index(work_db: str | Path) -> Counter:
    """Costruisce la classifica dei vicini (stadio `index`)."""
    con = dbmod.open_db(work_db)
    stats: Counter = Counter()
    with con:
        stats["neighbors"] = relations.build_neighbors(con)
        stats["cards"] = con.execute("SELECT COUNT(*) FROM card").fetchone()[0]
    con.close()
    return stats


def generate(
    work_db: str | Path,
    llm_model: str | None = None,
    llm_seed: int = 42,
    llm_temperature: float = 0.8,
) -> Counter:
    """Applica il motore a regole (stadio `generate`).

    Produce candidati grezzi: la selezione avviene in `validate`. Tenere i
    due stadi separati permette di cambiare le soglie di validazione senza
    rigenerare, che in M4 -- quando generare costera' secondi di modello per
    card -- fara' la differenza.
    """
    con = dbmod.open_db(work_db)
    stats: Counter = Counter()
    with con:
        corpus = _build_corpus(con)
        deck_name = dbmod.get_meta(con, "raccolta_name", "") or ""
        con.execute("DELETE FROM distractor WHERE origin IN ('rule', 'llm')")
        if llm_model:
            relations.build_exclusions(con)
            dbmod.set_meta(con, "llm_model", llm_model)
            dbmod.set_meta(con, "llm_seed", str(llm_seed))
            dbmod.set_meta(con, "llm_temperature", repr(llm_temperature))
        else:
            for key in ("llm_model", "llm_seed", "llm_temperature"):
                dbmod.delete_meta(con, key)
        rows = con.execute(
            "SELECT uid, front, answer_core, answer_note, core_norm, answer_type "
            "FROM card ORDER BY source_ord"
        ).fetchall()
        for row in rows:
            candidates = list(
                rules.generate(
                    row["answer_type"],
                    row["answer_core"] or "",
                    front=row["front"] or "",
                    corpus=corpus,
                )
            )
            for candidate in candidates:
                con.execute(
                    "INSERT INTO distractor"
                    "(card_uid, text, origin, rule, quality, gen_version) "
                    "VALUES(?,?,'rule',?,?,?) ON CONFLICT DO NOTHING",
                    (row["uid"], candidate.text, candidate.rule,
                     candidate.quality, rules.GEN_VERSION),
                )
            stats["candidates"] += len(candidates)
            if candidates:
                stats["cards_covered"] += 1
            elif row["answer_type"] in rules.COVERED_TYPES:
                stats["cards_uncovered"] += 1
        if llm_model:
            for row in rows:
                # I `term` erano stati esclusi quando la misura mostrava che
                # l'LLM produceva quasi solo output da scartare per
                # `lunghezza-anomala`, e su un mazzo 24/25 `term` questo
                # avrebbe acceso il modello quasi per tutto il deck senza
                # copertura reale. La misura aggiornata sul mazzo di
                # riferimento ha ribaltato il quadro: su 5 card `term` reali
                # il modello ha prodotto distrattori utili, con 1 solo scarto
                # legittimo su 15 per lunghezza. L'esclusione viene quindi
                # rimossa: lasciarla oggi significa rinunciare quasi a tutta
                # la copertura LLM del mazzo.
                neighbors = _neighbor_backs(con, row["uid"])
                for attempt in range(LLM_MAX_ATTEMPTS):
                    if len(_kept_candidate_texts(con, row)) >= 3:
                        break
                    stats["llm_calls"] += 1
                    if attempt:
                        stats["llm_retries"] += 1
                    generated = llm_mod.generate_distractors(
                        front=row["front"] or "",
                        answer_core=row["answer_core"] or "",
                        answer_note=row["answer_note"],
                        deck_name=deck_name,
                        neighbor_backs=neighbors,
                        model=llm_model,
                        seed=llm_seed + attempt,
                        temperature=llm_temperature,
                    )
                    if not generated:
                        break
                    stats["llm_candidates"] += len(generated)
                    for index, text in enumerate(generated):
                        con.execute(
                            "INSERT INTO distractor"
                            "(card_uid, text, origin, quality, gen_version) "
                            "VALUES(?,?,'llm',?,?) ON CONFLICT DO NOTHING",
                            (row["uid"], text, 0.75 - index * 0.01, f"llm:{llm_model}"),
                        )
            for origin, count in _project_coverage(con, rows).items():
                stats[f"coverage:{origin}"] = count
    con.close()
    return stats


def _build_corpus(con: sqlite3.Connection) -> rules.Corpus:
    by_type: dict[str, list[str]] = {}
    all_cores: list[str] = []
    for row in con.execute("SELECT answer_core, answer_type FROM card"):
        core = (row["answer_core"] or "").strip()
        if not core:
            continue
        by_type.setdefault(row["answer_type"] or "other", []).append(core)
        all_cores.append(core)
    return rules.Corpus(cores_by_type=by_type, all_cores=all_cores)


def _candidate_rows(con: sqlite3.Connection, card_uid: str) -> list[sqlite3.Row]:
    return con.execute(
        "SELECT text, origin FROM distractor WHERE card_uid = ? "
        "ORDER BY quality DESC, text",
        (card_uid,),
    ).fetchall()


def _kept_candidate_texts(con: sqlite3.Connection, row: sqlite3.Row) -> list[str]:
    answer_core = row["answer_core"] or ""
    answer_type = row["answer_type"]
    answer_norm = row["core_norm"] or norm_for_compare(answer_core, answer_type)
    excluded = relations.excluded_texts(con, row["uid"])
    texts = [candidate["text"] for candidate in _candidate_rows(con, row["uid"])]
    kept, _ = validate_mod.select_texts(
        texts,
        answer_core=answer_core,
        answer_norm=answer_norm,
        excluded=excluded,
        answer_note=row["answer_note"],
        answer_type=answer_type,
    )
    return kept


def _neighbor_backs(
    con: sqlite3.Connection, card_uid: str, limit: int = 3
) -> list[str]:
    rows = con.execute(
        "SELECT COALESCE(c.back, c.answer_core, '') AS back FROM neighbor n "
        "JOIN card c ON c.uid = n.other_uid "
        "WHERE n.card_uid = ? ORDER BY n.rank LIMIT ?",
        (card_uid, limit),
    ).fetchall()
    return [row["back"] for row in rows if row["back"]]


def _project_coverage(
    con: sqlite3.Connection, rows: list[sqlite3.Row]
) -> Counter[str]:
    counts: Counter[str] = Counter()
    for row in rows:
        candidates = _candidate_rows(con, row["uid"])
        kept = _kept_candidate_texts(con, row)
        if not kept:
            counts["sibling_only"] += 1
            continue
        kept_set = set(kept)
        origins = {
            candidate["origin"] for candidate in candidates if candidate["text"] in kept_set
        }
        if "llm" in origins:
            counts["llm"] += 1
        else:
            counts["rule"] += 1
    return counts


def validate(work_db: str | Path) -> Counter:
    """Popola `exclusion` e filtra i distrattori (stadio `validate`).

    L'ordine conta: le esclusioni servono alla regola 2 della validazione,
    quindi si costruiscono prima.
    """
    con = dbmod.open_db(work_db)
    stats: Counter = Counter()
    with con:
        for reason, count in relations.build_exclusions(con).items():
            stats[f"exclusion:{reason}"] = count

        rows = con.execute(
            "SELECT uid, answer_core, answer_note, core_norm, answer_type FROM card "
            "ORDER BY source_ord"
        ).fetchall()
        for row in rows:
            excluded = relations.excluded_texts(con, row["uid"])
            candidates = con.execute(
                "SELECT text FROM distractor WHERE card_uid = ? "
                "ORDER BY quality DESC, text",
                (row["uid"],),
            ).fetchall()
            kept, rejected = validate_mod.select_texts(
                [candidate["text"] for candidate in candidates],
                answer_core=row["answer_core"] or "",
                answer_norm=row["core_norm"] or "",
                excluded=excluded,
                answer_note=row["answer_note"],
                answer_type=row["answer_type"],
            )
            kept_set = set(kept)
            stats["kept"] += len(kept)
            for reason, count in rejected.items():
                stats[f"scartato:{reason}"] += count
            for candidate in candidates:
                if candidate["text"] in kept_set:
                    continue
                con.execute(
                    "DELETE FROM distractor WHERE card_uid = ? AND text = ?",
                    (row["uid"], candidate["text"]),
                )
    con.close()
    return stats


def stale_stage(con: sqlite3.Connection) -> str | None:
    """Il primo stadio che risulta non eseguito, se ce n'e' uno."""
    row = con.execute(
        "SELECT SUM(answer_core IS NULL) AS n_norm,"
        "       SUM(answer_type IS NULL) AS n_class,"
        "       COUNT(*) AS total FROM card"
    ).fetchone()
    if not row or not row["total"]:
        return "ingest"
    if row["n_norm"]:
        return "normalize"
    if row["n_class"]:
        return "classify"
    if not con.execute("SELECT 1 FROM neighbor LIMIT 1").fetchone():
        return "index"
    if not con.execute("SELECT 1 FROM distractor LIMIT 1").fetchone():
        return "generate"
    return None
