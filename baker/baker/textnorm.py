"""Normalizzazione del testo.

Questo modulo contiene funzioni con statuti molto diversi.

`hnorm` e' **congelata**. Entra nel calcolo dell'uid (vedi uid.py), quindi
esiste anche in Kotlin dentro l'app Android, e le due implementazioni devono
produrre lo stesso identico risultato su qualunque input. Ogni euristica in
piu' qui dentro e' un punto in cui possono divergere, e una divergenza
significa progressi di studio persi. Non si tocca senza cambiare il prefisso
di versione dell'uid.

`cnorm` serve al confronto delle risposte. Ha anche lei un gemello in Kotlin
(per giudicare la risposta scritta dall'utente), ma una divergenza qui costa
al massimo un giudizio sbagliato su una card, non i progressi.

`display_norm` vive solo qui: produce il testo che l'utente legge, e puo'
essere raffinata quando si vuole.
"""

from __future__ import annotations

import html
import re
import unicodedata

UID_RECIPE_VERSION = "v1"

# --------------------------------------------------------------------------
# Costanti congelate: replicare ALLA LETTERA in Kotlin.
# --------------------------------------------------------------------------

# Spazi, elencati per code point invece di usare `\s`.
#
# In Python `\s` su stringhe Unicode include U+00A0; in Java/Kotlin no.
# Affidarsi a `\s` significherebbe che baker e app calcolano uid diversi per
# ogni card che contiene un `&nbsp;` -- cioe' quasi tutte quelle esportate da
# Anki. L'elenco esplicito toglie di mezzo la questione.
WS_CODEPOINTS: tuple[int, ...] = (
    0x09,  # tab
    0x0A,  # line feed
    0x0B,  # vertical tab
    0x0C,  # form feed
    0x0D,  # carriage return
    0x20,  # space
    0x85,  # next line
    0xA0,  # no-break space   <- questo e' il &nbsp; di Anki
    0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
    0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
    0x2028, 0x2029,
    0x202F,  # narrow no-break space
    0x205F,
    0x3000,  # ideographic space
)

# Caratteri a larghezza zero: si eliminano, non diventano spazio.
ZW_CODEPOINTS: tuple[int, ...] = (0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF)

WS_CHARS = "".join(chr(c) for c in WS_CODEPOINTS)
ZERO_WIDTH = "".join(chr(c) for c in ZW_CODEPOINTS)

_NEWLINES = frozenset((0x0A, 0x0D, 0x1C, 0x2028, 0x2029))

_WS_RE = re.compile("[" + re.escape(WS_CHARS) + "]+")
_ZW_RE = re.compile("[" + re.escape(ZERO_WIDTH) + "]")
_TAG_RE = re.compile(r"<[^>]*>")


def strip_html(s: str) -> str:
    """Sostituisce ogni tag con uno spazio.

    Uno spazio e non la stringa vuota: `a<br>b` deve diventare `a b`, non
    `ab`. Lo spazio in eccesso lo mangia il collasso successivo.
    """
    return _TAG_RE.sub(" ", s)


def hnorm(s: str) -> str:
    """Normalizzazione per l'hash dell'uid. CONGELATA -- vedi C8.

    L'ordine dei passi e' parte della specifica:

    1. strip dei tag        prima dell'unescape, altrimenti un `&lt;b&gt;`
                            scritto nel contenuto verrebbe scambiato per tag
    2. unescape entita'     `&nbsp;` -> U+00A0, `&amp;` -> `&`
    3. rimozione zero-width
    4. NFC
    5. lowercase            senza locale (in Kotlin: `lowercase()` nudo)
    6. collasso spazi + trim
    """
    s = strip_html(s)
    s = html.unescape(s)
    s = _ZW_RE.sub("", s)
    s = unicodedata.normalize("NFC", s)
    s = s.lower()
    s = _WS_RE.sub(" ", s)
    return s.strip()


# --------------------------------------------------------------------------
# Normalizzazione per il confronto delle risposte
# --------------------------------------------------------------------------

