"""Test di M1: regole, esclusioni, validazione, export, assemblaggio.

Il test che conta e' `TestNoCorrectAnswerAsDistractor`: e' C10 reso
eseguibile. Se si rompe, il quiz sta offrendo una risposta giusta fra le
opzioni sbagliate, cioe' sta punendo chi ha capito.
"""

from __future__ import annotations

import json
import os
import sys
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from baker import assemble, export, llm, pipeline  # noqa: E402
from baker import db as dbmod  # noqa: E402
from baker.classify import FORMULA  # noqa: E402
from baker.tmpdir import ScratchDirectory  # noqa: E402
from baker.relations import build_exclusions  # noqa: E402
from baker.rules import dates, formulas, lists, numeric  # noqa: E402
from baker.rules.base import Corpus  # noqa: E402
from baker.similarity import tokens_subset  # noqa: E402
from baker.textnorm import cnorm  # noqa: E402
from baker.validate import check  # noqa: E402

# Il mazzo di riferimento non sta nel repository: e' materiale di terzi e
# pesa. Chi vuole eseguire anche i test che lo usano indica dove si trova:
#
#     MINDLOOP_TEST_DECK=/percorso/del/mazzo.apkg python -m unittest ...
#
# Senza quella variabile i test che dipendono dal mazzo si dichiarano saltati
# (vedi lo skipUnless piu' sotto): il resto della suite gira lo stesso.
DECK = Path(os.environ.get("MINDLOOP_TEST_DECK", "mazzo-di-riferimento-assente.apkg"))
CSV = Path(__file__).resolve().parents[2] / "testdata" / "misto.csv"


def texts(candidates) -> list[str]:
    return [c.text for c in candidates]


class TestNumericRule(unittest.TestCase):
    def test_preserves_unit_and_separators(self):
        out = texts(numeric.generate("1.500 byte"))
        self.assertIn("3.000 byte", out)
        self.assertIn("750 byte", out)
        # Nessun distrattore deve perdere l'unita': sarebbe un regalo.
        self.assertTrue(all(t.endswith("byte") for t in out), out)

    def test_plain_integer(self):
        out = texts(numeric.generate("65536"))
        self.assertIn("131072", out)

    def test_digit_swap(self):
        self.assertIn("73,2", texts(numeric.generate("37,2")))

    def test_subnet_mask_uses_legal_octets(self):
        out = texts(numeric.generate("255.255.255.0"))
        self.assertTrue(out)
        for text in out:
            octets = [int(o) for o in text.split(".")]
            self.assertTrue(all(o in numeric._MASK_OCTETS for o in octets), text)
        self.assertNotIn("255.255.255.0", out)

    def test_never_emits_the_original(self):
        for core in ("1500", "1,5 %", "64 KB", "255.255.255.0"):
            self.assertNotIn(core, texts(numeric.generate(core)), core)


class TestDateRule(unittest.TestCase):
    def test_year_shift(self):
        out = texts(dates.generate("1969"))
        self.assertIn("1970", out)
        self.assertIn("1968", out)

    def test_day_month_swap(self):
        """Solo quando lo scambio produce una data valida.

        Per `25/05/2018` la regola tace: `05/25/2018` non e' una data, e come
        opzione si scarterebbe senza nemmeno leggerla.
        """
        self.assertIn("03/05/2018", texts(dates.generate("05/03/2018")))
        self.assertNotIn("05/25/2018", texts(dates.generate("25/05/2018")))

    def test_month_year(self):
        out = texts(dates.generate("settembre 1981"))
        self.assertIn("ottobre 1981", out)
        self.assertIn("settembre 1982", out)


class TestListRule(unittest.TestCase):
    def test_replaces_one_element(self):
        corpus = Corpus(
            cores_by_type={"term": ["autenticazione", "cifratura"]}, all_cores=[]
        )
        core = "riservatezza, integrita', disponibilita'"
        out = texts(lists.generate(core, corpus=corpus))
        self.assertTrue(out)
        for text in out:
            items = [i.strip() for i in text.split(",")]
            original = [i.strip() for i in core.split(",")]
            self.assertEqual(len(items), len(original), text)
            differing = sum(1 for a, b in zip(items, original) if a != b)
            self.assertEqual(differing, 1, text)

    def test_without_corpus_produces_nothing(self):
        """Senza materiale da cui pescare, la regola tace invece di inventare."""
        self.assertEqual(texts(lists.generate("a, b, c", corpus=None)), [])


