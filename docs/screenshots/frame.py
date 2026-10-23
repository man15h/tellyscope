#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Puts the README's screenshots in a phone frame.

Render the screenshot tests first, then run this from the repository root:

    ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest' -Proborazzi.test.record=true
    python3 docs/screenshots/frame.py

Needs Pillow (pip install pillow).
"""
from pathlib import Path

from PIL import Image, ImageDraw

SRC = Path("app/build/outputs/roborazzi")
OUT = Path(__file__).parent

# Output name, the screenshot it comes from, and how far down the page it is scrolled, in dp.
# Tall renders are cut to one phone screen; a scrolled one keeps its top bar, as on a phone.
SHOTS = {
    "tv-page": ("04_device", 0),
    "tv-page-dark": ("04_device_dark", 0),
    "debloat": ("20_readme_debloat", 0),
    "debloat-confirm": ("21_readme_confirm", 0),
    "advanced": ("17_advanced", 0),
    "scan": ("01_scan_in_progress", 0),
    "install": ("10_sideload_new", 0),
    "history": ("22_readme_history", 300),
}

DP = 3  # the tests render at xxhdpi
SCREEN_DP = 891  # the tests' 411 x 891 dp phone
TOP_BAR_DP = 64

WIDTH = 540  # screen width in the output; GitHub shows it at about half that
STATUS = 30  # status bar height, where the camera sits
BEZEL = 14
RADIUS = 46


def frame(src: Path, scroll_dp: int) -> Image.Image:
    shot = Image.open(src).convert("RGB")
    phone_h, bar, scroll = SCREEN_DP * DP, TOP_BAR_DP * DP, scroll_dp * DP
    if scroll:
        page = shot.crop((0, bar + scroll, shot.width, scroll + phone_h))
        shot = shot.crop((0, 0, shot.width, phone_h))
        shot.paste(page, (0, bar))
    else:
        shot = shot.crop((0, 0, shot.width, min(shot.height, phone_h)))
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
    for name, (src, scroll) in SHOTS.items():
        frame(SRC / f"{src}.png", scroll).save(OUT / f"{name}.png", optimize=True)
        print(f"{name}.png")
