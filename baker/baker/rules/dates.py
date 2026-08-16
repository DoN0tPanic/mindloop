"""Distrattori per date.

Slittamenti temporali. La misura giusta dipende dalla scala: su un anno si
sposta di anni, su una data completa anche di giorni e mesi. Uno slittamento
troppo grande produce un'opzione che si scarta senza pensarci.
"""

from __future__ import annotations

import re
from typing import Iterator

from .base import Candidate

_MONTHS_IT = [
    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
    "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
]
_MONTHS_EN = [
    "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december",
]

_YEAR_RE = re.compile(r"^\s*(?P<year>\d{3,4})(?P<era>\s*[a-z.]{2,4})?\s*$", re.I)
_DMY_RE = re.compile(
    r"^\s*(?P<d>\d{1,2})\s*(?P<s>[/\-.])\s*(?P<m>\d{1,2})\s*(?P=s)\s*(?P<y>\d{2,4})\s*$"
)
_MONTH_YEAR_RE = re.compile(r"^\s*(?P<month>[a-zà-ù]+)\.?\s+(?P<year>\d{3,4})\s*$", re.I)

_YEAR_SHIFTS = ((1, 0.90), (-1, 0.90), (2, 0.80), (-2, 0.80), (5, 0.65), (-10, 0.60))


def generate(core: str, **_) -> Iterator[Candidate]:
    core = core.strip()

    match = _YEAR_RE.match(core)
    if match:
        year = int(match.group("year"))
        era = match.group("era") or ""
        for delta, quality in _YEAR_SHIFTS:
            new = year + delta
            if new <= 0:
                continue
            yield Candidate(f"{new}{era}", f"data:anno{delta:+d}", quality)
        return

    match = _DMY_RE.match(core)
    if match:
        yield from _shift_dmy(match)
        return

    match = _MONTH_YEAR_RE.match(core)
    if match:
        yield from _shift_month_year(match)


def _shift_dmy(match: re.Match) -> Iterator[Candidate]:
    day, month, year = int(match.group("d")), int(match.group("m")), match.group("y")
    sep = match.group("s")
    width = len(match.group("d"))

    def render(d: int, m: int, y: str) -> str:
        return f"{d:0{width}d}{sep}{m:02d}{sep}{y}"

    for delta, quality in ((1, 0.85), (-1, 0.85), (2, 0.75)):
        new_year = str(int(year) + delta) if len(year) == 4 else year
        if new_year != year:
            yield Candidate(render(day, month, new_year), f"data:anno{delta:+d}", quality)

    for delta, quality in ((1, 0.80), (-1, 0.80)):
        new_month = month + delta
        if 1 <= new_month <= 12:
            yield Candidate(render(day, new_month, year), f"data:mese{delta:+d}", quality)

    # Il giorno e il mese scambiati: l'errore classico fra convenzione
    # europea e americana, e quindi un distrattore molto credibile.
    if day != month and day <= 12 and month <= 31:
        yield Candidate(render(month, day, year), "data:giorno-mese-scambiati", 0.90)


def _shift_month_year(match: re.Match) -> Iterator[Candidate]:
    name = match.group("month").lower()
    year = int(match.group("year"))
    for months in (_MONTHS_IT, _MONTHS_EN):
        if name in months:
            index = months.index(name)
            break
    else:
        return

    original = match.group("month")
    capitalized = original[:1].isupper()

    def render(month_index: int, y: int) -> str:
        word = months[month_index % 12]
        if capitalized:
            word = word.capitalize()
        return f"{word} {y}"

    for delta, quality in ((1, 0.85), (-1, 0.85), (3, 0.70)):
        yield Candidate(render(index + delta, year), f"data:mese{delta:+d}", quality)
    for delta, quality in ((1, 0.85), (-1, 0.85)):
        yield Candidate(render(index, year + delta), f"data:anno{delta:+d}", quality)
