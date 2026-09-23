#!/usr/bin/env python3
"""Builds the Pokedex theme mod under mods/pokedex/.

Not a recreation of any one game's own Pokedex screen -- there is no ROM
art in here for the import scanner to object to, and there never should
be. It is this app's own frame, chrome and background pictures, drawn from
scratch, in the colours the device the games have always called a Pokedex
is known for: red shell, a pale screen, and the blue lens in the corner.

Usage:
    pip install pillow
    python3 tools/build_pokedex_mod.py

Writes:
    mods/pokedex/          mod.json + pictures, unpacked
    mods/pokedex/Pokedex.zip
"""

import json
import math
import os
import sys
import zipfile

from PIL import Image, ImageDraw

REPO_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(REPO_DIR, "mods", "pokedex")
HASHES_PATH = os.path.join(REPO_DIR, "app", "src", "main", "assets", "mod_reference_hashes.json")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_mod_scanner_hashes import HASH_SIZE, dhash  # noqa: E402

# DHash.kt's FLAG_THRESHOLD: at or under it, the import refuses a picture.
FLAG_THRESHOLD = 30

# The device's own colours, not any one cartridge's rendering of it.
SHELL_RED = "#D8241C"
SHELL_RED_DEEP = "#7A1212"
SHELL_MAROON = "#3B0A0A"
SCREEN_PALE = "#F7EFE3"
LENS_BLUE = "#2D6CB5"
LENS_BLUE_DEEP = "#173F73"
LIGHT_GREEN = "#3FBF63"
LIGHT_YELLOW = "#F5D033"
LIGHT_RED = "#E23B3B"
INK_MAROON = "#2A0808"

MANIFEST = {
    "name": "Pokedex",
    "author": "Logie",
    "smoothing": True,
    "palette": {
        "id": "pokedex",
        "label": "POKEDEX",
        "darkest": SHELL_MAROON,
        "dark": SHELL_RED_DEEP,
        "light": "#FF6B6B",
        "lightest": "#FFEAEA",
        "surround": SHELL_RED_DEEP,
        # A dex reads a Pokemon's own colours; it does not recolour them.
        "tintsSprites": False,
        "opacity": 1.0,
    },
    "chrome": {
        "ink": INK_MAROON,
        "panel": SCREEN_PALE,
        "shadow": SHELL_RED_DEEP,
        "muted": "#9C6B6B",
        "surround": SHELL_RED_DEEP,
        "barFill": LENS_BLUE,
        "barText": "#F5F5F5",
        "hpGreen": "#30A46C",
        "hpYellow": "#FFC53D",
        "hpRed": "#E5484D",
    },
    "background": {"asset": "background.png", "fit": "cover"},
    "ball": {"asset": "ball.png", "spins": True},
    "borderAsset": "border.png",
}

BACKGROUND_SIZE = (720, 1280)
# A faint diagonal sheen over the gradient, the same reason
# build_openhome_mod.py carries one: a bare gradient hashes as one long
# featureless slope, close enough to land inside the scanner's flag band.
SHEEN_AMPLITUDE = 4
SHEEN_WAVES = (2.5, 2.0)
# One border cell stands for one 8-pixel Game Boy tile; 32 source pixels a
# cell keeps the bezel line crisp once it is scaled up to a phone's tile.
BORDER_CELL = 32
BALL_SIZE = 512


def rgba(hex_colour: str, alpha: int = 255) -> tuple:
    h = hex_colour.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    a = int(h[6:8], 16) if len(h) == 8 else alpha
    return (r, g, b, a)


def css_linear_gradient(size: tuple, angle_deg: float, stops: list) -> Image.Image:
    """A linear-gradient(angle, stops...), as a browser lays one out."""
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


