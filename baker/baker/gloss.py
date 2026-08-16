"""Scorporo della glossa dalla risposta.

Nel mazzo di riferimento (PLAN.md 5.8) 16 risposte su 25 non contengono solo
la risposta: contengono la risposta piu' una precisazione.

    IEEE (Institute of Electrical and Electronics Engineers)
    Application layer
    *also called Layer 7 due to the OSI model
    segment or datagram
    (segment when using TCP, datagram when using UDP)

Il quiz deve confrontare e imitare solo il **nucleo**; la precisazione si
mostra dopo che l'utente ha risposto. Senza questo taglio, il generatore di
distrattori si troverebbe a dover inventare espansioni di acronimi plausibili:
lavoro sprecato su una parte di testo che non e' la risposta.

L'euristica e' volutamente conservativa. In caso di dubbio non taglia: una
glossa lasciata attaccata degrada la qualita' di un distrattore, un nucleo
tagliato male produce una risposta corretta sbagliata.
"""

from __future__ import annotations

from typing import NamedTuple

# Sotto questa lunghezza il nucleo residuo e' sospetto: meglio non tagliare.
_MIN_CORE_CHARS = 2

# Righe che iniziano con questi marcatori sono note, non risposta.
#
# Il trattino NON e' nell'elenco, per quanto capiti di vederlo usare come
# marcatore di nota: in una risposta e' molto piu' spesso un elenco puntato, e
# trattarlo da glossa significherebbe ridurre "- unicast / - multicast /
# - anycast" al solo primo elemento. Vale il principio del modulo: nel dubbio
# non si taglia.
_NOTE_PREFIXES = ("*", "(", "N.B.", "n.b.", "NB:", "Nota:", "Note:")


class Split(NamedTuple):
    core: str
    note: str | None


def split_gloss(back: str) -> Split:
    """Separa `back` in nucleo e glossa.

    Due regole, applicate in quest'ordine:

    1. **Righe di nota.** Le righe finali che iniziano con `*`, `(`, `-` o
       `Nota:` sono glossa. Si staccano dal fondo finche' ce ne sono.
    2. **Parentesi finale.** Se cio' che resta finisce con un gruppo
       `(...)` bilanciato preceduto da altro testo, quel gruppo e' glossa.
       Si stacca **un solo** gruppo, il piu' esterno alla fine: cosi'
       `a segment (TCP) or datagram (UDP) (L4PDU)` perde `(L4PDU)` e
       conserva le parentesi interne, che fanno parte della risposta.
    """
    lines = [ln.rstrip() for ln in back.split("\n")]
    notes: list[str] = []

    while len(lines) > 1:
        last = lines[-1].strip()
        if last and last.startswith(_NOTE_PREFIXES):
            notes.insert(0, _clean_note_line(last))
            lines.pop()
            continue
        if not last:  # riga vuota in coda
            lines.pop()
            continue
        break

    head = "\n".join(lines).strip()
    head, paren_note = _strip_trailing_paren(head)
    if paren_note:
        notes.insert(0, paren_note)

    note = "\n".join(n for n in notes if n).strip() or None
    return Split(core=head, note=note)


def _clean_note_line(line: str) -> str:
    """Toglie il marcatore iniziale e le parentesi che avvolgono tutta la riga."""
    for prefix in ("*", "N.B.", "n.b.", "NB:", "Nota:", "Note:"):
        if line.startswith(prefix):
            line = line[len(prefix):].strip()
            break
    if line.startswith("(") and line.endswith(")") and _balanced(line[1:-1]):
        line = line[1:-1].strip()
    return line


def _strip_trailing_paren(s: str) -> tuple[str, str | None]:
    """Stacca il gruppo `(...)` finale, se ce n'e' uno e se lascia un nucleo."""
    s = s.strip()
    if not s.endswith(")"):
        return s, None

    depth = 0
    open_at = -1
    for i in range(len(s) - 1, -1, -1):
        ch = s[i]
        if ch == ")":
            depth += 1
        elif ch == "(":
            depth -= 1
            if depth == 0:
                open_at = i
                break
    if open_at <= 0:
        # Nessuna parentesi bilanciata, oppure la parentesi apre a inizio
        # stringa: in quel caso l'intera risposta e' fra parentesi e non c'e'
        # nucleo da conservare.
        return s, None

    core = s[:open_at].strip()
    inner = s[open_at + 1: -1].strip()
    if len(core) < _MIN_CORE_CHARS or not inner:
        return s, None
    return core, inner


def _balanced(s: str) -> bool:
    depth = 0
    for ch in s:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth < 0:
                return False
    return depth == 0
