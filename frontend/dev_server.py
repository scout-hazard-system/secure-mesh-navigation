#!/usr/bin/env python3
# Copyright 2026 Scout Project Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import json
import os
import sys
import time
from collections import Counter, deque
from datetime import datetime, UTC
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib import error as urlerror
from urllib import request as urlrequest
from urllib.parse import parse_qs, urlparse

ROOT = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
LOG_PATH = Path(os.environ.get("PIPELINE_LOG_PATH", str(ROOT / "pipeline_live_doordash.log")))
HOST = os.environ.get("FRONTEND_DEV_HOST", "0.0.0.0")
PORT = int(os.environ.get("FRONTEND_DEV_PORT", "8787"))
BACKEND_BASE_URL = os.environ.get("BACKEND_BASE_URL", "http://127.0.0.1:18080").rstrip("/")
RECENT_EVENT_LIMIT = 120
STREAM_POLL_SECONDS = 0.35

try:
    import payment as _payment_mod
except Exception:
    _payment_mod = None

try:
    sys.path.insert(0, str(ROOT.parent / "LLM"))
    import scanner_llm_set as _scanner_llm
except Exception:
    _scanner_llm = None


def _payment_status():
    """One-time payment gate status for dev_server / health checks."""
    if _payment_mod is None:
        return {"payment": {"module_available": False}}
    gate = _payment_mod.OneTimePayment.from_env()
    return {"payment": gate.status()}


def _crypto_status():
    """Fernet encryption status for dev_server / health checks."""
    if _scanner_llm is None:
        return {"crypto": {"module_available": False}}
    return {"crypto": _scanner_llm.fernet_status()}


def _run_selftest():
    """Integration self-test covering payment, encryption, and shard plumbing.

    Results are non-destructive: payment uses an isolated in-memory ledger path
    and generated signing key so it never touches the real license ledger.
    """
    results = {}

    # 1. One-time payment gate (self-contained, temp ledger).
    try:
        import tempfile as _tempfile
        if _payment_mod is None:
            results["payment"] = {"ok": False, "reason": "payment module unavailable"}
        else:
            gate = _payment_mod.OneTimePayment(
                ledger_path=Path(_tempfile.gettempdir()) / "_smn_selftest_ledger.json",
                signing_key=os.urandom(32),
            )
            gate.clear_ledger()
            token = gate.issue_receipt(transaction_id="selftest-tx")
            first = gate.redeem(token)
            rejected = False
            try:
                gate.redeem(token)
            except _payment_mod.PaymentError:
                rejected = True
            results["payment"] = {
                "ok": first.get("licensed") is True and rejected,
                "redeemed": first.get("licensed"),
                "one_time_enforced": rejected,
            }
    except Exception as exc:
        results["payment"] = {"ok": False, "reason": repr(exc)}

    # 2. Fernet encryption roundtrip.
    if _scanner_llm is None:
        results["crypto"] = {"ok": False, "reason": "scanner_llm_set unavailable"}
    else:
        status = _scanner_llm.fernet_status()
        results["crypto"] = {
            "ok": bool(status.get("roundtrip_ok")) and bool(status.get("fernet_ready")),
            "roundtrip_ok": status.get("roundtrip_ok"),
            "fernet_ready": status.get("fernet_ready"),
        }

    # 3. Encrypted node channel implementation present (Java self-test is run via java main).
    channel_file = ROOT.parent / "encryption" / "secure_channel.java"
    results["secure_channel"] = {
        "ok": channel_file.exists(),
        "file": str(channel_file),
    }

    # 4. Dynamic shard allocation present in the backend tile store.
    shard_file = ROOT.parent / "backend" / "PlanetTileStore.java"
    shard_ok = False
    if shard_file.exists():
        shard_ok = "registerShard" in shard_file.read_text(
            encoding="utf-8", errors="ignore"
        ) and "resolveShardState" in shard_file.read_text(encoding="utf-8", errors="ignore")
    results["dynamic_shards"] = {"ok": shard_ok, "file": str(shard_file)}

    results["ok"] = all(
        entry.get("ok")
        for key, entry in results.items()
        if key != "ok" and isinstance(entry, dict) and "ok" in entry
    )
    results["ts"] = now_iso()
    return results


def now_iso():
    return datetime.now(UTC).isoformat()


def _safe_float(val, default=0.0):
    try:
        return float(val)
    except Exception:
        return default


def read_event_json_lines(max_events=RECENT_EVENT_LIMIT):
    events = deque(maxlen=max_events)
    if not LOG_PATH.exists():
        return list(events)
    with LOG_PATH.open("r", errors="ignore") as f:
        for raw in f:
            if not raw.startswith("[EVENT_JSON] "):
                continue
            payload = raw[len("[EVENT_JSON] ") :].strip()
            try:
                events.append(json.loads(payload))
            except Exception:
                continue
    return list(events)


