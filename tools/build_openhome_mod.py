#!/usr/bin/env python3
"""Builds the OpenHome theme mods under mods/openhome/.

OpenHome (https://github.com/andrewbenington/OpenHome) themes itself with
CSS custom properties in src/ui/App.css: a teal ramp, a navy ramp, a
diagonal teal gradient behind everything, flat mint cards with a soft
translucent hairline, and a dark variant that swaps the mint cards for
navy ones over a navy-to-deep-teal gradient. This script carries those
values across into the only things a mod here can say -- a palette, the
chrome colours, and a background, border and ball picture -- and writes
one zip per variant, ready for OPTIONS -> MODS -> IMPORT MOD.

Nothing is copied out of OpenHome but colour values. Every picture is
drawn here, from those values, so there is no art in the zips for the
import scanner to object to; and the script checks that anyway, against
the same reference hashes the app scans with, before it writes a zip.

Usage:
    pip install pillow
    python3 tools/build_openhome_mod.py

Writes:
    mods/openhome/light/   mod.json + pictures, unpacked
    mods/openhome/dark/    the same, for the dark variant
    mods/openhome/OpenHome.zip
    mods/openhome/OpenHome-Dark.zip
"""

import json
import math
import os
import sys
import zipfile

from PIL import Image, ImageDraw

REPO_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(REPO_DIR, "mods", "openhome")
HASHES_PATH = os.path.join(REPO_DIR, "app", "src", "main", "assets", "mod_reference_hashes.json")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_mod_scanner_hashes import HASH_SIZE, dhash  # noqa: E402

# DHash.kt's FLAG_THRESHOLD: at or under it, the import refuses a picture
# (outright when it is closer still, pending a human look otherwise).
FLAG_THRESHOLD = 30

# OpenHome's own primitives, from src/ui/App.css.
TEAL_100 = "#DDFFDD"  # #dfd
TEAL_300 = "#A0DAC2"
TEAL_400 = "#7DCEAB"
TEAL_450 = "#53B4A5"
TEAL_500 = "#7D9A9C"
TEAL_600 = "#6C8D8D"
TEAL_700 = "#4A6C70"
TEAL_800 = "#015354"
NAVY_800 = "#36454E"
NAVY_900 = "#081721"
NAVY_950 = "#5D707F"
SIDEBAR_DARK = "#446666"

# Radix Themes, which OpenHome is built on (accentColor="red", grayColor="gray").
RADIX_GRAY_12_LIGHT = "#202020"
RADIX_GRAY_12_DARK = "#EEEEEE"
RADIX_GREEN_9 = "#30A46C"
RADIX_AMBER_9 = "#FFC53D"
RADIX_RED_9 = "#E5484D"

VARIANTS = {
    "light": {
        "zip": "OpenHome.zip",
        "manifest": {
            "name": "OpenHome",
            "author": "After OpenHome by Andrew Benington",
            "smoothing": True,
            "palette": {
                "id": "openhome",
                "label": "OPENHOME",
                "darkest": NAVY_900,
                "dark": TEAL_700,
                "light": TEAL_400,
                "lightest": TEAL_100,
                "surround": NAVY_950,
                # OpenHome shows every sprite in its own colours.
                "tintsSprites": False,
                "opacity": 1.0,
            },
            "chrome": {
                "ink": RADIX_GRAY_12_LIGHT,
                "panel": TEAL_100,  # .radix-themes --color-panel
                "shadow": TEAL_700,
                "muted": TEAL_500,
                "surround": NAVY_950,  # --color-surface
                "barFill": TEAL_300,  # --color-sidebar
                "barText": NAVY_900,
                "hpGreen": RADIX_GREEN_9,
                "hpYellow": RADIX_AMBER_9,
                "hpRed": RADIX_RED_9,
            },
            "background": {"asset": "background.png", "fit": "cover"},
            "ball": {"asset": "ball.png", "spins": False},
            "borderAsset": "border.png",
        },
        # --background-gradient: linear-gradient(310deg, teal-450 0%, teal-400 85%)
        "gradient": (310, [(TEAL_450, 0.0), (TEAL_400, 0.85)]),
        # --soft-border: 0.5px solid #4448
        "hairline": "#44444488",
        "ball_fill": "#DDFFDD30",
        "ball_ring": "#DDFFDD70",
    },
    "dark": {
        "zip": "OpenHome-Dark.zip",
        "manifest": {
            "name": "OpenHome Dark",
            "author": "After OpenHome by Andrew Benington",
            "smoothing": True,
            "palette": {
                "id": "openhome_dark",
                "label": "OPENHOME DARK",
                "darkest": NAVY_900,
                "dark": TEAL_800,
                "light": TEAL_450,
                "lightest": TEAL_100,
                "surround": NAVY_800,
                "tintsSprites": False,
                "opacity": 1.0,
            },
            "chrome": {
                "ink": RADIX_GRAY_12_DARK,
                "panel": NAVY_900,  # .radix-themes --color-panel, dark
                "shadow": TEAL_300,
                "muted": TEAL_600,
                "surround": NAVY_800,
                "barFill": SIDEBAR_DARK,  # --color-sidebar, dark
                "barText": RADIX_GRAY_12_DARK,
                "hpGreen": RADIX_GREEN_9,
                "hpYellow": RADIX_AMBER_9,
                "hpRed": RADIX_RED_9,
            },
            "background": {"asset": "background.png", "fit": "cover"},
            "ball": {"asset": "ball.png", "spins": False},
            "borderAsset": "border.png",
        },
        # --background-gradient: linear-gradient(355deg, navy-800 0%, teal-800 85%)
        "gradient": (355, [(NAVY_800, 0.0), (TEAL_800, 0.85)]),
        # --soft-border: 1px solid var(--color-translucent-dark) = #8888
        "hairline": "#88888888",
        "ball_fill": "#53B4A520",
        "ball_ring": "#53B4A560",
    },
}

