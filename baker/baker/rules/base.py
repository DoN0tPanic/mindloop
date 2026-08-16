"""Tipi comuni al motore a regole.

Ogni generatore espone `generate(core, *, front, corpus) -> Iterator[Candidate]`
e non sa nulla del database. I candidati escono grezzi: e' `validate` a
decidere quali sopravvivono.

`quality` e' la stima del generatore, 0..1. Non e' una probabilita': e' un
ordinamento di preferenza fra i propri candidati, che l'assemblatore usera'
per scegliere le opzioni migliori quando ce ne sono piu' del necessario.
"""

from __future__ import annotations

from typing import NamedTuple

GEN_VERSION = "rules-1"


class Candidate(NamedTuple):
    text: str
    rule: str
    quality: float


class Corpus(NamedTuple):
    """Cio' che un generatore puo' sapere del resto della raccolta.

    Serve alle liste, che costruiscono un distrattore sostituendo un elemento
    con uno preso altrove: senza contesto quella regola non esiste.
    """
    cores_by_type: dict[str, list[str]]
    all_cores: list[str]
