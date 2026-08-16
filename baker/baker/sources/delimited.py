"""Lettura di sorgenti a due colonne: CSV, TSV.

Il minimo indispensabile, e il formato con cui si scrive un mazzo a mano senza
passare da Anki. La prima riga viene saltata solo se sembra un'intestazione.
"""

from __future__ import annotations

import csv
import io
from pathlib import Path

from .base import RawNote, SourceRead

_HEADER_WORDS = {
    "front", "fronte", "question", "domanda", "term", "termine",
    "back", "retro", "answer", "risposta", "definition", "definizione",
}


def _sniff_delimiter(sample: str, suffix: str) -> str:
    if suffix.lower() == ".tsv":
        return "\t"
    try:
        return csv.Sniffer().sniff(sample, delimiters=",;\t").delimiter
    except csv.Error:
        return "\t" if sample.count("\t") > sample.count(",") else ","


def read(path: str | Path) -> SourceRead:
    path = Path(path)
    text = path.read_text(encoding="utf-8-sig")
    delimiter = _sniff_delimiter(text[:4096], path.suffix)

    notes: list[RawNote] = []
    rejoined = 0
    # `newline=""` e non `splitlines()`: una risposta su piu' righe sta fra
    # virgolette e contiene a capo veri, che `splitlines()` toglierebbe dal
    # flusso prima che il parser CSV possa riconoscerli come parte del campo.
    stream = io.StringIO(text, newline="")
    for i, row in enumerate(csv.reader(stream, delimiter=delimiter)):
        if len(row) < 2:
            continue
        front = row[0].strip()
        # Una risposta che contiene virgole senza virgolette viene spezzata dal
        # parser. Ricucire le colonne in eccesso recupera il caso comune -- un
        # mazzo scritto a mano -- invece di troncare la risposta in silenzio.
        if len(row) > 2:
            rejoined += 1
        back = delimiter.join(row[1:]).strip()
        if not front or not back:
            continue
        if i == 0 and front.lower() in _HEADER_WORDS and back.lower() in _HEADER_WORDS:
            continue
        notes.append(RawNote(front=front, back=back, deck="", ord=len(notes)))

    if not notes:
        raise ValueError(f"{path.name}: nessuna riga a due colonne utilizzabile")

    warnings: list[str] = []
    if rejoined:
        warnings.append(
            f"{rejoined} righe avevano piu' di due colonne: le colonne in "
            f"eccesso sono state riunite alla risposta. Se il file ha davvero "
            f"tre o piu' colonne, mettere le risposte fra virgolette."
        )
    return SourceRead(notes=notes, suggested_name=path.stem, warnings=warnings)
