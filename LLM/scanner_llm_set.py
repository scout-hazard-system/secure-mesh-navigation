"""Client layer for the proprietary "scout" LLM set (Ollama-backed).

The scout set is built from llm_set/Modelfile.* (see llm_set/build_llm_set.sh):
- scout-alert: enforcement alert decision (ALERT:/IGNORE, 1 sentence)
- scout-intel: structured dispatch intel extraction (strict JSON)
- scout-rank:  channel selector reranking (strict JSON; used via channel_selector.py)

Every query gracefully falls back to the base model (qwen3:8b) with an inline
system prompt when the scout model is not installed, so the pipeline keeps
working before/without `llm_set/build_llm_set.sh` having been run.
"""

import base64
import json
import os
import time

import requests

try:
    from cryptography.fernet import Fernet, InvalidToken
    _FERNET_AVAILABLE = True
except Exception:  # pragma: no cover - optional dependency
    Fernet = None
    InvalidToken = Exception
    _FERNET_AVAILABLE = False

OLLAMA_GENERATE_URL = "http://localhost:11434/api/generate"
OLLAMA_TAGS_URL = "http://localhost:11434/api/tags"
BASE_MODEL = "qwen3:8b"

# Fernet encryption configuration. The key is loaded (in order of preference)
# from SCOUT_FERNET_KEY, a persisted key file, or freshly generated. When a
# fresh key is generated, SCOUT_FERNET_KEYFILE is written so later runs can
# still decrypt previously encrypted artifacts.
SCOUT_FERNET_KEY_ENV = "SCOUT_FERNET_KEY"
SCOUT_FERNET_KEYFILE_ENV = "SCOUT_FERNET_KEYFILE"
DEFAULT_FERNET_KEYFILE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), ".scout_fernet.key"
)
SCOUT_SECURE_TRANSPORT_ENV = "SCOUT_SECURE_TRANSPORT"
FERNET_ENVELOPE_PREFIX = "fernet:v1:"

ALERT_MODEL = "scout-alert"
INTEL_MODEL = "scout-intel"
RANK_MODEL = "scout-rank"
SCOUT_MODELS = (ALERT_MODEL, INTEL_MODEL, RANK_MODEL)


