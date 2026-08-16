"""Interfaccia a riga di comando del baker.

    baker ingest <file> --out work.db [--sezione NOME] [--raccolta NOME]
    baker normalize work.db [--review]
    baker classify  work.db [--review]
    baker inspect   work.db [--type TIPO] [--limit N]
    baker bake      <file> --out work.db      # ingest + normalize + classify

`--review` stampa cio' che lo stadio ha prodotto, card per card. E' il modo
previsto per giudicare un'euristica: si guarda l'output su un mazzo vero, non
si discute in astratto.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import assemble
from . import db as dbmod
from . import export
from . import pipeline
from . import server as server_mod
from .classify import ALL_TYPES, OTHER

# Larghezza a cui si troncano i testi nelle stampe di revisione.
_W = 58


def _clip(s: str | None, width: int = _W) -> str:
    s = (s or "").replace("\n", " ⏎ ")
    return s if len(s) <= width else s[: width - 1] + "…"


def _cmd_ingest(args: argparse.Namespace) -> int:
    r = pipeline.ingest(args.source, args.out, args.sezione, args.raccolta)
    print(f"sezione «{r.sezione_name}»  ->  {args.out}")
    print(f"  note lette         {r.read}")
    print(f"  card nuove         {r.inserted}")
    if r.shared:
        print(f"  card condivise     {r.shared}  (gia' presenti in un'altra sezione)")
    if r.duplicated_in_file:
        print(f"  duplicate nel file {r.duplicated_in_file}  (stesso fronte)")
    for warning in r.warnings:
        print(f"\n  ! {warning}")
    return 0


def _cmd_normalize(args: argparse.Namespace) -> int:
    stats = pipeline.normalize(args.work_db)
    total = stats["cards"]
    gloss = stats["with_gloss"]
    pct = (100 * gloss / total) if total else 0
    print(f"normalizzate {total} card, {gloss} con glossa scorporata ({pct:.0f}%)")
    if stats["empty_core"]:
        print(f"  ATTENZIONE: {stats['empty_core']} card con nucleo vuoto")
    if args.review:
        _review_normalize(args.work_db)
    return 0


def _review_normalize(work_db: str) -> None:
    con = dbmod.open_db(work_db)
    rows = con.execute(
        "SELECT answer_core, answer_note, back FROM card ORDER BY source_ord"
    ).fetchall()
    con.close()
    print()
    print(f"{'NUCLEO':<{_W}}  GLOSSA")
    print("-" * (_W + 2 + 40))
    for row in rows:
        note = row["answer_note"]
        print(f"{_clip(row['answer_core']):<{_W}}  {_clip(note, 40) if note else '·'}")


def _cmd_classify(args: argparse.Namespace) -> int:
    stats = pipeline.classify(args.work_db)
    total = sum(stats[t] for t in ALL_TYPES)
    print(f"classificate {total} card")

    for t in ALL_TYPES:
        if stats[t]:
            print(f"  {t:<12} {stats[t]:>4}  {100 * stats[t] / total:>4.0f}%")
    if stats["_with_blanks"]:
        print(f"  {'(con [...])':<12} {stats['_with_blanks']:>4}")
    if total and stats[OTHER] / total > 0.15:
        print(
            f"\n  ATTENZIONE: 'other' e' il {100 * stats[OTHER] / total:.0f}% "
            "del mazzo. Sopra il 15% e' un sintomo, non un fallback: il\n"
            "  classificatore va rivisto per questo dominio."
        )
    if args.review:
        _review_classify(args.work_db)
    return 0


def _review_classify(work_db: str) -> None:
    con = dbmod.open_db(work_db)
    rows = con.execute(
        "SELECT answer_type, blanks, front, answer_core "
        "FROM card ORDER BY answer_type, source_ord"
    ).fetchall()
    con.close()
    print()
    current = None
    for row in rows:
        if row["answer_type"] != current:
            current = row["answer_type"]
            print(f"\n── {current} " + "─" * (70 - len(current)))
        blank = "▫" if row["blanks"] else " "
        print(f" {blank} {_clip(row['front'], 46):<46}  {_clip(row['answer_core'], 30)}")


def _cmd_index(args: argparse.Namespace) -> int:
    stats = pipeline.index(args.work_db)
    print(f"indicizzate {stats['cards']} card, {stats['neighbors']} archi di vicinanza")
    print("  (M1 usa il fallback lessicale TF-IDF di PLAN.md 5.4; M3 lo sostituisce)")
    if args.review:
        _review_neighbors(args.work_db)
    return 0


def _review_neighbors(work_db: str) -> None:
    con = dbmod.open_db(work_db)
    rows = con.execute(
        "SELECT c.answer_core AS core, c.uid AS uid FROM card c ORDER BY c.source_ord"
    ).fetchall()
    print()
    for row in rows:
        near = con.execute(
            "SELECT c.answer_core AS core, n.sim AS sim FROM neighbor n "
            "JOIN card c ON c.uid = n.other_uid "
            "WHERE n.card_uid = ? ORDER BY n.rank LIMIT 3",
            (row["uid"],),
        ).fetchall()
        vicini = ", ".join(f"{_clip(n['core'], 22)} ({n['sim']:.2f})" for n in near)
        print(f"{_clip(row['core'], 30):<30}  ->  {vicini}")
    con.close()


def _cmd_generate(args: argparse.Namespace) -> int:
    stats = pipeline.generate(
        args.work_db,
        llm_model=args.llm,
        llm_seed=args.seed,
        llm_temperature=args.temperature,
    )
    print(
        f"generati {stats['candidates']} candidati su {stats['cards_covered']} card"
    )
    if stats["cards_uncovered"]:
        print(
            f"  ATTENZIONE: {stats['cards_uncovered']} card di tipo coperto da una "
            "regola non hanno prodotto nulla.\n"
            "  Sono difetti del motore, non scelte: `baker export --strict` le elenca."
        )
    if args.llm:
        print(
            f"  LLM: {stats.get('coverage:llm', 0)} card coperte, "
            f"{stats.get('coverage:sibling_only', 0)} solo campionamento"
        )
    if args.review:
        _review_pools(args.work_db, only_with_pool=True)
    return 0


def _cmd_validate(args: argparse.Namespace) -> int:
    stats = pipeline.validate(args.work_db)
    def _strip(prefix: str) -> dict[str, int]:
        return {
            k[len(prefix):]: v for k, v in stats.items() if k.startswith(prefix)
        }

    exclusions = _strip("exclusion:")
    rejected = _strip("scartato:")

    total_excl = sum(exclusions.values())
    print(f"esclusioni: {total_excl}")
    for reason, count in sorted(exclusions.items(), key=lambda kv: -kv[1]):
        if count:
            print(f"  {reason:<28} {count:>4}")

    print(f"\ndistrattori tenuti: {stats['kept']}")
    if rejected:
        print("scartati:")
        for reason, count in sorted(rejected.items(), key=lambda kv: -kv[1]):
            print(f"  {reason:<28} {count:>4}")
    if args.review:
        _review_pools(args.work_db, only_with_pool=True)
    return 0


def _review_pools(work_db: str, only_with_pool: bool = False) -> None:
    con = dbmod.open_db(work_db)
    rows = con.execute(
        "SELECT uid, front, answer_core FROM card ORDER BY source_ord"
    ).fetchall()
    print()
    for row in rows:
        pool = con.execute(
            "SELECT text, origin, rule, quality FROM distractor WHERE card_uid = ? "
            "ORDER BY quality DESC, text",
            (row["uid"],),
        ).fetchall()
        if only_with_pool and not pool:
            continue
        print(f"Q  {_clip(row['front'], 70)}")
        print(f"✓  {row['answer_core']}")
        for item in pool:
            label = item["rule"] if item["origin"] == "rule" else item["origin"]
            print(f"✗  {item['text']:<40} {label}  {item['quality']:.2f}")
        print()
    con.close()


def _cmd_preview(args: argparse.Namespace) -> int:
    """Assembla i quiz come farebbe il telefono e li stampa da leggere.

    Non e' una statistica: e' il momento in cui si guardano le opzioni una per
    una e si decide se il mazzo e' pronto."""
    con = dbmod.open_db(args.work_db)
    quizzes = assemble.assemble_all(con, seed=args.seed)
    con.close()

    incomplete = [q for q in quizzes if not q.complete]
    shown = quizzes if not args.limit else quizzes[: args.limit]

    for quiz in shown:
        print(f"\n{quiz.front}")
        for option in quiz.options:
            mark = "✓" if option.correct else " "
            print(f"  {mark} {option.text:<44} [{option.origin}]")
        if len(quiz.options) < assemble.OPTIONS:
            print(f"    ! solo {len(quiz.options)} opzioni")

    print(f"\n{len(quizzes)} quiz assemblati, seed {args.seed}")
    if incomplete:
        print(f"  ! {len(incomplete)} con meno di {assemble.OPTIONS} opzioni")
    origins = {}
    for quiz in quizzes:
        for option in quiz.options:
            origins[option.origin] = origins.get(option.origin, 0) + 1
    print("  provenienza opzioni: " + ", ".join(
        f"{k} {v}" for k, v in sorted(origins.items())
    ))
    return 0


