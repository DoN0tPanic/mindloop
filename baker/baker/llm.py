"""Client Ollama minimale, senza dipendenze esterne.

Il baker parla con il servizio locale via HTTP e chiede un array JSON di tre
stringhe. Se la chiamata fallisce o il modello risponde male, si torna una
lista vuota: il quiz degrada al solo campionamento (C4) invece di fermarsi.
"""

from __future__ import annotations

import json
import re
import sys
import urllib.error
import urllib.request

OLLAMA_URL = "http://localhost:11434/api/generate"
TIMEOUT_SECONDS = 60

_LEADING_ARTICLES = frozenset(
    ("the", "a", "an", "il", "lo", "la", "i", "gli", "le", "un", "uno", "una")
)
_ACRONYM_RE = re.compile(r"^[A-Z0-9]+(?:[./-][A-Z0-9]+)*(?:s)?$")

# Quante risposte sbagliate chiedere in UNA chiamata.
#
# Al quiz ne servono 3, ma i filtri a valle ne scartano circa meta' (troppo
# lunghe, alias della risposta, doppioni): chiedendone 3 si finiva spesso con
# 1 o 2, e per rimediare servivano altre chiamate da ~10 secondi l'una. Su un
# mazzo vero 7 card su 25 restavano sotto le 4 opzioni.
#
# Chiederne di piu' in una volta costa quasi niente in piu' -- il tempo se ne
# va nel caricamento del modello e nel prompt, non nelle poche parole in
# uscita -- e da' ai filtri abbastanza materiale da lavorare.
CANDIDATI_RICHIESTI = 6
CANDIDATI_MINIMI = 2

_PROMPT = """Sei un autore di quiz. Genera {wanted} risposte SBAGLIATE ma credibili.

Domanda: {front}
Risposta corretta: {back}
Nota / altro nome della risposta corretta: {answer_note}
Forma della risposta corretta: {shape_hint}
Riferimento di lunghezza della risposta corretta: {length_hint}
Vincolo sugli articoli iniziali: {article_hint}
Argomento del mazzo: {deck_name}
Concetti vicini nel mazzo: {neighbor_backs}

Regole tassative:
- Ogni risposta deve essere FALSA per la domanda data.
- La nota sopra e' un altro nome o una descrizione della risposta corretta:
  non proporla, non parafrasarla e non proporre varianti che le assomigliano.
- Stessa categoria della corretta: se la corretta e' un'organizzazione,
  proponi organizzazioni; se e' un livello dello stack, proponi altri livelli;
  se e' un processo, proponi altri processi. Mai cambiare tipo di cosa.
- Stessa specie e stessa forma contano quanto il contenuto: un distrattore di
  contenuto plausibile ma riconoscibile dalla forma rovina il quiz.
- Nessun sinonimo o parafrasi della risposta corretta.
- Stessa lunghezza (+/- 20%), stesso ordine di grandezza per parole e
  caratteri, stesso registro, stessa lingua della corretta.
- Errori plausibili: confusione con un concetto vicino, dettaglio alterato,
  causa/effetto invertiti. Mai assurdita' o altre materie.
- Nessuna spiegazione, nessuna numerazione, nessun preambolo.

Rispondi con un array JSON di {wanted} stringhe.
"""

# Lo schema resta volutamente largo: se il modello ne restituisce 4 invece di
# 6, sono comunque 4 candidati utili. Vincolarlo a un numero esatto voleva
# dire buttare via l'intera risposta per un elemento in piu' o in meno.
_FORMAT = {
    "type": "array",
    "items": {"type": "string"},
    "minItems": CANDIDATI_MINIMI,
    "maxItems": CANDIDATI_RICHIESTI + 2,
}


def _has_leading_article(text: str) -> bool:
    stripped = text.strip()
    if not stripped:
        return False
    parts = stripped.split(None, 1)
    return len(parts) > 1 and parts[0].lower() in _LEADING_ARTICLES


def _starts_like_acronym(text: str) -> bool:
    return bool(_ACRONYM_RE.fullmatch(text.strip()))


def _length_hint(text: str) -> str:
    stripped = text.strip()
    words = len(stripped.split())
    chars = len(stripped)
    word_label = "parola" if words == 1 else "parole"
    char_label = "carattere" if chars == 1 else "caratteri"
    return f"circa {words} {word_label} e {chars} {char_label}"


