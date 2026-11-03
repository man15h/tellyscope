#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Puts the README's screenshots in a phone frame.

Render the screenshot tests first, then run this from the repository root:

    ./gradlew :app:testGithubDebugUnitTest --tests '*ScreenshotTest' -Proborazzi.test.record=true
    python3 docs/screenshots/frame.py

Needs Pillow (pip install pillow).
"""
from pathlib import Path

from PIL import Image, ImageDraw

SRC = Path("app/build/outputs/roborazzi")
OUT = Path(__file__).parent

# Output name and the phone-sized screenshot it comes from. The tests scroll the page themselves,
# so the top bar and the tabs along the bottom stay in shot.
SHOTS = {
    "tv-page": "04_device",
    "remote": "12_remote_dock_full",
    "debloat": "20_readme_debloat",
    "install": "23_readme_install",
    "send": "25_readme_send",
    "tweaks": "29_readme_tweaks",
    "home": "30_readme_home",
}

DP = 3  # the tests render at xxhdpi
SCREEN_DP = 891  # the tests' 411 x 891 dp phone

WIDTH = 540  # screen width in the output; GitHub shows it at about half that
STATUS = 30  # status bar height, where the camera sits
BEZEL = 14
RADIUS = 46


def frame(src: Path) -> Image.Image:
    shot = Image.open(src).convert("RGB")
    shot = shot.crop((0, 0, shot.width, min(shot.height, SCREEN_DP * DP)))
    shot = shot.resize((WIDTH, shot.height * WIDTH // shot.width), Image.LANCZOS)

    # A status bar in the app's own top colour, as on a phone.
    screen = Image.new("RGB", (WIDTH, shot.height + STATUS), shot.getpixel((2, 2)))
    screen.paste(shot, (0, STATUS))
    w, h = screen.size

    out = Image.new("RGBA", (w + 2 * BEZEL, h + 2 * BEZEL), (0, 0, 0, 0))
    d = ImageDraw.Draw(out)
    d.rounded_rectangle((0, 0, out.width - 1, out.height - 1), RADIUS + BEZEL, fill=(27, 27, 31, 255))
    mask = Image.new("L", screen.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), RADIUS, fill=255)
    out.paste(screen, (BEZEL, BEZEL), mask)
    cx, cy, r = out.width // 2, BEZEL + STATUS // 2, 7
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(27, 27, 31, 255))
    return out


if __name__ == "__main__":
    for name, src in SHOTS.items():
        frame(SRC / f"{src}.png").save(OUT / f"{name}.png", optimize=True)
        print(f"{name}.png")
