"""Le due relazioni fra card: vicinanza ed esclusione.

**Vicinanza** dice da chi conviene pescare i distrattori a runtime. E' una
classifica: piu' in alto, piu' la risposta di quella card e' confondibile con
la nostra.

**Esclusione** dice da chi non si deve pescare mai, perche' la risposta di
quella card e' anch'essa corretta per la nostra domanda (C10). Non e' una
questione di qualita': mostrare una risposta giusta fra le opzioni sbagliate
punisce chi ha capito, ed e' l'errore peggiore che un quiz possa fare.

L'esclusione **deve** essere calcolata qui, sul desktop: il telefono non ha
ne' embedding ne' modello, e a runtime non ha modo di accorgersene.
"""

from __future__ import annotations

import sqlite3
from typing import NamedTuple

from .similarity import cosine, token_jaccard, tokens_subset, vectorize

NEIGHBOR_K = 10

# Soglia dell'esclusione per sovrapposizione di token.
JACCARD_EXCLUDE = 0.60

# Sotto questo numero di token un nucleo non puo' far scattare il
# contenimento: "data" compare dentro "Local Network layer (a.k.a. Data Link
# layer)" senza che le due risposte c'entrino nulla l'una con l'altra.
MIN_TOKENS_FOR_CONTAINMENT = 2

# NOTA su una regola che c'era e non c'e' piu'.
#
# La prima versione escludeva anche le coppie con distanza di edit sopra 0.85.
# Sul mazzo di riferimento questo escludeva «Layer 2» come distrattore di
# «Layer 1» -- differiscono di un carattere su sette -- cioe' esattamente le
# opzioni migliori del mazzo, e svuotava i quiz delle card 9-13.
#
# La lezione e' che la vicinanza lessicale non e' equivalenza semantica, e
# sulle risposte corte le due cose sono spesso in opposizione: piu' due
# risposte si somigliano nella forma, piu' e' probabile che siano gli elementi
# contrapposti di una stessa serie. Nessuna soglia su una distanza di stringhe
# puo' distinguere «Layer 1»/«Layer 2» da «Layer 3»/«Livello 3».


class Card(NamedTuple):
    uid: str
    core: str
    core_norm: str
    back_norm: str      # nucleo + glossa, normalizzati: la risposta "completa"


def load_cards(con: sqlite3.Connection) -> list[Card]:
    from .textnorm import cnorm

    rows = con.execute(
        "SELECT uid, answer_core, core_norm, answer_note FROM card ORDER BY source_ord"
    ).fetchall()
    cards = []
    for r in rows:
        full = " ".join(filter(None, (r["answer_core"], r["answer_note"])))
        cards.append(
            Card(
                uid=r["uid"],
                core=r["answer_core"] or "",
                core_norm=r["core_norm"] or "",
                back_norm=cnorm(full),
            )
        )
    return cards


def build_neighbors(con: sqlite3.Connection, k: int = NEIGHBOR_K) -> int:
    """Riempie `neighbor` con i k piu' simili per ciascuna card.

    Il vettore si costruisce sul **nucleo della risposta**, non sulla domanda:
    quel che deve essere confondibile e' l'opzione che l'utente legge. Con le
    domande, le card 4-8 del mazzo di riferimento ("what is Layer N?")
    risulterebbero vicinissime fra loro per via del testo condiviso, e la
    classifica direbbe poco.
    """
    cards = load_cards(con)
    if len(cards) < 2:
        con.execute("DELETE FROM neighbor")
        return 0

    vectors = vectorize([c.core for c in cards])
    con.execute("DELETE FROM neighbor")

    written = 0
    for i, card in enumerate(cards):
        scored = [
            (cosine(vectors[i], vectors[j]), cards[j].uid)
            for j in range(len(cards))
            if j != i
        ]
        scored.sort(key=lambda t: (-t[0], t[1]))
        for rank, (sim, other) in enumerate(scored[:k]):
            con.execute(
                "INSERT INTO neighbor(card_uid, other_uid, rank, sim) VALUES(?,?,?,?)",
                (card.uid, other, rank, round(sim, 4)),
            )
            written += 1
    return written


def build_exclusions(con: sqlite3.Connection) -> dict[str, int]:
    """Riempie `exclusion`. Tre regole lessicali, nessun modello.

    1. **Stesso nucleo.** Due card con la stessa risposta: ovvia, reciproca.

    2. **Contenimento.** Tutti i token del nucleo di A compaiono nella
       risposta completa di B. E' il caso del mazzo di riferimento: la card 4
       chiede "what is Layer 3?" e risponde "Internet layer"; la card 11
       chiede quale livello usa gli indirizzi IP e risponde "Layer 3 (Internet
       layer, a.k.a. Network layer)". "Internet layer" e' una risposta
       corretta anche alla domanda della card 11, quindi non puo' esserne un
       distrattore. La relazione e' **asimmetrica**: si esclude A come opzione
       per B.

       Il confronto e' su **insiemi** di token e non su sequenze: la card 9
       risponde "Layer 1 (Physical)" e la card 4 "Physical layer", che come
       sequenza non compare da nessuna parte, ma come insieme si'.

    3. **Sovrapposizione forte.** Jaccard sui token sopra 0.60, fra nuclei di
       almeno due parole: prende le riformulazioni, come "segment or datagram"
       contro "a segment (TCP) or datagram (UDP)". Reciproca.

    Resta scoperto tutto cio' che e' equivalente senza condividere parole --
    "Livello di collegamento" e "Data Link" -- ed e' esattamente il buco che
    gli embedding di M3 vanno a chiudere.
    """
    cards = load_cards(con)
    con.execute("DELETE FROM exclusion WHERE reason != 'user_burned'")

    stats = {"same_core": 0, "contained": 0, "overlap": 0}
    for i, a in enumerate(cards):
        for j, b in enumerate(cards):
            if i == j or not a.core_norm or not b.core_norm:
                continue

            a_tokens = a.core_norm.split()
            reason = None
            if a.core_norm == b.core_norm:
                reason = "same_core"
            elif (
                len(a_tokens) >= MIN_TOKENS_FOR_CONTAINMENT
                and tokens_subset(a.core_norm, b.back_norm)
            ):
                # Il nucleo di A vive dentro la risposta completa di B:
                # A e' una risposta accettabile alla domanda di B.
                reason = "contained"
            elif (
                len(a_tokens) >= 2
                and len(b.core_norm.split()) >= 2
                and token_jaccard(a.core_norm, b.core_norm) >= JACCARD_EXCLUDE
            ):
                reason = "overlap"

            if reason:
                con.execute(
                    "INSERT INTO exclusion(card_uid, other_uid, reason) "
                    "VALUES(?,?,?) ON CONFLICT DO NOTHING",
                    (b.uid, a.uid, reason),
                )
                stats[reason] += 1
    return stats


def excluded_texts(con: sqlite3.Connection, card_uid: str) -> set[str]:
    """I `core_norm` che non possono comparire come opzione per questa card."""
    rows = con.execute(
        "SELECT c.core_norm FROM exclusion e JOIN card c ON c.uid = e.other_uid "
        "WHERE e.card_uid = ?",
        (card_uid,),
    )
    return {r[0] for r in rows if r[0]}
