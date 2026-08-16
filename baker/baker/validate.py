"""Validazione dei distrattori.

Un generatore che produce troppo non fa danni: e' qui che si decide. Le
regole sono in ordine di gravita' del difetto che intercettano, e la prima e'
l'unica davvero critica -- un distrattore che e' in realta' corretto punisce
chi ha capito.

Le altre difendono da un problema piu' sottile: il **giveaway**. Se le
opzioni sbagliate si distinguono per forma -- piu' corte, senza unita' di
misura, con la punteggiatura diversa -- l'utente impara a riconoscere lo
stile invece del contenuto, e il quiz smette di misurare qualcosa.
"""

from __future__ import annotations

from collections import Counter
import re
from typing import NamedTuple

from .classify import FORMULA
from .similarity import edit_ratio
from .textnorm import STRUCTURED_TYPES, cnorm, norm_for_compare

# Sopra questa soglia il distrattore e' una riscrittura della risposta.
TOO_SIMILAR = 0.85

# Tolleranza di lunghezza rispetto alla risposta corretta.
LENGTH_TOLERANCE = 0.40
LENGTH_FLOOR = 12          # sotto questa lunghezza il vincolo non si applica

_BANNED_RE = re.compile(
    r"\b(nessuna delle (precedenti|altre)|tutte le (precedenti|altre)|"
    r"none of the above|all of the above|non lo so|i don't know)\b",
    re.I,
)

# Operatori commutativi: scambiare gli operandi intorno a questi non cambia
# il valore della formula. `snorm` toglie TUTTI gli spazi (anche intorno a
# "x"), quindi questo confronto va fatto sul testo grezzo -- dopo snorm
# "banda x RTT" e "RTT x banda" diventano stringhe diverse per costruzione
# (l'ordine dei caratteri cambia) anche se la formula e' la stessa.
_COMMUTATIVE_OP_RE = re.compile(r"\s*[+x×*·]\s*", re.I)


def _commutative_formula_key(s: str) -> str | None:
    """Firma insensibile all'ordine degli operandi commutativi, o None se
    la stringa non ne contiene (nessun rischio, nessun confronto da fare).

    Il lato sinistro di un `=` (il nome della grandezza, es. "BDP") va
    tenuto fuori dal riordino: altrimenti resta incollato a operandi diversi
    a seconda di quale lato dell'operatore occupava nella stringa originale,
    e due formule uguali smettono di produrre la stessa chiave.
    """
    lowered = s.strip().lower()
    if not re.search(r"[+x×*·]", lowered):
        return None
    lhs, sep, rhs = lowered.partition("=")
    target = rhs if sep else lowered
    # .strip() ogni pezzo, non solo il filtro sui vuoti: la parola che
    # precede il primo operatore eredita lo spazio lasciato da `partition`
    # e uno spazio (ord 32) ordina prima di qualunque lettera, quindi senza
    # lo strip il sort non mescola mai il primo operando -- resta sempre in
    # testa, qualunque cosa sia, e due formule equivalenti producono chiavi
    # diverse invece che uguali.
    parts = [p.strip() for p in _COMMUTATIVE_OP_RE.split(target) if p.strip()]
    if len(parts) < 2:
        return None
    canonical = "|".join(sorted(parts))
    return f"{lhs.strip()}={canonical}" if sep else canonical


class Verdict(NamedTuple):
    ok: bool
    reason: str = ""


def _note_alias_norm(s: str, answer_type: str | None) -> str:
    """Normalizza il testo da cercare nella glossa.

    La glossa e' prosa libera anche quando il nucleo e' strutturato: per
    cercare alias testuali servono confini di parola, quindi sui tipi
    strutturati qui si usa `cnorm` invece di `snorm`.
    """
    return cnorm(s) if answer_type in STRUCTURED_TYPES else norm_for_compare(s, answer_type)


def _contains_token_sequence(needle: str, haystack: str) -> bool:
    """Vero se `needle` compare in `haystack` come sequenza contigua di token."""
    ned = needle.split()
    hay = haystack.split()
    if not ned or len(ned) > len(hay):
        return False
    width = len(ned)
    return any(hay[i: i + width] == ned for i in range(len(hay) - width + 1))


