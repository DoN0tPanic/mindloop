"""Distrattori per formule.

Gli errori che si fanno davvero: un segno invertito, un esponente sbagliato,
una moltiplicazione al posto di una divisione, due termini scambiati. Sono
tutti errori *locali*: la formula resta plausibile, ed e' proprio questo che
rende il distrattore utile.
"""

from __future__ import annotations

import re
from typing import Iterator

from .base import Candidate

_EXPONENT_RE = re.compile(r"\^\s*(\d+)")
_CONSTANT_RE = re.compile(r"(?<![\w.])(\d+)(?![\w.])")
_SIGN_RE = re.compile(r"(?<=[\w)\s])([+\-])(?=\s*[\w(])")
_MULDIV_RE = re.compile(r"(?<=[\w)\s])([*/x×÷])(?=\s*[\w(])")


def generate(core: str, **_) -> Iterator[Candidate]:
    core = core.strip()
    head, sep, body = core.partition("=")
    if not sep:
        head, body = "", core
    target = body if sep else core

    for text, rule, quality in _variants(target):
        if text == target:
            continue
        yield Candidate(
            text=(head + sep + text) if sep else text,
            rule=rule,
            quality=quality,
        )


def _variants(expr: str) -> Iterator[tuple[str, str, float]]:
    for match in _EXPONENT_RE.finditer(expr):
        value = int(match.group(1))
        for delta in (1, -1):
            new = value + delta
            if new < 0:
                continue
            yield (
                expr[: match.start(1)] + str(new) + expr[match.end(1):],
                f"formula:esponente{delta:+d}",
                0.85,
            )

    for match in _SIGN_RE.finditer(expr):
        flipped = "-" if match.group(1) == "+" else "+"
        yield (
            expr[: match.start(1)] + flipped + expr[match.end(1):],
            "formula:segno-invertito",
            0.90,
        )

    # Costante sbagliata di uno. Su `H = 2^n - 2` produce `H = 2^n - 1`, che
    # e' l'errore che si fa davvero calcolando gli host di una sottorete: si
    # sottrae solo l'indirizzo di rete e ci si dimentica del broadcast.
    for match in _CONSTANT_RE.finditer(expr):
        value = int(match.group(1))
        for delta in (1, -1):
            new = value + delta
            if new < 0:
                continue
            yield (
                expr[: match.start(1)] + str(new) + expr[match.end(1):],
                f"formula:costante{delta:+d}",
                0.80,
            )

    for match in _MULDIV_RE.finditer(expr):
        symbol = match.group(1)
        swapped = {"*": "/", "/": "*", "x": "/", "×": "÷", "÷": "×"}.get(symbol)
        if not swapped:
            continue
        yield (
            expr[: match.start(1)] + swapped + expr[match.end(1):],
            "formula:operatore-scambiato",
            0.85,
        )
