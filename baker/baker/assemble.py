"""Assemblaggio di un quiz a scelta multipla.

Questo modulo non serve al baker: serve a **guardare** il risultato. E'
l'implementazione di riferimento dell'algoritmo di PLAN.md 6.3, quello che
girera' in Kotlin dentro l'app, e sta qui per due motivi.

Il primo e' che il criterio di uscita da M1 dice "una lettura manuale dei 25
quiz non trova nemmeno un'opzione corretta spacciata per sbagliata". Senza
questo modulo quella lettura non e' possibile: nel `.qzd` ci sono pool,
vicini ed esclusioni, ma il quiz non esiste finche' qualcuno non li mette
insieme.

Il secondo e' che quando arrivera' il `QuizAssembler` in Kotlin, avra' un
riferimento eseguibile con cui essere confrontato invece di una descrizione a
parole.

**Nessuna generazione qui dentro** (C3): ogni opzione o viene dal pool cotto,
o e' la risposta corretta di un'altra card della raccolta.
"""

from __future__ import annotations

import random
import sqlite3
from typing import NamedTuple

from .textnorm import norm_for_compare
from .validate import length_ok

OPTIONS = 4              # una corretta + tre distrattori
FROM_POOL = 2            # quante prenderne dal pool cotto
FROM_NEIGHBOURS = 2      # quante pescarne dai vicini
NEIGHBOUR_WINDOW = 5     # si pesca a caso fra i primi N vicini

# Sotto questa somiglianza un "vicino" non e' un vicino: e' una card qualsiasi
# che si e' trovata in cima a una classifica corta. Prenderla non e' un
# errore -- serve comunque a riempire le quattro opzioni -- ma va contata come
# pescata a caso, altrimenti le statistiche dicono che il campionamento sta
# funzionando quando non sta funzionando. Sul mazzo di riferimento, con il
# TF-IDF di M1, un terzo degli archi in cima alla classifica sta sotto questa
# soglia: e' la misura di quanto ci sia da guadagnare dagli embedding di M3.
MIN_NEIGHBOUR_SIM = 0.10


class Option(NamedTuple):
    text: str
    correct: bool
    origin: str          # pool | sibling | random


class Quiz(NamedTuple):
    card_uid: str
    front: str
    answer: str
    note: str | None
    options: list[Option]

    @property
    def complete(self) -> bool:
        return len(self.options) == OPTIONS