class FernetCrypto:
    """Fernet (AES-128-CBC + HMAC-SHA256) helper for scanner payloads.

    Key resolution order:
      1. SCOUT_FERNET_KEY env var (explicit key, base64 urlsafe)
      2. SCOUT_FERNET_KEYFILE env var (path to a persisted key file)
      3. DEFAULT_FERNET_KEYFILE next to this module (auto-created)
      4. in-memory generated key (not persisted; decrypts nothing across runs)
    """

    def __init__(self, key=None):
        if key is None:
            key = self._load_or_create_key()
        if Fernet is None:
            raise RuntimeError("Fernet requires the 'cryptography' package (pip install cryptography)")
        self.fernet = Fernet(self._normalize_key(key))

    def _normalize_key(self, key):
        key = key.strip()
        if isinstance(key, str) and not key.endswith("="):
            try:
                base64.urlsafe_b64decode(key + "===")
                return key
            except Exception:
                pass
        return key

    @staticmethod
    def _load_or_create_key():
        key = os.environ.get(SCOUT_FERNET_KEY_ENV, "").strip()
        if key:
            return key
        keyfile = os.environ.get(SCOUT_FERNET_KEYFILE_ENV, "").strip() or DEFAULT_FERNET_KEYFILE
        try:
            with open(keyfile, "r", encoding="utf-8") as fh:
                stored = fh.read().strip()
            if stored:
                return stored
        except OSError:
            pass
        if Fernet is None:
            return "in-memory-key-unavailable"
        generated = Fernet.generate_key().decode("ascii")
        try:
            with open(keyfile, "x", encoding="utf-8") as fh:
                fh.write(generated + "\n")
                os.chmod(keyfile, 0o600)
        except OSError:
            pass
        return generated

    def encrypt_bytes(self, data):
        return self.fernet.encrypt(data)

    def decrypt_bytes(self, token):
        try:
            return self.fernet.decrypt(token)
        except InvalidToken as exc:
            raise InvalidToken(f"fernet decryption failed: {exc}")

    def encrypt_text(self, text):
        return self.fernet.encrypt(text.encode("utf-8"))

    def decrypt_text(self, token):
        return self.fernet.decrypt(token).decode("utf-8")

    def wrap_payload(self, payload):
        """Encrypt a dict payload into a base64 fernet token string."""
        token = self.fernet.encrypt(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
        return FERNET_ENVELOPE_PREFIX + base64.urlsafe_b64encode(token).decode("ascii")

    def unwrap_payload(self, wrapped):
        """Decrypt a wrapped payload string back into a dict."""
        if isinstance(wrapped, str) and wrapped.startswith(FERNET_ENVELOPE_PREFIX):
            token = base64.urlsafe_b64decode(wrapped[len(FERNET_ENVELOPE_PREFIX):].encode("ascii"))
            return json.loads(self.fernet.decrypt(token).decode("utf-8"))
        return None

    def verify_roundtrip(self):
        probe = {"model": BASE_MODEL, "prompt": "fernet roundtrip probe"}
        wrapped = self.wrap_payload(probe)
        unwrapped = self.unwrap_payload(wrapped)
        return unwrapped == probe


def fernet_enabled():
    return _FERNET_AVAILABLE and os.environ.get(SCOUT_SECURE_TRANSPORT_ENV, "0") not in ("", "0", "false", "False")


_crypto_singleton = None
_crypto_singleton_ok = False


def get_fernet_crypto():
    """Shared FernetCrypto singleton. Returns None when unavailable."""
    global _crypto_singleton, _crypto_singleton_ok
    if not _FERNET_AVAILABLE:
        return None
    if _crypto_singleton_ok:
        return _crypto_singleton
    try:
        _crypto_singleton = FernetCrypto()
        _crypto_singleton_ok = True
    except Exception:
        _crypto_singleton_ok = False
    return _crypto_singleton


def fernet_status():
    """Diagnostics for dev_server / health checks."""
    crypto = get_fernet_crypto()
    return {
        "fernet_available": _FERNET_AVAILABLE,
        "secure_transport_enabled": fernet_enabled(),
        "fernet_ready": crypto is not None,
        "roundtrip_ok": crypto is not None and crypto.verify_roundtrip(),
    }

# Inline fallback system prompts (mirror the Modelfile SYSTEM blocks) used when
# the scout model is missing and we must run against the raw base model.
ALERT_FALLBACK_SYSTEM = """
You are an in-car speed trap and radar alert assistant for a driver on a cross country trip.
The user gives you one police scanner transcript. Decide if it describes ACTIVE or PLANNED roadway traffic enforcement.

Reply ALERT for any of these (a single clue is enough):
- radar, laser, lidar, speed trap, clocking, pacing, speed enforcement (ground or aircraft)
- traffic stop / vehicle stop / 10-38 / pulling a vehicle over
- pursuit, spike strips, PIT maneuver, subject vehicle at high speed
- DUI checkpoint or emphasis patrol; seatbelt, cell phone, or school-zone emphasis
- officers "running traffic", traffic detail issuing citations, motor units working traffic

Reply IGNORE for everything else, including:
- fire/EMS/medical calls, welfare checks, alarms, thefts, noise complaints, warrants, K9 tracks
- accidents/collisions UNLESS enforcement activity is also mentioned
- radio checks, shift/admin chatter, meal breaks, plate returns with no stop, parades/road closures, static/unreadable audio

Output contract:
- If ALERT: reply EXACTLY one sentence starting with 'ALERT:' describing the enforcement.
- Repeat every location mentioned VERBATIM in your sentence: streets, intersections, highways/routes,
  exits, mile markers, directions of travel, and points of interest (businesses, schools, parks, landmarks).
- If IGNORE: reply with the single word IGNORE.
- Never add explanations, quotes, or extra lines.
"""

INTEL_FALLBACK_SYSTEM = """
You are a police scanner dispatch analyst. Extract structured intel from the transcript and
reply with ONLY a single JSON object using exactly this schema (all keys always present):
{"call_types": ["traffic_stop"|"speed_enforcement"|"pursuit"|"welfare_check"|"suspicious_activity"|"accident"|"units_coordination"|"unclassified", ...],
 "priority": "high"|"medium"|"low"|"unknown", "codes": [...],
 "units": [...], "locations": [...], "pois": [...], "summary": "..."}
call_types definitions (pick every one that applies; use "unclassified" only when none apply):
- traffic_stop: an officer stopping or having stopped a specific vehicle (10-38, vehicle stop, plate on a stop).
- speed_enforcement: radar/laser/lidar, speed trap, clocking, pacing, aircraft speed work, "running traffic",
  DUI checkpoint, and any emphasis patrol (speed, seatbelt, cell phone, school zone) or traffic detail with citations.
- pursuit: fleeing vehicle, failure to yield, spike strips, PIT, high-speed chase.
- welfare_check: checking on a person's wellbeing.
- suspicious_activity: prowlers, suspicious persons/vehicles, thefts and shoplifting
  (in progress, just occurred, or suspect in custody).
- accident: collisions, crashes, injury or non-injury wrecks.
- units_coordination: channel switches, staging, backup requests, detail assignments without another category.
- unclassified: fire/EMS/medical, alarms, admin chatter, anything not covered above.
Rules:
- Copy location and POI wording verbatim from the transcript; never invent places.
- Keep full street numbers verbatim: "1204 M Avenue", never just "M Avenue".
- "locations": streets, intersections, highways, exits, mile markers, blocks, directions of travel.
- "pois": named or described places such as businesses, schools, parks, terminals, libraries,
  fairgrounds, hotels, restaurants, landmarks. A place like "the library" or "the ferry terminal" is a POI.
- Apartment complexes and named buildings are POIs, not locations.
- "codes": any 10-codes or 'code N' phrases heard. "units": unit numbers/callsigns.
- "priority": high for in-progress pursuit/officer-assist/emergency, medium for stops/accidents/suspicious,
  low for clear/cancel/information-only, unknown otherwise.
- Use empty arrays / empty string when nothing applies. No prose outside the JSON object.
"""

_INTEL_LIST_KEYS = ("call_types", "codes", "units", "locations", "pois")

_availability_cache = {"ts": 0.0, "models": None}
AVAILABILITY_TTL_SECONDS = 300.0


def installed_models(tags_url=OLLAMA_TAGS_URL, timeout_seconds=2.0, force_refresh=False):
    """Return the set of installed Ollama model names (without ':latest'), or None if Ollama is down."""
    now = time.time()
    if (
        not force_refresh
        and _availability_cache["models"] is not None
        and now - _availability_cache["ts"] < AVAILABILITY_TTL_SECONDS
    ):
        return _availability_cache["models"]
    try:
        response = requests.get(tags_url, timeout=timeout_seconds)
        response.raise_for_status()
        names = set()
        for item in response.json().get("models", []):
            name = str(item.get("name", ""))
            if name:
                names.add(name)
                if name.endswith(":latest"):
                    names.add(name[: -len(":latest")])
        _availability_cache["models"] = names
        _availability_cache["ts"] = now
        return names
    except Exception:
        _availability_cache["models"] = None
        _availability_cache["ts"] = now
        return None


def llm_set_status(tags_url=OLLAMA_TAGS_URL, timeout_seconds=2.0, force_refresh=False):
    """Availability summary for pipeline_ready events and diagnostics."""
    models = installed_models(tags_url, timeout_seconds, force_refresh)
    ollama_up = models is not None
    models = models or set()
    return {
        "ollama_up": ollama_up,
        "base_model": BASE_MODEL,
        "base_model_installed": BASE_MODEL in models,
        "models": {name: name in models for name in SCOUT_MODELS},
        "complete": all(name in models for name in SCOUT_MODELS),
    }


def resolve_model(preferred, fallback=BASE_MODEL, tags_url=OLLAMA_TAGS_URL):
    """Pick the preferred scout model when installed, else the fallback base model."""
    models = installed_models(tags_url)
    if models is None or preferred in models:
        # Ollama down: keep preferred so error surfaces attribute the right model.
        return preferred, False
    return fallback, True


def _generate(payload, url, timeout_seconds, retries, secure=None, crypto=None):
    attempts = retries + 1
    last_error = None
    last_status = None
    last_raw = None
    if secure is None:
        secure = fernet_enabled()
    crypto = crypto if crypto is not None else (get_fernet_crypto() if secure else None)
    for _ in range(attempts):
        try:
            if secure and crypto is not None:
                body = crypto.wrap_payload(payload)
                response = requests.post(
                    url,
                    data=body,
                    headers={"Content-Type": "application/x-fernet"},
                    timeout=timeout_seconds,
                )
                last_status = response.status_code
                raw_text = response.text
                last_raw = raw_text[:500]
                unwrapped = crypto.unwrap_payload(raw_text)
                parsed = unwrapped if isinstance(unwrapped, dict) else {}
                if parsed.get("response") is None and isinstance(unwrapped, dict):
                    parsed = unwrapped
            else:
                response = requests.post(url, json=payload, timeout=timeout_seconds)
                last_status = response.status_code
                raw_text = response.text
                last_raw = raw_text[:500]
                try:
                    parsed = response.json()
                except Exception:
                    parsed = {}
            return {
                "response": parsed.get("response", ""),
                "status_code": last_status,
                "error": None,
                "raw": last_raw,
                "attempts": attempts,
                "secure": bool(secure and crypto is not None),
            }
        except Exception as e:
            last_error = repr(e)
    return {
        "response": "",
        "status_code": last_status,
        "error": last_error,
        "raw": last_raw,
        "attempts": attempts,
        "secure": bool(secure and crypto is not None),
    }


def query_alert(
    transcript_text,
    timeout_seconds=8.0,
    retries=0,
    url=OLLAMA_GENERATE_URL,
    model=ALERT_MODEL,
    secure=None,
):
    """Enforcement alert decision. Return contract matches pipeline.query_llm plus model info."""
    resolved_model, used_fallback = resolve_model(model)
    if used_fallback:
        prompt = f"{ALERT_FALLBACK_SYSTEM}\n\nTranscript: {transcript_text}"
    else:
        prompt = f"Transcript: {transcript_text}"
    payload = {"model": resolved_model, "prompt": prompt, "stream": False}
    result = _generate(payload, url, timeout_seconds, retries, secure=secure)
    if not result["response"]:
        result["response"] = "IGNORE"
    result["model"] = resolved_model
    result["used_fallback"] = used_fallback
    return result


def _coerce_intel(parsed):
    """Normalize a parsed intel object to the fixed schema."""
    intel = {key: [] for key in _INTEL_LIST_KEYS}
    intel["priority"] = "unknown"
    intel["summary"] = ""
    if not isinstance(parsed, dict):
        return intel
    for key in _INTEL_LIST_KEYS:
        value = parsed.get(key)
        if isinstance(value, list):
            intel[key] = [str(v).strip() for v in value if str(v).strip()]
        elif isinstance(value, str) and value.strip():
            intel[key] = [value.strip()]
    priority = str(parsed.get("priority", "unknown")).strip().lower()
    if priority in ("high", "medium", "low"):
        intel["priority"] = priority
    summary = parsed.get("summary")
    if isinstance(summary, str):
        intel["summary"] = summary.strip()
    return intel


def query_intel(
    transcript_text,
    timeout_seconds=10.0,
    retries=0,
    url=OLLAMA_GENERATE_URL,
    model=INTEL_MODEL,
    secure=None,
):
    """Structured dispatch intel extraction. Returns {'intel': dict|None, ...diagnostics}."""
    resolved_model, used_fallback = resolve_model(model)
    if used_fallback:
        prompt = f"{INTEL_FALLBACK_SYSTEM}\n\nTranscript: {transcript_text}"
    else:
        prompt = f"Transcript: {transcript_text}"
    payload = {"model": resolved_model, "prompt": prompt, "stream": False, "format": "json"}
    result = _generate(payload, url, timeout_seconds, retries, secure=secure)
    intel = None
    parse_error = None
    if result["response"]:
        try:
            intel = _coerce_intel(json.loads(result["response"]))
        except Exception as e:
            parse_error = repr(e)
    result["intel"] = intel
    result["parse_error"] = parse_error
    result["model"] = resolved_model
    result["used_fallback"] = used_fallback
    return result


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="scout LLM set client (status/alert/intel/crypto)")
    parser.add_argument("command", choices=["status", "alert", "intel", "crypto"])
    parser.add_argument("text", nargs="?", default="", help="Transcript text for alert/intel")
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--secure", action="store_true", help="Wrap requests in a Fernet envelope")
    args = parser.parse_args()
    if args.command == "crypto":
        status = fernet_status()
        status["keyfile"] = DEFAULT_FERNET_KEYFILE
        status["keyfile_used"] = os.environ.get(SCOUT_FERNET_KEYFILE_ENV, DEFAULT_FERNET_KEYFILE)
        print(json.dumps(status, indent=2))
    elif args.command == "status":
        out = llm_set_status(force_refresh=True)
        out["crypto"] = fernet_status()
        print(json.dumps(out, indent=2))
    elif args.command == "alert":
        print(json.dumps(query_alert(args.text, timeout_seconds=args.timeout, secure=args.secure), indent=2))
    else:
        print(json.dumps(query_intel(args.text, timeout_seconds=args.timeout, secure=args.secure), indent=2))
