"""Il motore a regole.

Copre i tipi in cui un distrattore credibile si costruisce meccanicamente:
numeri, date, elenchi, formule. Resta scoperto tutto il resto -- termini,
definizioni -- ed e' voluto:

- i **termini** si coprono con il campionamento dai vicini, che a runtime
  costa due SELECT e produce distrattori migliori di qualunque regola;
- le **definizioni** richiedono un near-miss credibile, che e' il solo caso
  in cui serve davvero un modello (M4).

Una regola che non sa cosa produrre non produce niente. La card finisce fra i
`sibling_only` e il campionamento la copre.
"""

from __future__ import annotations

from typing import Iterator

from ..classify import DATE, FORMULA, LIST, NUMERIC
from .base import Candidate, Corpus, GEN_VERSION
from . import dates, formulas, lists, numeric

GENERATORS = {
    NUMERIC: numeric.generate,
    DATE: dates.generate,
    LIST: lists.generate,
    FORMULA: formulas.generate,
}

COVERED_TYPES = frozenset(GENERATORS)

__all__ = ["Candidate", "Corpus", "GEN_VERSION", "GENERATORS", "COVERED_TYPES", "generate"]


def generate(
    answer_type: str, core: str, *, front: str = "", corpus: Corpus | None = None
) -> Iterator[Candidate]:
    generator = GENERATORS.get(answer_type)
    if not generator:
        return iter(())
    return generator(core, front=front, corpus=corpus)