def select_texts(
    texts: list[str],
    *,
    answer_core: str,
    answer_norm: str,
    excluded: set[str],
    answer_note: str | None = None,
    answer_type: str | None = None,
) -> tuple[list[str], Counter[str]]:
    """Applica `check()` a una sequenza, preservandone l'ordine."""
    kept: list[str] = []
    seen: set[str] = set()
    rejected: Counter[str] = Counter()
    for text in texts:
        verdict = check(
            text,
            answer_core=answer_core,
            answer_norm=answer_norm,
            excluded=excluded,
            already=seen,
            answer_note=answer_note,
            answer_type=answer_type,
        )
        if verdict.ok:
            kept.append(text)
            seen.add(norm_for_compare(text, answer_type))
        else:
            rejected[verdict.reason] += 1
    return kept, rejected


def check(
    text: str,
    *,
    answer_core: str,
    answer_norm: str,
    excluded: set[str],
    already: set[str],
    answer_note: str | None = None,
    answer_type: str | None = None,
) -> Verdict:
    """Decide se un candidato puo' essere mostrato come opzione sbagliata."""
    stripped = text.strip()
    if not stripped:
        return Verdict(False, "vuoto")

    norm = norm_for_compare(stripped, answer_type)
    if not norm:
        return Verdict(False, "vuoto-dopo-normalizzazione")

    # 1. E' la risposta corretta, o una sua riscrittura.
    if norm == answer_norm:
        return Verdict(False, "uguale-alla-risposta")

    # 1b. Per le formule, anche riscritta scambiando operandi commutativi:
    # "BDP = RTT x banda" non e' un distrattore di "BDP = banda x RTT", e'
    # la stessa formula. E' esattamente il caso peggiore che questo modulo
    # esiste per bloccare (vedi docstring in testa al file) -- qui l'ha
    # prodotto l'LLM, che non ha nozione di algebra, solo di testo.
    if answer_type == FORMULA:
        candidate_key = _commutative_formula_key(stripped)
        if candidate_key is not None and candidate_key == _commutative_formula_key(answer_core):
            return Verdict(False, "equivalente-alla-risposta")

    # Scelta deliberatamente asimmetrica: se la glossa contiene un alias
    # della risposta corretta, o anche solo un suo pezzo, si preferisce
    # scartare un distrattore potenzialmente buono invece di mostrare una
    # risposta corretta fra le sbagliate. C4 regge un pool piu' povero; C10 no.
    note_norm = _note_alias_norm(answer_note or "", answer_type)
    alias_norm = _note_alias_norm(stripped, answer_type)
    if note_norm and alias_norm and _contains_token_sequence(alias_norm, note_norm):
        return Verdict(False, "alias-nella-nota")

    # La soglia di somiglianza vale solo per la prosa. Su una risposta
    # strutturata una differenza di un carattere e' una differenza di
    # sostanza: `25/05/2019` dista un decimo da `25/05/2018` e proprio per
    # questo e' il distrattore giusto. Applicare qui una distanza di stringhe
    # cancellerebbe tutto cio' che il motore a regole sa fare.
    if answer_type not in STRUCTURED_TYPES and edit_ratio(norm, answer_norm) >= TOO_SIMILAR:
        return Verdict(False, "troppo-simile-alla-risposta")

    # 2. E' la risposta di una card che risponde alla stessa cosa (C10).
    if norm in excluded:
        return Verdict(False, "esclusa-perche-corretta")

    # 3. Doppione nel pool.
    if norm in already:
        return Verdict(False, "duplicato")

    # 4. Anomalia di lunghezza: il giveaway piu' comune.
    if not length_ok(stripped, answer_core):
        return Verdict(False, "lunghezza-anomala")

    # 5. Formule di riempimento che non sono risposte.
    if _BANNED_RE.search(stripped):
        return Verdict(False, "formula-vietata")

    return Verdict(True)


def length_ok(candidate: str, answer: str) -> bool:
    reference = len(answer.strip())
    if reference <= LENGTH_FLOOR:
        # Su risposte cortissime la percentuale non dice niente: "packet" e
        # "frame" differiscono del 17% ed e' irrilevante.
        return len(candidate) <= max(LENGTH_FLOOR * 2, reference * 3)
    ratio = len(candidate) / reference
    return 1 - LENGTH_TOLERANCE <= ratio <= 1 + LENGTH_TOLERANCE
