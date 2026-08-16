"""Tipi comuni ai lettori di sorgenti.

Ogni lettore espone una sola funzione `read(path) -> SourceRead`. Le
avvertenze non sono errori: sono cose che l'utente deve sapere sul proprio
file (colonne ricucite, note scartate) e che devono comparire a schermo
invece di restare in un log.
"""

from __future__ import annotations

from typing import NamedTuple


class RawNote(NamedTuple):
    front: str
    back: str
    deck: str
    ord: int


class SourceRead(NamedTuple):
    notes: list[RawNote]
    suggested_name: str
    warnings: list[str]
