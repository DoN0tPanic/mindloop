"""Test del server LAN del baker."""

from __future__ import annotations

from contextlib import contextmanager
import io
import json
import socket
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from baker import server as server_mod  # noqa: E402
from baker.tmpdir import ScratchDirectory  # noqa: E402
from baker.uid import card_uid  # noqa: E402


class TestBakeServer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = ScratchDirectory(prefix="server-tests-")
        cls._jobs_root = Path(cls._tmp.name) / "jobs"
        cls._server = server_mod.create_server(
            host="127.0.0.1",
            port=0,
            pairing_code="123456",
            jobs_root=cls._jobs_root,
        )
        cls._thread = threading.Thread(
            target=cls._server.serve_forever,
            name="test-baker-server",
            daemon=True,
        )
        cls._thread.start()
        cls._base = f"http://127.0.0.1:{cls._server.server_address[1]}"
        for _ in range(100):
            try:
                status, _, _ = cls._request_json_static(cls._base, "/ping")
            except OSError:
                time.sleep(0.01)
                continue
            if status == 200:
                break
            time.sleep(0.01)
        else:
            raise RuntimeError("server HTTP di test non raggiungibile")

    @classmethod
    def tearDownClass(cls):
        cls._server.shutdown()
        cls._server.server_close()
        cls._thread.join(timeout=2)
        cls._tmp.cleanup()

    def test_ping(self):
        status, _, payload = self._request_json("/ping")
        self.assertEqual(status, 200)
        self.assertEqual(payload["app"], "mindloop-baker")
        self.assertEqual(payload["version"], "0.1.0")
        self.assertEqual(payload["name"], self._server.service.hostname)
        self.assertTrue(payload["requires_code"])

    def _skip_if_foreign_responder(self, payload):
        """Salta se a rispondere e' stato un altro `baker serve` della macchina.

        La porta UDP di scoperta e' fissa e una sola: con un server vero gia'
        in esecuzione il probe puo' finire a lui invece che al server di test.
        Su Windows non basta guardare l'esito del bind, perche' SO_REUSEADDR
        lascia legare due socket alla stessa porta e la consegna diventa
        arbitraria; l'unico criterio affidabile e' guardare CHI ha risposto.
        Meglio un test saltato con una ragione chiara che un rosso che fa
        sembrare rotto del codice sano.
        """
        if payload.get("port") != self._server.server_address[1]:
            self.skipTest(
                "ha risposto un altro baker in esecuzione su questa macchina "
                f"(porta {payload.get('port')})"
            )

    def test_discovery_replies_with_runtime_http_port(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.bind(("127.0.0.1", 0))
            client.settimeout(1)
            client.sendto(
                server_mod.DISCOVERY_REQUEST,
                ("127.0.0.1", server_mod.DISCOVERY_PORT),
            )
            body, _ = client.recvfrom(4096)

        payload = json.loads(body.decode("utf-8"))
        self._skip_if_foreign_responder(payload)
        self.assertEqual(
            payload,
            {
                "app": "mindloop-baker",
                "name": self._server.service.hostname,
                "version": "0.1.0",
                "port": self._server.server_address[1],
                "requires_code": True,
            },
        )

    def test_discovery_ignores_garbage_and_server_stays_alive(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.bind(("127.0.0.1", 0))
            client.settimeout(0.2)
            client.sendto(b"rumore-di-rete", ("127.0.0.1", server_mod.DISCOVERY_PORT))
            with self.assertRaises(socket.timeout):
                client.recvfrom(4096)

        status, _, payload = self._request_json("/ping")
        self.assertEqual(status, 200)
        self.assertEqual(payload["app"], "mindloop-baker")

    def test_rejects_wrong_pairing_code(self):
        payload = {
            "raccolta": "Reti",
            "lang": "it",
            "cards": [
                {
                    "uid": card_uid("Qual e' l'MTU di Ethernet?"),
                    "front": "Qual e' l'MTU di Ethernet?",
                    "back": "1500 byte",
                }
            ],
        }
        status, _, body = self._request_json("/bake", method="POST", payload=payload, code="654321")
        self.assertEqual(status, 403)
        self.assertEqual(body, {"error": "codice-non-valido"})

    def test_blocks_after_failed_threshold_and_resets_after_success(self):
        throttle = server_mod.PairingThrottleConfig(
            delay_after_failures=99,
            delay_base_seconds=0.0,
            delay_max_seconds=0.0,
            block_after_failures=3,
            block_seconds=60.0,
            stale_after_seconds=60.0,
        )
        with self._running_server(pairing_throttle=throttle) as base:
            status, _, body = self._request_json_static(base, "/status/sconosciuto", code="654321")
            self.assertEqual(status, 403)
            self.assertEqual(body, {"error": "codice-non-valido"})

            status, _, body = self._request_json_static(base, "/status/sconosciuto", code="123456")
            self.assertEqual(status, 404)
            self.assertEqual(body, {"error": "job-non-trovato"})

            status, _, body = self._request_json_static(base, "/status/sconosciuto", code="654321")
            self.assertEqual(status, 403)
            self.assertEqual(body, {"error": "codice-non-valido"})

            status, _, body = self._request_json_static(base, "/status/sconosciuto", code="654321")
            self.assertEqual(status, 403)
            self.assertEqual(body, {"error": "codice-non-valido"})

            status, headers, body = self._request_json_static(base, "/status/sconosciuto", code="654321")
            self.assertEqual(status, 429)
            self.assertEqual(body, {"error": "troppi-tentativi"})
            self.assertEqual(headers["Retry-After"], "60")

    def test_rejects_payloads_over_configured_limit(self):
        limits = server_mod.RequestLimits(
            max_body_bytes=16,
            socket_timeout_seconds=5.0,
        )
        with self._running_server(request_limits=limits) as base:
            status, _, body = self._request_json_static(
                base,
                "/bake",
                method="POST",
                body=b'{"raccolta":"troppo-lunga"}',
                code="123456",
                extra_headers={"Content-Type": "application/json"},
            )
            self.assertEqual(status, 413)
            self.assertEqual(body, {"error": "richiesta-troppo-grande"})

    def test_full_cycle_bake_status_result(self):
        front_ok = "Qual e' l'MTU di Ethernet?"
        front_bad = "In che anno nacque ARPANET?"
        payload = {
            "raccolta": "Reti",
            "lang": "it",
            "cards": [
                {
                    "uid": card_uid(front_ok),
                    "front": front_ok,
                    "back": "1500 byte",
                },
                {
                    "uid": "aaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "front": front_bad,
                    "back": "1969",
                },
            ],
        }

        status, _, created = self._request_json(
            "/bake",
            method="POST",
            payload=payload,
            code="123456",
        )
        self.assertEqual(status, 202)
        job_id = created["job"]

        final_status = None
        for _ in range(100):
            _, _, current = self._request_json(f"/status/{job_id}", code="123456")
            final_status = current
            if current["state"] in {"done", "error"}:
                break
            time.sleep(0.05)

        self.assertIsNotNone(final_status)
        self.assertEqual(final_status["state"], "done", final_status)
        self.assertEqual(final_status["stage"], "export")
        self.assertEqual(final_status["progress"], 1.0)
        self.assertIsNone(final_status["error"])
        self.assertEqual(len(final_status["uid_mismatch"]), 1)
        self.assertEqual(
            final_status["uid_mismatch"][0]["computed"],
            card_uid(front_bad),
        )

        status, headers, archive = self._request_bytes(f"/result/{job_id}", code="123456")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get_content_type(), "application/octet-stream")
        self.assertIn('attachment; filename="Reti.qzd"', headers["Content-Disposition"])

        with zipfile.ZipFile(io.BytesIO(archive)) as zf:
            self.assertEqual(
                sorted(zf.namelist()),
                ["content.sqlite", "manifest.json"],
            )
            manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
        self.assertEqual(manifest["raccolta"]["name"], "Reti")
        self.assertEqual(manifest["card_count"], 2)
        self.assertEqual(manifest["lang"], "it")
        self.assertIsNone(manifest["llm"])

    def test_status_returns_404_for_unknown_job(self):
        status, _, body = self._request_json("/status/job-che-non-esiste", code="123456")
        self.assertEqual(status, 404)
        self.assertEqual(body, {"error": "job-non-trovato"})

    def _request_json(
        self,
        path: str,
        *,
        method: str = "GET",
        payload: dict | None = None,
        body: bytes | None = None,
        code: str | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, object, dict]:
        status, headers, body = self._request_bytes(
            path,
            method=method,
            payload=payload,
            body=body,
            code=code,
            extra_headers=extra_headers,
        )
        return status, headers, json.loads(body.decode("utf-8"))

    @staticmethod
    def _request_json_static(
        base: str,
        path: str,
        *,
        method: str = "GET",
        payload: dict | None = None,
        body: bytes | None = None,
        code: str | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, object, dict]:
        status, headers, body = TestBakeServer._request_bytes_static(
            base,
            path,
            method=method,
            payload=payload,
            body=body,
            code=code,
            extra_headers=extra_headers,
        )
        return status, headers, json.loads(body.decode("utf-8"))

    def _request_bytes(
        self,
        path: str,
        *,
        method: str = "GET",
        payload: dict | None = None,
        body: bytes | None = None,
        code: str | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, object, bytes]:
        return self._request_bytes_static(
            self._base,
            path,
            method=method,
            payload=payload,
            body=body,
            code=code,
            extra_headers=extra_headers,
        )

    @staticmethod
    def _request_bytes_static(
        base: str,
        path: str,
        *,
        method: str = "GET",
        payload: dict | None = None,
        body: bytes | None = None,
        code: str | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, object, bytes]:
        if payload is not None and body is not None:
            raise ValueError("payload e body sono mutuamente esclusivi")
        data = body
        headers = dict(extra_headers or {})
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if code is not None:
            headers[server_mod.PAIRING_HEADER] = code
        request = urllib.request.Request(
            f"{base}{path}",
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as exc:
            body = exc.read()
            exc.close()
            return exc.code, exc.headers, body

    @classmethod
    @contextmanager
    def _running_server(cls, **server_kwargs):
        tmp = ScratchDirectory(prefix="server-tests-")
        jobs_root = Path(tmp.name) / "jobs"
        server = server_mod.create_server(
            host="127.0.0.1",
            port=0,
            pairing_code="123456",
            jobs_root=jobs_root,
            discovery_enabled=False,
            **server_kwargs,
        )
        thread = threading.Thread(
            target=server.serve_forever,
            name="test-baker-server-ephemeral",
            daemon=True,
        )
        thread.start()
        base = f"http://127.0.0.1:{server.server_address[1]}"
        try:
            for _ in range(100):
                try:
                    status, _, _ = cls._request_json_static(base, "/ping")
                except OSError:
                    time.sleep(0.01)
                    continue
                if status == 200:
                    break
                time.sleep(0.01)
            else:
                raise RuntimeError("server HTTP di test non raggiungibile")
            yield base
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
            tmp.cleanup()


if __name__ == "__main__":
    unittest.main(verbosity=2)
