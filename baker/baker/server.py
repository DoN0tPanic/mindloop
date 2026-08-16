"""Server HTTP LAN per cuocere card e restituire un `.qzd`.

Il server espone un contratto piccolo e stabile per il client Android.
La pipeline non viene duplicata: i job scrivono un CSV persistente e poi
passano dagli stessi stadi gia' usati dalla CLI.
"""

from __future__ import annotations

import csv
import ipaddress
import json
import math
import re
import secrets
import shutil
import socket
import sys
import threading
import time
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from . import export, pipeline
from .uid import card_uid, container_uid

APP_NAME = "mindloop-baker"
DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8765
DISCOVERY_HOST = "0.0.0.0"
DISCOVERY_PORT = 8766
DISCOVERY_REQUEST = b"MINDLOOP-DISCOVER-v1"
PAIRING_HEADER = "X-Pairing-Code"
STAGES = (
    "ingest",
    "normalize",
    "classify",
    "index",
    "generate",
    "validate",
    "export",
)
_STAGE_MESSAGES = {
    "ingest": "importazione card",
    "normalize": "normalizzazione",
    "classify": "classificazione",
    "index": "indicizzazione",
    "generate": "generazione distrattori",
    "validate": "validazione distrattori",
    "export": "esportazione qzd",
}
_SAFE_FILENAME_RE = re.compile(r"[^A-Za-z0-9._-]+")
_DISCOVERY_BUFFER_SIZE = 4096
_DISCOVERY_SOCKET_TIMEOUT = 0.5
DEFAULT_MAX_BODY_BYTES = 8 * 1024 * 1024
DEFAULT_REQUEST_TIMEOUT_SECONDS = 30.0
_DISCARD_CHUNK_BYTES = 64 * 1024


@dataclass(frozen=True)
class CardInput:
    uid: str
    front: str
    back: str


@dataclass(frozen=True)
class BakeRequest:
    raccolta: str
    lang: str
    cards: list[CardInput]


@dataclass
class BakeJob:
    job_id: str
    raccolta: str
    lang: str
    job_dir: Path
    state: str = "running"
    stage: str = STAGES[0]
    progress: float = 0.0
    message: str = _STAGE_MESSAGES[STAGES[0]]
    error: str | None = None
    uid_mismatch: list[dict[str, str]] = field(default_factory=list)
    qzd_path: Path | None = None
    filename: str | None = None

    def status_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "state": self.state,
            "stage": self.stage,
            "progress": round(self.progress, 3),
            "message": self.message,
            "error": self.error,
        }
        if self.uid_mismatch:
            payload["uid_mismatch"] = list(self.uid_mismatch)
        return payload


@dataclass(frozen=True)
class PairingThrottleConfig:
    delay_after_failures: int = 5
    delay_base_seconds: float = 0.5
    delay_max_seconds: float = 30.0
    block_after_failures: int = 20
    block_seconds: float = 15 * 60.0
    stale_after_seconds: float = 60 * 60.0


@dataclass(frozen=True)
class RequestLimits:
    max_body_bytes: int = DEFAULT_MAX_BODY_BYTES
    socket_timeout_seconds: float = DEFAULT_REQUEST_TIMEOUT_SECONDS


@dataclass
class PairingAttemptState:
    failures: int = 0
    blocked_until: float = 0.0
    last_seen: float = 0.0


@dataclass(frozen=True)
class PairingDecision:
    allowed: bool
    status: HTTPStatus | None = None
    error: str | None = None
    delay_seconds: float = 0.0
    retry_after_seconds: int | None = None


class RequestBodyError(ValueError):
    def __init__(
        self,
        status: HTTPStatus,
        error: str,
    ) -> None:
        super().__init__(error)
        self.status = status
        self.error = error


def create_pairing_code() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


def default_jobs_root() -> Path:
    return Path.home() / ".mindloop" / "jobs"


def create_server(
    host: str = DEFAULT_HOST,
    port: int = DEFAULT_PORT,
    *,
    llm_model: str | None = None,
    pairing_code: str | None = None,
    jobs_root: str | Path | None = None,
    discovery_enabled: bool = True,
    pairing_throttle: PairingThrottleConfig | None = None,
    request_limits: RequestLimits | None = None,
) -> "BakeHttpServer":
    service = BakeService(
        llm_model=llm_model,
        pairing_code=pairing_code,
        jobs_root=jobs_root,
        pairing_throttle=pairing_throttle,
    )
    return BakeHttpServer(
        (host, port),
        BakeRequestHandler,
        service,
        discovery_enabled=discovery_enabled,
        request_limits=request_limits,
    )