def _shape_hint(answer_core: str) -> str:
    if _starts_like_acronym(answer_core):
        return (
            "sigla o abbreviazione; anche i distrattori devono essere sigle o "
            "abbreviazioni, non espansioni per esteso. Esempio: per `IETF` "
            "sono buoni `IEEE`, `W3C`, `ISO`; sono cattivi `World Wide Web "
            "Consortium`, `Internet Society`."
        )
    return (
        "testo per esteso; anche i distrattori devono essere scritti per "
        "esteso, non come sigle isolate o etichette di tipo diverso."
    )


def _article_hint(answer_core: str) -> str:
    if _has_leading_article(answer_core):
        return "la risposta corretta ha gia' un articolo iniziale: non toglierlo"
    return (
        "la risposta corretta NON ha articolo iniziale: non usare articoli "
        "come `the`, `a`, `an`, `il`, `lo`, `la`, `i`, `gli`, `le`, `un`, "
        "`uno`, `una`"
    )


def _capitalize_like_answer(text: str, answer_core: str) -> str:
    if not text:
        return text
    if not answer_core[:1].isupper() or not text[:1].islower():
        return text
    return text[0].upper() + text[1:]


def _normalize_candidate(text: str, answer_core: str) -> str:
    stripped = text.strip()
    if not stripped or _has_leading_article(answer_core):
        return stripped
    parts = stripped.split(None, 1)
    if len(parts) < 2 or parts[0].lower() not in _LEADING_ARTICLES:
        return stripped
    # Qui il problema non e' il contenuto, ma il giveaway di forma: un
    # distrattore plausibile ma marcato da un articolo che la corretta non ha
    # continua a sembrare "giusto", pero' smette di misurare il contenuto.
    return _capitalize_like_answer(parts[1].lstrip(), answer_core).strip()


def _build_prompt(
    front: str,
    answer_core: str,
    answer_note: str | None,
    deck_name: str,
    neighbor_backs: list[str],
) -> str:
    return _PROMPT.format(
        front=front,
        back=answer_core,
        answer_note=(answer_note or "").strip() or "(nessuna)",
        shape_hint=_shape_hint(answer_core),
        length_hint=_length_hint(answer_core),
        article_hint=_article_hint(answer_core),
        deck_name=deck_name,
        neighbor_backs=neighbor_backs[:3],
        wanted=CANDIDATI_RICHIESTI,
    )


def generate_distractors(
    front: str,
    answer_core: str,
    answer_note: str | None,
    deck_name: str,
    neighbor_backs: list[str],
    model: str,
    seed: int,
    temperature: float = 0.8,
) -> list[str]:
    """Ritorna tre stringhe candidate, o `[]` se Ollama non risponde bene."""
    prompt = _build_prompt(front, answer_core, answer_note, deck_name, neighbor_backs)
    body = {
        "model": model,
        "prompt": prompt,
        "stream": False,
        "think": False,
        "format": _FORMAT,
        "options": {"temperature": temperature, "seed": seed},
    }
    request = urllib.request.Request(
        OLLAMA_URL,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            payload = json.loads(response.read().decode("utf-8"))
        raw = payload.get("response")
        if not isinstance(raw, str) or not raw.strip():
            _log_error(model, front, "risposta vuota o mancante")
            return []
        parsed = json.loads(raw)
    except (TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError,
            urllib.error.URLError) as exc:
        _log_error(model, front, str(exc))
        return []

    if not isinstance(parsed, list) or not parsed:
        _log_error(model, front, f"payload inatteso: {parsed!r}")
        return []

    # Un elemento sbagliato non invalida gli altri: prima bastava una stringa
    # vuota per buttare via tutta la risposta, comprese le tre buone che la
    # accompagnavano, e la card restava senza distrattori. Si scarta il
    # singolo elemento e si tiene il resto -- tanto a valle c'e' comunque la
    # validazione, che e' la vera garanzia.
    out = []
    scartati = 0
    for item in parsed:
        if not isinstance(item, str) or not item.strip():
            scartati += 1
            continue
        out.append(_normalize_candidate(item.strip(), answer_core))
    if scartati:
        _log_error(model, front, f"{scartati} elementi non utilizzabili, ignorati")
    return out


def _log_error(model: str, front: str, message: str) -> None:
    clipped = " ".join(front.split())
    if len(clipped) > 80:
        clipped = clipped[:77] + "..."
    print(
        f"llm[{model}] {message} | card: {clipped}",
        file=sys.stderr,
    )