BACKGROUND_SIZE = (720, 1280)
# A faint diagonal sheen over the gradient: +/-4 levels, well under what
# reads as a pattern. A bare two-stop gradient hashes as one long slope, and
# a slope that plain lands inside the scanner's "close to a sprite" band of
# the Pokedex frame's own hash; the sheen gives the hash some shape of its own.
SHEEN_AMPLITUDE = 4
SHEEN_WAVES = (2.5, 2.0)
# One border cell stands for one 8-pixel Game Boy tile. 32 source pixels a
# cell keeps the hairline crisp once it is scaled up to a phone's tile.
BORDER_CELL = 32
HAIRLINE_WIDTH = 2
BORDER_SHADE = NAVY_900
BORDER_SHADE_ALPHA = 0x20
BALL_SIZE = 512


def rgba(hex_colour: str) -> tuple:
    """#RRGGBB or #RRGGBBAA (CSS order) to an RGBA tuple."""
    h = hex_colour.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    a = int(h[6:8], 16) if len(h) == 8 else 255
    return (r, g, b, a)


def css_linear_gradient(size: tuple, angle_deg: float, stops: list) -> Image.Image:
    """A CSS linear-gradient(angle, stops...), as a browser lays one out.

    0deg points up and angles turn clockwise; the gradient line runs through
    the centre and is just long enough for its ends to touch the corners.
    Past the last stop the colour holds, as it does in CSS.
    """
    width, height = size
    angle = math.radians(angle_deg)
    dx, dy = math.sin(angle), -math.cos(angle)
    length = abs(width * dx) + abs(height * dy)
    colours = [(rgba(c)[:3], t) for c, t in stops]
    img = Image.new("RGB", size)
    px = img.load()
    cx, cy = width / 2, height / 2
    for y in range(height):
        for x in range(width):
            t = ((x + 0.5 - cx) * dx + (y + 0.5 - cy) * dy) / length + 0.5
            if t <= colours[0][1]:
                px[x, y] = colours[0][0]
                continue
            if t >= colours[-1][1]:
                px[x, y] = colours[-1][0]
                continue
            for (c0, t0), (c1, t1) in zip(colours, colours[1:]):
                if t0 <= t <= t1:
                    f = (t - t0) / (t1 - t0)
                    px[x, y] = tuple(round(a + (b - a) * f) for a, b in zip(c0, c1))
                    break
    return img


def add_sheen(img: Image.Image) -> Image.Image:
    width, height = img.size
    kx, ky = SHEEN_WAVES
    px = img.load()
    for y in range(height):
        for x in range(width):
            shift = SHEEN_AMPLITUDE * math.sin(2 * math.pi * (kx * x / width + ky * y / height))
            px[x, y] = tuple(max(0, min(255, round(c + shift))) for c in px[x, y])
    return img


def soft_border(hairline: str) -> Image.Image:
    """A 3x3 tileset of OpenHome's flat card edge: a soft hairline, square corners.

    OpenHome rounds its cards, but a window here is filled as a plain
    rectangle underneath its border, so a rounded corner would only draw a
    curve over a square fill. The hairline is the part that carries.

    Under it, a faint shade fading inward over one tile, the depth a Radix
    card's own shadow gives it. It is also what the import scanner sees: a
    lone 2-pixel line is skipped entirely by a plain bilinear shrink to the
    hash grid, leaving a near-blank picture that sits too close to the
    Pokedex frame's hash, where the shade is too broad to miss.
    """
    side = BORDER_CELL * 3
    img = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    px = img.load()
    r, g, b, _ = rgba(BORDER_SHADE)
    for y in range(side):
        for x in range(side):
            inset = min(x, y, side - 1 - x, side - 1 - y)
            if inset < BORDER_CELL:
                px[x, y] = (r, g, b, round(BORDER_SHADE_ALPHA * (1 - inset / BORDER_CELL) ** 2))
    line = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    ImageDraw.Draw(line).rectangle([0, 0, side - 1, side - 1], outline=rgba(hairline), width=HAIRLINE_WIDTH)
    return Image.alpha_composite(img, line)


