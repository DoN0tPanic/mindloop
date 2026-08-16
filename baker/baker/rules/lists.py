"""Distrattori per risposte-elenco.

La regola buona e' una sola: **sostituire un elemento**. Produce un'opzione
che a colpo d'occhio e' identica alla risposta corretta e differisce in un
punto, che e' il tipo di distrattore piu' cattivo esistente, e costa zero.

Due regole che sembrano buone e non lo sono:

- *riordinare* -- in un elenco non ordinato una permutazione e' ancora la
  risposta corretta;
- *togliere un elemento* -- produce una risposta incompleta piu' che
  sbagliata, e in un quiz a scelta multipla si riconosce dalla lunghezza.

Se manca un elemento da cui pescare la sostituzione, la regola non produce
niente e la card passa al campionamento o all'LLM. Meglio nessun distrattore
che uno inventato male.
"""

from __future__ import annotations

import re
from typing import Iterator

from .base import Candidate, Corpus

_BULLET_RE = re.compile(r"^\s*(?:[-•*–]|\d+[.)])\s*", re.M)
_MIN_ITEMS = 3


def split_items(core: str) -> tuple[list[str], str]:
    """Divide un elenco nei suoi elementi e restituisce come ricomporlo."""
    if "\n" in core:
        lines = [ln for ln in core.split("\n") if ln.strip()]
        items = [_BULLET_RE.sub("", ln).strip() for ln in lines]
        prefix = _BULLET_RE.match(lines[0])
        marker = prefix.group(0) if prefix else ""
        return items, "\n" + marker if marker else "\n"

    for sep in ("; ", ", "):
        if core.count(sep) >= _MIN_ITEMS - 1:
            return [p.strip() for p in core.split(sep.strip())], sep
    return [], ""


def generate(core: str, *, corpus: Corpus | None = None, **_) -> Iterator[Candidate]:
    items, joiner = split_items(core)
    if len(items) < _MIN_ITEMS or corpus is None:
        return

    known = {i.strip().lower() for i in items}
    replacements = _pool(corpus, known)
    if not replacements:
        return

    # Si sostituisce un elemento diverso per ogni candidato, cominciando
    # dall'ultimo: e' quello che l'occhio controlla per ultimo.
    for offset, replacement in enumerate(replacements[:4]):
        position = len(items) - 1 - (offset % len(items))
        variant = list(items)
        variant[position] = replacement
        yield Candidate(
            text=joiner.join(variant) if joiner != "\n" else _rejoin(core, variant),
            rule="lista:elemento-sostituito",
            quality=0.85,
        )


def _rejoin(core: str, items: list[str]) -> str:
    """Ricompone un elenco su piu' righe conservando i marcatori originali."""
    lines = [ln for ln in core.split("\n") if ln.strip()]
    out = []
    for line, item in zip(lines, items):
        prefix = _BULLET_RE.match(line)
        out.append((prefix.group(0) if prefix else "") + item)
    return "\n".join(out)


def _pool(corpus: Corpus, exclude: set[str]) -> list[str]:
    """Elementi presi da altri elenchi della raccolta, poi dai termini.

    Gli elementi di un altro elenco sono i migliori: appartengono gia' a una
    enumerazione dello stesso dominio.
    """
    pool: list[str] = []
    for other in corpus.cores_by_type.get("list", []):
        items, _ = split_items(other)
        pool.extend(items)
    pool.extend(corpus.cores_by_type.get("term", []))

    seen: set[str] = set()
    out = []
    for item in pool:
        key = item.strip().lower()
        if not key or key in exclude or key in seen:
            continue
        seen.add(key)
        out.append(item.strip())
    return out
