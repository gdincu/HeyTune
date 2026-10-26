import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "python"))

from pipeline import (  # noqa: E402
    VOICE_LANGUAGE,
    dispatch,
    parse_fallback,
    route_result,
    transcribe_then_complete,
)


def test_english_only():
    assert VOICE_LANGUAGE == "en"


def test_no_wake_needed():
    # No wake word: every utterance is a command (pause is the trigger).
    assert parse_fallback("play Bohemian Rhapsody").name == "play_song"
    assert parse_fallback("pause the music").name == "pause_playback"
    # "hey tune" prefix (old wake) is just ignored words, still parses.
    assert parse_fallback("hey tune play X").name == "play_song"


def test_fallback_commands():
    assert parse_fallback("play Bohemian Rhapsody").name == "play_song"
    assert parse_fallback("play X").arguments["query"] == "X"
    assert parse_fallback("pause").name == "pause_playback"
    assert parse_fallback("resume").name == "resume_playback"
    assert parse_fallback("next song").name == "next_track"
    assert parse_fallback("previous").name == "previous_track"
    assert parse_fallback("volume to 50").arguments == {"level": 50}
    assert parse_fallback("shuffle on").arguments == {"on": True}
    assert parse_fallback("like this song").name == "like_current_song"
    assert parse_fallback("hello world") is None
    assert parse_fallback("") is None


def test_routing_thresholds():
    ok = {"function_calls": [{"name": "pause_playback", "arguments": []}],
          "suppressed_calls": [], "confidence": 0.94}
    assert route_result(ok)[0] == "execute"
    mid = {"function_calls": [{"name": "pause_playback", "arguments": []}],
           "suppressed_calls": [], "confidence": 0.4}
    assert route_result(mid)[0] == "confirm"
    held = {"function_calls": [], "suppressed_calls": [
        {"name": "pause_playback", "arguments": []}], "confidence": 0.05}
    assert route_result(held)[0] == "confirm"
    assert route_result({"function_calls": [], "confidence": 0.0})[0] == "refuse"


class FakeAgent:
    def __init__(self, result):
        self._result = result

    def complete(self, text):
        assert isinstance(text, str)
        return self._result


class FakePlayer:
    def __init__(self):
        self.calls = []

    def play_song(self, query):
        self.calls.append(("play_song", query))
        return {"played": query}

    def pause(self):
        self.calls.append(("pause",))
        return {"paused": True}

    def resume(self):
        self.calls.append(("resume",))
        return True

    def next(self):
        self.calls.append(("next",))
        return True

    def previous(self):
        self.calls.append(("previous",))
        return True

    def set_volume(self, level):
        self.calls.append(("set_volume", level))
        return True

    def set_shuffle(self, on):
        self.calls.append(("set_shuffle", on))
        return True

    def like_current(self):
        self.calls.append(("like",))
        return True


def fake_transcribe(text, language="en"):
    def _fn(audio, language=None, **kwargs):
        assert (language or "en") == "en", "Whistle must be English-only"
        return {"text": text, "language": "en",
                "ttft_ms": 1.0, "decode_tps": 100.0}
    return _fn


def test_pipeline_silence_ignored():
    out = transcribe_then_complete(
        "clip.wav", agent=FakeAgent({"function_calls": [], "confidence": 0}),
        transcribe_fn=fake_transcribe(""))
    assert out["decision"] == "ignored"
    assert out["audio_language"] == "en"


def test_pipeline_no_wake_gate():
    # "pause the music" with no wake word must NOT be ignored anymore.
    out = transcribe_then_complete(
        "clip.wav",
        agent=FakeAgent({"function_calls": [{"name": "pause_playback",
                                             "arguments": {}}], "confidence": 0.9}),
        transcribe_fn=fake_transcribe("pause the music"))
    assert out["decision"] == "execute"


def test_pipeline_execute_and_dispatch():
    agent = FakeAgent({"function_calls": [{"name": "pause_playback",
                                           "arguments": {}}],
                       "suppressed_calls": [], "confidence": 0.94,
                       "reasoning": "pause"})
    out = transcribe_then_complete(
        "clip.wav", agent=agent,
        transcribe_fn=fake_transcribe("pause the music"))
    assert out["decision"] == "execute"
    assert out["audio_text"] == "pause the music"
    assert out["audio_language"] == "en"
    player = FakePlayer()
    res = dispatch(out["function_calls"], player)
    assert player.calls == [("pause",)] and res == [{"paused": True}]


def test_pipeline_agreement_upgrade():
    # Model under-confident (0.43) but fallback parser agrees -> execute.
    agent = FakeAgent({"function_calls": [{"name": "pause_playback",
                                           "arguments": {}}],
                       "suppressed_calls": [], "confidence": 0.43,
                       "reasoning": "pause"})
    out = transcribe_then_complete(
        "clip.wav", agent=agent,
        transcribe_fn=fake_transcribe("pause the music"))
    assert out["decision"] == "execute" and out.get("agreed_with_fallback") is True


def test_pipeline_confirm_when_model_and_fallback_disagree():
    agent = FakeAgent({"function_calls": [{"name": "like_current_song",
                                           "arguments": {}}],
                       "suppressed_calls": [], "confidence": 0.43,
                       "reasoning": "like"})
    out = transcribe_then_complete(
        "clip.wav", agent=agent,
        transcribe_fn=fake_transcribe("pause the music"))
    assert out["decision"] == "confirm"


def test_pipeline_fallback_when_model_refuses():
    agent = FakeAgent({"function_calls": [], "suppressed_calls": [],
                       "confidence": 0.0, "reasoning": ""})
    out = transcribe_then_complete(
        "clip.wav", agent=agent,
        transcribe_fn=fake_transcribe("volume to 42"))
    assert out["decision"] == "fallback"
    assert out["function_calls"] == [{"name": "set_volume", "arguments": {"level": 42}}]


def test_pipeline_forces_english():
    seen = {}

    def _fn(audio, language=None, **kwargs):
        seen["lang"] = language
        return {"text": "next song", "language": "en"}

    agent = FakeAgent({"function_calls": [{"name": "next_track", "arguments": {}}],
                       "suppressed_calls": [], "confidence": 0.9})
    out = transcribe_then_complete("clip.wav", agent=agent, transcribe_fn=_fn,
                                   language="de", keywords=["x"])
    assert seen["lang"] == "en"
    assert out["decision"] == "execute"
    assert out["audio_language"] == "en"
