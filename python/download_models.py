"""Prefetch Cactus weights + native engine into heytune/handsfree/engine/.

Usage:
    python download_models.py            # engine (arm64+armv7) into the Gradle module
    python download_models.py --no-engine

The .cact weights are NOT bundled in the APK; the app downloads them on
first enable (see handsfree ModelManager.kt). This script only stages the
compile-time engine (libneedle.a + needle.h) that CI also fetches.
"""

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENGINE_DIR = ROOT / "heytune" / "handsfree" / "engine"


def run(*cmd: str) -> None:
    print("+", " ".join(cmd))
    subprocess.run(list(cmd), check=True)


def stage_engine(abi: str, tmp: Path) -> None:
    """needle download <platform> drops libneedle.a + needle.h somewhere under out."""
    if tmp.exists():
        shutil.rmtree(tmp)
    run("needle", "download", abi, "--out", str(tmp))
    libs = list(tmp.rglob("libneedle.a"))
    headers = list(tmp.rglob("needle.h"))
    if not libs or not headers:
        raise SystemExit(f"engine download for {abi} produced no libneedle.a/needle.h")
    dest = ENGINE_DIR / abi
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(libs[0], dest / "libneedle.a")
    shutil.copy2(headers[0], dest / "needle.h")
    print(f"staged {abi} -> {dest}")
    shutil.rmtree(tmp, ignore_errors=True)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--no-engine", action="store_true")
    p.add_argument("--tmp", default="engine-tmp")
    args = p.parse_args()

    if not args.no_engine:
        tmp = Path(args.tmp)
        stage_engine("android-arm64", tmp / "arm64")
        stage_engine("android-armv7", tmp / "armv7")
        shutil.rmtree(tmp, ignore_errors=True)
    print("OK: engine in", ENGINE_DIR)


if __name__ == "__main__":
    main()