def background() -> Image.Image:
    """The shell's own gradient, with the two details that say "dex" rather
    than just "red": the blue lens glow low in one corner, and a run of
    small round indicator lights along the top the way a real one's status
    strip does."""
    img = add_sheen(css_linear_gradient(BACKGROUND_SIZE, 200, [(SHELL_RED, 0.0), (SHELL_MAROON, 0.9)]))
    width, height = img.size

    # The lens: a soft blue disc bleeding off the bottom-left corner, the
    # one shape everyone who has seen a Pokedex on screen recognises before
    # anything else about it.
    scale = 4
    lens_d = int(width * 0.62)
    big = lens_d * scale
    lens = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ldraw = ImageDraw.Draw(lens)
    for r in range(big // 2, 0, -2 * scale):
        t = 1 - r / (big / 2)
        colour = rgba(LENS_BLUE, alpha=round(150 * t))
        ldraw.ellipse([big // 2 - r, big // 2 - r, big // 2 + r, big // 2 + r], fill=colour)
    ldraw.ellipse([2 * scale, 2 * scale, big - 2 * scale, big - 2 * scale], outline=rgba(LENS_BLUE_DEEP, 220), width=5 * scale)
    lens = lens.resize((lens_d, lens_d), Image.Resampling.LANCZOS)
    img = img.convert("RGBA")
    img.alpha_composite(lens, (-lens_d // 3, height - int(lens_d * 0.78)))

    # Three small status lights, upper right -- green, yellow, red, the
    # cluster every dex and every Poke Ball-adjacent console prop carries.
    draw = ImageDraw.Draw(img)
    light_r = int(width * 0.018)
    cx0 = width - int(width * 0.10)
    cy = int(height * 0.06)
    for i, colour in enumerate((LIGHT_GREEN, LIGHT_YELLOW, LIGHT_RED)):
        cx = cx0 - i * light_r * 3
        draw.ellipse([cx - light_r, cy - light_r, cx + light_r, cy + light_r], fill=rgba(colour))
        draw.ellipse(
            [cx - light_r, cy - light_r, cx + light_r * 0.2, cy + light_r * 0.2],
            fill=rgba("#FFFFFF", alpha=90),
        )
    return img.convert("RGB")


def border() -> Image.Image:
    """A dark bezel with a thin blue rim light along its inner edge, and a
    screw dot in each corner -- the frame a device's own screen sits in,
    not the games' own cartridge border."""
    side = BORDER_CELL * 3
    scale = 4
    big = side * scale
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    px = img.load()
    bezel = rgba(SHELL_MAROON)
    rim = rgba(LENS_BLUE)
    cell = BORDER_CELL * scale
    for y in range(big):
        for x in range(big):
            inset = min(x, y, big - 1 - x, big - 1 - y)
            if inset >= cell:
                continue
            # A solid bezel for most of the tile's depth, with a two
            # source-pixel rim light riding just inside its inner face.
            band = cell - inset
            if band <= 3 * scale:
                f = 1 - (band - 1 * scale) / (2 * scale)
                f = max(0.0, min(1.0, f))
                px[x, y] = tuple(round(a + (b - a) * f) for a, b in zip(bezel, rim))
            else:
                px[x, y] = bezel
    img = img.resize((side, side), Image.Resampling.LANCZOS)

    draw = ImageDraw.Draw(img)
    screw = rgba(SHELL_RED_DEEP)
    screw_r = 3
    half = BORDER_CELL // 2
    for cx, cy in ((half, half), (side - half, half), (half, side - half), (side - half, side - half)):
        draw.ellipse([cx - screw_r, cy - screw_r, cx + screw_r, cy + screw_r], fill=screw)
    return img


def ball() -> Image.Image:
    """A plain red-and-white Poke Ball, this app's own colours rather than
    any cartridge's sprite sheet."""
    scale = 4
    big = BALL_SIZE * scale
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    r = big // 2 - 4 * scale
    cx = cy = big // 2
    box = [cx - r, cy - r, cx + r, cy + r]
    draw.pieslice(box, 180, 360, fill=rgba(SHELL_RED))
    draw.pieslice(box, 0, 180, fill=rgba("#F5F5F5"))
    band = 5 * scale
    draw.rectangle([cx - r, cy - band, cx + r, cy + band], fill=rgba(SHELL_MAROON))
    draw.ellipse(box, outline=rgba(SHELL_MAROON), width=4 * scale)
    hub_r = r // 4
    draw.ellipse([cx - hub_r, cy - hub_r, cx + hub_r, cy + hub_r], fill=rgba("#F5F5F5"), outline=rgba(SHELL_MAROON), width=3 * scale)
    hub_inner = hub_r // 2
    draw.ellipse(
        [cx - hub_inner, cy - hub_inner, cx + hub_inner, cy + hub_inner],
        fill=rgba(SHELL_MAROON),
    )
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
    """The hash as a plain bilinear shrink computes it -- see the same
    function in build_openhome_mod.py for why a picture has to pass both."""
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


def preview(bg: Image.Image, brd: Image.Image, bl: Image.Image) -> Image.Image:
    """A quick mockup, the same way mods/openhome/preview.png shows the
    look before anyone imports it: the background, a window using the
    bezel, and the ball resting in its corner."""
    canvas = bg.copy().convert("RGBA")
    width, height = canvas.size
    win = Image.new("RGBA", (int(width * 0.8), int(height * 0.28)), rgba(SCREEN_PALE))
    wx, wy = (width - win.width) // 2, int(height * 0.36)
    canvas.alpha_composite(win, (wx, wy))
    cell = brd.width // 3
    tile = 24
    corners = [(0, 0), (2, 0), (0, 2), (2, 2)]
    positions = [
        (wx - tile, wy - tile),
        (wx + win.width, wy - tile),
        (wx - tile, wy + win.height),
        (wx + win.width, wy + win.height),
    ]
    for (cxg, cyg), (px_, py_) in zip(corners, positions):
        piece = brd.crop((cxg * cell, cyg * cell, (cxg + 1) * cell, (cyg + 1) * cell)).resize((tile, tile))
        canvas.alpha_composite(piece, (px_, py_))
    ball_small = bl.resize((80, 80), Image.Resampling.LANCZOS)
    canvas.alpha_composite(ball_small, (width - 96, height - 96))
    return canvas.convert("RGB")


def build() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    references = load_reference_hashes()
    pictures = {
        MANIFEST["background"]["asset"]: background(),
        MANIFEST["borderAsset"]: border(),
        MANIFEST["ball"]["asset"]: ball(),
    }

    for name, img in pictures.items():
        key, distance = min(
            (closest(h, references) for h in (dhash(img), naive_bilinear_dhash(img))),
            key=lambda match: match[1],
        )
        if distance <= FLAG_THRESHOLD:
            sys.exit(f"pokedex/{name} is {distance}/256 from {key}; the import would refuse it.")
        img.save(os.path.join(OUT_DIR, name), optimize=True)
        print(f"  pokedex/{name}: nearest reference {distance}/256 away, clean")

    with open(os.path.join(OUT_DIR, "mod.json"), "w") as f:
        json.dump(MANIFEST, f, indent=2)
        f.write("\n")

    preview(pictures[MANIFEST["background"]["asset"]], pictures[MANIFEST["borderAsset"]], pictures[MANIFEST["ball"]["asset"]]).save(
        os.path.join(OUT_DIR, "preview.png"), optimize=True
    )

    zip_path = os.path.join(OUT_DIR, "Pokedex.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name in ["mod.json", *pictures]:
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(os.path.join(OUT_DIR, name), "rb") as f:
                zf.writestr(info, f.read())
    print(f"Wrote {os.path.relpath(zip_path, REPO_DIR)}")


if __name__ == "__main__":
    build()
