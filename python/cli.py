"""Desktop reference CLI for the hands-free pipeline (no Android needed)."""

import argparse
import json
import sys


def cmd_tools(_args):
    from voice_tools import tool_schemas

    print(json.dumps(tool_schemas(), indent=2))


def cmd_text(args):
    from pipeline import dispatch, has_wake, parse_fallback, strip_wake
    from voice_tools import build_agent

    text = args.text
    print(f"heard: {text!r} wake={has_wake(text)} command={strip_wake(text)!r}")

    if args.fallback_only:
        fb = parse_fallback(text)
        print(json.dumps(
            {"decision": "fallback" if fb else "refuse",
             "function_calls": [{"name": fb.name, "arguments": fb.arguments}] if fb else []},
            indent=2))
        return

    agent = build_agent()
    result = agent.complete(strip_wake(text))
    print(json.dumps(result, indent=2, default=str))

    if args.execute:
        from cli_player import LoggingPlayer

        from pipeline import route_result

        decision, calls = route_result(result)
        print(f"decision: {decision}")
        if decision == "execute":
            print(dispatch(calls, LoggingPlayer()))


def cmd_wav(args):
    from pipeline import dispatch, transcribe_then_complete
    from cli_player import LoggingPlayer
    from voice_tools import build_agent

    agent = None if args.fallback_only else build_agent()
    if args.fallback_only:
        # No model: transcribe with Whistle only, parse with fallback.
        import needle

        stt = needle.transcribe(args.wav, language=args.language,
                                keywords=args.keywords)
        print(json.dumps(stt, indent=2, default=str))
        from pipeline import has_wake, parse_fallback, strip_wake

        text = (stt.get("text") or "")
        fb = parse_fallback(text) if (not args.require_wake or has_wake(text)) else None
        print(json.dumps(
            {"decision": "fallback" if fb else "ignored",
             "function_calls": [{"name": fb.name, "arguments": fb.arguments}] if fb else []},
            indent=2))
        return

    out = transcribe_then_complete(
        args.wav, agent=agent, language=args.language,
        keywords=args.keywords, require_wake=args.require_wake)
    print(json.dumps(out, indent=2, default=str))
    if out.get("decision") in ("execute", "fallback") and args.execute:
        print(dispatch(out["function_calls"], LoggingPlayer()))


def cmd_mic(args):
    try:
        import sounddevice as sd  # type: ignore
        import numpy as np  # type: ignore
    except ImportError:
        sys.exit("mic mode needs: pip install sounddevice numpy")
    from pipeline import dispatch, transcribe_then_complete
    from cli_player import LoggingPlayer
    from voice_tools import build_agent

    agent = build_agent()
    print("Recording 6s @16kHz mono... speak now (with wake phrase).")
    rec = sd.rec(int(6 * 16000), samplerate=16000, channels=1, dtype="float32")
    sd.wait()
    audio = rec[:, 0].astype("float32")
    out = transcribe_then_complete(audio, agent=agent, require_wake=args.require_wake)
    print(json.dumps(out, indent=2, default=str))
    if out.get("decision") in ("execute", "fallback"):
        print(dispatch(out["function_calls"], LoggingPlayer()))


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="HeyTune reference CLI")
    sub = p.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("tools", help="print Needle tool schemas")
    t.set_defaults(fn=cmd_tools)
    x = sub.add_parser("text", help="run a text command through Needle")
    x.add_argument("text")
    x.add_argument("--execute", action="store_true")
    x.add_argument("--fallback-only", action="store_true")
    x.set_defaults(fn=cmd_text)
    w = sub.add_parser("wav", help="transcribe a wav then map to tools")
    w.add_argument("wav")
    w.add_argument("--execute", action="store_true")
    w.add_argument("--fallback-only", action="store_true")
    w.add_argument("--language", default=None)
    w.add_argument("--keywords", nargs="*", default=None)
    w.add_argument("--require-wake", dest="require_wake", action="store_true", default=True)
    w.add_argument("--no-require-wake", dest="require_wake", action="store_false")
    w.set_defaults(fn=cmd_wav)
    m = sub.add_parser("mic", help="record 6s from mic, transcribe, map to tools")
    m.add_argument("--no-require-wake", dest="require_wake", action="store_false", default=True)
    m.set_defaults(fn=cmd_mic)
    return p


if __name__ == "__main__":
    parser = build_parser()
    a = parser.parse_args()
    a.fn(a)
