"""Test di M0.

Il test piu' importante di questo file e' `TestUidFrozen`: se si rompe, non
significa "aggiorna il valore atteso", significa che la ricetta dell'uid e'
cambiata e che ogni utente che ha gia' studiato perderebbe i progressi al
prossimo import. La reazione corretta e' o annullare la modifica, o cambiare
UID_RECIPE_VERSION e predisporre la migrazione (PLAN.md 5.1).
"""

from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from baker import pipeline  # noqa: E402
from baker import db as dbmod  # noqa: E402
from baker.tmpdir import ScratchDirectory  # noqa: E402
from baker.classify import DATE, DEFINITION, FORMULA, LIST, NUMERIC, TERM, classify  # noqa: E402
from baker.gloss import split_gloss  # noqa: E402
from baker.sources import delimited  # noqa: E402
from baker.textnorm import cnorm, display_norm, hnorm  # noqa: E402
from baker.uid import card_uid  # noqa: E402

# Il mazzo di riferimento non sta nel repository: e' materiale di terzi e
# pesa. Chi vuole eseguire anche i test che lo usano indica dove si trova:
#
#     MINDLOOP_TEST_DECK=/percorso/del/mazzo.apkg python -m unittest ...
#
# Senza quella variabile i test che dipendono dal mazzo si dichiarano saltati
# (vedi lo skipUnless piu' sotto): il resto della suite gira lo stesso.
DECK = Path(os.environ.get("MINDLOOP_TEST_DECK", "mazzo-di-riferimento-assente.apkg"))


class TestUidFrozen(unittest.TestCase):
    """Valori d'oro. Non aggiornare alla leggera: vedi il docstring in testa."""

    GOLDEN = {
        "In the 5-layer TCP/IP model, what is Layer 5?": "faooyfp2g47nkmzojbk3lpt7kn",
        "What does PDU stand for?": "id33p4flrhw4lmuyx7gwg54kau",
    }

    def test_golden(self):
        for front, expected in self.GOLDEN.items():
            self.assertEqual(card_uid(front), expected, front)

    def test_invariant_to_presentation(self):
        """Le differenze che non cambiano la domanda non cambiano l'uid.

        E' la proprieta' che permette a un mazzo importato sul telefono e allo
        stesso mazzo cotto sul PC di riferirsi alle stesse card.
        """
        base = "In the 5-layer TCP/IP model, what is Layer 5?"
        for variant in (
            "In the 5-layer TCP/IP model, what is&nbsp;Layer 5?",
            "  IN THE 5-LAYER TCP/IP MODEL, WHAT IS   LAYER 5?  ",
            "<div>In the 5-layer TCP/IP model, what is Layer 5?</div>",
            "In the 5-layer TCP/IP model,\twhat is\nLayer 5?",
        ):
            self.assertEqual(card_uid(variant), card_uid(base), variant)

    def test_different_question_different_uid(self):
        self.assertNotEqual(card_uid("What is Layer 4?"), card_uid("What is Layer 5?"))


class TestHnorm(unittest.TestCase):
    def test_nbsp_is_whitespace(self):
        # In Python `\s` include U+00A0, in Kotlin no: l'elenco esplicito di
        # WS_CODEPOINTS esiste per non dipendere da questa differenza.
        self.assertEqual(hnorm("a\u00a0b"), "a b")

    def test_tag_becomes_space_not_nothing(self):
        self.assertEqual(hnorm("a<br>b"), "a b")

    def test_entities_after_tags(self):
        # `&lt;b&gt;` e' contenuto, non un tag: deve sopravvivere.
        self.assertEqual(hnorm("uso di &lt;b&gt; in HTML"), "uso di <b> in html")

    def test_zero_width_removed(self):
        self.assertEqual(hnorm("ab\u200bc"), "abc")


