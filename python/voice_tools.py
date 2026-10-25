"""HeyTune voice tools (Cactus Needle function definitions).

These are the exact tool schemas shipped to Needle on Android (see
handsfree-android/.../VoiceTools.kt) and used by the desktop reference
pipeline. Keep the two in sync: one tool per action, plain descriptions,
enum values named after what users say.
"""

from typing import Literal

import needle


@needle.tool(
    triggers=[
        r"\bplay\b.*",
        r"\bput on\b.*",
        r"\bstart\b.*\b(song|music|track)\b.*",
    ]
)
def play_song(query: str):
    """Play a song, artist or album by name. Use for 'play X', 'put on X'."""
    return {"query": query}


@needle.tool(triggers=[r"\bpause\b.*", r"\bstop\b.*\b(music|song|playback)\b.*"])
def pause_playback():
    """Pause the current playback. Use for 'pause', 'pause the music'."""
    return {}


@needle.tool(
    triggers=[r"\bresume\b.*", r"\bcontinue\b.*", r"\bunpause\b.*", r"\bplay\b\s*$"]
)
def resume_playback():
    """Resume paused playback. Use for 'resume', 'continue playing'."""
    return {}


@needle.tool(triggers=[r"\bnext\b.*", r"\bskip\b.*"])
def next_track():
    """Skip to the next track. Use for 'next', 'next song', 'skip'."""
    return {}


@needle.tool(triggers=[r"\bprevious\b.*", r"\bback\b.*", r"\blast song\b.*"])
def previous_track():
    """Go back to the previous track. Use for 'previous', 'go back', 'last song'."""
    return {}


@needle.tool(triggers=[r"\bvolume\b.*", r"\blouder\b.*", r"\bquieter\b.*"])
def set_volume(level: int):
    """Set playback volume 0-100. Use for 'volume to 50', 'louder', 'quieter'."""
    return {"level": level}


@needle.tool(triggers=[r"\bshuffl\w*\b.*"])
def toggle_shuffle(on: bool):
    """Set shuffle mode. on=true when the user says 'shuffle on' or 'enable shuffle'; on=false for 'shuffle off', 'disable shuffle' or 'stop shuffling'."""
    return {"on": on}


@needle.tool(triggers=[r"\blike\b.*", r"\badd\b.*\b(favorite|favourite|playlist)\b.*"])
def like_current_song():
    """Like / favourite the currently playing song."""
    return {}


TOOLS = [
    play_song,
    pause_playback,
    resume_playback,
    next_track,
    previous_track,
    set_volume,
    toggle_shuffle,
    like_current_song,
]


def build_agent(**kwargs) -> "needle.Needle":
    """Build a Needle agent bound to the music tools."""
    return needle.Needle(tools=TOOLS, **kwargs)


def tool_schemas() -> list:
    """Return the JSON-serialisable tool schemas (mirrors tools.json)."""
    return [fn._needle_tool for fn in TOOLS]  # type: ignore[attr-defined]