class TestFormulaRule(unittest.TestCase):
    def test_exponent_and_sign(self):
        out = texts(formulas.generate("H = 2^n - 2"))
        self.assertIn("H = 2^n + 2", out)

    def test_operator_swap(self):
        self.assertIn("BDP = banda / RTT", texts(formulas.generate("BDP = banda x RTT")))


class TestValidate(unittest.TestCase):
    def _check(
        self,
        text,
        answer="Physical layer",
        excluded=(),
        already=(),
        answer_note=None,
    ):
        return check(
            text,
            answer_core=answer,
            answer_norm=cnorm(answer),
            excluded=set(excluded),
            already=set(already),
            answer_note=answer_note,
        )

    def test_rejects_the_answer_itself(self):
        self.assertFalse(self._check("Physical layer").ok)
        self.assertFalse(self._check("the physical layer!").ok)

    def test_rejects_excluded(self):
        verdict = self._check("Layer 1", excluded={cnorm("Layer 1")})
        self.assertFalse(verdict.ok)
        self.assertEqual(verdict.reason, "esclusa-perche-corretta")

    def test_rejects_length_giveaway(self):
        long = "Il livello che si occupa della trasmissione dei bit sul mezzo fisico"
        self.assertFalse(self._check(long).ok)

    def test_rejects_banned_phrases(self):
        self.assertFalse(self._check("nessuna delle precedenti").ok)

    def test_short_answers_are_not_length_checked(self):
        """Su "frame" e "packet" la percentuale di lunghezza non dice niente."""
        self.assertTrue(self._check("packet", answer="frame").ok)

    def test_accepts_a_good_distractor(self):
        self.assertTrue(self._check("Transport layer").ok)

    def test_rejects_commutative_formula_rewrite(self):
        """Trovato per davvero in M4: un LLM ha proposto "BDP = RTT x banda"
        come distrattore di "BDP = banda x RTT" -- stessa formula, operandi
        scambiati intorno a una moltiplicazione, quindi matematicamente
        corretta. E' il caso peggiore che questo modulo esiste per bloccare:
        una card sbagliata segnata come giusta perche' sbagliata."""
        verdict = check(
            "BDP = RTT x banda",
            answer_core="BDP = banda x RTT",
            answer_norm=cnorm("BDP = banda x RTT"),
            excluded=set(),
            already=set(),
            answer_type=FORMULA,
        )
        self.assertFalse(verdict.ok)
        self.assertEqual(verdict.reason, "equivalente-alla-risposta")

    def test_accepts_genuinely_different_formulas(self):
        """La correzione sopra non deve diventare troppo aggressiva: divisione
        al posto di moltiplicazione, o un esponente in piu', restano
        distrattori validi -- sono formule diverse, non la stessa riscritta."""
        for candidate in ("BDP = banda / RTT", "BDP = 2^RTT x banda"):
            verdict = check(
                candidate,
                answer_core="BDP = banda x RTT",
                answer_norm=cnorm("BDP = banda x RTT"),
                excluded=set(),
                already=set(),
                answer_type=FORMULA,
            )
            self.assertTrue(verdict.ok, f"{candidate}: {verdict.reason}")


class TestC10AnswerNoteAliases(unittest.TestCase):
    def _check(self, text, answer, answer_note):
        return check(
            text,
            answer_core=answer,
            answer_norm=cnorm(answer),
            excluded=set(),
            already=set(),
            answer_note=answer_note,
        )

    def test_rejects_alias_mentioned_in_answer_note(self):
        verdict = self._check(
            "Data Link layer",
            answer="Local Network layer",
            answer_note="a.k.a., Data Link layer",
        )
        self.assertFalse(verdict.ok)
        self.assertEqual(verdict.reason, "alias-nella-nota")

    def test_accepts_candidate_absent_from_answer_note(self):
        verdict = self._check(
            "Transport layer",
            answer="Local Network layer",
            answer_note="a.k.a., Data Link layer",
        )
        self.assertTrue(verdict.ok, verdict.reason)

    def test_note_match_requires_whole_tokens(self):
        verdict = self._check(
            "port",
            answer="frame",
            answer_note="transport layer",
        )
        self.assertTrue(verdict.ok, verdict.reason)