def serve_forever(
    host: str = DEFAULT_HOST,
    port: int = DEFAULT_PORT,
    *,
    llm_model: str | None = None,
    pairing_code: str | None = None,
    jobs_root: str | Path | None = None,
    discovery_enabled: bool = True,
    pairing_throttle: PairingThrottleConfig | None = None,
    request_limits: RequestLimits | None = None,
) -> None:
    server = create_server(
        host,
        port,
        llm_model=llm_model,
        pairing_code=pairing_code,
        jobs_root=jobs_root,
        discovery_enabled=discovery_enabled,
        pairing_throttle=pairing_throttle,
        request_limits=request_limits,
    )
    try:
        server.start_discovery()
        print_banner(server)
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nArresto server...")
    finally:
        server.server_close()


def print_banner(server: "BakeHttpServer") -> None:
    service = server.service
    port = server.server_address[1]
    print()
    print("=" * 60)
    print("Mindloop baker LAN server")
    print(f"Pairing code: {service.pairing_code}")
    print(server.discovery_status_line())
    print("Indirizzi raggiungibili:")
    for address in announce_addresses(server.server_address[0], port, service.hostname):
        print(f"  http://{address}:{port}")
    print("=" * 60)
    print()


def announce_addresses(host: str, port: int, hostname: str | None = None) -> list[str]:
    if host not in ("", DEFAULT_HOST):
        return [host]
    addresses = local_ipv4_addresses(hostname=hostname)
    return addresses or ["127.0.0.1"]


def local_ipv4_addresses(hostname: str | None = None) -> list[str]:
    name = hostname or socket.gethostname()
    found: set[str] = set()

    for candidate in {name, socket.getfqdn(), "localhost"}:
        if not candidate:
            continue
        try:
            infos = socket.getaddrinfo(candidate, None, socket.AF_INET, socket.SOCK_STREAM)
        except socket.gaierror:
            continue
        for info in infos:
            address = info[4][0]
            if address and address != "0.0.0.0":
                found.add(address)

    # UDP connect non invia traffico, ma costringe il kernel a scegliere
    # un'interfaccia locale e ci fa vedere gli IP davvero instradabili.
    for target in ("8.8.8.8", "1.1.1.1", "192.168.0.1", "10.0.0.1", "172.16.0.1"):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
                probe.connect((target, 80))
                address = probe.getsockname()[0]
        except OSError:
            continue
        if address and address != "0.0.0.0":
            found.add(address)

    return sorted(found, key=_ip_sort_key)


def validate_pairing_code(code: str) -> str:
    if not re.fullmatch(r"\d{6}", code or ""):
        raise ValueError("il pairing code deve avere esattamente 6 cifre")
    return code