def _cmd_export(args: argparse.Namespace) -> int:
    con = dbmod.open_db(args.work_db)
    missing = export.uncovered_by_rules(con)
    stale = pipeline.stale_stage(con)
    con.close()

    if stale and stale != "generate":
        print(f"errore: stadio «{stale}» non ancora eseguito", file=sys.stderr)
        return 1
    if missing and args.strict:
        print(
            f"errore: {len(missing)} card di tipo coperto da una regola sono "
            "senza distrattori:",
            file=sys.stderr,
        )
        for row in missing[:10]:
            print(f"  [{row['answer_type']}] {row['answer_core']}", file=sys.stderr)
        return 1

    manifest = export.write_qzd(args.work_db, args.out, lang=args.lang or "")
    size = manifest.pop("_size")
    cov = manifest["coverage"]
    print(f"{args.out}  ({size / 1024:.1f} KB)")
    print(f"  raccolta      {manifest['raccolta']['name']}")
    print(f"  card          {manifest['card_count']}")
    print(f"  sezioni       {len(manifest['sezioni'])}")
    print(
        f"  copertura     regole {cov.get('rule', 0)}, "
        f"llm {cov.get('llm', 0)}, solo campionamento {cov.get('sibling_only', 0)}"
    )
    if missing:
        print(f"\n  ! {len(missing)} card di tipo coperto da una regola sono senza pool")
    return 0


