"""Classificazione del tipo di risposta.

Puro euristico, nessun modello. Il tipo decide quale strategia generera' i
distrattori in M1/M4: le regole deterministiche coprono numeri, date, liste e
formule; il campionamento dai vicini copre i termini; l'LLM interviene solo
sulle definizioni e su cio' che resta scoperto.

Se `other` supera il 15% di un mazzo, il classificatore va rivisto per quel
dominio: non e' un fallback accettabile su larga scala, e' un sintomo.
"""

from __future__ import annotations

import re

NUMERIC = "numeric"
DATE = "date"
TERM = "term"
LIST = "list"
FORMULA = "formula"
DEFINITION = "definition"
OTHER = "other"

ALL_TYPES = (NUMERIC, DATE, TERM, LIST, FORMULA, DEFINITION, OTHER)

# Il segnaposto usato dai mazzi per il testo mancante. Non e' un cloze Anki
# (`{{c1::...}}`), e' testo semplice, ma dice la stessa cosa: la risposta e'
# un riempimento, quindi un termine o una lista.
BLANK_RE = re.compile(r"\[\s*\.\.\.\s*\]|\[\s*…\s*\]|_{3,}")

_NUMERIC_RE = re.compile(
    r"""^
    [+\-−]?
    (?:
        \d{1,3}(?:[.,\s]\d{3})+           # 1.500   1 500   1,234
      | \d+                                # 1500    65536   7
    )
    (?:[.,]\d+)*                           # decimali, e anche 255.255.255.0
    (?:\s*[x×]\s*10\s*\^?\s*[+\-]?\d+)?   # 3 x 10^8
    (?:\s*%|\s*[A-Za-z°/]{1,8})?           # unita' o percentuale
    $""",
    re.VERBOSE,
)

# Un anno nudo di quattro cifre e' indistinguibile da una quantita': `1500`
# puo' essere l'anno o il numero di byte di un MTU. Si guarda la domanda.
_BARE_YEAR_RE = re.compile(r"^\s*(1[0-9]{3}|20[0-9]{2})\s*$")
_ERA_YEAR_RE = re.compile(
    r"^\s*[1-9][0-9]{0,3}\s*(a\.?\s?C\.?|b\.?\s?c\.?|d\.?\s?C\.?|a\.?\s?d\.?)\s*$", re.I
)
_DATE_HINT_RE = re.compile(
    r"\b(anno|anni|annata|data|date|dates|year|years|quando|when|secolo|century|"
    r"nacque|nato|morto|born|died|fondat|founded|pubblicat|published|"
    r"rilasciat|released)\w*\b",
    re.I,
)
_DATE_RE = re.compile(
    r"^\s*\d{1,2}\s*[/\-.]\s*\d{1,2}\s*[/\-.]\s*\d{2,4}\s*$"
    r"|^\s*\d{4}\s*[/\-.]\s*\d{1,2}\s*[/\-.]\s*\d{1,2}\s*$"
)
_MONTHS = (
    "gennaio febbraio marzo aprile maggio giugno luglio agosto settembre "
    "ottobre novembre dicembre january february march april may june july "
    "august september october november december"
).split()
_MONTH_DATE_RE = re.compile(
    r"^\s*(\d{1,2}\s+)?(" + "|".join(_MONTHS) + r")\.?\s+\d{1,4}\s*$", re.I
)

_FORMULA_RE = re.compile(r"[=≠≤≥]|\\frac|\\sum|\^[0-9{]|\$.+\$")

# Separatori di lista: virgola, punto e virgola, bullet, oppure righe che
# iniziano con un trattino o un numero.
_BULLET_LINE_RE = re.compile(r"^\s*(?:[-•*–]|\d+[.)])\s+", re.M)
_INLINE_SEP_RE = re.compile(r"[;•]|,\s")

_WORD_RE = re.compile(r"[\w'’\-]+", re.UNICODE)

TERM_MAX_WORDS = 3
DEFINITION_MIN_WORDS = 8


def count_blanks(front: str) -> int:
    return len(BLANK_RE.findall(front))


def classify(core: str, front: str = "") -> str:
    """Determina il tipo della risposta.

    `core` e' il nucleo gia' scorporato dalla glossa: classificare il `back`
    intero sbaglierebbe quasi sempre, perche' la glossa e' lunga e farebbe
    passare per definizione qualunque acronimo.
    """
    core = (core or "").strip()
    if not core:
        return OTHER

    words = _WORD_RE.findall(core)
    n_words = len(words)

    # L'ordine e' la specifica. I tipi piu' riconoscibili per forma vengono
    # prima, quelli decisi dalla lunghezza per ultimi.
    if _DATE_RE.match(core) or _MONTH_DATE_RE.match(core) or _ERA_YEAR_RE.match(core):
        return DATE
    if _BARE_YEAR_RE.match(core):
        # Ambiguo per forma: `1500` e' un anno o un numero di byte? Decide la
        # domanda. In assenza di indizi si sceglie NUMERIC, perche' una
        # perturbazione numerica su un anno resta un distrattore utilizzabile,
        # mentre uno slittamento di anni su una quantita' e' assurdo.
        return DATE if _DATE_HINT_RE.search(front or "") else NUMERIC
    if _NUMERIC_RE.match(core):
        return NUMERIC
    if _FORMULA_RE.search(core):
        return FORMULA
    if _is_list(core, n_words):
        return LIST

    # Un segnaposto nel fronte e' un segnale forte: la risposta e' un
    # riempimento, quindi un termine anche quando supera di poco la soglia.
    blanks = count_blanks(front)
    if blanks and n_words <= TERM_MAX_WORDS + 2 * blanks:
        return TERM

    if n_words <= TERM_MAX_WORDS:
        return TERM
    if n_words >= DEFINITION_MIN_WORDS:
        return DEFINITION
    return OTHER


def _is_list(core: str, n_words: int) -> bool:
    if len(_BULLET_LINE_RE.findall(core)) >= 2:
        return True
    if core.count("\n") >= 2 and n_words >= 3:
        return True
    # Una lista in linea deve avere almeno tre elementi separati: due
    # separatori. Con uno solo si tratta quasi sempre di una frase.
    return len(_INLINE_SEP_RE.findall(core)) >= 2 and n_words >= 3
