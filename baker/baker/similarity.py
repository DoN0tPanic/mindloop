"""Misure di somiglianza, senza dipendenze.

In M1 il baker non ha ancora un modello di embedding, e questo modulo e' il
sostituto documentato in PLAN.md 5.4: TF-IDF su n-grammi di caratteri. E'
peggiore di un embedder vero -- non sa che "Data Link" e "Livello 2" parlano
della stessa cosa -- ma e' sufficiente su mazzi tematicamente omogenei, che
sono quelli in cui i quiz hanno senso, e costa zero installare.

M3 sostituisce solo `vectorize`: tutto il resto (classifica dei vicini,
soglie, esclusioni) resta identico.
"""

from __future__ import annotations

import math
from collections import Counter

NGRAM = 3


def ngrams(s: str, n: int = NGRAM) -> Counter:
    """N-grammi di caratteri, con i bordi marcati.

    I bordi contano: senza di loro "layer" e "relayer" si somigliano piu' di
    quanto dovrebbero.
    """
    s = f" {s.strip()} "
    if len(s) <= n:
        return Counter([s])
    return Counter(s[i: i + n] for i in range(len(s) - n + 1))


def vectorize(texts: list[str]) -> list[dict[str, float]]:
    """Vettori TF-IDF normalizzati, uno per testo."""
    docs = [ngrams(t) for t in texts]
    n_docs = len(docs) or 1

    df: Counter = Counter()
    for doc in docs:
        df.update(doc.keys())

    vectors: list[dict[str, float]] = []
    for doc in docs:
        vec: dict[str, float] = {}
        for gram, count in doc.items():
            idf = math.log((1 + n_docs) / (1 + df[gram])) + 1.0
            vec[gram] = (1 + math.log(count)) * idf
        norm = math.sqrt(sum(v * v for v in vec.values())) or 1.0
        vectors.append({g: v / norm for g, v in vec.items()})
    return vectors


def cosine(a: dict[str, float], b: dict[str, float]) -> float:
    if len(a) > len(b):
        a, b = b, a
    return sum(w * b.get(g, 0.0) for g, w in a.items())


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(
                min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb))
            )
        previous = current
    return previous[-1]


def edit_ratio(a: str, b: str) -> float:
    """1.0 se identiche, 0.0 se completamente diverse."""
    longest = max(len(a), len(b))
    if not longest:
        return 1.0
    return 1.0 - levenshtein(a, b) / longest


def token_jaccard(a: str, b: str) -> float:
    ta, tb = set(a.split()), set(b.split())
    if not ta or not tb:
        return 0.0
    return len(ta & tb) / len(ta | tb)


def tokens_subset(needle: str, haystack: str) -> bool:
    """Vero se ogni parola di `needle` compare in `haystack`.

    Su insiemi e non su sequenze: l'ordine delle parole cambia fra due modi di
    dire la stessa cosa ("Physical layer" contro "Layer 1 (Physical)") molto
    piu' spesso di quanto cambi il significato.

    Il confronto e' per parole intere e non per sottostringa, quindi "layer 3"
    non risulta contenuto in "layer 30".
    """
    ned = set(needle.split())
    return bool(ned) and ned <= set(haystack.split())
