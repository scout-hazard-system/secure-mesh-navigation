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

import sys
from pathlib import Path as _Path
_REPO_ROOT = _Path(__file__).resolve().parents[2]
_CLIENT = _REPO_ROOT / "llm" / "client"
_PIPELINE_DIR = _Path(__file__).resolve().parent
for _p in (str(_CLIENT), str(_PIPELINE_DIR), str(_REPO_ROOT)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import argparse
import os
import subprocess
import shutil
import time
import re
import sys
import signal
import json
import tempfile
from datetime import datetime, UTC
import scipy.io.wavfile as wav
import requests
import numpy as np
from faster_whisper import WhisperModel
from channel_selector import SelectorContext, haversine_km, load_channels, select_channels
from optional_audio_routes import ensure_optional_route_enabled
try:
    import llm_set_client
except Exception:
    llm_set_client = None

try:
    import payment as payment_gate
except Exception:
    payment_gate = None


def payment_enabled():
    return payment_gate is not None


FS = 16000          # Audio frequency standard for Whisper
DURATION = 12       # Grabs audio in 12-second intervals to minimize processing lag
OLLAMA_URL = "http://localhost:11434/api/generate"
SHOULD_EXIT = False
sd = None
HTTP_SESSION = requests.Session()
ALERT_PREFIX_RE = re.compile(r"^\s*ALERT\s*:", flags=re.IGNORECASE)
PIPER_BIN = os.environ.get("PIPER_BIN", os.path.join(os.environ.get("SCOUT_REPO_ROOT", "/home/gibi/Desktop"), "cop_pipeline", "bin", "piper"))
PIPER_MODEL_PATH = os.environ.get("PIPER_MODEL_PATH", "").strip()
PIPER_MODEL_CONFIG_PATH = os.environ.get("PIPER_MODEL_CONFIG_PATH", "").strip()
TTS_REPEAT_SUPPRESS_SECONDS = float(os.environ.get("TTS_REPEAT_SUPPRESS_SECONDS", "20"))
TTS_MAX_MESSAGE_CHARS = int(os.environ.get("TTS_MAX_MESSAGE_CHARS", "220"))
_LAST_TTS_MESSAGE = ""
_LAST_TTS_TS = 0.0


def require_sounddevice():
    global sd
    if sd is None:
        import sounddevice as _sd
        sd = _sd
    return sd

def request_shutdown(signum, _frame):
    global SHOULD_EXIT
    SHOULD_EXIT = True
    print(f"\nReceived signal {signum}. Shutting down gracefully...")

def emit_event_json(event_type, enabled=True, **payload):
    if not enabled:
        return
    event = {
        "ts": datetime.now(UTC).isoformat(),
        "event_type": event_type,
        **payload,
    }
    print(f"[EVENT_JSON] {json.dumps(event, ensure_ascii=False)}")

# Legacy inline alert prompt: only used when llm_set_client cannot be imported.
# Kept in sync with the alert TASK contract in scout-vet1.0.8
# (llm/vet/Modelfile.scout-vet* / llm_set_client.ALERT_FALLBACK_SYSTEM).
SYSTEM_PROMPT = """
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
if llm_set_client is not None:
    SYSTEM_PROMPT = llm_set_client.ALERT_FALLBACK_SYSTEM

CALL_TYPE_KEYWORDS = {
    "traffic_stop": ["traffic stop", "stopped", "vehicle stop", "10-38", "10 38"],
    "speed_enforcement": ["radar", "laser", "clocked", "speed trap", "pacing"],
    "pursuit": ["pursuit", "chase", "failing to yield"],
    "welfare_check": ["welfare check", "check well-being"],
    "suspicious_activity": ["suspicious", "loitering", "prowler"],
    "accident": ["accident", "mvc", "crash", "collision"],
    "units_coordination": ["copy", "switch over", "channel", "unit", "dispatch"],
}
PRIORITY_KEYWORDS = {
    "high": ["in progress", "shots fired", "officer needs assistance", "pursuit", "urgent"],
    "medium": ["traffic stop", "suspicious", "welfare check", "accident"],
    "low": ["clear", "cancel", "advised", "non-injury", "information only"],
}
DISPATCH_CUE_GROUPS = {
    "primary_enforcement": ["radar", "laser", "clocked", "speed trap", "pacing", "10-38", "10 38", "vehicle stop", "running traffic"],
    "unit_ack": ["unit", "copy", "go ahead", "10-", "code "],
    "location_markers": ["mile marker", "exit", "northbound", "southbound", "on-ramp", "shoulder", "highway"],
    "coordination": ["switch to channel", "channel", "in progress", "dispatch"],
}
DISPATCH_CUE_WEIGHTS = {
    "primary_enforcement": 3,
    "location_markers": 2,
    "unit_ack": 1,
    "coordination": 1,
}
PRIMARY_ENFORCEMENT_STRONG = {"radar", "laser", "clocked", "speed trap", "pacing", "vehicle stop", "running traffic"}
DIRECT_SOURCE_FALLBACK_TOKENS = [
    "pipewire",
    "alsa_input",
    "analog",
    "usb",
    "microphone",
    "mic",
    "input",
]

def log_safe(text):
    return text.replace('"', "'").replace("\n", " ").strip()

AUDIO_LEVEL_WINDOW_MS = 250
AUDIO_LEVEL_MAX_WINDOWS = 240

def compute_audio_levels(mono, sample_rate, window_ms=AUDIO_LEVEL_WINDOW_MS, max_windows=AUDIO_LEVEL_MAX_WINDOWS):
    """Per-window RMS envelope of a captured chunk.

    Drives the app's popup audio visualizer so the bars play back the actual
    audio envelope instead of a single static amplitude. Returns a list of
    rounded floats (one per window_ms slice), capped at max_windows to bound
    event payload size.
    """
    if mono is None or len(mono) == 0 or sample_rate <= 0:
        return []
    window = max(1, int(sample_rate * window_ms / 1000.0))
    count = min(max_windows, (len(mono) + window - 1) // window)
    levels = []
    for i in range(count):
        segment = mono[i * window:(i + 1) * window]
        if len(segment) == 0:
            break
        levels.append(round(float(np.sqrt(np.mean(segment ** 2))), 4))
    return levels

def extract_dispatch_cues(text):
    lower = text.lower()
    matched = {}
    for group, cues in DISPATCH_CUE_GROUPS.items():
        found = []
        for cue in cues:
            if cue in lower:
                found.append(cue)
        if found:
            matched[group] = sorted(set(found))
    cue_count = sum(len(v) for v in matched.values())
    return matched, cue_count

def score_dispatch_cues(cue_map):
    score = 0
    for group, values in cue_map.items():
        weight = DISPATCH_CUE_WEIGHTS.get(group, 1)
        score += weight * len(values)
    return score

def has_strong_enforcement_signal(cue_map):
    for cue in cue_map.get("primary_enforcement", []):
        if cue in PRIMARY_ENFORCEMENT_STRONG:
            return True
    return False

def extract_codes(text):
    ten_codes = [f"10-{m.group(1)}" for m in re.finditer(r"\b10[-\s]?(\d{1,2})\b", text, flags=re.IGNORECASE)]
    generic_codes = [f"code {m.group(1)}" for m in re.finditer(r"\bcode[-\s]?(\d{1,3})\b", text, flags=re.IGNORECASE)]
    return sorted(set(ten_codes + generic_codes))

POI_KEYWORDS = [
    "gas station", "truck stop", "rest area", "rest stop", "weigh station",
    "high school", "middle school", "elementary school", "school", "college", "university",
    "hospital", "clinic", "fire station", "police station", "courthouse", "post office",
    "library", "city hall", "park", "fairgrounds", "campground", "marina", "ferry terminal",
    "airport", "train station", "bus station", "transit center",
    "mall", "shopping center", "plaza", "supermarket", "grocery store", "convenience store",
    "liquor store", "pharmacy", "bank", "casino", "church",
    "hotel", "motel", "inn", "apartment complex", "trailer park", "mobile home park",
    "restaurant", "diner", "bar", "tavern", "car wash", "parking lot", "parking garage",
    "bridge", "overpass", "underpass", "tunnel", "roundabout", "railroad crossing",
]

def _dedupe_mentions(mentions):
    deduped = []
    seen = set()
    for mention in mentions:
        key = mention.lower()
        if key not in seen:
            seen.add(key)
            deduped.append(mention)
    return deduped

def extract_location_mentions(text):
    patterns = [
        r"\b\d{1,5}\s+[A-Za-z0-9.'-]+(?:\s+[A-Za-z0-9.'-]+){0,4}\s+(?:st|street|ave|avenue|rd|road|blvd|boulevard|dr|drive|ln|lane|ct|court|hwy|highway|pkwy|parkway|way|pl|place)\b",
        r"\b\d{2,5}\s+block\s+of\s+[A-Za-z0-9.'-]+(?:\s+[A-Za-z0-9.'-]+){0,4}\b",
        r"\b(?:at|near|on)\s+([A-Za-z0-9.'-]+(?:\s+[A-Za-z0-9.'-]+){0,4}\s+(?:and|&)\s+[A-Za-z0-9.'-]+(?:\s+[A-Za-z0-9.'-]+){0,4})\b",
        r"\b(?:interstate|i)[-\s]?\d{1,3}\b",
        r"\b(?:us|state route|sr|route|highway|hwy)[-\s]?\d{1,3}\b",
        r"\b(?:mile marker|mm)\s*\d{1,3}(?:\.\d+)?\b",
        r"\bexit\s+\d+[A-Za-z]?\b",
        r"\b(?:northbound|southbound|eastbound|westbound)\b",
        r"\b(?:on-ramp|off-ramp|shoulder|interchange)\b",
    ]
    mentions = []
    for pattern in patterns:
        for match in re.finditer(pattern, text, flags=re.IGNORECASE):
            if match.groups():
                candidate = match.group(1)
            else:
                candidate = match.group(0)
            clean = re.sub(r"\s+", " ", candidate).strip(" ,.;:")
            # Trim trailing connector clauses (e.g. '... Avenue near the Shell').
            clean = re.split(r"\s+(?:near|by|at)\s+", clean, maxsplit=1, flags=re.IGNORECASE)[0]
            if clean:
                mentions.append(clean)
    return _dedupe_mentions(mentions)

def extract_poi_mentions(text):
    mentions = []
    for keyword in POI_KEYWORDS:
        for match in re.finditer(r"\b" + re.escape(keyword) + r"\b", text, flags=re.IGNORECASE):
            # Pull up to three preceding capitalized name words so named POIs are
            # captured whole (e.g. 'Anacortes High School', 'Shell gas station').
            prefix = text[: match.start()]
            lead = re.search(r"((?:[A-Z][A-Za-z0-9'&.-]*\s+){1,3})$", prefix)
            mention = (lead.group(1) if lead else "") + match.group(0)
            clean = re.sub(r"\s+", " ", mention).strip(" ,.;:")
            if clean:
                mentions.append(clean)
    # Drop shorter mentions fully contained in a longer one (keyword-only vs named POI).
    filtered = []
    lowered = [m.lower() for m in mentions]
    for i, mention in enumerate(mentions):
        contained = any(
            j != i and lowered[i] in lowered[j] and len(lowered[j]) > len(lowered[i])
            for j in range(len(mentions))
        )
        if not contained:
            filtered.append(mention)
    return _dedupe_mentions(filtered)

def classify_transcript(text):
    lower = text.lower()
    matched_types = []
    for call_type, keywords in CALL_TYPE_KEYWORDS.items():
        if any(k in lower for k in keywords):
            matched_types.append(call_type)
    if not matched_types:
        matched_types = ["unclassified"]

    priority = "unknown"
    for level, keywords in PRIORITY_KEYWORDS.items():
        if any(k in lower for k in keywords):
            priority = level
            break

    confidence = 0.45
    if matched_types != ["unclassified"]:
        confidence += 0.25
    if priority != "unknown":
        confidence += 0.15
    codes = extract_codes(text)
    if codes:
        confidence += 0.15
    confidence = min(confidence, 0.95)

    return {
        "call_types": matched_types,
        "priority": priority,
        "codes": codes,
        "confidence": round(confidence, 2),
    }

def normalize_llm_response_text(response_text):
    if response_text is None:
        return "IGNORE"
    normalized = str(response_text).strip()
    if len(normalized) >= 2 and normalized[0] == normalized[-1] and normalized[0] in {"\"", "'"}:
        normalized = normalized[1:-1].strip()
    return normalized or "IGNORE"

def is_alert_response(response_text):
    return bool(ALERT_PREFIX_RE.match(normalize_llm_response_text(response_text)))
def query_llm(transcript_text, timeout_seconds=3.0, retries=0):
    payload = {
        "model": os.getenv("OLLAMA_ALERT_MODEL", "llama3.1"),
        "prompt": f"{SYSTEM_PROMPT}\n\nTranscript: {transcript_text}",
        "stream": False
    }
    attempts = retries + 1
    last_error = None
    last_status = None
    last_raw = None
    for attempt_idx in range(attempts):
        try:
            response = HTTP_SESSION.post(OLLAMA_URL, json=payload, timeout=timeout_seconds)
            last_status = response.status_code
            raw_text = response.text or ""
            last_raw = raw_text[:500]
            try:
                parsed = response.json()
            except Exception:
                parsed = {}
            model_response = parsed.get("response")
            if not isinstance(model_response, str):
                model_response = raw_text
            model_response = normalize_llm_response_text(model_response)
            if response.status_code >= 400 and model_response == "IGNORE":
                last_error = f"ollama_http_{response.status_code}"
                if attempt_idx < attempts - 1:
                    time.sleep(min(0.6 * (2 ** attempt_idx), 2.5))
                    continue
            return {
                "response": model_response,
                "status_code": last_status,
                "error": None,
                "raw": last_raw,
                "attempts": attempt_idx + 1,
            }
        except Exception as e:
            last_error = repr(e)
            if attempt_idx < attempts - 1:
                time.sleep(min(0.6 * (2 ** attempt_idx), 2.5))
    return {
        "response": "IGNORE",
        "status_code": last_status,
        "error": last_error,
        "raw": last_raw,
        "attempts": attempts,
    }

def build_ollama_options(num_gpu=None, num_thread=None, temperature=None):
    options = {}
    if isinstance(num_gpu, (int, float)):
        options["num_gpu"] = int(num_gpu)
    if isinstance(num_thread, int) and num_thread > 0:
        options["num_thread"] = num_thread
    if isinstance(temperature, (int, float)):
        options["temperature"] = float(temperature)
    return options or None

def resolve_vet_core_runtime_options(args):
    vet_options = None
    core_options = None
    if args.llm_vet_cpu:
        vet_options = build_ollama_options(
            num_gpu=args.llm_vet_num_gpu,
            num_thread=args.llm_vet_num_thread,
            temperature=0.0,
        )
    if args.llm_core_num_gpu >= 0 or args.llm_core_num_thread > 0:
        core_options = build_ollama_options(
            num_gpu=(args.llm_core_num_gpu if args.llm_core_num_gpu >= 0 else None),
            num_thread=(args.llm_core_num_thread if args.llm_core_num_thread > 0 else None),
        )
    return vet_options, core_options
def list_input_devices():
    devices = require_sounddevice().query_devices()
    return [(idx, dev) for idx, dev in enumerate(devices) if dev["max_input_channels"] > 0]
def pick_default_input_device():
    sdev = require_sounddevice()
    input_devices = list_input_devices()
    if not input_devices:
        raise RuntimeError("No audio input devices detected by sounddevice.")
    for idx, dev in input_devices:
        if "pipewire" in dev["name"].lower():
            return idx, dev["name"], "preferred_pipewire_match"
    default_input_idx = None
    default_device = sdev.default.device
    if isinstance(default_device, (list, tuple)) and len(default_device) >= 1:
        default_input_idx = default_device[0]
    elif isinstance(default_device, int):
        default_input_idx = default_device
    if isinstance(default_input_idx, int) and default_input_idx >= 0:
        default_info = sdev.query_devices(default_input_idx)
        if default_info["max_input_channels"] > 0:
            return default_input_idx, default_info["name"], "system_default_input"
    first_idx, first_dev = input_devices[0]
    return first_idx, first_dev["name"], "first_available_input"
def resolve_input_device(device_index=None, source_name=None, strict_source_match=False):
    sdev = require_sounddevice()
    input_devices = list_input_devices()
    if device_index is not None:
        devices = sdev.query_devices()
        dev = devices[device_index]
        if dev["max_input_channels"] <= 0:
            raise RuntimeError(f"Selected device index {device_index} is not an input device: {dev['name']}")
        return device_index, dev["name"], "explicit_device_index"
    if source_name:
        token = source_name.lower()
        for idx, dev in input_devices:
            if token in dev["name"].lower():
                return idx, dev["name"], "explicit_source_token_match"
        if strict_source_match:
            raise RuntimeError(f"No input device matched --source-node '{source_name}'. Use --list-devices to inspect names.")
        print(f"No input device matched --source-node '{source_name}', continuing with auto-detection fallback.")
    used_fallback_tokens = []
    for token in DIRECT_SOURCE_FALLBACK_TOKENS:
        used_fallback_tokens.append(token)
        for idx, dev in input_devices:
            if token in dev["name"].lower():
                print(f"Auto-detected input via fallback token '{token}': {dev['name']} (index {idx})")
                return idx, dev["name"], f"fallback_token:{token}"
    idx, dev_name, selection_reason = pick_default_input_device()
    print(
        "No fallback token match found; using default strategy "
        f"({selection_reason}) -> {dev_name} (index {idx}). "
        f"Tried tokens: {used_fallback_tokens}"
    )
    return idx, dev_name, selection_reason
def resolve_capture_samplerate(device_index, requested_rate):
    sdev = require_sounddevice()
    try:
        sdev.check_input_settings(device=device_index, samplerate=requested_rate, channels=1, dtype="float32")
        return requested_rate
    except Exception:
        device_info = sdev.query_devices(device_index)
        fallback_rate = int(device_info["default_samplerate"])
        print(f"Requested sample rate {requested_rate} unsupported on this source; using {fallback_rate} Hz.")
        return fallback_rate

def speak_alert(message):
    global _LAST_TTS_MESSAGE, _LAST_TTS_TS
    message = (message or "").strip()
    if not message:
        return
    if len(message) > TTS_MAX_MESSAGE_CHARS:
        message = message[:TTS_MAX_MESSAGE_CHARS].rstrip() + "…"
    now = time.time()
    if message.lower() == _LAST_TTS_MESSAGE.lower() and (now - _LAST_TTS_TS) < TTS_REPEAT_SUPPRESS_SECONDS:
        return
    _LAST_TTS_MESSAGE = message
    _LAST_TTS_TS = now
    if PIPER_MODEL_PATH and os.path.isfile(PIPER_MODEL_PATH) and shutil.which(PIPER_BIN):
        wav_path = None
        try:
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp_wav:
                wav_path = tmp_wav.name
            piper_cmd = [PIPER_BIN, "--model", PIPER_MODEL_PATH, "--output_file", wav_path]
            if PIPER_MODEL_CONFIG_PATH and os.path.isfile(PIPER_MODEL_CONFIG_PATH):
                piper_cmd.extend(["--config", PIPER_MODEL_CONFIG_PATH])
            subprocess.run(piper_cmd, input=message, text=True, check=False)
            if shutil.which("paplay"):
                subprocess.run(["paplay", wav_path], check=False)
                return
            if shutil.which("aplay"):
                subprocess.run(["aplay", wav_path], check=False)
                return
        except Exception:
            pass
        finally:
            if wav_path and os.path.exists(wav_path):
                try:
                    os.remove(wav_path)
                except Exception:
                    pass
    try:
        subprocess.run(["spd-say", message], check=False)
    except Exception:
        pass
def run_command(command):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)
def list_sinks():
    out = run_command(["pactl", "list", "short", "sinks"])
    sinks = []
    for line in out.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            try:
                sink_index = int(parts[0])
            except ValueError:
                continue
            sinks.append({"index": sink_index, "name": parts[1]})
    return sinks
def sink_name_index_maps():
    sinks = list_sinks()
    index_to_name = {s["index"]: s["name"] for s in sinks}
    name_to_index = {s["name"]: s["index"] for s in sinks}
    return index_to_name, name_to_index
def default_sink_name():
    out = run_command(["pactl", "info"])
    for line in out.splitlines():
        if line.lower().startswith("default sink:"):
            return line.split(":", 1)[1].strip()
    raise RuntimeError("Could not determine default sink from pactl info.")
def parse_sink_inputs():
    out = run_command(["pactl", "list", "sink-inputs"])
    sink_inputs = []
    chunks = out.split("Sink Input #")
    for chunk in chunks[1:]:
        lines = chunk.splitlines()
        if not lines:
            continue
        try:
            sink_input_id = int(lines[0].strip())
        except ValueError:
            continue
        sink_index = None
        for line in lines:
            stripped = line.strip()
            if stripped.startswith("Sink:"):
                try:
                    sink_index = int(stripped.split(":", 1)[1].strip())
                except ValueError:
                    sink_index = None
                break
        lower = chunk.lower()
        is_scrcpy = (
            'application.process.binary = "scrcpy"' in lower
            or 'application.name = "scrcpy"' in lower
            or "scrcpy" in lower
        )
        sink_inputs.append({
            "id": sink_input_id,
            "sink_index": sink_index,
            "is_scrcpy": is_scrcpy,
        })
    return sink_inputs
def ensure_binary(name_or_path):
    if "/" in name_or_path:
        if not shutil.which(name_or_path):
            raise RuntimeError(f"Required binary '{name_or_path}' not found or not executable.")
    elif shutil.which(name_or_path) is None:
        raise RuntimeError(f"Required binary '{name_or_path}' not found. Install it first.")
def ensure_scrcpy_audio_support(scrcpy_bin):
    out = subprocess.check_output([scrcpy_bin, "--version"], text=True)
    first = out.splitlines()[0].strip().lower()
    # scrcpy audio forwarding is available in modern releases (2.x+)
    version_token = first.split()[1]
    major = int(version_token.split(".")[0])
    if major < 2:
        raise RuntimeError(
            f"Installed scrcpy version {version_token} does not support audio forwarding. "
            "Install scrcpy 2.x+ or use direct mode."
        )
def adb_connected_devices():
    output = subprocess.check_output(["adb", "devices"], text=True)
    lines = [ln.strip() for ln in output.splitlines()[1:] if ln.strip()]
    devices = []
    for ln in lines:
        if "\tdevice" in ln:
            devices.append(ln.split("\t")[0])
    return devices
def sink_exists(sink_name):
    _, name_to_index = sink_name_index_maps()
    return sink_name in name_to_index
def ensure_null_sink(sink_name):
    if sink_exists(sink_name):
        return
    run_command([
        "pactl", "load-module", "module-null-sink",
        f"sink_name={sink_name}",
        "sink_properties=device.description=ScannerSink"
    ])
def find_scrcpy_sink_input_id():
    scrcpy_inputs = [si["id"] for si in parse_sink_inputs() if si["is_scrcpy"]]
    if scrcpy_inputs:
        return max(scrcpy_inputs)
    return None
def wait_for_scrcpy_sink_input(timeout_seconds=20):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        sink_input_id = find_scrcpy_sink_input_id()
        if sink_input_id is not None:
            return sink_input_id
        time.sleep(0.5)
    return None
def move_sink_input_to_sink(sink_input_id, sink_name):
    run_command(["pactl", "move-sink-input", str(sink_input_id), sink_name])
def enforce_scrcpy_sink_purity(target_sink_name, scrcpy_sink_input_id):
    index_to_name, name_to_index = sink_name_index_maps()
    target_sink_index = name_to_index.get(target_sink_name)
    if target_sink_index is None:
        raise RuntimeError(f"Target sink '{target_sink_name}' is missing.")
    current_default_sink = default_sink_name()
    fallback_sink = current_default_sink
    if fallback_sink == target_sink_name:
        for name in name_to_index.keys():
            if name != target_sink_name:
                fallback_sink = name
                break
    evicted = 0
    for sink_input in parse_sink_inputs():
        if sink_input["sink_index"] != target_sink_index:
            continue
        if sink_input["id"] == scrcpy_sink_input_id:
            continue
        if sink_input["is_scrcpy"]:
            continue
        if fallback_sink != target_sink_name:
            move_sink_input_to_sink(sink_input["id"], fallback_sink)
            evicted += 1
    if evicted > 0:
        print(f"Evicted {evicted} non-scrcpy stream(s) from '{target_sink_name}'.")
    return evicted
def capture_scrcpy_chunk_to_wav(source_name, duration_seconds, output_path, sample_rate):
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "pulse",
        "-i", source_name,
        "-ac", "1",
        "-ar", str(sample_rate),
        "-t", str(duration_seconds),
        output_path
    ], check=True)
BROWSER_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)
BROADCASTIFY_LISTEN_RE = re.compile(r"https?://(?:www\.)?broadcastify\.com/listen/feed/(\d+)")
_broadcastify_sessions = {}
def resolve_broadcastify_stream(stream_url):
    """Resolve a Broadcastify listen-page URL to a playable signed HLS URL.

    Catalog stream_urls point at HTML listen pages. Each page embeds a
    short-lived signed session (hlsUrl + sessionId); the playable stream is
    hlsUrl?s=<sessionId>. Sessions are cached per feed and kept alive with
    beacon pings; call invalidate_broadcastify_session on capture failure so
    the next attempt fetches a fresh session. Non-Broadcastify URLs pass
    through unchanged.
    """
    match = BROADCASTIFY_LISTEN_RE.match(stream_url or "")
    if not match:
        return stream_url
    feed_id = match.group(1)
    cached = _broadcastify_sessions.get(feed_id)
    if cached:
        return cached["url"]
    page = HTTP_SESSION.get(
        f"https://www.broadcastify.com/listen/feed/{feed_id}?_={int(time.time() * 1000)}",
        headers={
            "User-Agent": BROWSER_USER_AGENT,
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
        },
        timeout=10,
    ).text
    hls_match = re.search(r'hlsUrl:\s*"([^"]+)"', page)
    sid_match = re.search(r'sessionId:\s*"([^"]+)"', page)
    if not hls_match or not sid_match:
        raise RuntimeError(f"Could not extract HLS session from Broadcastify feed {feed_id} listen page.")
    hls_url = hls_match.group(1).replace("\\/", "/")
    session_id = sid_match.group(1)
    beacon_match = re.search(r"beaconUrl:\s*'([^']+)'", page)
    _broadcastify_sessions[feed_id] = {
        "url": f"{hls_url}?s={session_id}",
        "session_id": session_id,
        "feed_id": feed_id,
        "beacon_url": beacon_match.group(1) if beacon_match else None,
        "resolved_at": time.time(),
    }
    print(f"[BroadcastifyResolve] feed={feed_id} resolved listen page to signed HLS session.")
    return _broadcastify_sessions[feed_id]["url"]
def invalidate_broadcastify_session(stream_url):
    match = BROADCASTIFY_LISTEN_RE.match(stream_url or "")
    if match:
        _broadcastify_sessions.pop(match.group(1), None)
def broadcastify_beacon_ping(stream_url):
    """Keep the signed HLS session alive (server expects a ping every ~60s)."""
    match = BROADCASTIFY_LISTEN_RE.match(stream_url or "")
    if not match:
        return
    cached = _broadcastify_sessions.get(match.group(1))
    if not cached or not cached.get("beacon_url"):
        return
    try:
        HTTP_SESSION.post(
            cached["beacon_url"],
            json={"feedId": int(cached["feed_id"]), "sessionId": cached["session_id"]},
            headers={"User-Agent": BROWSER_USER_AGENT},
            timeout=5,
        )
    except Exception:
        pass

def fetch_url_with_curl(url, timeout_seconds=10):
    """Fetch URL bytes via curl, failing on HTTP errors."""
    proc = subprocess.run(
        [
            "curl",
            "-sS",
            "-L",
            "-f",
            "--max-time",
            str(timeout_seconds),
            "-A",
            BROWSER_USER_AGENT,
            url,
        ],
        check=True,
        capture_output=True,
    )
    return proc.stdout
_hls_seen_segments = {}
def capture_hls_chunk_to_wav(playlist_url, duration_seconds, output_path, sample_rate):
    """Capture ~duration_seconds of a live HLS stream without ffmpeg networking.

    Broadcastify's edge rejects ffmpeg's TLS fingerprint (403) while browser
    clients (curl) pass, so segments are downloaded with curl and
    concatenated to a local MPEG-TS file that ffmpeg then transcodes to wav.
    Tracks consumed segment URIs per playlist so consecutive chunks continue
    from the live edge instead of re-reading the backlog.
    """
    base = playlist_url.split("?")[0].rsplit("/", 1)[0]
    seen = _hls_seen_segments.setdefault(playlist_url, set())
    if len(_hls_seen_segments) > 32:
        for key in list(_hls_seen_segments):
            if key != playlist_url:
                _hls_seen_segments.pop(key, None)
    ts_path = output_path + ".ts"
    collected = 0.0
    deadline = time.time() + duration_seconds * 2 + 20
    with open(ts_path, "wb") as out:
        while collected < duration_seconds and time.time() < deadline and not SHOULD_EXIT:
            response_text = fetch_url_with_curl(playlist_url, timeout_seconds=10).decode("utf-8", errors="replace")
            segment_entries = []
            entry_duration = None
            for line in response_text.splitlines():
                line = line.strip()
                if line.startswith("#EXTINF:"):
                    try:
                        entry_duration = float(line[len("#EXTINF:"):].split(",")[0])
                    except ValueError:
                        entry_duration = None
                elif line and not line.startswith("#"):
                    segment_entries.append((line, entry_duration if entry_duration else 4.0))
                    entry_duration = None
            got_new_segment = False
            for uri, seg_duration in segment_entries:
                if uri in seen:
                    continue
                seen.add(uri)
                if len(seen) > 512:
                    seen.clear()
                    seen.add(uri)
                seg_url = uri if uri.startswith("http") else f"{base}/{uri}"
                try:
                    seg_bytes = fetch_url_with_curl(seg_url, timeout_seconds=10)
                except subprocess.CalledProcessError:
                    # Live HLS playlists can reference segments that have just
                    # rolled off edge storage; skip stale/unavailable segments
                    # and continue collecting from newer entries.
                    continue
                out.write(seg_bytes)
                collected += seg_duration
                got_new_segment = True
                if collected >= duration_seconds:
                    break
            if not got_new_segment and collected < duration_seconds:
                time.sleep(1.0)
    if collected <= 0.0:
        try:
            os.remove(ts_path)
        except OSError:
            pass
        raise RuntimeError(f"No HLS segments collected from {playlist_url.split('?')[0]}")
    try:
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-i", ts_path,
            "-vn",
            "-ac", "1",
            "-ar", str(sample_rate),
            output_path
        ], check=True)
    finally:
        try:
            os.remove(ts_path)
        except OSError:
            pass
def capture_stream_chunk_to_wav(stream_url, duration_seconds, output_path, sample_rate):
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-rw_timeout", "15000000",
    ]
    if stream_url.startswith("http://") or stream_url.startswith("https://"):
        cmd.extend([
            "-user_agent", BROWSER_USER_AGENT,
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "5",
        ])
    cmd.extend([
        "-i", stream_url,
        "-vn",
        "-ac", "1",
        "-ar", str(sample_rate),
        "-t", str(duration_seconds),
        output_path
    ])
    subprocess.run(cmd, check=True)
def resolve_broadcast_streams(args):
    if args.stream_url:
        return [{"id": "manual_stream", "name": "manual_stream", "stream_url": args.stream_url}], None
    if not args.channels_file:
        raise RuntimeError("Broadcastify mode requires --stream-url or --channels-file for selector-based auto-selection.")
    desired_types = [token.strip() for token in args.selector_desired_types.split(",") if token.strip()]
    ctx = SelectorContext(
        lat=args.selector_lat,
        lon=args.selector_lon,
        city=args.selector_city,
        county=args.selector_county,
        state=args.selector_state,
        desired_types=desired_types,
    )
    channels = load_channels(args.channels_file, ctx=ctx)
    rerank_model = args.selector_ollama_model
    if llm_set_client is not None:
        # Prefer the scout-rank model when built; fall back to base llama3.1.
        rerank_model, rank_fallback = llm_set_client.resolve_model(rerank_model)
        if rank_fallback:
            print(f"Selector rerank model '{args.selector_ollama_model}' not installed; using '{rerank_model}'.")
    ranked, rerank_error = select_channels(
        channels=channels,
        ctx=ctx,
        top_k=args.selector_top_k,
        use_ollama_rerank=args.selector_use_ollama_rerank,
        ollama_model=rerank_model,
        ollama_url=args.selector_ollama_url,
        ollama_timeout=args.selector_ollama_timeout,
        ollama_weight=args.selector_ollama_weight,
    )
    if not ranked:
        raise RuntimeError("Channel selector did not return any ranked candidates.")
    candidates = []
    for item in ranked:
        channel = dict(item["channel"])
        stream_url = channel.get("stream_url")
        if not stream_url:
            continue
        channel["_rank_score"] = item.get("score")
        candidates.append(channel)
    if not candidates:
        raise RuntimeError("Channel selector returned no streamable channels (missing stream_url).")
    return candidates, rerank_error
def fetch_device_gps(url, timeout_seconds=3.0):
    """Fetch the latest streaming-device GPS fix from the Java backend.

    Returns {lat, lon, source, user_id, ts} when a device fix is available,
    otherwise None. The backend only reports fixes posted by streaming
    clients (e.g. the Android app via /api/gps/update), so this never
    silently falls back to server-side coordinates.
    """
    try:
        response = HTTP_SESSION.get(url, timeout=timeout_seconds)
        payload = response.json()
    except Exception:
        return None
    if not isinstance(payload, dict) or payload.get("status") != "ok":
        return None
    point = payload.get("point")
    if not isinstance(point, dict):
        return None
    try:
        lat = float(point["lat"])
        lon = float(point["lon"])
    except (KeyError, TypeError, ValueError):
        return None
    return {
        "lat": lat,
        "lon": lon,
        "source": str(point.get("source", "")),
        "user_id": str(point.get("user_id", "")),
        "ts": str(point.get("ts", "")),
    }
def wait_for_device_gps(url, wait_seconds, poll_seconds=2.0):
    deadline = time.time() + max(0.0, wait_seconds)
    while True:
        fix = fetch_device_gps(url)
        if fix is not None:
            return fix
        if SHOULD_EXIT or time.time() >= deadline:
            return None
        time.sleep(poll_seconds)
class TeeStream:
    def __init__(self, *streams):
        self.streams = streams
    def write(self, data):
        for s in self.streams:
            s.write(data)
            s.flush()
        return len(data)
    def flush(self):
        for s in self.streams:
            s.flush()
    def isatty(self):
        return any(getattr(s, "isatty", lambda: False)() for s in self.streams)
    def fileno(self):
        for s in self.streams:
            if hasattr(s, "fileno"):
                try:
                    return s.fileno()
                except Exception:
                    continue
        raise OSError("No fileno available")
def start_scrcpy(scrcpy_bin, serial=None):
    ensure_binary(scrcpy_bin)
    ensure_binary("adb")
    ensure_scrcpy_audio_support(scrcpy_bin)
    devices = adb_connected_devices()
    if serial:
        if serial not in devices:
            raise RuntimeError(f"ADB device '{serial}' not connected/authorized. Connected: {devices}")
    elif not devices:
        raise RuntimeError("No authorized ADB devices connected.")
    cmd = [scrcpy_bin, "--no-video", "--require-audio", "--audio-source=output", "--no-control"]
    if serial:
        cmd.extend(["-s", serial])
    print(f"Starting scrcpy: {' '.join(cmd)}")
    return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

parser = argparse.ArgumentParser(description="Live police scanner -> Whisper -> LLM alert pipeline")
parser.add_argument(
    "--mode",
    choices=["broadcastify", "direct", "scrcpy", "audiorelay", "voicemeeter"],
    default="broadcastify",
    help="Audio capture mode. Scanner base defaults to broadcastify; other routes are optional.",
)
parser.add_argument(
    "--enable-optional-audio-routes",
    action=argparse.BooleanOptionalAction,
    default=False,
    help="Enable optional non-broadcast routes (direct, scrcpy, audiorelay, voicemeeter).",
)
parser.add_argument("--device", type=int, default=None, help="Input device index (use --list-devices to inspect)")
parser.add_argument("--source-node", type=str, default=None, help="Input source name substring (used when --device is not set)")
parser.add_argument("--duration", type=float, default=DURATION, help="Capture duration per chunk in seconds")
parser.add_argument("--list-devices", action="store_true", help="List audio devices and exit")
parser.add_argument("--start-scrcpy", action="store_true", help="Auto-launch scrcpy before capturing (scrcpy mode only)")
parser.add_argument("--serial", type=str, default=None, help="ADB device serial (optional)")
parser.add_argument("--scrcpy-bin", type=str, default="scrcpy", help="scrcpy executable path/name for scrcpy mode")
parser.add_argument("--scrcpy-sink", type=str, default="scanner_sink", help="PulseAudio sink name used to isolate scrcpy audio")
parser.add_argument("--stream-url", type=str, default=None, help="Direct broadcast stream URL (broadcastify mode)")
parser.add_argument("--channels-file", type=str, default=None, help="Channel catalog JSON path for broadcastify auto-selection")
parser.add_argument("--selector-city", type=str, default="", help="Jurisdiction city for channel selector scoring")
parser.add_argument("--selector-county", type=str, default="", help="Jurisdiction county for channel selector scoring")
parser.add_argument("--selector-state", type=str, default="", help="Jurisdiction state for channel selector scoring")
parser.add_argument("--selector-lat", type=float, default=None, help="Latitude for distance-aware selector scoring")
parser.add_argument("--selector-lon", type=float, default=None, help="Longitude for distance-aware selector scoring")
parser.add_argument("--selector-desired-types", type=str, default="law,dispatch", help="Comma-separated desired channel type tokens")
parser.add_argument("--selector-top-k", type=int, default=8, help="Top deterministic candidates to consider before reranking")
parser.add_argument("--selector-use-ollama-rerank", action=argparse.BooleanOptionalAction, default=True, help="Enable optional Ollama rerank for selector")
parser.add_argument("--selector-ollama-model", type=str, default="scout-rank", help="Ollama model for selector reranking (scout-rank from the proprietary LLM set; falls back to llama3.1 when not built)")
parser.add_argument("--selector-ollama-url", type=str, default="http://localhost:11434/api/generate", help="Ollama endpoint for selector reranking")
parser.add_argument("--selector-ollama-timeout", type=float, default=8.0, help="Ollama timeout in seconds for selector reranking")
parser.add_argument("--selector-ollama-weight", type=float, default=0.2, help="Blend weight [0..1] for Ollama rerank influence")
parser.add_argument("--use-device-gps", action=argparse.BooleanOptionalAction, default=True, help="Route selector coordinates from the streaming device GPS (backend /api/gps/latest) instead of static server-side values")
parser.add_argument("--gps-latest-url", type=str, default=f"http://127.0.0.1:{os.environ.get('JAVA_BACKEND_PORT', os.environ.get('BACKEND_PORT', '18080'))}/api/gps/latest", help="Backend endpoint reporting the latest streaming-device GPS fix")
parser.add_argument("--gps-startup-wait", type=float, default=20.0, help="Seconds to wait for a device GPS fix at startup before falling back to configured selector coordinates")
parser.add_argument("--gps-refresh-seconds", type=float, default=45.0, help="How often to re-poll the device GPS while scanning")
parser.add_argument("--gps-reselect-km", type=float, default=30.0, help="Re-run channel selection when the device moves at least this many km from the coordinates used for the current selection")
parser.add_argument("--stream-switch-delay", type=float, default=1.5, help="Seconds to wait before switching streams after a capture failure (first failover cycle)")
parser.add_argument("--stream-backoff-base", type=float, default=5.0, help="Base backoff seconds once every candidate has failed a full cycle")
parser.add_argument("--stream-backoff-max", type=float, default=60.0, help="Maximum backoff seconds between capture retries")
parser.add_argument("--log-file", type=str, default="/tmp/pipeline_runtime.log", help="Path to append pipeline logs")
parser.add_argument("--alert-debug", action=argparse.BooleanOptionalAction, default=True, help="Emit detailed [ALERT_DEBUG] lines for cue matching and Ollama decisions")
parser.add_argument("--ollama-timeout", type=float, default=8.0, help="Ollama request timeout in seconds")
parser.add_argument("--ollama-retries", type=int, default=1, help="Retry attempts after first Ollama failure")
parser.add_argument("--llm-set", action=argparse.BooleanOptionalAction, default=True, help="Use the proprietary scout LLM set (scout-core1.0.7/scout-vet1.0.8/scout-rank); falls back to base llama3.1 prompts when models are not built")
parser.add_argument("--llm-alert-model", type=str, default="scout-vet1.0.8", help="Scout model for scanner alert decision")
parser.add_argument("--llm-intel-model", type=str, default="scout-vet1.0.8", help="Scout model for scanner intel extraction")
parser.add_argument("--llm-nav-model", type=str, default="scout-core1.0.7", help="Scout model for spoken navigation guidance")
parser.add_argument("--llm-intel", action=argparse.BooleanOptionalAction, default=True, help="Run structured intel extraction on alert-worthy transcripts")
parser.add_argument("--llm-intel-timeout", type=float, default=10.0, help="Intel extraction request timeout in seconds")
parser.add_argument("--llm-alert-vet", action=argparse.BooleanOptionalAction, default=True, help="Run second-stage lightweight vet model before emitting alert_triggered events")
parser.add_argument("--llm-alert-vet-model", type=str, default="scout-vet1.0.8", help="Scout vet model for alert gating")
parser.add_argument("--llm-alert-vet-timeout", type=float, default=6.0, help="Vet model request timeout in seconds")
parser.add_argument("--llm-alert-vet-retries", type=int, default=0, help="Retry attempts after first vet model failure")
parser.add_argument("--llm-vet-cpu", action=argparse.BooleanOptionalAction, default=True, help="Run vet-stage models with CPU-first Ollama options")
parser.add_argument("--llm-vet-num-thread", type=int, default=max(1, os.cpu_count() or 4), help="Thread count for vet-stage CPU execution")
parser.add_argument("--llm-vet-num-gpu", type=int, default=0, help="GPU layers for vet-stage execution (0 keeps vet on CPU)")
parser.add_argument("--llm-core-num-thread", type=int, default=-1, help="Optional core-stage thread count override; -1 leaves Ollama default")
parser.add_argument("--llm-core-num-gpu", type=int, default=-1, help="Optional core-stage GPU layer override; -1 leaves Ollama default")
parser.add_argument("--rms-threshold", type=float, default=0.01, help="Skip chunk when RMS is below this value")
parser.add_argument("--clip-threshold", type=float, default=0.15, help="Skip chunk when clipped sample ratio exceeds this value")
parser.add_argument("--soft-alert-fallback", action=argparse.BooleanOptionalAction, default=True, help="Emit SOFT_ALERT when dispatch cues meet threshold but LLM does not emit ALERT")
parser.add_argument("--integration-json", action=argparse.BooleanOptionalAction, default=True, help="Emit structured [EVENT_JSON] lines for Java/HTML integrations")
parser.add_argument("--rule-score-threshold", type=int, default=3, help="Weighted dispatch score threshold that marks a transcript as rule-expected alert")
parser.add_argument("--hard-rule-score-threshold", type=int, default=4, help="Weighted dispatch score threshold for hard rule-based alert promotion")
parser.add_argument("--loop-heartbeat", action=argparse.BooleanOptionalAction, default=False, help="Emit per-loop heartbeat diagnostics during capture/transcription runtime")
parser.add_argument("--loop-heartbeat-every", type=int, default=1, help="Emit loop heartbeat every N loop iterations when --loop-heartbeat is enabled")
parser.add_argument("--payment-token", type=str, default=None, help="One-time payment receipt token to redeem before running (sets the lifetime license)")
parser.add_argument("--payment-file", type=str, default=None, help="Override the payment ledger path (default: env PAYMENT_LEDGER_PATH or ./frontend/.payment_ledger.json)")
parser.add_argument("--payment-status", action="store_true", help="Print payment/license status and exit")
parser.add_argument("--payment-enforce", action=argparse.BooleanOptionalAction, default=True, help="Fail fast at startup when the one-time license is missing/unredeemed")
parser.add_argument("--payment-grant-dev", action="store_true", help="Grant a dev license (one-time, for local development/testing)")
args = parser.parse_args()
signal.signal(signal.SIGTERM, request_shutdown)
signal.signal(signal.SIGINT, request_shutdown)
for _sig in (signal.SIGTSTP, signal.SIGTTIN, signal.SIGTTOU):
    try:
        signal.signal(_sig, signal.SIG_IGN)
    except Exception:
        pass
log_handle = open(args.log_file, "a", buffering=1)
sys.stdout = TeeStream(sys.stdout, log_handle)
sys.stderr = TeeStream(sys.stderr, log_handle)
print(f"Logging pipeline output to: {args.log_file}")
ensure_optional_route_enabled(args.mode, args.enable_optional_audio_routes)

# ---- One-time payment gate ----
if payment_gate is not None:
    try:
        pay_gate = payment_gate.OneTimePayment(
            ledger_path=args.payment_file or None,
            signing_key=os.environ.get(payment_gate.PAYMENT_SIGNING_KEY_ENV) or None,
        )
    except payment_gate.PaymentError as exc:
        print(f"Payment gate init failed: {exc}")
        if args.payment_enforce:
            raise SystemExit(2)
        pay_gate = None
else:
    pay_gate = None

if pay_gate is not None:
    if args.payment_status:
        print("[PAYMENT] " + json.dumps(pay_gate.status()))
        raise SystemExit(0)
    if args.payment_token:
        try:
            status = pay_gate.redeem(args.payment_token)
            print("[PAYMENT] license redeemed: " + json.dumps(status))
        except payment_gate.PaymentError as exc:
            print(f"[PAYMENT] redemption rejected: {exc}")
            if args.payment_enforce:
                raise SystemExit(2)
    if args.payment_grant_dev:
        status = pay_gate.grant_dev_license()
        print("[PAYMENT] dev license granted: " + json.dumps(status))
    licensed = pay_gate.is_licensed()
    payment_state = pay_gate.status()
    emit_event_json(
        "payment_status",
        enabled=args.integration_json,
        licensed=licensed,
        product=payment_state.get("product"),
        one_time_enforced=payment_state.get("one_time_enforced"),
        source=payment_state.get("license", {}).get("source") if payment_state.get("license") else None,
    )
    if not licensed and args.payment_enforce:
        print(
            "[PAYMENT] one-time license required for "
            f"{payment_state.get('product')}; run with --payment-token <receipt> "
            "or --payment-grant-dev for local development."
        )
        raise SystemExit(2)
    if licensed:
        print("[PAYMENT] one-time license active: " + json.dumps(payment_state.get("license")))
    else:
        print("[PAYMENT] unlicensed run in grace mode (no enforcement).")
elif args.payment_enforce:
    print("[PAYMENT] payment module unavailable but enforcement requested; enabling grace mode.")
# ---- / One-time payment gate ----

if args.list_devices:
    print(require_sounddevice().query_devices())
    raise SystemExit(0)
scrcpy_proc = None
source_node = args.source_node
input_device = None
input_device_name = None
input_selection_reason = None
stream_url = None
selected_channel = None
broadcast_candidates = []
broadcast_candidate_idx = 0
broadcast_failures = 0
gps_source = "config"
selection_gps = None
last_gps_poll = 0.0
if args.mode == "scrcpy":
    ensure_binary("ffmpeg")
    ensure_binary("pactl")
    if args.start_scrcpy:
        scrcpy_proc = start_scrcpy(args.scrcpy_bin, args.serial)
        time.sleep(3)
    ensure_null_sink(args.scrcpy_sink)
    sink_input_id = wait_for_scrcpy_sink_input(timeout_seconds=20)
    if sink_input_id is None:
        raise RuntimeError("Could not detect scrcpy audio stream in PulseAudio sink-inputs.")
    move_sink_input_to_sink(sink_input_id, args.scrcpy_sink)
    enforce_scrcpy_sink_purity(args.scrcpy_sink, sink_input_id)
    source_node = f"{args.scrcpy_sink}.monitor"
    capture_fs = FS
    print(f"Routed scrcpy sink-input #{sink_input_id} to isolated sink '{args.scrcpy_sink}'.")
elif args.mode == "broadcastify":
    ensure_binary("ffmpeg")
    if args.stream_url:
        gps_source = "manual_stream_url"
    elif args.use_device_gps:
        print(f"Waiting up to {args.gps_startup_wait:.0f}s for streaming-device GPS fix from {args.gps_latest_url} ...")
        device_fix = wait_for_device_gps(args.gps_latest_url, args.gps_startup_wait)
        if device_fix is not None:
            args.selector_lat = device_fix["lat"]
            args.selector_lon = device_fix["lon"]
            # Static city/county describe the server-configured home location;
            # drop them so ranking follows the device position, and clear
            # static state so cross-shard catalogs can be considered.
            args.selector_city = ""
            args.selector_county = ""
            args.selector_state = ""
            selection_gps = (device_fix["lat"], device_fix["lon"])
            gps_source = "device"
            print(
                "Using streaming-device GPS for channel selection: "
                f"lat={device_fix['lat']:.5f} lon={device_fix['lon']:.5f} "
                f"(source={device_fix['source']} user={device_fix['user_id']})"
            )
        else:
            gps_source = "config_fallback"
            print(
                "No streaming-device GPS fix available yet; starting with configured selector coordinates "
                f"lat={args.selector_lat} lon={args.selector_lon} (will switch to device GPS once it reports)."
            )
    broadcast_candidates, selector_rerank_error = resolve_broadcast_streams(args)
    broadcast_candidate_idx = 0
    selected_channel = broadcast_candidates[broadcast_candidate_idx]
    stream_url = selected_channel.get("stream_url")
    capture_fs = FS
    print(
        f"Selected broadcast stream: id={selected_channel.get('id')} "
        f"name={selected_channel.get('name')} url={stream_url}"
    )
    if len(broadcast_candidates) > 1:
        print(f"Broadcast fallback enabled across {len(broadcast_candidates)} ranked channels.")
    if selector_rerank_error:
        print(f"Selector rerank fallback to deterministic score due to: {selector_rerank_error}")
elif args.mode == "direct":
    strict_source_match = False
    input_device, input_device_name, input_selection_reason = resolve_input_device(
        args.device,
        source_node,
        strict_source_match=strict_source_match
    )
    capture_fs = resolve_capture_samplerate(input_device, FS)
else:
    raise RuntimeError(
        f"Mode '{args.mode}' is optional and not wired in the scanner base runtime build."
    )
chunk_duration = args.duration
print("Loading Whisper model...")
whisper_engine = WhisperModel("medium", device="cpu", compute_type="int8")
print("Whisper forced to CPU (int8).")

print("\n--- Pipeline Fully Loaded and Active ---")
print(f"Listening in mode: {args.mode}")
if args.mode == "scrcpy":
    print(f"Input source node: {source_node}")
elif args.mode == "broadcastify":
    print(f"Broadcast stream URL: {stream_url}")
    if selected_channel:
        print(
            "Broadcast channel context: "
            f"id={selected_channel.get('id')} "
            f"name={selected_channel.get('name')} "
            f"jurisdiction={selected_channel.get('city','')}/{selected_channel.get('county','')}/{selected_channel.get('state','')}"
        )
else:
    print(f"Input device index: {input_device}")
    print(f"Input source node: {input_device_name}")
    print(f"Input selection strategy: {input_selection_reason}")
print(f"Capture sample rate: {capture_fs}")
llm_set_info = None
use_llm_set = bool(args.llm_set and llm_set_client is not None)
vet_runtime_options = None
core_runtime_options = None
if use_llm_set:
    llm_set_info = llm_set_client.llm_set_status(force_refresh=True)
    vet_runtime_options, core_runtime_options = resolve_vet_core_runtime_options(args)
    print(
        "LLM set (scout): "
        f"ollama_up={llm_set_info['ollama_up']} "
        f"complete={llm_set_info['complete']} "
        f"models={llm_set_info['models']}"
    )
    print(
        "Vet/Core runtime options: "
        f"vet={vet_runtime_options} "
        f"core={core_runtime_options}"
    )
elif args.llm_set:
    print("LLM set requested but llm_set_client module unavailable; using legacy inline prompt.")
emit_event_json(
    "pipeline_ready",
    enabled=args.integration_json,
    mode=args.mode,
    source_node=source_node if args.mode == "scrcpy" else (stream_url if args.mode == "broadcastify" else input_device_name),
    input_selection_reason=(
        "broadcast_stream_selector"
        if args.mode == "broadcastify"
        else ("scrcpy_sink_monitor" if args.mode == "scrcpy" else input_selection_reason)
    ),
    sample_rate=capture_fs,
    soft_alert_fallback=args.soft_alert_fallback,
    channel_id=selected_channel.get("id") if selected_channel else None,
    channel_name=selected_channel.get("name") if selected_channel else None,
    gps_source=gps_source if args.mode == "broadcastify" else None,
    selector_lat=args.selector_lat,
    selector_lon=args.selector_lon,
    llm_set=llm_set_info,
    llm_runtime={
        "vet_cpu": bool(args.llm_vet_cpu),
        "vet_options": vet_runtime_options,
        "core_options": core_runtime_options,
    },
)
run_stats = {
    "captured": 0,
    "skipped_silence": 0,
    "skipped_clipped": 0,
    "llm_alert": 0,
    "soft_alert_fallback": 0,
    "vetted_blocked": 0,
}
run_started_at = time.time()
loop_counter = 0
try:
    while not SHOULD_EXIT:
        try:
            loop_counter += 1
            if args.loop_heartbeat and loop_counter % max(1, args.loop_heartbeat_every) == 0:
                print(
                    "[LOOP_HEARTBEAT] "
                    f"loop={loop_counter} "
                    f"mode={args.mode} "
                    f"captured={run_stats['captured']} "
                    f"skipped_silence={run_stats['skipped_silence']} "
                    f"skipped_clipped={run_stats['skipped_clipped']} "
                    f"llm_alert={run_stats['llm_alert']} "
                    f"soft_alert_fallback={run_stats['soft_alert_fallback']}"
                )
                emit_event_json(
                    "loop_heartbeat",
                    enabled=args.integration_json,
                    loop=loop_counter,
                    mode=args.mode,
                    uptime_seconds=round(time.time() - run_started_at, 3),
                    captured=run_stats["captured"],
                    skipped_silence=run_stats["skipped_silence"],
                    skipped_clipped=run_stats["skipped_clipped"],
                    llm_alert=run_stats["llm_alert"],
                    soft_alert_fallback=run_stats["soft_alert_fallback"],
                )
            if args.mode == "scrcpy":
                enforce_scrcpy_sink_purity(args.scrcpy_sink, sink_input_id)
                capture_scrcpy_chunk_to_wav(source_node, chunk_duration, "buffer_chunk.wav", capture_fs)
                chunk_fs, chunk_data = wav.read("buffer_chunk.wav")
                if chunk_data.ndim == 2:
                    chunk_data = chunk_data[:, 0]
                mono = chunk_data.astype(np.float32)
                if chunk_data.dtype == np.int16:
                    mono = mono / 32768.0
                elif chunk_data.dtype == np.int32:
                    mono = mono / 2147483648.0
                capture_fs = chunk_fs
            elif args.mode == "broadcastify":
                if (
                    args.use_device_gps
                    and not args.stream_url
                    and time.time() - last_gps_poll >= args.gps_refresh_seconds
                ):
                    last_gps_poll = time.time()
                    device_fix = fetch_device_gps(args.gps_latest_url)
                    if device_fix is not None:
                        moved_km = (
                            haversine_km(selection_gps[0], selection_gps[1], device_fix["lat"], device_fix["lon"])
                            if selection_gps is not None
                            else None
                        )
                        if selection_gps is None or moved_km >= args.gps_reselect_km:
                            reselect_reason = "gps_acquired" if selection_gps is None else "gps_moved"
                            args.selector_lat = device_fix["lat"]
                            args.selector_lon = device_fix["lon"]
                            args.selector_city = ""
                            args.selector_county = ""
                            args.selector_state = ""
                            new_candidates = None
                            try:
                                new_candidates, _ = resolve_broadcast_streams(args)
                            except Exception as reselect_err:
                                print(f"[DeviceGpsReselect] selection failed ({reselect_err}); keeping current channel.")
                            if new_candidates:
                                previous_channel = selected_channel
                                broadcast_candidates = new_candidates
                                broadcast_candidate_idx = 0
                                broadcast_failures = 0
                                selected_channel = broadcast_candidates[0]
                                stream_url = selected_channel.get("stream_url")
                                selection_gps = (device_fix["lat"], device_fix["lon"])
                                gps_source = "device"
                                print(
                                    "[DeviceGpsReselect] "
                                    f"reason={reselect_reason} "
                                    f"moved_km={None if moved_km is None else round(moved_km, 2)} "
                                    f"lat={device_fix['lat']:.5f} lon={device_fix['lon']:.5f} "
                                    f"from={previous_channel.get('id') if previous_channel else None} "
                                    f"-> to={selected_channel.get('id')}"
                                )
                                emit_event_json(
                                    "broadcast_reselect",
                                    enabled=args.integration_json,
                                    reason=reselect_reason,
                                    gps_source="device",
                                    lat=device_fix["lat"],
                                    lon=device_fix["lon"],
                                    moved_km=None if moved_km is None else round(moved_km, 3),
                                    from_channel_id=previous_channel.get("id") if previous_channel else None,
                                    to_channel_id=selected_channel.get("id"),
                                    to_channel_name=selected_channel.get("name"),
                                )
                try:
                    capture_url = resolve_broadcastify_stream(stream_url)
                    if ".m3u8" in capture_url.split("?")[0]:
                        capture_hls_chunk_to_wav(capture_url, chunk_duration, "buffer_chunk.wav", capture_fs)
                    else:
                        capture_stream_chunk_to_wav(capture_url, chunk_duration, "buffer_chunk.wav", capture_fs)
                except (subprocess.CalledProcessError, requests.RequestException, RuntimeError) as stream_err:
                    # Signed sessions are short-lived; force a fresh resolve on
                    # the next attempt for this feed.
                    invalidate_broadcastify_session(stream_url)
                    stream_err_code = getattr(stream_err, "returncode", None)
                    stream_err_detail = str(stream_err)
                    broadcast_failures += 1
                    candidate_count = max(1, len(broadcast_candidates))
                    failed_cycles = broadcast_failures // candidate_count
                    if failed_cycles <= 0:
                        retry_delay = args.stream_switch_delay
                    else:
                        retry_delay = min(
                            args.stream_backoff_max,
                            args.stream_backoff_base * (2 ** min(failed_cycles - 1, 6)),
                        )
                    if len(broadcast_candidates) > 1:
                        from_channel = selected_channel
                        broadcast_candidate_idx = (broadcast_candidate_idx + 1) % len(broadcast_candidates)
                        selected_channel = broadcast_candidates[broadcast_candidate_idx]
                        stream_url = selected_channel.get("stream_url")
                        print(
                            "[BroadcastifyFallback] "
                            f"capture_failed_exit={stream_err_code} "
                            f"error={type(stream_err).__name__} "
                            f"detail={stream_err_detail} "
                            f"consecutive_failures={broadcast_failures} "
                            f"retry_delay={retry_delay:.1f}s "
                            f"from={from_channel.get('id')} -> to={selected_channel.get('id')} "
                            f"url={stream_url}"
                        )
                        emit_event_json(
                            "broadcast_channel_switch",
                            enabled=args.integration_json,
                            reason="capture_failed",
                            from_channel_id=from_channel.get("id"),
                            from_channel_name=from_channel.get("name"),
                            to_channel_id=selected_channel.get("id"),
                            to_channel_name=selected_channel.get("name"),
                            ffmpeg_exit_code=stream_err_code,
                            error_kind=type(stream_err).__name__,
                            error_detail=stream_err_detail,
                            consecutive_failures=broadcast_failures,
                            retry_delay_seconds=round(retry_delay, 3),
                        )
                        time.sleep(retry_delay)
                        continue
                    print(
                        "[BroadcastifyFallback] "
                        f"capture_failed_exit={stream_err_code} "
                        f"error={type(stream_err).__name__} "
                        f"detail={stream_err_detail} "
                        f"consecutive_failures={broadcast_failures} "
                        f"retry_delay={retry_delay:.1f}s (single stream; retrying)"
                    )
                    emit_event_json(
                        "broadcast_capture_retry",
                        enabled=args.integration_json,
                        reason="capture_failed",
                        channel_id=selected_channel.get("id") if selected_channel else None,
                        channel_name=selected_channel.get("name") if selected_channel else None,
                        ffmpeg_exit_code=stream_err_code,
                        error_kind=type(stream_err).__name__,
                        error_detail=stream_err_detail,
                        consecutive_failures=broadcast_failures,
                        retry_delay_seconds=round(retry_delay, 3),
                    )
                    time.sleep(retry_delay)
                    continue
                broadcastify_beacon_ping(stream_url)
                if broadcast_failures:
                    print(f"[BroadcastifyRecovered] stream capture succeeded after {broadcast_failures} consecutive failures.")
                    emit_event_json(
                        "broadcast_stream_recovered",
                        enabled=args.integration_json,
                        channel_id=selected_channel.get("id") if selected_channel else None,
                        channel_name=selected_channel.get("name") if selected_channel else None,
                        consecutive_failures=broadcast_failures,
                    )
                    broadcast_failures = 0
                chunk_fs, chunk_data = wav.read("buffer_chunk.wav")
                if chunk_data.ndim == 2:
                    chunk_data = chunk_data[:, 0]
                mono = chunk_data.astype(np.float32)
                if chunk_data.dtype == np.int16:
                    mono = mono / 32768.0
                elif chunk_data.dtype == np.int32:
                    mono = mono / 2147483648.0
                capture_fs = chunk_fs
            else:
                # 2. Record raw scanner snippet from direct audio capture
                sdev = require_sounddevice()
                audio_buffer = sdev.rec(
                    int(chunk_duration * capture_fs),
                    samplerate=capture_fs,
                    channels=1,
                    dtype='float32',
                    device=input_device
                )
                sdev.wait()
                wav.write("buffer_chunk.wav", capture_fs, audio_buffer)
                mono = audio_buffer[:, 0]
            rms = float(np.sqrt(np.mean(mono ** 2)))
            clip_ratio = float(np.mean(np.abs(mono) > 0.98))
            audio_levels = compute_audio_levels(mono, capture_fs)
            if rms < args.rms_threshold:
                run_stats["skipped_silence"] += 1
                if args.alert_debug:
                    print(f"[Skipped]: near-silence chunk (rms={rms:.6f}, threshold={args.rms_threshold})")
                else:
                    print("[Skipped]: near-silence chunk")
                emit_event_json(
                    "chunk_skipped_silence",
                    enabled=args.integration_json,
                    rms=rms,
                    threshold=args.rms_threshold,
                )
                continue
            if clip_ratio > args.clip_threshold:
                run_stats["skipped_clipped"] += 1
                print(f"[Skipped]: heavily clipped chunk (clip_ratio={clip_ratio:.4f}, threshold={args.clip_threshold})")
                emit_event_json(
                    "chunk_skipped_clipped",
                    enabled=args.integration_json,
                    clip_ratio=clip_ratio,
                    threshold=args.clip_threshold,
                )
                continue
            
            # 3. Process transcription on CPU
            segments, _ = whisper_engine.transcribe(
                "buffer_chunk.wav",
                beam_size=5,
                vad_filter=True,
                condition_on_previous_text=False,
                no_speech_threshold=0.6,
                log_prob_threshold=-1.0
            )
            raw_text = " ".join([seg.text for seg in segments]).strip()
            
            if raw_text:
                run_stats["captured"] += 1
                print(f"[Captured Chatter]: {raw_text}")
                classification = classify_transcript(raw_text)
                location_mentions = extract_location_mentions(raw_text)
                poi_mentions = extract_poi_mentions(raw_text)
                if location_mentions or poi_mentions:
                    print(f"[Location Notes]: locations={location_mentions} pois={poi_mentions}")
                print(
                    "[Classification]: "
                    f"types={classification['call_types']} "
                    f"priority={classification['priority']} "
                    f"codes={classification['codes']} "
                    f"confidence={classification['confidence']}"
                )
                emit_event_json(
                    "chunk_captured",
                    enabled=args.integration_json,
                    transcript=raw_text,
                    classification=classification,
                    location_mentions=location_mentions,
                    poi_mentions=poi_mentions,
                    rms=rms,
                    clip_ratio=clip_ratio,
                    audio_levels=audio_levels,
                    audio_level_window_ms=AUDIO_LEVEL_WINDOW_MS,
                )
                cue_map, cue_count = extract_dispatch_cues(raw_text)
                dispatch_score = score_dispatch_cues(cue_map)
                strong_enforcement = has_strong_enforcement_signal(cue_map)
                has_location = bool(cue_map.get("location_markers"))
                
                # 4. Offload text to the proprietary scout LLM set (Ollama-backed)
                if use_llm_set:
                    llm_result = llm_set_client.query_alert(
                        raw_text,
                        timeout_seconds=args.ollama_timeout,
                        retries=args.ollama_retries,
                        model=args.llm_alert_model,
                        options=vet_runtime_options,
                    )
                else:
                    llm_result = query_llm(raw_text, timeout_seconds=args.ollama_timeout, retries=args.ollama_retries)
                ai_response = normalize_llm_response_text(llm_result["response"])
                llm_alert = is_alert_response(ai_response)
                rule_expected_alert = dispatch_score >= args.rule_score_threshold
                hard_rule_alert = (
                    dispatch_score >= args.hard_rule_score_threshold
                    and strong_enforcement
                    and (has_location or classification["call_types"] != ["unclassified"] or classification["codes"])
                )
                fallback_soft_alert = (
                    args.soft_alert_fallback
                    and rule_expected_alert
                    and not hard_rule_alert
                    and (not llm_alert)
                    and (strong_enforcement or has_location)
                )
                candidate_alert = llm_alert or hard_rule_alert or fallback_soft_alert
                proposed_alert_message = ai_response
                if not llm_alert and hard_rule_alert:
                    proposed_alert_message = (
                        f"ALERT: probable enforcement activity (rule score={dispatch_score}, "
                        f"strong_enforcement={strong_enforcement}, location={has_location})."
                    )
                elif not llm_alert and fallback_soft_alert:
                    proposed_alert_message = (
                        f"SOFT_ALERT: dispatch-style cues met threshold (cue_count={cue_count}, score={dispatch_score}) "
                        f"but LLM returned IGNORE."
                    )
                vet_result = {
                    "decision": "VET_PASS",
                    "model": None,
                    "status_code": None,
                    "error": None,
                    "attempts": 0,
                    "used_fallback": False,
                }
                alert_vetted = True
                if (
                    candidate_alert
                    and args.llm_alert_vet
                    and use_llm_set
                    and llm_set_client is not None
                ):
                    vet_result = llm_set_client.query_alert_vet(
                        raw_text,
                        proposed_alert_text=proposed_alert_message,
                        timeout_seconds=args.llm_alert_vet_timeout,
                        retries=args.llm_alert_vet_retries,
                        model=args.llm_alert_vet_model,
                        options=vet_runtime_options,
                    )
                    alert_vetted = vet_result.get("decision") == "VET_PASS"
                nav_voice_guidance = None
                if candidate_alert and alert_vetted and use_llm_set and llm_set_client is not None:
                    try:
                        nav_result = llm_set_client.query_nav(
                            raw_text,
                            timeout_seconds=args.ollama_timeout,
                            retries=args.ollama_retries,
                            model=args.llm_nav_model,
                            options=core_runtime_options,
                        )
                        nav_voice_guidance = normalize_llm_response_text(nav_result.get("response", ""))
                    except Exception:
                        nav_voice_guidance = None
                if llm_alert:
                    decision_reason = "llm_alert"
                elif hard_rule_alert:
                    decision_reason = "hard_rule_alert_promotion"
                elif llm_result["error"]:
                    decision_reason = "llm_error_timeout_or_transport"
                elif rule_expected_alert and not llm_alert:
                    decision_reason = "llm_ignore_despite_rule_expected_alert"
                else:
                    decision_reason = "insufficient_dispatch_cues_or_llm_ignore"
                if candidate_alert and not alert_vetted:
                    decision_reason = "vetted_out_by_sub_model"
                # 4b. Structured intel extraction on alert-worthy transcripts
                llm_intel = None
                if candidate_alert and alert_vetted and use_llm_set and args.llm_intel:
                    intel_result = llm_set_client.query_intel(
                        raw_text,
                        timeout_seconds=args.llm_intel_timeout,
                        model=args.llm_intel_model,
                        options=vet_runtime_options,
                    )
                    llm_intel = intel_result["intel"]
                    if llm_intel:
                        location_mentions = _dedupe_mentions(location_mentions + llm_intel["locations"])
                        poi_mentions = _dedupe_mentions(poi_mentions + llm_intel["pois"])
                        if args.alert_debug:
                            print(
                                "[INTEL] "
                                f"model={intel_result['model']} "
                                f"fallback={intel_result['used_fallback']} "
                                f"call_types={llm_intel['call_types']} "
                                f"priority={llm_intel['priority']} "
                                f"codes={llm_intel['codes']} "
                                f"units={llm_intel['units']} "
                                f"locations={llm_intel['locations']} "
                                f"pois={llm_intel['pois']} "
                                f"summary=\"{log_safe(llm_intel['summary'])}\""
                            )
                    elif args.alert_debug:
                        print(
                            "[INTEL] extraction failed: "
                            f"error={log_safe(str(intel_result['error'])) if intel_result['error'] else 'none'} "
                            f"parse_error={log_safe(str(intel_result['parse_error'])) if intel_result['parse_error'] else 'none'}"
                        )
                emit_event_json(
                    "alert_decision",
                    enabled=args.integration_json,
                    transcript=raw_text,
                    cue_count=cue_count,
                    dispatch_score=dispatch_score,
                    cue_groups=cue_map,
                    llm_alert=llm_alert,
                    rule_expected_alert=rule_expected_alert,
                    hard_rule_alert=hard_rule_alert,
                    decision_reason=decision_reason,
                    fallback_soft_alert=fallback_soft_alert,
                    llm_status=llm_result["status_code"],
                    llm_attempts=llm_result["attempts"],
                    llm_error=llm_result["error"],
                    llm_response=ai_response,
                    pipeline_stage_flow="whisper->vet(alert+vet+intel)->core(nav)",
                    vet_runtime_options=vet_runtime_options,
                    core_runtime_options=core_runtime_options,
                    alert_vetted=alert_vetted,
                    alert_vet_decision=vet_result.get("decision"),
                    alert_vet_model=vet_result.get("model"),
                    alert_vet_status=vet_result.get("status_code"),
                    alert_vet_attempts=vet_result.get("attempts"),
                    alert_vet_error=vet_result.get("error"),
                    alert_vet_used_fallback=vet_result.get("used_fallback"),
                    classification=classification,
                    location_mentions=location_mentions,
                    poi_mentions=poi_mentions,
                    llm_intel=llm_intel,
                    rms=rms,
                    clip_ratio=clip_ratio,
                )
                if args.alert_debug:
                    llm_raw_excerpt = log_safe(llm_result["raw"]) if llm_result["raw"] else "none"
                    print(
                        "[ALERT_DEBUG] "
                        f"ts={datetime.now(UTC).isoformat()} "
                        f"cue_count={cue_count} "
                        f"dispatch_score={dispatch_score} "
                        f"cue_groups={cue_map} "
                        f"rule_expected_alert={rule_expected_alert} "
                        f"hard_rule_alert={hard_rule_alert} "
                        f"llm_alert={llm_alert} "
                        f"decision_reason={decision_reason} "
                        f"classification_types={classification['call_types']} "
                        f"classification_priority={classification['priority']} "
                        f"llm_status={llm_result['status_code']} "
                        f"llm_attempts={llm_result['attempts']} "
                        f"llm_error={log_safe(str(llm_result['error'])) if llm_result['error'] else 'none'} "
                        f"alert_vetted={alert_vetted} "
                        f"alert_vet_decision={vet_result.get('decision')} "
                        f"alert_vet_model={vet_result.get('model')} "
                        f"alert_vet_error={log_safe(str(vet_result.get('error'))) if vet_result.get('error') else 'none'} "
                        f"fallback_soft_alert={fallback_soft_alert} "
                        f"llm_raw_excerpt=\"{llm_raw_excerpt}\" "
                        f"llm_response=\"{log_safe(ai_response)}\" "
                        f"transcript=\"{log_safe(raw_text)}\""
                    )
                
                if llm_alert and alert_vetted:
                    run_stats["llm_alert"] += 1
                    print(f"🚨 {ai_response}")
                    print(
                        "[ALERT_LOG] "
                        f"ts={datetime.now(UTC).isoformat()} "
                        f"kind=llm_alert "
                        f"alert=\"{ai_response}\" "
                        f"types={classification['call_types']} "
                        f"priority={classification['priority']} "
                        f"codes={classification['codes']} "
                        f"locations={location_mentions} "
                        f"pois={poi_mentions} "
                        f"transcript=\"{raw_text}\""
                    )
                    # 5. Linux Native Voice Notification Engine
                    clean_message = ALERT_PREFIX_RE.sub("", ai_response, count=1).strip()
                    speak_alert(nav_voice_guidance or clean_message)
                    emit_event_json(
                        "alert_triggered",
                        enabled=args.integration_json,
                        kind="llm_alert",
                        alert=ai_response,
                        transcript=raw_text,
                        classification=classification,
                        location_mentions=location_mentions,
                        poi_mentions=poi_mentions,
                        llm_intel=llm_intel,
                        alert_vetted=alert_vetted,
                        alert_vet_decision=vet_result.get("decision"),
                        alert_vet_model=vet_result.get("model"),
                        alert_vet_reason=decision_reason,
                        nav_voice_guidance=nav_voice_guidance,
                        rms=rms,
                        clip_ratio=clip_ratio,
                        audio_levels=audio_levels,
                        audio_level_window_ms=AUDIO_LEVEL_WINDOW_MS,
                    )
                elif hard_rule_alert and alert_vetted:
                    run_stats["llm_alert"] += 1
                    hard_alert_message = proposed_alert_message
                    print(f"🚨 {hard_alert_message}")
                    print(
                        "[ALERT_LOG] "
                        f"ts={datetime.now(UTC).isoformat()} "
                        f"kind=rule_alert_high_confidence "
                        f"alert=\"{hard_alert_message}\" "
                        f"types={classification['call_types']} "
                        f"priority={classification['priority']} "
                        f"codes={classification['codes']} "
                        f"locations={location_mentions} "
                        f"pois={poi_mentions} "
                        f"transcript=\"{raw_text}\""
                    )
                    speak_alert(nav_voice_guidance or "Potential traffic enforcement ahead. Slow down and use caution.")
                    emit_event_json(
                        "alert_triggered",
                        enabled=args.integration_json,
                        kind="rule_alert_high_confidence",
                        alert=hard_alert_message,
                        transcript=raw_text,
                        classification=classification,
                        location_mentions=location_mentions,
                        poi_mentions=poi_mentions,
                        llm_intel=llm_intel,
                        alert_vetted=alert_vetted,
                        alert_vet_decision=vet_result.get("decision"),
                        alert_vet_model=vet_result.get("model"),
                        alert_vet_reason=decision_reason,
                        cue_count=cue_count,
                        dispatch_score=dispatch_score,
                        rms=rms,
                        clip_ratio=clip_ratio,
                        audio_levels=audio_levels,
                        audio_level_window_ms=AUDIO_LEVEL_WINDOW_MS,
                    )
                elif fallback_soft_alert and alert_vetted:
                    soft_alert_message = proposed_alert_message
                    run_stats["soft_alert_fallback"] += 1
                    print(f"⚠️ {soft_alert_message}")
                    print(
                        "[FALLBACK_LOG] "
                        f"ts={datetime.now(UTC).isoformat()} "
                        f"reason=llm_ignore_despite_rule_expected_alert "
                        f"cue_count={cue_count} "
                        f"dispatch_score={dispatch_score} "
                        f"types={classification['call_types']} "
                        f"priority={classification['priority']} "
                        f"codes={classification['codes']} "
                        f"locations={location_mentions} "
                        f"transcript=\"{raw_text}\""
                    )
                    print(
                        "[ALERT_LOG] "
                        f"ts={datetime.now(UTC).isoformat()} "
                        f"kind=soft_alert_fallback "
                        f"alert=\"{soft_alert_message}\" "
                        f"types={classification['call_types']} "
                        f"priority={classification['priority']} "
                        f"codes={classification['codes']} "
                        f"locations={location_mentions} "
                        f"pois={poi_mentions} "
                        f"transcript=\"{raw_text}\""
                    )
                    speak_alert(nav_voice_guidance or "Possible traffic enforcement activity detected. Use caution.")
                    emit_event_json(
                        "alert_triggered",
                        enabled=args.integration_json,
                        kind="soft_alert_fallback",
                        alert=soft_alert_message,
                        transcript=raw_text,
                        classification=classification,
                        location_mentions=location_mentions,
                        poi_mentions=poi_mentions,
                        llm_intel=llm_intel,
                        alert_vetted=alert_vetted,
                        alert_vet_decision=vet_result.get("decision"),
                        alert_vet_model=vet_result.get("model"),
                        alert_vet_reason=decision_reason,
                        cue_count=cue_count,
                        rms=rms,
                        clip_ratio=clip_ratio,
                        audio_levels=audio_levels,
                        audio_level_window_ms=AUDIO_LEVEL_WINDOW_MS,
                    )
                elif candidate_alert and not alert_vetted:
                    run_stats["vetted_blocked"] += 1
                    print(
                        "[VET_BLOCKED] "
                        f"decision={vet_result.get('decision')} "
                        f"model={vet_result.get('model')} "
                        f"reason={decision_reason} "
                        f"transcript=\"{log_safe(raw_text)}\""
                    )
                    emit_event_json(
                        "alert_vetoed",
                        enabled=args.integration_json,
                        transcript=raw_text,
                        proposed_alert=proposed_alert_message,
                        decision_reason=decision_reason,
                        alert_vetted=alert_vetted,
                        alert_vet_decision=vet_result.get("decision"),
                        alert_vet_model=vet_result.get("model"),
                        alert_vet_status=vet_result.get("status_code"),
                        alert_vet_error=vet_result.get("error"),
                        classification=classification,
                        location_mentions=location_mentions,
                        poi_mentions=poi_mentions,
                        rms=rms,
                        clip_ratio=clip_ratio,
                    )
        except Exception as e:
            if SHOULD_EXIT:
                break
            print(f"[LoopError] recoverable error: {repr(e)}")
            emit_event_json(
                "loop_error",
                enabled=args.integration_json,
                error=repr(e),
            )
            time.sleep(1)
finally:
    print(
        "[RUN_SUMMARY] "
        f"captured={run_stats['captured']} "
        f"skipped_silence={run_stats['skipped_silence']} "
        f"skipped_clipped={run_stats['skipped_clipped']} "
        f"llm_alert={run_stats['llm_alert']} "
        f"soft_alert_fallback={run_stats['soft_alert_fallback']} "
        f"vetted_blocked={run_stats['vetted_blocked']}"
    )
    emit_event_json(
        "run_summary",
        enabled=args.integration_json,
        **run_stats,
    )
    if scrcpy_proc is not None and scrcpy_proc.poll() is None:
        scrcpy_proc.terminate()