def _cmd_inspect(args: argparse.Namespace) -> int:
    con = dbmod.open_db(args.work_db)
    name = dbmod.get_meta(con, "raccolta_name", "?")
    total = con.execute("SELECT COUNT(*) FROM card").fetchone()[0]
    print(f"raccolta «{name}» — {total} card")
    for row in con.execute(
        "SELECT name, card_count, src_name FROM sezione ORDER BY imported_at"
    ):
        print(f"  · {row['name']:<28} {row['card_count']:>4} card   ({row['src_name']})")

    stale = pipeline.stale_stage(con)
    if stale:
        print(f"\n  stadio da eseguire: {stale}")

    sql = (
        "SELECT front, answer_core, answer_note, answer_type, blanks "
        "FROM card"
    )
    params: tuple = ()
    if args.type:
        sql += " WHERE answer_type = ?"
        params = (args.type,)
    sql += " ORDER BY source_ord LIMIT ?"
    params += (args.limit,)

    print()
    for row in con.execute(sql, params):
        print(f"Q  {row['front']}")
        print(f"A  {row['answer_core']}")
        if row["answer_note"]:
            print(f"   ⤷ {row['answer_note']}")
        flags = row["answer_type"] or "?"
        if row["blanks"]:
            flags += f", {row['blanks']} segnaposto"
        print(f"   [{flags}]")
        print()
    con.close()
    return 0


def _cmd_bake(args: argparse.Namespace) -> int:
    """Tutti gli stadi in fila. Comodo, ma i singoli stadi restano il modo
    normale di lavorare: `bake` rifa' tutto da capo ogni volta."""
    _cmd_ingest(args)
    args.work_db = args.out
    args.review = False
    args.strict = False
    for stage in (_cmd_normalize, _cmd_classify, _cmd_index, _cmd_generate,
                  _cmd_validate):
        print()
        stage(args)
    if args.qzd:
        print()
        args.out = args.qzd
        _cmd_export(args)
    return 0


def _cmd_serve(args: argparse.Namespace) -> int:
    server_mod.serve_forever(
        port=args.port,
        llm_model=args.llm,
        pairing_code=args.code,
        discovery_enabled=not args.no_discovery,
    )
    return 0


def _cmd_gui(_args: argparse.Namespace) -> int:
    from . import gui

    return gui.avvia()


