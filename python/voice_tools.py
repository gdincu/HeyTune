"""HeyTune voice tools (Cactus Needle function definitions).

These are the exact tool schemas shipped to Needle on Android (see
handsfree-android/.../VoiceTools.kt) and used by the desktop reference
pipeline. Keep the two in sync: one tool per action, plain descriptions,
enum values named after what users say.
"""

from typing import Literal

import needle


@needle.tool()
def play_song(query: str):
    """Play a song, artist or album by name. Use for 'play X', 'put on X'."""
    return {"query": query}


@needle.tool()
def pause_playback():
    """Pause the current playback. Use for 'pause', 'pause the music'."""
    return {}


@needle.tool()
def resume_playback():
    """Resume paused playback. Use for 'resume', 'continue playing'."""
    return {}


@needle.tool()
def next_track():
    """Skip to the next track. Use for 'next', 'next song', 'skip'."""
    return {}


@needle.tool()
def previous_track():
    """Go back to the previous track. Use for 'previous', 'go back', 'last song'."""
    return {}


@needle.tool()
def set_volume(level: int):
    """Set playback volume 0-100. Use for 'volume to 50', 'louder', 'quieter'."""
    return {"level": level}


@needle.tool()
def toggle_shuffle(on: bool):
    """Set shuffle mode. on=true when the user says 'shuffle on' or 'enable shuffle'; on=false for 'shuffle off', 'disable shuffle' or 'stop shuffling'."""
    return {"on": on}


@needle.tool()
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