class BakeService:
    def __init__(
        self,
        *,
        llm_model: str | None,
        pairing_code: str | None,
        jobs_root: str | Path | None,
        pairing_throttle: PairingThrottleConfig | None = None,
    ) -> None:
        self.hostname = socket.gethostname()
        self.llm_model = llm_model
        self.pairing_code = validate_pairing_code(pairing_code or create_pairing_code())
        self.jobs_root = Path(jobs_root) if jobs_root is not None else default_jobs_root()
        self.jobs_root.mkdir(parents=True, exist_ok=True)
        self._jobs: dict[str, BakeJob] = {}
        self._lock = threading.RLock()
        self._pairing_throttle = pairing_throttle or PairingThrottleConfig()
        self._pairing_attempts: dict[str, PairingAttemptState] = {}
        self._pairing_lock = threading.Lock()

    def ping_payload(self) -> dict[str, Any]:
        return {
            "app": APP_NAME,
            "version": export.BAKER_VERSION,
            "name": self.hostname,
            "requires_code": True,
        }

    def discovery_payload(self, port: int) -> dict[str, Any]:
        # Il pairing code non va mai annunciato via discovery: il broadcast
        # serve solo a trovare il PC, il segreto resta digitato dall'utente.
        return {
            "app": APP_NAME,
            "name": self.hostname,
            "version": export.BAKER_VERSION,
            "port": port,
            "requires_code": True,
        }

    def pairing_ok(self, provided: object) -> bool:
        if not isinstance(provided, str):
            return False
        try:
            return secrets.compare_digest(provided, self.pairing_code)
        except TypeError:
            return False

    def verify_pairing(self, client_ip: str, provided: object) -> PairingDecision:
        blocked = self._pairing_block_status(client_ip)
        if blocked is not None:
            return blocked

        if self.pairing_ok(provided):
            self._reset_pairing_attempts(client_ip)
            return PairingDecision(allowed=True)

        return self._register_pairing_failure(client_ip)

    def create_job(self, payload: dict[str, Any]) -> BakeJob:
        request = _validate_bake_payload(payload)
        job_id = container_uid()
        job_dir = self.jobs_root / job_id
        job_dir.mkdir(parents=True, exist_ok=False)

        uid_mismatch = _uid_mismatches(request.cards)
        _write_request_snapshot(job_dir, payload, request.cards)

        job = BakeJob(
            job_id=job_id,
            raccolta=request.raccolta,
            lang=request.lang,
            job_dir=job_dir,
            uid_mismatch=uid_mismatch,
        )
        with self._lock:
            self._jobs[job_id] = job

        worker = threading.Thread(
            target=self._run_job,
            args=(job, request),
            name=f"baker-job-{job_id[:8]}",
            daemon=True,
        )
        worker.start()
        return job

    def get_job(self, job_id: str) -> BakeJob | None:
        with self._lock:
            return self._jobs.get(job_id)

    def list_jobs(self) -> list[BakeJob]:
        """I job noti, dal piu' recente. Serve all'interfaccia grafica.

        Ritorna una copia della lista: chi la legge lo fa da un altro thread
        (quello della finestra) mentre le cotture continuano, e iterare
        direttamente sul dizionario mentre un worker lo modifica farebbe
        saltare tutto proprio mentre l'utente guarda l'avanzamento.
        """
        with self._lock:
            return list(reversed(list(self._jobs.values())))

    def _run_job(self, job: BakeJob, request: BakeRequest) -> None:
        csv_path = job.job_dir / "cards.csv"
        work_db = job.job_dir / "work.db"
        qzd_path = job.job_dir / f"{_safe_filename(request.raccolta)}.qzd"
        try:
            self._set_stage(job, "ingest")
            pipeline.ingest(
                csv_path,
                work_db,
                sezione_name=request.raccolta,
                raccolta_name=request.raccolta,
            )
            self._set_stage(job, "normalize")
            pipeline.normalize(work_db)
            self._set_stage(job, "classify")
            pipeline.classify(work_db)
            self._set_stage(job, "index")
            pipeline.index(work_db)
            self._set_stage(job, "generate")
            pipeline.generate(work_db, llm_model=self.llm_model)
            self._set_stage(job, "validate")
            pipeline.validate(work_db)
            self._set_stage(job, "export")
            export.write_qzd(work_db, qzd_path, lang=request.lang)
        except Exception as exc:  # pragma: no cover - error path tested via status
            self._set_error(job, exc)
            return
        self._set_done(job, qzd_path)

    def _set_stage(self, job: BakeJob, stage: str) -> None:
        with self._lock:
            job.state = "running"
            job.stage = stage
            job.progress = STAGES.index(stage) / len(STAGES)
            job.message = _STAGE_MESSAGES[stage]
            job.error = None

    def _set_done(self, job: BakeJob, qzd_path: Path) -> None:
        with self._lock:
            job.state = "done"
            job.stage = "export"
            job.progress = 1.0
            job.message = "qzd pronto"
            job.error = None
            job.qzd_path = qzd_path
            job.filename = qzd_path.name

    def _set_error(self, job: BakeJob, exc: Exception) -> None:
        with self._lock:
            job.state = "error"
            job.message = f"errore nello stadio {job.stage}"
            job.error = str(exc)

    def _pairing_block_status(self, client_ip: str) -> PairingDecision | None:
        now = time.monotonic()
        with self._pairing_lock:
            self._cleanup_pairing_attempts(now)
            state = self._pairing_attempts.get(client_ip)
            if state is None:
                return None
            if state.blocked_until <= now:
                if state.blocked_until > 0.0:
                    state.failures = 0
                    state.blocked_until = 0.0
                state.last_seen = now
                return None
            state.last_seen = now
            retry_after_seconds = max(1, math.ceil(state.blocked_until - now))
            return PairingDecision(
                allowed=False,
                status=HTTPStatus.TOO_MANY_REQUESTS,
                error="troppi-tentativi",
                retry_after_seconds=retry_after_seconds,
            )

    def _register_pairing_failure(self, client_ip: str) -> PairingDecision:
        now = time.monotonic()
        log_block = False
        with self._pairing_lock:
            self._cleanup_pairing_attempts(now)
            state = self._pairing_attempts.get(client_ip)
            if state is None:
                state = PairingAttemptState()
                self._pairing_attempts[client_ip] = state
            elif state.blocked_until > 0.0 and state.blocked_until <= now:
                state.failures = 0
                state.blocked_until = 0.0

            state.failures += 1
            state.last_seen = now
            if state.failures >= self._pairing_throttle.block_after_failures:
                state.blocked_until = now + self._pairing_throttle.block_seconds
                retry_after_seconds = max(1, math.ceil(self._pairing_throttle.block_seconds))
                log_block = True
                decision = PairingDecision(
                    allowed=False,
                    status=HTTPStatus.TOO_MANY_REQUESTS,
                    error="troppi-tentativi",
                    retry_after_seconds=retry_after_seconds,
                )
            else:
                decision = PairingDecision(
                    allowed=False,
                    status=HTTPStatus.FORBIDDEN,
                    error="codice-non-valido",
                    delay_seconds=self._pairing_delay_seconds(state.failures),
                )

        if log_block:
            print(
                f"Avviso: pairing bloccato per {client_ip} dopo "
                f"{state.failures} tentativi falliti.",
                file=sys.stderr,
            )
        return decision

    def _reset_pairing_attempts(self, client_ip: str) -> None:
        with self._pairing_lock:
            self._pairing_attempts.pop(client_ip, None)

    def _pairing_delay_seconds(self, failures: int) -> float:
        if failures <= self._pairing_throttle.delay_after_failures:
            return 0.0
        exponent = failures - self._pairing_throttle.delay_after_failures - 1
        delay_seconds = self._pairing_throttle.delay_base_seconds * (2**exponent)
        return min(delay_seconds, self._pairing_throttle.delay_max_seconds)

    def _cleanup_pairing_attempts(self, now: float) -> None:
        stale_before = now - self._pairing_throttle.stale_after_seconds
        expired: list[str] = []
        for client_ip, state in self._pairing_attempts.items():
            if state.blocked_until > now:
                continue
            if state.last_seen <= stale_before:
                expired.append(client_ip)
        for client_ip in expired:
            self._pairing_attempts.pop(client_ip, None)


class BakeHttpServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(
        self,
        server_address: tuple[str, int],
        handler_cls: type[BaseHTTPRequestHandler],
        service: BakeService,
        *,
        discovery_enabled: bool = True,
        request_limits: RequestLimits | None = None,
    ) -> None:
        super().__init__(server_address, handler_cls)
        self.service = service
        self.request_limits = request_limits or RequestLimits()
        self._discovery_requested = discovery_enabled
        self._discovery_started = False
        self._discovery = (
            DiscoveryResponder(service, self.server_address[1])
            if discovery_enabled
            else None
        )

    def get_request(self) -> tuple[socket.socket, tuple[str, int]]:
        request, client_address = super().get_request()
        request.settimeout(self.request_limits.socket_timeout_seconds)
        return request, client_address

    def serve_forever(self, poll_interval: float = 0.5) -> None:
        self.start_discovery()
        super().serve_forever(poll_interval=poll_interval)

    def server_close(self) -> None:
        self.stop_discovery()
        super().server_close()

    @property
    def discovery_active(self) -> bool:
        """Se la scoperta sta davvero rispondendo.

        Diverso da "richiesta": la porta UDP e' una sola per macchina, quindi
        un secondo server sulla stessa macchina parte comunque ma senza
        scoperta (vedi `start_discovery`).
        """
        return self._discovery is not None and self._discovery.bind_error is None

    def start_discovery(self) -> None:
        if self._discovery is None or self._discovery_started:
            return
        self._discovery_started = True
        if self._discovery.start():
            return
        exc = self._discovery.bind_error
        message = (
            "scoperta automatica non disponibile: impossibile aprire "
            f"UDP {DISCOVERY_HOST}:{DISCOVERY_PORT}"
        )
        if exc is not None:
            message = f"{message} ({exc})"
        print(
            f"Avviso: {message}. Il server HTTP continua senza discovery.",
            file=sys.stderr,
        )

    def stop_discovery(self) -> None:
        if self._discovery is not None:
            self._discovery.stop()

    def discovery_status_line(self) -> str:
        if self._discovery is not None and self._discovery.active:
            return (
                "Scoperta automatica: attiva "
                "(sul telefono non serve digitare l'indirizzo IP)"
            )
        if not self._discovery_requested:
            return "Scoperta automatica: disattivata (--no-discovery)"
        return "Scoperta automatica: non disponibile"