def ball(fill: str, ring: str) -> Image.Image:
    """A soft disc with two rings, standing in for the turning Poke Ball.

    Only its top-left quarter shows, in the screen's bottom-right corner:
    a quiet translucent arc over the gradient, the way OpenHome keeps its
    background free of anything louder.
    """
    scale = 4  # Drawn large and shrunk, for a smooth edge.
    big = BALL_SIZE * scale
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    stroke = 6 * scale
    draw.ellipse([0, 0, big - 1, big - 1], fill=rgba(fill))
    draw.ellipse([0, 0, big - 1, big - 1], outline=rgba(ring), width=stroke)
    inset = big // 5
    draw.ellipse([inset, inset, big - 1 - inset, big - 1 - inset], outline=rgba(ring), width=stroke)
    return img.resize((BALL_SIZE, BALL_SIZE), Image.Resampling.LANCZOS)


def load_reference_hashes() -> dict:
    with open(HASHES_PATH) as f:
        return json.load(f)["hashes"]


def closest(hash_hex: str, references: dict) -> tuple:
    value = int(hash_hex, 16)
    best_key, best = None, HASH_SIZE * HASH_SIZE + 1
    for key, ref in references.items():
        distance = bin(value ^ int(ref, 16)).count("1")
        if distance < best:
            best_key, best = key, distance
    return best_key, best


def naive_bilinear_dhash(img: Image.Image) -> str:
    """The hash as a plain bilinear shrink computes it, one 2x2 sample a cell.

    Pillow's LANCZOS (what [dhash] uses) looks at every source pixel, but
    Android's createScaledBitmap, which the import itself uses, samples
    only a few; fine detail that one sees and the other misses can move a
    picture across the threshold. A picture has to pass both.
    """
    rgba_img = img.convert("RGBA")
    white = Image.new("RGBA", rgba_img.size, (255, 255, 255, 255))
    grey = Image.alpha_composite(white, rgba_img).convert("L")
    width, height = grey.size
    px = grey.load()
    cols, rows = HASH_SIZE + 1, HASH_SIZE
    lum = []
    for row in range(rows):
        for col in range(cols):
            fx = (col + 0.5) * width / cols - 0.5
            fy = (row + 0.5) * height / rows - 0.5
            x0 = max(0, min(width - 1, math.floor(fx)))
            y0 = max(0, min(height - 1, math.floor(fy)))
            x1, y1 = min(width - 1, x0 + 1), min(height - 1, y0 + 1)
            ax, ay = min(max(fx - x0, 0), 1), min(max(fy - y0, 0), 1)
            top = px[x0, y0] * (1 - ax) + px[x1, y0] * ax
            bottom = px[x0, y1] * (1 - ax) + px[x1, y1] * ax
            lum.append(top * (1 - ay) + bottom * ay)
    bits = "".join(
        "1" if lum[row * cols + col + 1] > lum[row * cols + col] else "0"
        for row in range(rows)
        for col in range(HASH_SIZE)
    )
    return "%064x" % int(bits, 2)


def build(variant: str, spec: dict, references: dict) -> None:
    folder = os.path.join(OUT_DIR, variant)
    os.makedirs(folder, exist_ok=True)
    manifest = spec["manifest"]
    angle, stops = spec["gradient"]
    pictures = {
        manifest["background"]["asset"]: add_sheen(css_linear_gradient(BACKGROUND_SIZE, angle, stops)),
        manifest["borderAsset"]: soft_border(spec["hairline"]),
        manifest["ball"]["asset"]: ball(spec["ball_fill"], spec["ball_ring"]),
    }

    for name, img in pictures.items():
        key, distance = min(
            (closest(h, references) for h in (dhash(img), naive_bilinear_dhash(img))),
            key=lambda match: match[1],
        )
        if distance <= FLAG_THRESHOLD:
            sys.exit(f"{variant}/{name} is {distance}/256 from {key}; the import would refuse it.")
        img.save(os.path.join(folder, name), optimize=True)
        print(f"  {variant}/{name}: nearest reference {distance}/256 away, clean")

    with open(os.path.join(folder, "mod.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")

    # Fixed timestamps, so rebuilding unchanged pictures gives the same zip.
    zip_path = os.path.join(OUT_DIR, spec["zip"])
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name in ["mod.json", *pictures]:
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(os.path.join(folder, name), "rb") as f:
                zf.writestr(info, f.read())
    print(f"Wrote {os.path.relpath(zip_path, REPO_DIR)}")


def main() -> None:
    references = load_reference_hashes()
    for variant, spec in VARIANTS.items():
        build(variant, spec, references)


if __name__ == "__main__":
    main()
