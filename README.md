# HeyTune — pause-to-talk music player

HeyTune is a fork of [OuterTune](https://github.com/OuterTune/OuterTune) (`lite`, local-playback only) that adds [Whistle](https://huggingface.co/Cactus-Compute/whistle) (speech-to-text) and [Needle 3](https://huggingface.co/Cactus-Compute/needle3) from [Cactus Compute](https://www.cactuscompute.com) to control the media player with your voice.

It plays the music stored on your phone — no wake word and nothing is always listening: you tap **pause** (in-app, notification, headset or Android Auto), say what you want in English, and the app does it — fully on-device, no network needed. After every command the music resumes automatically (except when you say “pause”).

## What you need

- An Android phone with **arm64-v8a** (every modern phone since ~2017 — the build is arm64-only).
- Your music files on the device (HeyTune plays your local library).
- Microphone permission (only used in the short moment after you tap pause).
- About 45 MB of free space once, for the offline voice models.
- English commands — other languages are not supported.

## Install it

1. Open this repo's **Actions** tab and pick the latest successful run of
   the `android` workflow.
2. Download the **HeyTune-debug** artifact and install the APK on your
   phone (allow "install unknown apps" when asked).
3. Open HeyTune and let it scan your music library.

No Play Store, no account, no sign-in.

## Set up voice control (one time)

1. In HeyTune, go to **Player → Settings** and turn on the voice mode
   toggle.
2. Allow microphone access when asked.
3. Wait for the voice models to download (~45 MB, once). Leave the app
   open until it finishes — if it fails, check your connection and try
   the toggle again.

## Use it

1. Whenever music is playing and you want something, tap the **pause**
   button (in the full player, the mini player, or the queue screen).
   The music stops so the microphone hears only you.
2. A "Listening… speak now" message appears — just speak your command.
   While the microphone is open you will see two green indicators: the
   phone's own green mic dot at the top of the screen (shown by Android
   whenever any app uses the mic) and a small green mic badge on the
   pause button inside the app. When they disappear, the moment has
   passed — just tap pause again. Changed your mind mid-listen? Just
   tap play: the microphone closes immediately and nothing is executed.
3. The app carries it out. If it wasn't sure what you said, it tells you
   what it heard instead of guessing.

Tap pause to talk, every time. Pausing first is the whole trick: no
background music means the app understands you much better, and there is
never a microphone listening in the background.

## What you can say

Speak naturally in English. All commands are defined in `heytune/app/src/main/assets/handsfree_tools.json` and run via Needle 3 on-device:

- **"play Bohemian Rhapsody" / "put on Bohemian Rhapsody" / "start song Bohemian Rhapsody"** — find a song, artist or album in your library and play it (`play_song` with `query`). Saying an artist name (e.g. “play Tom Walker”) plays a random song from that artist.
- **"play a random song" / "play something random" / "surprise me" / "shuffle play" / "random"** — play a random song from your library (`play_random_song`)
- **"pause" / "stop the music" / "stop"** — pause playback (`pause_playback`)
- **"resume" / "continue playing" / "unpause" / "play"** — resume playback (`resume_playback`)
- **"next" / "next song" / "next track" / "skip" / "skip this"** — jump to the next track (`next_track`)
- **"previous" / "previous song" / "go back" / "last song"** — go back a track (`previous_track`)
- **"volume to 50" / "volume at 30" / "louder" (≈70) / "quieter" (≈30)** — set volume 0–100 (`set_volume` with `level`)
- **"shuffle on" / "shuffle off" / "stop shuffling" / "disable shuffle"** — control shuffle (`toggle_shuffle` with `on`)
- **"repeat off" / "don't repeat" / "repeat all" / "loop all" / "repeat one" / "repeat this song" / "loop this"** — control repeat (`set_repeat_mode` with `mode: off/all/one`)
- **"like this song" / "like the song" / "add to favorite/favourite"** — favourite the current song (`like_current_song`)

Anything else is ignored with a short message saying what was heard (“Heard ‘…’ — no matching command”).

## Your privacy

Everything happens on your phone: speech recognition (Whistle) and
command understanding (Needle 3) both run on-device. Your voice and
commands never leave the device, and the microphone is only open in the
few seconds after you tap pause.

## If something goes wrong

- **"Voice models not ready"** — turn the voice toggle off and on again
  with a working connection so the ~45 MB download can finish.
- **"Microphone access is required"** — grant the permission in your
  phone's Settings → Apps → HeyTune → Permissions.
- **"Voice engine unavailable on this device"** — you are on an emulator,
  an x86 device, or an older armv7 phone. The voice engine needs arm64 —
  use a regular modern Android phone.
- **It heard me but did nothing** — rephrase more simply (e.g. "next",
  "volume to 40"). Background noise and mumbling are the usual culprits;
  make sure the music actually paused first.

## For developers

HeyTune is a fork of [OuterTune](https://github.com/OuterTune/OuterTune) (`lite`, local-playback only) with voice integration added — the same approach applies to active forks (`Metrolist`, `InterTune`, …). It adds Whistle and Needle 3 from Cactus Compute (see `heytune/handsfree/`). Upstream files under `heytune/` keep their original copyright headers and docs. App identity: `com.heytune.app` (`Theme.HeyTune`, launcher name **HeyTune**).

```
heytune/                        # App snapshot + integration
  settings.gradle.kts           # includes :handsfree
  app/.../handsfree/VoiceIntegration.kt   # search+play, like, models, service lifecycle
  app/.../settings/fragments/VoiceDrivingFrag.kt  # Player settings toggle
  app/src/main/assets/handsfree_tools.json  # voice command definitions
  handsfree/                    # :handsfree Android library module
    VoiceService.kt             # foreground mic service, pause-triggered, music ducking
    ModelManager.kt             # downloads whistle.cact + needle3.cact on first enable
    NeedleBridge.kt + src/main/cpp/needle_bridge.cpp  # JNI -> libneedle.a
    PlayerBridge.kt / VoiceTools.kt / CommandParser.kt / WakeWordDetector.kt
```

Pushing to GitHub runs the `android` workflow: it stages the native
engine for arm64-v8a, checks the model URLs, compiles a single arm64 APK,
and uploads it as the `HeyTune-debug` artifact. No keystore is needed
for debug builds.