class TestCnorm(unittest.TestCase):
    def test_strips_leading_article(self):
        self.assertEqual(cnorm("a packet"), cnorm("packet"))

    def test_keeps_internal_words(self):
        self.assertEqual(cnorm("The payload of a frame"), "payload of a frame")

    def test_strips_punctuation(self):
        self.assertEqual(cnorm("Layer 2 (Data Link)"), "layer 2 data link")


class TestDisplayNorm(unittest.TestCase):
    def test_br_becomes_newline(self):
        self.assertEqual(display_norm("Application layer<br>*nota"), "Application layer\n*nota")

    def test_typographic_quotes(self):
        self.assertEqual(display_norm("layer\u2019s header"), "layer's header")


class TestGloss(unittest.TestCase):
    CASES = [
        ("IEEE (Institute of Electrical and Electronics Engineers)",
         "IEEE", "Institute of Electrical and Electronics Engineers"),
        ("Physical layer", "Physical layer", None),
        ("Application layer\n*also called Layer 7 due to the OSI model",
         "Application layer", "also called Layer 7 due to the OSI model"),
        ("segment or datagram\n(segment when using TCP, datagram when using UDP)",
         "segment or datagram", "segment when using TCP, datagram when using UDP"),
        ("Decapsulation (or de-encapsulation)", "Decapsulation", "or de-encapsulation"),
        ("Layer 5 (Application layer)\n*Also known as Layer 7 due to the OSI model",
         "Layer 5", "Application layer\nAlso known as Layer 7 due to the OSI model"),
    ]

    def test_cases(self):
        for back, core, note in self.CASES:
            got = split_gloss(back)
            self.assertEqual(got.core, core, back)
            self.assertEqual(got.note, note, back)

    def test_keeps_inner_parens(self):
        """Si stacca un solo gruppo finale: le parentesi interne sono risposta."""
        got = split_gloss("a segment (TCP) or datagram (UDP) (L4PDU)")
        self.assertEqual(got.core, "a segment (TCP) or datagram (UDP)")
        self.assertEqual(got.note, "L4PDU")

    def test_bullet_list_is_not_a_gloss(self):
        """Un elenco puntato e' la risposta, non una nota in coda.

        Col trattino fra i marcatori di nota, questa risposta si riduceva al
        solo primo elemento.
        """
        back = "- unicast\n- multicast\n- anycast"
        got = split_gloss(back)
        self.assertEqual(got.core, back)
        self.assertIsNone(got.note)

    def test_never_empties_the_core(self):
        """Se tagliare lascerebbe il nucleo vuoto, non si taglia."""
        for back in ("(solo una parentesi)", "(a)", ""):
            self.assertEqual(split_gloss(back).core, back.strip())


class TestClassify(unittest.TestCase):
    CASES = [
        ("1500", NUMERIC), ("1.500", NUMERIC), ("32,7 %", NUMERIC),
        ("255.255.255.0", NUMERIC), ("64 KB", NUMERIC),
        ("12/03/1998", DATE), ("aprile 1994", DATE), ("753 a.C.", DATE),
        ("Physical layer", TERM), ("IEEE", TERM), ("Layer 2", TERM),
        ("segment or datagram", TERM),
        ("E = mc^2", FORMULA), ("v = s/t", FORMULA),
        ("Fisico, Collegamento, Rete, Trasporto", LIST),
        ("- uno\n- due\n- tre", LIST),
        ("Il processo con cui a un messaggio vengono aggiunte le intestazioni "
         "prima della trasmissione", DEFINITION),
    ]

    def test_cases(self):
        for core, expected in self.CASES:
            self.assertEqual(classify(core), expected, core)

    def test_bare_year_needs_a_hint_from_the_question(self):
        """`1500` e' un anno o un MTU? La forma non basta, decide la domanda."""
        self.assertEqual(classify("1969", "Qual e' l'MTU di Ethernet?"), NUMERIC)
        self.assertEqual(classify("1969", "In che anno nacque ARPANET?"), DATE)
        self.assertEqual(classify("1969", "When was ARPANET born?"), DATE)
        self.assertEqual(classify("1969", ""), NUMERIC)

    def test_blank_marker_widens_term(self):
        """Con dei segnaposto nel fronte la risposta e' un riempimento."""
        core = "adjacent-layer interaction"
        self.assertEqual(classify(core, "Interaction ... is called [...]."), TERM)


