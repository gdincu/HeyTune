"""Always-on voice pipeline: wake-word gate -> Whistle STT -> Needle tools.

Mirrors the Android implementation in
handsfree-android/.../VoiceService.kt + CommandParser.kt.

On device the C API does audio->calls in one call (needle_complete).
On desktop the Python package does it in two steps, which is what
transcribe_then_complete() implements:

    text = needle.transcribe(wav)["text"]
    result = agent.complete(text)
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Callable

# The wake word is bare "hey" (word-boundary matched: "they", "whey" etc.
# must NOT trigger). Anything after it is the command; "hey" alone is ignored.
WAKE_WORD = "hey"
_WAKE_RE = re.compile(r"\bhey\b", re.IGNORECASE)
_WAKE_LEAD_RE = re.compile(r"^\s*hey\b[\s,.!:-]*", re.IGNORECASE)

# Confidence routing thresholds (Cactus guide: act / confirm / refuse).
# Calibrated 2026-10-25 on Needle 3 base (no fine-tune):
#   play X -> 0.855 | next -> 0.836 | volume 50 -> 0.644
#   pause -> 0.613  | shuffle on -> 0.562 | resume -> 0.462
# 0.50 executes the common driving commands while keeping genuinely
# ambiguous requests in confirm. Fine-tuning on these 8 tools lifts every
# rung +18-36 pts (Cactus DroidCall result) and is the proper fix.
EXECUTE_AT = 0.50  # act at once
CONFIRM_AT = 0.10  # engine floor; below -> suppressed_calls


def has_wake(text: str) -> bool:
    return _WAKE_RE.search(text.strip()) is not None


def strip_wake(text: str) -> str:
    """Remove a leading wake word so the command parses cleanly."""
    return _WAKE_LEAD_RE.sub("", text.strip())


@dataclass
class ParsedCall:
    name: str
    arguments: dict
    source: str = "fallback"  # or "needle"


# ---------------------------------------------------------------------------
# Offline fallback parser (fast path, no model). Also used when Needle
# confidence is below floor or the model is unavailable.
# ---------------------------------------------------------------------------

_PATTERNS: list[tuple[str, re.Pattern, Callable[[re.Match], dict]]] = [
    ("pause_playback", re.compile(r"\b(pause|pause the music|stop the music)\b", re.I), lambda m: {}),
    ("resume_playback", re.compile(r"\b(resume|continue( playing)?|unpause)\b", re.I), lambda m: {}),
    ("next_track", re.compile(r"\b(next( song| track)?|skip( this)?)\b", re.I), lambda m: {}),
    ("previous_track", re.compile(r"\b(previous( song| track)?|go back|last song)\b", re.I), lambda m: {}),
    (
        "set_volume",
        re.compile(r"\bvolume(?: to| at)?\s*(?P<level>\d{1,3})\b", re.I),
        lambda m: {"level": max(0, min(100, int(m.group("level"))))},
    ),
    ("set_volume", re.compile(r"\blouder\b", re.I), lambda m: {"level": 70}),
    ("set_volume", re.compile(r"\bquieter\b", re.I), lambda m: {"level": 30}),
    ("toggle_shuffle", re.compile(r"\bshuffle\s*(?P<state>on|off)\b", re.I),
     lambda m: {"on": m.group("state").lower() == "on"}),
    ("toggle_shuffle", re.compile(r"\b(stop shuffling|disable shuffle)\b", re.I), lambda m: {"on": False}),
    ("like_current_song", re.compile(r"\b(like( this( song)?)?|add .*to (my )?favo(u)?rites?)\b", re.I), lambda m: {}),
    (
        "play_song",
        re.compile(r"\b(?:play|put on|start)\b\s+(?P<query>.+)$", re.I),
        lambda m: {"query": m.group("query").strip()},
    ),
]


def parse_fallback(text: str) -> ParsedCall | None:
    """Parse a command without any model. Returns None if unrecognised."""
    t = strip_wake(text).strip()
    if not t:
        return None
    for name, pattern, args in _PATTERNS:
        m = pattern.search(t)
        if m:
            q = args(m)
            if name == "play_song" and not str(q.get("query", "")).strip():
                continue
            return ParsedCall(name=name, arguments=q)
    # Bare wake ("hey") or anything else unrecognised -> None (ignored).
    return None


# ---------------------------------------------------------------------------
# Confidence routing (Cactus guide: act / confirm / refuse)
# ---------------------------------------------------------------------------

def route_result(result: dict[str, Any]) -> tuple[str, list[dict]]:
    """Decide execute|confirm|refuse from a Needle complete() response."""
    calls = list(result.get("function_calls") or [])
    held = list(result.get("suppressed_calls") or [])
    conf = float(result.get("confidence") or 0.0)
    if calls and conf >= EXECUTE_AT:
        return "execute", calls
    if calls or held:
        return "confirm", calls or held
    return "refuse", []


# ---------------------------------------------------------------------------
# Full pipeline: audio -> tool calls (injectable for tests)
# ---------------------------------------------------------------------------

def transcribe_then_complete(
    audio,
    agent=None,
    transcribe_fn: Callable[..., dict] | None = None,
    language: str | None = None,
    keywords: list[str] | None = None,
    require_wake: bool = True,
    fallback_on_low_confidence: bool = True,
) -> dict[str, Any]:
    """Transcribe a clip with Whistle, answer it with Needle.

    audio: wav path or 16kHz mono float samples (whatever needle.transcribe takes).
    Returns a dict like the C engine: function_calls, confidence, audio_text,
    audio_language, decision (execute|confirm|refuse|ignored|fallback).
    """
    import needle as _needle  # local import so pure helpers stay import-light

    t_fn = transcribe_fn or _needle.transcribe
    stt = t_fn(audio, language=language, keywords=keywords)
    text = (stt.get("text") or "").strip()
    lang = stt.get("language")

    if not text:
        return {"decision": "ignored", "reason": "silence",
                "audio_text": text, "audio_language": lang,
                "function_calls": []}
    if require_wake and not has_wake(text):
        return {"decision": "ignored", "reason": "no-wake-word",
                "audio_text": text, "audio_language": lang,
                "function_calls": []}

    command = strip_wake(text) if require_wake else text

    if require_wake and not command:
        # Bare "hey" with nothing after it: attention, not a command.
        # Ignore without spending a Needle call.
        return {"decision": "ignored", "reason": "wake-only",
                "audio_text": text, "audio_language": lang,
                "function_calls": []}

    ag = agent
    if ag is None:
        from .voice_tools import build_agent
        ag = build_agent()
    result = ag.complete(command)
    decision, calls = route_result(result)

    # Driving-mode agreement upgrade: Needle is calibrated conservatively and
    # the base model under-scores some commands (pause ~0.43-0.61). If the
    # deterministic fallback parser independently produces the SAME call,
    # two systems agree -> execute instead of bothering the driver.
    if decision == "confirm" and calls:
        fb = parse_fallback(command)
        if fb is not None and any(
            c.get("name") == fb.name and dict(c.get("arguments") or {}) == fb.arguments
            for c in calls
        ):
            return {**result, "decision": "execute", "audio_text": text,
                    "audio_language": lang, "agreed_with_fallback": True}

    if decision == "refuse" and fallback_on_low_confidence:
        fb = parse_fallback(command)
        if fb is not None:
            return {"decision": "fallback", "function_calls": [
                {"name": fb.name, "arguments": fb.arguments}],
                "confidence": result.get("confidence"),
                "audio_text": text, "audio_language": lang,
                "reasoning": result.get("reasoning")}
    return {**result, "decision": decision,
            "audio_text": text, "audio_language": lang}


# ---------------------------------------------------------------------------
# Dispatch to a player (HeyTune MusicService on Android; fake in tests)
# ---------------------------------------------------------------------------

class PlayerLike:
    """Structural interface the dispatcher needs (see PlayerBridge.kt)."""

    def play_song(self, query: str) -> Any: ...
    def pause(self) -> Any: ...
    def resume(self) -> Any: ...
    def next(self) -> Any: ...
    def previous(self) -> Any: ...
    def set_volume(self, level: int) -> Any: ...
    def set_shuffle(self, on: bool) -> Any: ...
    def like_current(self) -> Any: ...


def dispatch(calls: list[dict], player: PlayerLike) -> list[Any]:
    out = []
    for call in calls:
        name, args = call.get("name"), dict(call.get("arguments") or {})
        if name == "play_song":
            out.append(player.play_song(str(args.get("query", ""))))
        elif name == "pause_playback":
            out.append(player.pause())
        elif name == "resume_playback":
            out.append(player.resume())
        elif name == "next_track":
            out.append(player.next())
        elif name == "previous_track":
            out.append(player.previous())
        elif name == "set_volume":
            out.append(player.set_volume(int(args.get("level", 50))))
        elif name == "toggle_shuffle":
            out.append(player.set_shuffle(bool(args.get("on", True))))
        elif name == "like_current_song":
            out.append(player.like_current())
        else:
            out.append({"error": f"unknown tool: {name}"})
    return out