class TestValidateAfterLlmCleanup(unittest.TestCase):
    def test_cleaned_candidate_is_kept(self):
        candidate = llm._normalize_candidate("The session layer", "Application layer")
        self.assertEqual(candidate, "Session layer")
        verdict = check(
            candidate,
            answer_core="Application layer",
            answer_norm=cnorm("Application layer"),
            excluded=set(),
            already=set(),
        )
        self.assertTrue(verdict.ok, verdict.reason)

    def test_cleaned_candidate_equal_to_answer_is_rejected(self):
        candidate = llm._normalize_candidate("The application layer", "Application layer")
        self.assertEqual(candidate, "Application layer")
        verdict = check(
            candidate,
            answer_core="Application layer",
            answer_norm=cnorm("Application layer"),
            excluded=set(),
            already=set(),
        )
        self.assertFalse(verdict.ok)
        self.assertEqual(verdict.reason, "uguale-alla-risposta")


class TestTokensSubset(unittest.TestCase):
    def test_order_insensitive(self):
        # "Physical layer" contro "Layer 1 (Physical)": stessa cosa, ordine
        # diverso. E' il caso che la versione a sequenza si perdeva.
        self.assertTrue(tokens_subset("physical layer", "layer 1 physical"))

    def test_whole_words_only(self):
        self.assertFalse(tokens_subset("layer 3", "layer 30"))


@unittest.skipUnless(DECK.exists(), f"mazzo di riferimento assente: {DECK}")
class TestReferenceDeckM1(unittest.TestCase):
    """Criterio di uscita da M1 sul mazzo di PLAN.md 5.8."""

    @classmethod
    def setUpClass(cls):
        cls._tmp = ScratchDirectory()
        cls.work = Path(cls._tmp.name) / "work.db"
        pipeline.ingest(DECK, cls.work, raccolta_name="Reti")
        pipeline.normalize(cls.work)
        pipeline.classify(cls.work)
        pipeline.index(cls.work)
        pipeline.generate(cls.work)
        pipeline.validate(cls.work)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def test_layer_family_is_excluded(self):
        """Le due serie parallele non devono presentarsi a vicenda.

        La card 4 chiede "what is Layer 3?" e risponde "Internet layer"; la
        card 11 chiede quale livello usa gli indirizzi IP e risponde
        "Layer 3". Ognuna delle due risposte e' corretta anche per l'altra
        domanda.
        """
        con = dbmod.open_db(self.work)
        pairs = {
            (r["a"], r["b"])
            for r in con.execute(
                "SELECT a.answer_core AS a, b.answer_core AS b FROM exclusion e "
                "JOIN card a ON a.uid = e.card_uid JOIN card b ON b.uid = e.other_uid"
            )
        }
        con.close()
        for layer, name in (
            ("Layer 1", "Physical layer"),
            ("Layer 2", "Local Network layer"),
            ("Layer 3", "Internet layer"),
            ("Layer 4", "Transport layer"),
            ("Layer 5", "Application layer"),
        ):
            self.assertIn((layer, name), pairs, f"{name} non esclusa per {layer}")

    def test_layer_siblings_are_not_excluded(self):
        """...ma «Layer 2» deve restare un distrattore di «Layer 1».

        Una regola basata sulla distanza di edit li escludeva, svuotando i
        quiz della seconda serie.
        """
        con = dbmod.open_db(self.work)
        pairs = {
            (r["a"], r["b"])
            for r in con.execute(
                "SELECT a.answer_core AS a, b.answer_core AS b FROM exclusion e "
                "JOIN card a ON a.uid = e.card_uid JOIN card b ON b.uid = e.other_uid"
            )
        }
        con.close()
        for a, b in (("Layer 1", "Layer 2"), ("Layer 3", "Layer 5"),
                     ("Physical layer", "Transport layer")):
            self.assertNotIn((a, b), pairs, f"{b} esclusa a torto per {a}")

    def test_every_quiz_has_four_options(self):
        con = dbmod.open_db(self.work)
        for seed in range(8):
            for quiz in assemble.assemble_all(con, seed=seed):
                self.assertEqual(
                    len(quiz.options), assemble.OPTIONS,
                    f"seed {seed}: «{quiz.front[:40]}» ha {len(quiz.options)} opzioni",
                )
        con.close()

    def test_no_correct_answer_as_distractor(self):
        """C10, reso eseguibile.

        Nessuna opzione sbagliata puo' essere la risposta corretta della card,
        ne' la risposta di una card marcata come equivalente.
        """
        con = dbmod.open_db(self.work)
        for seed in range(8):
            for quiz in assemble.assemble_all(con, seed=seed):
                banned = assemble._banned_norms(con, quiz.card_uid)
                answer = cnorm(quiz.answer)
                for option in quiz.options:
                    if option.correct:
                        continue
                    norm = cnorm(option.text)
                    self.assertNotEqual(norm, answer, quiz.front)
                    self.assertNotIn(norm, banned, f"{quiz.front} -> {option.text}")
        con.close()

    def test_options_are_never_invented(self):
        """C3: ogni opzione o e' nel pool, o e' la risposta di un'altra card."""
        con = dbmod.open_db(self.work)
        known = {cnorm(r[0]) for r in con.execute("SELECT answer_core FROM card")}
        known |= {cnorm(r[0]) for r in con.execute("SELECT text FROM distractor")}
        for quiz in assemble.assemble_all(con, seed=3):
            for option in quiz.options:
                self.assertIn(cnorm(option.text), known, option.text)
        con.close()

    def test_export_produces_a_readable_qzd(self):
        out = Path(self._tmp.name) / "Reti.qzd"
        manifest = export.write_qzd(self.work, out, lang="en")
        self.assertEqual(manifest["card_count"], 25)

        with zipfile.ZipFile(out) as zf:
            self.assertEqual(
                sorted(zf.namelist()), ["content.sqlite", "manifest.json"]
            )
            parsed = json.loads(zf.read("manifest.json"))
        self.assertEqual(parsed["schema_version"], export.SCHEMA_VERSION)
        self.assertEqual(len(parsed["sezioni"]), 1)
        self.assertIsNone(parsed["llm"])