class TestDelimitedSource(unittest.TestCase):
    def _read(self, text: str, suffix: str = ".csv"):
        with ScratchDirectory() as tmp:
            path = Path(tmp) / f"mazzo{suffix}"
            path.write_text(text, encoding="utf-8")
            return delimited.read(path)

    def test_multiline_quoted_field(self):
        """Gli a capo dentro un campo quotato devono sopravvivere.

        Con `splitlines()` il parser non li vedeva e un elenco puntato
        arrivava tutto su una riga.
        """
        src = self._read('front,back\nTipi IPv6?,"- unicast\n- multicast\n- anycast"\n')
        self.assertEqual(len(src.notes), 1)
        self.assertEqual(src.notes[0].back.count("\n"), 2)

    def test_extra_columns_are_rejoined_and_reported(self):
        """Virgole non quotate: si ricuce e si avvisa, non si tronca."""
        src = self._read("front,back\nChe cos'e' il TCP?,affidabile, ordinato\n")
        self.assertEqual(src.notes[0].back, "affidabile, ordinato")
        self.assertTrue(src.warnings)

    def test_header_skipped_only_when_it_is_one(self):
        src = self._read("front,back\nDomanda?,Risposta\n")
        self.assertEqual(len(src.notes), 1)
        src = self._read("Domanda?,Risposta\nAltra?,Altra\n")
        self.assertEqual(len(src.notes), 2)


@unittest.skipUnless(DECK.exists(), f"mazzo di riferimento assente: {DECK}")
class TestReferenceDeck(unittest.TestCase):
    """Criterio di uscita da M0 sul mazzo di PLAN.md 5.8."""

    def setUp(self):
        self._tmp = ScratchDirectory()
        self.work = Path(self._tmp.name) / "work.db"

    def tearDown(self):
        self._tmp.cleanup()

    def test_full_pipeline(self):
        result = pipeline.ingest(DECK, self.work, raccolta_name="Reti")
        self.assertEqual(result.read, 25)
        self.assertEqual(result.inserted, 25)

        norm = pipeline.normalize(self.work)
        self.assertEqual(norm["cards"], 25)
        self.assertEqual(norm["empty_core"], 0)
        self.assertEqual(norm["with_gloss"], 16)

        stats = pipeline.classify(self.work)
        self.assertEqual(stats["_with_blanks"], 10)
        total = 25
        self.assertLessEqual(
            stats["other"] / total, 0.15,
            "'other' sopra il 15%: il classificatore non regge questo dominio",
        )

    def test_does_not_read_the_v1_stub(self):
        """Il v3 contiene anche un collection.anki2 con una card fittizia.

        Leggerlo darebbe 1 card invece di 25, senza errori.
        """
        result = pipeline.ingest(DECK, self.work)
        self.assertGreater(result.read, 1)

    def test_reimport_shares_cards_instead_of_duplicating(self):
        pipeline.ingest(DECK, self.work, sezione_name="prima")
        second = pipeline.ingest(DECK, self.work, sezione_name="seconda")
        self.assertEqual(second.inserted, 0)
        self.assertEqual(second.shared, 25)

        con = dbmod.open_db(self.work)
        cards = con.execute("SELECT COUNT(*) FROM card").fetchone()[0]
        links = con.execute("SELECT COUNT(*) FROM card_sezione").fetchone()[0]
        con.close()
        # Una card sola, due appartenenze: e' cio' che permette di rimuovere
        # una sezione senza portarsi via le card condivise (PLAN.md 4.1).
        self.assertEqual(cards, 25)
        self.assertEqual(links, 50)


if __name__ == "__main__":
    unittest.main(verbosity=2)
