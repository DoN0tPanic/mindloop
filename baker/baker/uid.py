"""Identita'.

Nel sistema c'e' una sola identita' che trasporta valore: il `card_uid`.
E' l'unica cosa a cui sono agganciati i progressi di studio, ed e' l'unica
che deve essere calcolata in modo identico dal baker (qui) e dall'app
Android in Kotlin. Vedi PLAN.md C8 e C11.

Raccolte e sezioni sono contenitori organizzativi: la loro identita' e'
casuale e locale al dispositivo che li crea. Se due dispositivi non
concordano su di essa non si perde nulla, perche' i progressi non ci sono
appesi.
"""

from __future__ import annotations

import base64
import hashlib
import uuid

from .textnorm import UID_RECIPE_VERSION, hnorm

# 26 caratteri base32 = 130 bit. Sufficiente e leggibile.
_UID_LEN = 26


def card_uid(front: str) -> str:
    """uid della card, derivato dal solo fronte. CONGELATO -- vedi C8.

        uid = base32( sha256( "v1|" + hnorm(front) ) )[:26]

    Il fronte e' l'unico ingrediente: correggere un refuso nella risposta
    conserva i progressi (giusto), riscrivere la domanda crea una card nuova
    (accettabile: una domanda riformulata e', ai fini della memoria, un'altra
    domanda).

    Se un giorno la ricetta dovesse cambiare, si cambia UID_RECIPE_VERSION e
    il baker emette una mappa di migrazione dentro il .qzd. Non si modifica
    silenziosamente questa funzione.
    """
    payload = f"{UID_RECIPE_VERSION}|{hnorm(front)}".encode("utf-8")
    digest = hashlib.sha256(payload).digest()
    return base64.b32encode(digest).decode("ascii").lower()[:_UID_LEN]


def container_uid() -> str:
    """uid di una raccolta o di una sezione: casuale, non derivato (C11)."""
    return uuid.uuid4().hex