class DiscoveryResponder:
    def __init__(self, service: BakeService, http_port: int) -> None:
        self._service = service
        self._http_port = http_port
        self._thread: threading.Thread | None = None
        self._socket: socket.socket | None = None
        self._stop = threading.Event()
        self.bind_error: OSError | None = None
        self._response = json.dumps(
            service.discovery_payload(http_port),
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")

    @property
    def active(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> bool:
        if self._thread is not None:
            return True
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((DISCOVERY_HOST, DISCOVERY_PORT))
            sock.settimeout(_DISCOVERY_SOCKET_TIMEOUT)
        except OSError as exc:
            sock.close()
            self.bind_error = exc
            return False
        self.bind_error = None
        self._socket = sock
        self._stop.clear()
        self._thread = threading.Thread(
            target=self._serve,
            name="baker-discovery",
            daemon=True,
        )
        self._thread.start()
        return True

    def stop(self) -> None:
        self._stop.set()
        sock = self._socket
        thread = self._thread
        self._socket = None
        self._thread = None
        if sock is not None:
            sock.close()
        if thread is not None:
            thread.join(timeout=1)

    def _serve(self) -> None:
        sock = self._socket
        if sock is None:
            return
        while not self._stop.is_set():
            try:
                payload, client = sock.recvfrom(_DISCOVERY_BUFFER_SIZE)
            except socket.timeout:
                continue
            except OSError:
                if self._stop.is_set():
                    break
                continue
            if payload != DISCOVERY_REQUEST:
                continue
            try:
                sock.sendto(self._response, client)
            except OSError:
                continue


class BakeRequestHandler(BaseHTTPRequestHandler):
    server_version = f"{APP_NAME}/{export.BAKER_VERSION}"

    @property
    def service(self) -> BakeService:
        return self.server.service  # type: ignore[attr-defined]

    @property
    def request_limits(self) -> RequestLimits:
        return self.server.request_limits  # type: ignore[attr-defined]

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/ping":
            self._write_json(HTTPStatus.OK, self.service.ping_payload())
            return
        if not self._require_pairing():
            return
        if path.startswith("/status/"):
            self._handle_status(path)
            return
        if path.startswith("/result/"):
            self._handle_result(path)
            return
        self._write_json(HTTPStatus.NOT_FOUND, {"error": "job-non-trovato"})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path != "/bake":
            self._discard_request_body()
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "job-non-trovato"})
            return
        if not self._require_pairing():
            return
        try:
            payload = self._read_json_body()
        except RequestBodyError as exc:
            self._write_json(exc.status, {"error": exc.error})
            return
        except ValueError as exc:
            self._write_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        except OSError:
            self.close_connection = True
            return
        try:
            job = self.service.create_job(payload)
        except ValueError as exc:
            self._write_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        except OSError:
            self._write_json(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                {"error": "job-non-creabile"},
            )
            return
        self._write_json(HTTPStatus.ACCEPTED, {"job": job.job_id})

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _handle_status(self, path: str) -> None:
        job = self.service.get_job(_job_id_from_path(path, "/status/"))
        if job is None:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "job-non-trovato"})
            return
        self._write_json(HTTPStatus.OK, job.status_payload())

    def _handle_result(self, path: str) -> None:
        job = self.service.get_job(_job_id_from_path(path, "/result/"))
        if job is None:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "job-non-trovato"})
            return
        if job.state != "done" or job.qzd_path is None or job.filename is None:
            self._write_json(HTTPStatus.CONFLICT, job.status_payload())
            return

        body_size = job.qzd_path.stat().st_size
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header(
            "Content-Disposition",
            f'attachment; filename="{job.filename}"',
        )
        self.send_header("Content-Length", str(body_size))
        self.end_headers()
        with job.qzd_path.open("rb") as handle:
            shutil.copyfileobj(handle, self.wfile)

    def _read_json_body(self) -> dict[str, Any]:
        length = self._content_length(required=True)
        if length > self.request_limits.max_body_bytes:
            self.close_connection = True
            raise RequestBodyError(
                HTTPStatus(413),
                "richiesta-troppo-grande",
            )
        try:
            body = self.rfile.read(min(length, self.request_limits.max_body_bytes)).decode("utf-8")
            payload = json.loads(body or "{}")
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("json-malformato") from exc
        if not isinstance(payload, dict):
            raise ValueError("json-malformato")
        return payload

    def _require_pairing(self) -> bool:
        client_ip = self.client_address[0] if self.client_address else "unknown"
        decision = self.service.verify_pairing(
            client_ip,
            self.headers.get(PAIRING_HEADER),
        )
        if decision.allowed:
            return True
        self._discard_request_body()
        if decision.delay_seconds > 0.0:
            time.sleep(decision.delay_seconds)
        extra_headers = None
        if decision.retry_after_seconds is not None:
            extra_headers = {"Retry-After": str(decision.retry_after_seconds)}
        self._write_json(
            decision.status or HTTPStatus.FORBIDDEN,
            {"error": decision.error or "codice-non-valido"},
            extra_headers=extra_headers,
        )
        return False

    def _write_json(
        self,
        status: HTTPStatus,
        payload: dict[str, Any],
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if self.close_connection:
            self.send_header("Connection", "close")
        if extra_headers is not None:
            for name, value in extra_headers.items():
                self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _discard_request_body(self) -> None:
        if self.command not in {"POST", "PUT", "PATCH"}:
            return
        try:
            length = self._content_length(required=False)
        except RequestBodyError:
            self.close_connection = True
            return
        if length is None:
            self.close_connection = True
            return
        if length > self.request_limits.max_body_bytes:
            self.close_connection = True
            return
        remaining = length
        while remaining > 0:
            chunk = self.rfile.read(min(remaining, _DISCARD_CHUNK_BYTES))
            if not chunk:
                self.close_connection = True
                return
            remaining -= len(chunk)

    def _content_length(self, *, required: bool) -> int | None:
        raw_value = self.headers.get("Content-Length")
        if raw_value is None:
            if required:
                raise RequestBodyError(HTTPStatus.BAD_REQUEST, "json-malformato")
            return None
        try:
            length = int(raw_value)
        except ValueError as exc:
            raise RequestBodyError(HTTPStatus.BAD_REQUEST, "json-malformato") from exc
        if length < 0:
            raise RequestBodyError(HTTPStatus.BAD_REQUEST, "json-malformato")
        return length


def _validate_bake_payload(payload: dict[str, Any]) -> BakeRequest:
    raccolta = payload.get("raccolta")
    lang = payload.get("lang")
    cards = payload.get("cards")

    if not isinstance(raccolta, str) or not raccolta.strip():
        raise ValueError("raccolta-non-valida")
    if lang is None:
        lang = ""
    if not isinstance(lang, str):
        raise ValueError("lang-non-valida")
    if not isinstance(cards, list) or not cards:
        raise ValueError("cards-vuote")

    parsed_cards: list[CardInput] = []
    for card in cards:
        if not isinstance(card, dict):
            raise ValueError("card-non-valida")
        uid = card.get("uid")
        front = card.get("front")
        back = card.get("back")
        if not all(isinstance(value, str) for value in (uid, front, back)):
            raise ValueError("card-non-valida")
        if not front.strip() or not back.strip():
            raise ValueError("card-vuota")
        parsed_cards.append(CardInput(uid=uid, front=front, back=back))

    return BakeRequest(raccolta=raccolta.strip(), lang=lang.strip(), cards=parsed_cards)


def _write_request_snapshot(
    job_dir: Path,
    payload: dict[str, Any],
    cards: list[CardInput],
) -> None:
    request_path = job_dir / "request.json"
    request_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    csv_path = job_dir / "cards.csv"
    # Riusiamo l'ingest del baker passando da un CSV persistente invece di
    # duplicare qui la logica di import, dedup e inizializzazione del work.db.
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["front", "back"])
        for card in cards:
            writer.writerow([card.front, card.back])


def _uid_mismatches(cards: list[CardInput]) -> list[dict[str, str]]:
    mismatch: list[dict[str, str]] = []
    for card in cards:
        computed = card_uid(card.front)
        if card.uid != computed:
            mismatch.append(
                {
                    "front": card.front,
                    "received": card.uid,
                    "computed": computed,
                }
            )
    return mismatch


def _job_id_from_path(path: str, prefix: str) -> str:
    job_id = path[len(prefix):]
    if "/" in job_id:
        return ""
    return job_id


def _safe_filename(name: str) -> str:
    cleaned = _SAFE_FILENAME_RE.sub("_", name.strip()).strip("._")
    return cleaned or "deck"


def _ip_sort_key(address: str) -> tuple[int, int]:
    ip = ipaddress.IPv4Address(address)
    return (1 if ip.is_loopback else 0, int(ip))
