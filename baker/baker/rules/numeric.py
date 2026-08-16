"""Distrattori per risposte numeriche.

Perturbazioni deterministiche del numero, conservando **la forma**: se la
risposta e' `1.500 byte`, i distrattori devono essere `3.000 byte` e
`750 byte`, non `3000` e `750.0`. Un distrattore formattato diversamente
dalla risposta corretta e' un regalo: l'utente impara a riconoscere lo stile.
"""

from __future__ import annotations

import re
from typing import Iterator

from .base import Candidate

# numero + eventuale unita'. Il numero puo' avere separatori di migliaia e
# decimali, in convenzione italiana o inglese.
_NUM_RE = re.compile(
    r"^(?P<sign>[+\-−]?)(?P<num>\d[\d.,\s ]*\d|\d)\s*(?P<unit>.*)$"
)
_DOTTED_QUAD_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")

# Valori che compaiono negli ottetti di una maschera di sottorete. Perturbare
# una maschera aritmeticamente produce numeri che nessuno scriverebbe mai;
# scambiare un ottetto con un altro valore legale produce l'errore che si fa
# davvero.
_MASK_OCTETS = (0, 128, 192, 224, 240, 248, 252, 254, 255)


class _Format:
    """La forma del numero originale, per riprodurla nei distrattori."""

    def __init__(self, raw: str):
        self.thousands = ""
        self.decimal = ""
        self.decimals = 0

        body = raw.replace(" ", " ")
        if "," in body and "." in body:
            # L'ultimo separatore che compare e' quello decimale.
            self.decimal = "," if body.rfind(",") > body.rfind(".") else "."
            self.thousands = "." if self.decimal == "," else ","
        elif "," in body:
            head, _, tail = body.rpartition(",")
            if len(tail) == 3 and head:
                self.thousands = ","
            else:
                self.decimal = ","
        elif "." in body:
            head, _, tail = body.rpartition(".")
            if len(tail) == 3 and head:
                self.thousands = "."
            else:
                self.decimal = "."
        elif " " in body.strip():
            self.thousands = " "

        if self.decimal:
            self.decimals = len(body.rpartition(self.decimal)[2])

    def value(self, raw: str) -> float | None:
        body = raw.replace(" ", " ")
        if self.thousands:
            body = body.replace(self.thousands, "")
        if self.decimal:
            body = body.replace(self.decimal, ".")
        body = body.replace(" ", "")
        try:
            return float(body)
        except ValueError:
            return None

    def render(self, value: float) -> str:
        if self.decimals:
            text = f"{value:,.{self.decimals}f}"
        elif value == int(value):
            text = f"{int(value):,}"
        else:
            text = f"{value:,.2f}".rstrip("0").rstrip(".")

        # `format` produce sempre `,` per le migliaia e `.` per i decimali:
        # si traduce nella convenzione dell'originale.
        integer, _, fraction = text.partition(".")
        integer = integer.replace(",", self.thousands or "")
        if fraction:
            return integer + (self.decimal or ".") + fraction
        return integer


def generate(core: str, **_) -> Iterator[Candidate]:
    if _DOTTED_QUAD_RE.match(core.strip()):
        yield from _dotted_quad(core.strip())
        return

    match = _NUM_RE.match(core.strip())
    if not match:
        return
    unit = match.group("unit").strip()
    fmt = _Format(match.group("num"))
    value = fmt.value(match.group("num"))
    if value is None:
        return
    sign = "-" if match.group("sign") in ("-", "−") else ""

    def emit(new: float, rule: str, quality: float):
        if new <= 0 or new == value:
            return
        text = sign + fmt.render(new)
        if unit:
            text = f"{text} {unit}"
        return Candidate(text=text, rule=rule, quality=quality)

    # L'ordine e' l'ordine di preferenza: le perturbazioni piccole sono
    # distrattori migliori, quelle grandi si riconoscono a colpo d'occhio.
    plan = [
        (value * 2, "num:x2", 0.85),
        (value / 2, "num:/2", 0.85),
        (value * 1.25, "num:+25%", 0.75),
        (value * 0.75, "num:-25%", 0.75),
        (value * 10, "num:x10", 0.60),
        (value / 10, "num:/10", 0.60),
    ]
    for new, rule, quality in plan:
        candidate = emit(_tidy(new, fmt), rule, quality)
        if candidate:
            yield candidate

    swapped = _swap_digits(match.group("num"))
    if swapped is not None:
        new = fmt.value(swapped)
        if new is not None:
            candidate = emit(new, "num:cifre-scambiate", 0.90)
            if candidate:
                yield candidate


def _tidy(value: float, fmt: _Format) -> float:
    """Evita decimali spuri su una risposta che era intera."""
    if not fmt.decimals and abs(value - round(value)) < 1e-9:
        return float(round(value))
    return value


def _swap_digits(raw: str) -> str | None:
    """Scambia le due prime cifre diverse: 37,2 -> 73,2.

    E' l'errore di trascrizione piu' comune, quindi il distrattore piu'
    credibile fra quelli generabili a regola.
    """
    chars = list(raw)
    positions = [i for i, c in enumerate(chars) if c.isdigit()]
    for a, b in zip(positions, positions[1:]):
        if chars[a] != chars[b] and chars[b] != "0":
            chars[a], chars[b] = chars[b], chars[a]
            return "".join(chars)
    return None


def _dotted_quad(core: str) -> Iterator[Candidate]:
    """Maschere e indirizzi in notazione puntata."""
    octets = [int(o) for o in core.split(".")]
    is_mask = all(o in _MASK_OCTETS for o in octets)

    if is_mask:
        # Si cambia l'ottetto significativo: e' l'errore di chi sbaglia il
        # prefisso di una sottorete.
        target = max(
            (i for i, o in enumerate(octets) if 0 < o < 255), default=len(octets) - 1
        )
        for value in (192, 224, 240, 248, 252, 128, 0, 255):
            if value == octets[target]:
                continue
            variant = list(octets)
            variant[target] = value
            yield Candidate(
                text=".".join(str(o) for o in variant),
                rule="quad:ottetto-maschera",
                quality=0.85,
            )
    else:
        for index in (3, 2):
            for delta in (1, -1, 10):
                value = octets[index] + delta
                if not 0 <= value <= 255 or value == octets[index]:
                    continue
                variant = list(octets)
                variant[index] = value
                yield Candidate(
                    text=".".join(str(o) for o in variant),
                    rule="quad:ottetto",
                    quality=0.80,
                )