# Articoli tolti dalla testa della risposta: "a packet" e "packet" sono la
# stessa risposta. Solo in testa, mai in mezzo.
_LEADING_ARTICLES = frozenset(
    ("a", "an", "the", "il", "lo", "la", "i", "gli", "le", "un", "uno", "una")
)

_PUNCT_RE = re.compile(r"[^\w\s]", re.UNICODE)


def cnorm(s: str) -> str:
    """Normalizzazione per il confronto (`core_norm`).

    Piu' aggressiva di `hnorm`: toglie punteggiatura e articolo iniziale.
    Serve a dire se due risposte sono "la stessa" -- nel confronto con la
    risposta scritta dall'utente e nella deduplica dei distrattori.
    """
    s = hnorm(s)
    s = _PUNCT_RE.sub(" ", s)
    s = _WS_RE.sub(" ", s).strip()
    if not s:
        return s
    parts = s.split(" ")
    if len(parts) > 1 and parts[0] in _LEADING_ARTICLES:
        parts = parts[1:]
    return " ".join(parts)


def snorm(s: str) -> str:
    """Normalizzazione per il confronto di risposte **strutturate**.

    Numeri, date e formule non vanno trattati come prosa: `cnorm` toglie la
    punteggiatura, e in `H = 2^n - 2` la punteggiatura *e'* la risposta --
    ridotta a "h 2 n 2" diventa indistinguibile da `H = 2^n + 2`, che e' il
    distrattore migliore che si possa costruire.

    Qui si conserva ogni simbolo e si toglie solo cio' che non porta
    significato: maiuscole e spazi. `H = 2^n - 2` e `H=2^n-2` sono la stessa
    formula; `25/05/2018` e `25/05/2019` non sono la stessa data.
    """
    s = hnorm(s)
    return _WS_RE.sub("", s)


# Tipi in cui una differenza di un carattere e' una differenza di sostanza.
STRUCTURED_TYPES = frozenset(("numeric", "date", "formula"))


def norm_for_compare(s: str, answer_type: str | None) -> str:
    """La normalizzazione giusta per il tipo di risposta."""
    return snorm(s) if answer_type in STRUCTURED_TYPES else cnorm(s)


# --------------------------------------------------------------------------
# Normalizzazione per la visualizzazione
# --------------------------------------------------------------------------

_BLOCK_TAG_RE = re.compile(r"</?\s*(br|div|p|li|tr|h[1-6])\b[^>]*>", re.IGNORECASE)
_HSPACE_RE = re.compile(
    "["
    + re.escape("".join(chr(c) for c in WS_CODEPOINTS if c not in _NEWLINES))
    + "]+"
)
_BLANK_LINES_RE = re.compile(r"\n{3,}")

# Apici e trattini tipografici -> ASCII. Riduce le differenze fra una card
# scritta a mano e la stessa incollata da un PDF.
_TYPO_MAP = {
    chr(0x2018): "'", chr(0x2019): "'", chr(0x201A): "'", chr(0x201B): "'",
    chr(0x201C): '"', chr(0x201D): '"', chr(0x201E): '"',
    chr(0x2013): "-", chr(0x2014): "-", chr(0x2212): "-",
    chr(0x2026): "...",
}
_TYPO_RE = re.compile("[" + re.escape("".join(_TYPO_MAP)) + "]")


def display_norm(s: str) -> str:
    """Testo leggibile: i tag di blocco diventano a capo, gli altri spariscono."""
    s = _BLOCK_TAG_RE.sub("\n", s)
    s = strip_html(s)
    s = html.unescape(s)
    s = _ZW_RE.sub("", s)
    s = unicodedata.normalize("NFC", s)
    s = _TYPO_RE.sub(lambda m: _TYPO_MAP[m.group()], s)
    s = s.replace("\r\n", "\n").replace("\r", "\n")
    s = _HSPACE_RE.sub(" ", s)
    s = "\n".join(line.strip() for line in s.split("\n"))
    s = _BLANK_LINES_RE.sub("\n\n", s)
    return s.strip()
