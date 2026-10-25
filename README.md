# HeyTune — hands-free music player (Whistle STT + Needle function calling)

Press your steering-wheel button while driving to **play a song / pause / resume / next /
previous / volume / shuffle / like** — fully on-device, no network, using:

- [Whistle](https://cactuscompute.com/blog/whistle) — 16.9 MB on-device speech-to-text (7 languages, 11 ms to first token)
- [Needle 3](https://cactuscompute.com/needle) — 8–29 MB on-device function caller

One C++ engine loads both `.cact` files; `needle_complete(audio)` returns
tool calls + `audio_text` in a single call, so the transcript never leaves the device.

> HeyTune is a rebrand of an `OuterTune/OuterTune` (`lite`, local-playback
> only) snapshot with voice integration applied; the same patches apply to
> active forks (`Metrolist`, `InterTune`, …). Upstream files under `heytune/`
> keep their original copyright headers and docs.

App identity: `com.heytune.app` (`Theme.HeyTune`, launcher name **HeyTune**).

## Layout

```
.github/workflows/android.yml   # CI: python checks + handsfree tests + debug APK
tools.json                      # Needle tool schemas (single source of truth)
python/                         # Desktop reference pipeline — runs WITHOUT Android
  voice_tools.py                # @needle.tool definitions (mirrors tools.json)
  pipeline.py                   # wake gate -> transcribe -> complete -> dispatch
  cli.py                        # try it: text / wav / mic
  download_models.py            # stage native engine into heytune/handsfree/engine/
tests/test_pipeline.py          # 10 offline tests (no model download needed)
heytune/                        # App snapshot (OuterTune lite) + integration
  settings.gradle.kts           # includes :handsfree
  app/.../handsfree/VoiceIntegration.kt   # search+play, like, models, service lifecycle
  app/.../settings/fragments/VoiceDrivingFrag.kt  # Player settings toggle
  app/src/main/assets/handsfree_tools.json  # == ../../tools.json (CI diffs them)
  handsfree/                    # :handsfree Android library module
    VoiceService.kt             # foreground mic service, VAD-gated, music ducking
    ModelManager.kt             # downloads whistle.cact + needle3.cact on first enable
    NeedleBridge.kt + src/main/cpp/needle_bridge.cpp  # JNI -> libneedle.a
    PlayerBridge.kt / VoiceTools.kt / CommandParser.kt / WakeWordDetector.kt
```

## Build the APK (GitHub Actions)

Push this repo to GitHub — the `android` workflow does everything:

1. **python-checks**: pytest + `tools.json` ↔ app-assets sync check.
2. **build-apk**: JDK 21, Android SDK 37, NDK `27.0.12077973`, CMake `3.22.1`,
   `needle download android-arm64/android-armv7` (staged to
   `handsfree/engine/…`), model-URL reachability check,
   `:handsfree:testDebugUnitTest`, `:app:assembleCoreDebug`.
3. Debug APKs (`HeyTune-…apk`) uploaded as artifact `HeyTune-debug`.

No keystore needed for debug. For a signed release, add `keystore.properties`
as GitHub secrets (the app already supports `ot_release` signing config).

## Try the voice pipeline on desktop (no phone needed)

```bat
pip install -r python\requirements.txt
pip install pytest
python -m pytest tests -q
python python\cli.py text "hey tune volume to 42" --fallback-only
```

With models (downloaded once, then offline):

```bat
python python\cli.py text "hey tune play Bohemian Rhapsody" --execute
python python\cli.py wav clip.wav --execute
python python\cli.py mic
```

## How it works on device

1. Player → Settings → **Hands-free driving mode** on (mic permission →
   downloads `whistle.cact` + `needle3.cact` once, ~45 MB).
2. Press your steering-wheel assistant button (or the 🎤 notification
   action) and speak a command — no wake phrase needed. The mic opens
   only for that window (≤10 s audio, ends on ~1.2 s silence), then the
   service stops itself. Nothing is always listening.
3. `needle_complete(audio)` → tool calls; `confidence ≥ 0.50` executes,
   otherwise a confirm notification/TTS, otherwise the offline regex fallback.
   If model and fallback agree on a low-confidence call, it executes
   (agreement upgrade — no driver nagging).
4. `PlayerBridge` dispatches via `MediaController`:
   pause/resume/next/previous/volume/shuffle/like directly;
   `play X` searches the local library (`searchSongs` + `searchArtistSongs`),
   enqueues a `ListQueue` and plays the first hit.

Car noise: `VOICE_RECOGNITION` source + transient music ducking; Whistle's
silence gate drops steady noise. x86/x86_64 emulator builds assemble with a
native stub (`nativeAvailable=false`, service refuses to start) since Cactus
ships no x86 engine.

## Steering-wheel / headset button (digital assistant)

The car button never reaches apps directly — Android routes it to the
selected *digital assistant*. HeyTune registers one
(`HeyTuneAssistantService` + headless session): Player → Settings →
voice section → **Set HeyTune as digital assistant**, then confirm in
system Settings → Apps → Default apps if asked. A press opens one listen
window (same as the notification 🎤 action) with no wake phrase; the
service stops itself afterwards unless driving mode is on.

Caveat: classic Android Auto **projection** reserves its button/mic for
Gemini/Assistant and won't offer third-party assistants there. The
assistant path works on the phone itself (Bluetooth, headset button)
and on AAOS-native head units.

## Measured behaviour (Needle 3 base, no fine-tune, 2026-10-25)

| command | call | confidence | decision |
|---|---|---|---|
| play Bohemian Rhapsody | `play_song` ✓ args | 0.855 | execute |
| next song | `next_track` | 0.836 | execute |
| stop shuffling | `toggle_shuffle(off)` | 0.799 | execute |
| shuffle on / off | `toggle_shuffle` ✓ | 0.903 / 0.772 | execute |
| volume to 50 | `set_volume(50)` | 0.644 | execute¹ |
| pause the music | `pause_playback` | 0.43–0.61 | execute¹ |
| silence (Whistle) | empty transcript | — | ignored |

¹ via agreement upgrade. `EXECUTE_AT = 0.50`. Fine-tuning on these 8 tools
(`needle finetune` / Cactus Platform, +18–36 pts) is the long-tail fix.
