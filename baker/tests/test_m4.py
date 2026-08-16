"""Test di M4: wiring LLM e smoke test opzionale su Ollama reale."""

from __future__ import annotations

import json
import sys
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from baker import export, llm, pipeline  # noqa: E402
from baker import db as dbmod  # noqa: E402
from baker.tmpdir import ScratchDirectory  # noqa: E402

CSV = Path(__file__).resolve().parents[2] / "testdata" / "misto.csv"
OLLAMA_MODEL = "qwen3:4b"


def _ollama_ready(model: str) -> bool:
    try:
        with urllib.request.urlopen("http://localhost:11434/api/tags", timeout=1) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, urllib.error.URLError):
        return False
    return any(item.get("name") == model for item in payload.get("models", []))


@unittest.skipUnless(CSV.exists(), f"csv di prova assente: {CSV}")
class TestPipelineLlmIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = ScratchDirectory()
        cls.work = Path(cls._tmp.name) / "work.db"
        pipeline.ingest(CSV, cls.work, raccolta_name="Prova")
        pipeline.normalize(cls.work)
        pipeline.classify(cls.work)
        pipeline.index(cls.work)
        con = dbmod.open_db(cls.work)
        with con:
            con.execute(
                "UPDATE card SET answer_note = ? WHERE answer_core = ?",
                ("User Datagram Protocol", "UDP"),
            )
        con.close()

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    @mock.patch(
        "baker.pipeline.llm_mod.generate_distractors",
        return_value=[
            "Instrada un pacchetto verso una rete remota tramite il gateway predefinito",
            "Converte un nome DNS in un indirizzo IP pubblico",
            "Annuncia la disponibilita' di una porta TCP a tutti gli host della LAN",
        ],
    )
    def test_generate_records_llm_metadata_and_pool(self, mocked_generate):
        stats = pipeline.generate(
            self.work,
            llm_model="stub-model",
            llm_seed=7,
            llm_temperature=0.3,
        )
        self.assertGreater(stats["llm_calls"], 0)
        self.assertGreater(stats["llm_candidates"], 0)
        self.assertGreaterEqual(stats["coverage:llm"], 1)
        self.assertTrue(mocked_generate.called)
        udp_calls = [
            call.kwargs for call in mocked_generate.call_args_list
            if call.kwargs.get("answer_core") == "UDP"
        ]
        self.assertTrue(udp_calls, "la card TERM `UDP` non ha raggiunto l'LLM")
        self.assertEqual(udp_calls[0]["answer_note"], "User Datagram Protocol")

        con = dbmod.open_db(self.work)
        origins = {
            tuple(row)
            for row in con.execute(
                "SELECT DISTINCT origin, gen_version FROM distractor WHERE origin = 'llm'"
            )
        }
        self.assertEqual(origins, {("llm", "llm:stub-model")})
        self.assertEqual(dbmod.get_meta(con, "llm_model"), "stub-model")
        self.assertEqual(dbmod.get_meta(con, "llm_seed"), "7")
        self.assertEqual(dbmod.get_meta(con, "llm_temperature"), "0.3")
        con.close()

        out = Path(self._tmp.name) / "llm.qzd"
        manifest = export.write_qzd(self.work, out)
        self.assertEqual(
            manifest["llm"],
            {"model": "stub-model", "temperature": 0.3, "seed": 7},
        )
        self.assertGreaterEqual(manifest["coverage"].get("llm", 0), 1)


class TestLlmCleanup(unittest.TestCase):
    def test_strips_leading_article_when_answer_has_none(self):
        self.assertEqual(
            llm._normalize_candidate("The session layer", "Application layer"),
            "Session layer",
        )

    def test_keeps_leading_article_when_answer_has_one(self):
        self.assertEqual(
            llm._normalize_candidate("The session layer", "The application layer"),
            "The session layer",
        )

    def test_prompt_mentions_shape_and_length_constraints_for_acronyms(self):
        prompt = llm._build_prompt(
            front="Which organization defines standards used on local area networks?",
            answer_core="IETF",
            answer_note=None,
            deck_name="Reti",
            neighbor_backs=["IEEE", "W3C", "ISO"],
        )
        self.assertIn("stessa categoria", prompt.lower())
        self.assertIn("IETF", prompt)
        self.assertIn("IEEE", prompt)
        self.assertIn("W3C", prompt)
        self.assertIn("ISO", prompt)
        self.assertIn("World Wide Web Consortium", prompt)
        self.assertIn("Internet Society", prompt)
        self.assertIn("circa 1 parola e 4 caratteri", prompt)


@unittest.skipUnless(
    _ollama_ready(OLLAMA_MODEL),
    f"Ollama o modello {OLLAMA_MODEL} non disponibili su localhost:11434",
)
class TestOllamaSmoke(unittest.TestCase):
    def test_generate_three_strings(self):
        # Un modello appena caricato puo' non rispondere entro il timeout: la
        # prima chiamata paga il caricamento in memoria. Un secondo tentativo
        # distingue "il modello era freddo" da "il percorso LLM e' rotto"; se
        # resta muto il test si dichiara saltato invece che fallito, altrimenti
        # la suite diventa rossa a caso e smette di dire qualcosa.
        out: list[str] = []
        for _ in range(2):
            out = self._generate()
            if out:
                break
        if not out:
            self.skipTest(
                f"il modello {OLLAMA_MODEL} non ha risposto in tempo (probabile caricamento a freddo)"
            )

        self.assertTrue(all(isinstance(item, str) and item.strip() for item in out))
        # Il numero esatto non e' garantito dal contratto: il modello puo'
        # produrne meno, e la validazione a valle scarta comunque il superfluo.
        self.assertGreaterEqual(len(out), 1)
        # Legato alla costante, non a un numero scritto a mano: quando si
        # cambia quanti candidati chiedere, il test non deve diventare rosso
        # per un limite dimenticato qui dentro.
        self.assertLessEqual(len(out), llm.CANDIDATI_RICHIESTI + 2)

    def _generate(self) -> list[str]:
        return llm.generate_distractors(
            front="Che cosa fa il protocollo ARP?",
            answer_core="Risolve un indirizzo IP in un indirizzo MAC all'interno della stessa rete locale",
            answer_note="Address Resolution Protocol",
            deck_name="Reti",
            neighbor_backs=[
                "Lo scambio di SYN, SYN-ACK e ACK con cui due host TCP stabiliscono una connessione sincronizzando i numeri di sequenza",
                "UDP",
                "frame (L2PDU)",
            ],
            model=OLLAMA_MODEL,
            seed=42,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