def assemble(con: sqlite3.Connection, card_uid: str, rng: random.Random) -> Quiz:
    card = con.execute(
        "SELECT uid, front, answer_core, answer_note, core_norm, answer_type "
        "FROM card WHERE uid = ?",
        (card_uid,),
    ).fetchone()

    answer_type = card["answer_type"]
    answer_norm = card["core_norm"] or norm_for_compare(
        card["answer_core"], answer_type
    )
    banned = _banned_norms(con, card_uid) | {answer_norm}
    taken: list[Option] = []
    seen: set[str] = set()
    strict = True

    def shape_ok(text: str, answer: str) -> bool:
        return length_ok(text, answer)

    def take(text: str, origin: str) -> bool:
        nonlocal strict
        norm = norm_for_compare(text, answer_type)
        if not norm or norm in banned or norm in seen:
            return False
        # Il controllo di forma vale anche su cio' che si pesca a runtime, non
        # solo sul pool cotto: offrire un elenco di quattro voci come opzione
        # per "quante porte TCP esistono?" regala la risposta a chi guarda la
        # forma invece del contenuto. Al secondo giro il vincolo cade, perche'
        # quattro opzioni con una brutta valgono piu' di tre.
        if strict and not shape_ok(text, card["answer_core"]):
            return False
        seen.add(norm)
        taken.append(Option(text=text, correct=False, origin=origin))
        return True

    for row in con.execute(
        "SELECT text FROM distractor WHERE card_uid = ? ORDER BY quality DESC, text",
        (card_uid,),
    ):
        if len(taken) >= FROM_POOL:
            break
        take(row["text"], "pool")

    neighbours = con.execute(
        "SELECT c.answer_core AS core, n.sim AS sim FROM neighbor n "
        "JOIN card c ON c.uid = n.other_uid "
        "WHERE n.card_uid = ? AND n.sim >= ? ORDER BY n.rank LIMIT ?",
        (card_uid, MIN_NEIGHBOUR_SIM, NEIGHBOUR_WINDOW),
    ).fetchall()
    # Si pesca nella finestra invece di prendere sempre i primi, perche' due
    # ripassi della stessa card non devono mostrare le stesse identiche
    # opzioni -- ma **pesati sulla somiglianza**, non a caso.
    #
    # Il decadimento e' ripido: per "adjacent-layer interaction" il primo
    # vicino e' "same-layer interaction" a 0.60 e il secondo sta a 0.18. Con
    # un sorteggio uniforme il distrattore migliore del mazzo veniva scartato
    # tre volte su cinque, a favore di opzioni che nessuno sceglierebbe mai.
    for row in _weighted_shuffle(neighbours, rng):
        if len(taken) >= FROM_POOL + FROM_NEIGHBOURS:
            break
        take(row["core"], "sibling")

    # Riempimento. Prima le card dello **stesso tipo di risposta**: per una
    # domanda che vuole un numero, un altro numero e' un'opzione che va almeno
    # letta, mentre una data o una formula si scartano senza pensarci e
    # riducono di fatto il quiz a tre opzioni. Il tipo e' gia' nel database,
    # quindi non costa niente ne' qui ne' sul telefono.
    fallback = con.execute(
        "SELECT answer_core AS core FROM card WHERE uid != ? AND answer_type = ?",
        (card_uid, answer_type),
    ).fetchall()
    rest = con.execute(
        "SELECT answer_core AS core FROM card WHERE uid != ? AND answer_type != ?",
        (card_uid, answer_type),
    ).fetchall()
    fallback = rng.sample(fallback, len(fallback)) + rng.sample(rest, len(rest))
    if len(taken) < OPTIONS - 1:
        for row in fallback:
            if len(taken) >= OPTIONS - 1:
                break
            take(row["core"], "random")

    # Secondo giro senza vincolo di forma: si preferisce un'opzione di
    # lunghezza sbagliata a un quiz con tre opzioni invece di quattro.
    if len(taken) < OPTIONS - 1:
        strict = False
        for row in fallback:
            if len(taken) >= OPTIONS - 1:
                break
            take(row["core"], "random")

    options = taken[: OPTIONS - 1] + [
        Option(text=card["answer_core"], correct=True, origin="answer")
    ]
    rng.shuffle(options)
    return Quiz(
        card_uid=card["uid"],
        front=card["front"],
        answer=card["answer_core"],
        note=card["answer_note"],
        options=options,
    )


def _weighted_shuffle(rows: list, rng: random.Random) -> list:
    """Ordina a caso, ma con probabilita' proporzionale alla somiglianza.

    Estrazione senza reimmissione: a ogni giro si sorteggia fra i rimasti con
    peso `sim`. Un vicino a 0.60 esce quasi sempre prima di uno a 0.11, ma non
    sempre, ed e' quel "non sempre" a dare varieta' ai ripassi successivi.
    """
    remaining = list(rows)
    out = []
    while remaining:
        weights = [max(r["sim"], 1e-6) for r in remaining]
        total = sum(weights)
        threshold = rng.random() * total
        cumulative = 0.0
        for index, weight in enumerate(weights):
            cumulative += weight
            if cumulative >= threshold:
                break
        out.append(remaining.pop(index))
    return out


def _banned_norms(con: sqlite3.Connection, card_uid: str) -> set[str]:
    """I `core_norm` che non possono comparire fra le opzioni (C10)."""
    rows = con.execute(
        "SELECT c.core_norm FROM exclusion e JOIN card c ON c.uid = e.other_uid "
        "WHERE e.card_uid = ?",
        (card_uid,),
    )
    return {r[0] for r in rows if r[0]}


def assemble_all(con: sqlite3.Connection, seed: int = 0) -> list[Quiz]:
    rng = random.Random(seed)
    uids = [r[0] for r in con.execute("SELECT uid FROM card ORDER BY source_ord")]
    return [assemble(con, uid, rng) for uid in uids]