def build_snapshot():
    events = read_event_json_lines()
    metrics = {
        "captured": 0,
        "skipped_silence": 0,
        "skipped_clipped": 0,
        "llm_alert": 0,
        "soft_alert_fallback": 0,
    }
    counters = Counter()

    for ev in events:
        et = ev.get("event_type")
        counters[et] += 1
        if et == "chunk_captured":
            metrics["captured"] += 1
        elif et == "chunk_skipped_silence":
            metrics["skipped_silence"] += 1
        elif et == "chunk_skipped_clipped":
            metrics["skipped_clipped"] += 1
        elif et == "alert_triggered":
            kind = ev.get("kind")
            if kind == "soft_alert_fallback":
                metrics["soft_alert_fallback"] += 1
            else:
                metrics["llm_alert"] += 1
        elif et == "run_summary":
            metrics = {
                "captured": int(ev.get("captured", metrics["captured"])),
                "skipped_silence": int(ev.get("skipped_silence", metrics["skipped_silence"])),
                "skipped_clipped": int(ev.get("skipped_clipped", metrics["skipped_clipped"])),
                "llm_alert": int(ev.get("llm_alert", metrics["llm_alert"])),
                "soft_alert_fallback": int(ev.get("soft_alert_fallback", metrics["soft_alert_fallback"])),
            }

    return {
        "ts": now_iso(),
        "log_path": str(LOG_PATH),
        "event_type_counts": dict(counters),
        "metrics": metrics,
        "recentEvents": events[-30:],
    }


def mock_weather(start, end):
    base = [
        ("start", "Now", 78, "clear"),
        ("segment-1", "+20m", 79, "partly_cloudy"),
        ("segment-2", "+40m", 80, "windy"),
        ("segment-3", "+60m", 81, "light_rain"),
        ("destination", "+80m", 79, "cloudy"),
    ]
    return {
        "ts": now_iso(),
        "start": start,
        "end": end,
        "forecast": [
            {"segment": seg, "time": t, "temp": temp, "condition": cond}
            for seg, t, temp, cond in base
        ],
    }


class FrontendHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def _send_json(self, payload, status=HTTPStatus.OK):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_sse_headers(self):
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Connection", "keep-alive")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

    def _stream_pipeline_events(self):
        self._send_sse_headers()
        heartbeat = {
            "ts": now_iso(),
            "event_type": "server_heartbeat",
            "source": "dev_server",
            "log_path": str(LOG_PATH),
        }
        self.wfile.write(f"data: {json.dumps(heartbeat)}\n\n".encode("utf-8"))
        self.wfile.flush()

        if not LOG_PATH.exists():
            warn = {
                "ts": now_iso(),
                "event_type": "server_warning",
                "message": f"log file not found: {LOG_PATH}",
            }
            self.wfile.write(f"data: {json.dumps(warn)}\n\n".encode("utf-8"))
            self.wfile.flush()

        offset = 0
        if LOG_PATH.exists():
            offset = LOG_PATH.stat().st_size

        try:
            while True:
                if not LOG_PATH.exists():
                    time.sleep(STREAM_POLL_SECONDS)
                    continue

                current_size = LOG_PATH.stat().st_size
                if current_size < offset:
                    offset = 0
                if current_size == offset:
                    time.sleep(STREAM_POLL_SECONDS)
                    continue

                with LOG_PATH.open("r", errors="ignore") as f:
                    f.seek(offset)
                    chunk = f.read()
                    offset = f.tell()

                for line in chunk.splitlines():
                    if not line.startswith("[EVENT_JSON] "):
                        continue
                    payload = line[len("[EVENT_JSON] ") :].strip()
                    try:
                        ev = json.loads(payload)
                    except Exception:
                        continue
                    msg = f"data: {json.dumps(ev, ensure_ascii=False)}\n\n".encode("utf-8")
                    self.wfile.write(msg)
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            return

    def _proxy_backend_get(self):
        upstream_url = f"{BACKEND_BASE_URL}{self.path}"
        try:
            req = urlrequest.Request(
                upstream_url,
                method="GET",
                headers={"User-Agent": "scanner-frontend-lite/0.1"},
            )
            with urlrequest.urlopen(req, timeout=20) as upstream:
                body = upstream.read()
                content_type = upstream.headers.get("Content-Type", "application/octet-stream")
                self.send_response(upstream.status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)
                return
        except urlerror.HTTPError as exc:
            body = exc.read()
            content_type = exc.headers.get("Content-Type", "application/json; charset=utf-8")
            self.send_response(exc.code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
            return
        except Exception as exc:
            self._send_json(
                {
                    "error": "backend_proxy_unavailable",
                    "message": str(exc),
                    "upstream": upstream_url,
                },
                status=HTTPStatus.BAD_GATEWAY,
            )
            return

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        if path == "/api/pipeline/snapshot":
            self._send_json(build_snapshot())
            return

        if path == "/api/pipeline/stream":
            self._stream_pipeline_events()
            return

        if path == "/api/route/weather":
            start = query.get("start", [""])[0]
            end = query.get("end", [""])[0]
            self._send_json(mock_weather(start, end))
            return

        if path == "/api/health":
            self._send_json(
                {
                    "status": "ok",
                    "ts": now_iso(),
                    "log_exists": LOG_PATH.exists(),
                    "log_path": str(LOG_PATH),
                }
            )
            return
        if path == "/api/platform/payment/status":
            self._send_json(_payment_status())
            return
        if path == "/api/platform/crypto/status":
            self._send_json(_crypto_status())
            return
        if path == "/api/platform/dev/selftest":
            self._send_json(_run_selftest())
            return
        if path.startswith("/api/"):
            self._proxy_backend_get()
            return

        return super().do_GET()


def main():
    server = ThreadingHTTPServer((HOST, PORT), FrontendHandler)
    print(f"[frontend-dev-server] serving {ROOT} on http://{HOST}:{PORT}")
    print(f"[frontend-dev-server] pipeline log source: {LOG_PATH}")
    server.serve_forever()


if __name__ == "__main__":
    main()