def _pairing_code_arg(value: str) -> str:
    try:
        return server_mod.validate_pairing_code(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(str(exc)) from exc


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="baker", description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)

    ing = sub.add_parser("ingest", help="importa un file come nuova sezione")
    ing.add_argument("source", help="file .apkg, .csv o .tsv")
    ing.add_argument("--out", required=True, help="work.db di destinazione")
    ing.add_argument("--sezione", help="nome della sezione (default: nome del mazzo)")
    ing.add_argument("--raccolta", help="nome della raccolta, alla creazione")
    ing.set_defaults(func=_cmd_ingest)

    nor = sub.add_parser("normalize", help="testo, nucleo e glossa")
    nor.add_argument("work_db")
    nor.add_argument("--review", action="store_true", help="stampa nucleo e glossa")
    nor.set_defaults(func=_cmd_normalize)

    cls = sub.add_parser("classify", help="tipo della risposta")
    cls.add_argument("work_db")
    cls.add_argument("--review", action="store_true", help="stampa le card per tipo")
    cls.set_defaults(func=_cmd_classify)

    ins = sub.add_parser("inspect", help="mostra il contenuto del work.db")
    ins.add_argument("work_db")
    ins.add_argument("--type", help="filtra per answer_type")
    ins.add_argument("--limit", type=int, default=10)
    ins.set_defaults(func=_cmd_inspect)

    idx = sub.add_parser("index", help="classifica dei vicini")
    idx.add_argument("work_db")
    idx.add_argument("--review", action="store_true", help="stampa i primi vicini")
    idx.set_defaults(func=_cmd_index)

    gen = sub.add_parser("generate", help="distrattori a regole, con fallback LLM opzionale")
    gen.add_argument("work_db")
    gen.add_argument("--llm", help="modello Ollama da usare per le card scoperte")
    gen.add_argument("--seed", type=int, default=42, help="seed per il modello LLM")
    gen.add_argument(
        "--temperature", type=float, default=0.8,
        help="temperatura per il modello LLM",
    )
    gen.add_argument("--review", action="store_true", help="stampa i pool")
    gen.set_defaults(func=_cmd_generate)

    val = sub.add_parser("validate", help="esclusioni e filtro dei distrattori")
    val.add_argument("work_db")
    val.add_argument("--review", action="store_true", help="stampa i pool superstiti")
    val.set_defaults(func=_cmd_validate)

    pre = sub.add_parser("preview", help="assembla i quiz come farebbe il telefono")
    pre.add_argument("work_db")
    pre.add_argument("--seed", type=int, default=0)
    pre.add_argument("--limit", type=int, default=0, help="0 = tutti")
    pre.set_defaults(func=_cmd_preview)

    exp = sub.add_parser("export", help="scrive il .qzd")
    exp.add_argument("work_db")
    exp.add_argument("--out", required=True, help="percorso del .qzd")
    exp.add_argument("--lang", help="codice lingua da mettere nel manifest")
    exp.add_argument(
        "--strict", action="store_true",
        help="fallisce se una card di tipo coperto da una regola non ha pool",
    )
    exp.set_defaults(func=_cmd_export)

    bak = sub.add_parser("bake", help="tutti gli stadi in fila")
    bak.add_argument("source")
    bak.add_argument("--out", required=True, help="work.db di destinazione")
    bak.add_argument("--qzd", help="se indicato, esporta anche il .qzd")
    bak.add_argument("--sezione")
    bak.add_argument("--raccolta")
    bak.add_argument("--lang")
    bak.add_argument("--llm", help="modello Ollama da usare per le card scoperte")
    bak.add_argument("--seed", type=int, default=42, help="seed per il modello LLM")
    bak.add_argument(
        "--temperature", type=float, default=0.8,
        help="temperatura per il modello LLM",
    )
    bak.set_defaults(func=_cmd_bake)

    srv = sub.add_parser("serve", help="server LAN per bake ed export via HTTP")
    srv.add_argument("--port", type=int, default=server_mod.DEFAULT_PORT)
    srv.add_argument("--llm", help="modello Ollama da usare per le card scoperte")
    srv.add_argument("--code", type=_pairing_code_arg, help="pairing code a 6 cifre")
    srv.add_argument(
        "--no-discovery",
        action="store_true",
        help="disattiva la scoperta automatica via UDP broadcast",
    )
    srv.set_defaults(func=_cmd_serve)

    gui_parser = sub.add_parser(
        "gui", help="finestra per scegliere il modello e seguire le cotture"
    )
    gui_parser.set_defaults(func=_cmd_gui)

    return p


def _fix_console_encoding() -> None:
    """La console di Windows non e' UTF-8 per default e le stampe di revisione
    contengono accenti e caratteri di riquadro. `errors="replace"` garantisce
    che una console legacy imbruttisca l'output invece di far fallire il
    comando a meta'."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):  # pragma: no cover
            pass


def main(argv: list[str] | None = None) -> int:
    _fix_console_encoding()
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except (ValueError, OSError) as exc:
        print(f"errore: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