@unittest.skipUnless(CSV.exists(), f"csv di prova assente: {CSV}")
class TestMixedDeckM1(unittest.TestCase):
    """Il mazzo sintetico e' l'unico che esercita davvero il motore a regole."""

    @classmethod
    def setUpClass(cls):
        cls._tmp = ScratchDirectory()
        cls.work = Path(cls._tmp.name) / "work.db"
        pipeline.ingest(CSV, cls.work, raccolta_name="Prova")
        pipeline.normalize(cls.work)
        pipeline.classify(cls.work)
        pipeline.index(cls.work)
        cls.gen = pipeline.generate(cls.work)
        pipeline.validate(cls.work)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def test_every_rule_covered_type_gets_a_pool(self):
        """Una card di tipo numerico senza distrattori e' un difetto del motore."""
        con = dbmod.open_db(self.work)
        missing = export.uncovered_by_rules(con)
        con.close()
        self.assertEqual(
            [(r["answer_type"], r["answer_core"]) for r in missing], []
        )

    def test_pools_survive_validation(self):
        """Numeri, date ed elenchi devono arrivare ad almeno due distrattori.

        Le formule no, ed e' un limite reale del motore: su `BDP = banda x RTT`
        l'unica perturbazione sensata e' scambiare l'operatore, perche' non ci
        sono costanti da spostare ne' segni da invertire. Una formula
        puramente simbolica e' una delle card per cui esiste M4. Il quiz
        funziona lo stesso: l'assemblatore completa le quattro opzioni
        pescando dai vicini.
        """
        con = dbmod.open_db(self.work)
        rows = con.execute(
            "SELECT c.answer_core AS core, c.answer_type AS t,"
            "       (SELECT COUNT(*) FROM distractor d WHERE d.card_uid = c.uid) AS n"
            "  FROM card c WHERE c.answer_type IN ('numeric','date','formula','list')"
        ).fetchall()
        con.close()
        for row in rows:
            floor = 1 if row["t"] == "formula" else 2
            self.assertGreaterEqual(
                row["n"], floor, f"«{row['core']}» ({row['t']}) ha {row['n']}"
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
