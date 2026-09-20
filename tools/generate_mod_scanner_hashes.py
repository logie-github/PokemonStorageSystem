#!/usr/bin/env python3
"""Generate the mod scanner's reference hash database from pret's own decompilations.

A mod that recolours or redraws a sprite is welcome; a mod that ships the
cartridge's own art dumped out of pret's decompilations is not, and the only
way to tell the two apart at import time is to compare against the real
thing. Rather than shipping any of pret's art in this repository or the app
itself, this script computes a 256-bit perceptual hash (dHash) of every
sprite pret's own repositories carry and writes only the hashes out -- the
same approach https://github.com/1Jamie/mod-scanner uses to police mods for
the wider Game Boy decompilation scene, restricted here to the four games
this app actually reads a ROM for.

A hash is not the art: nothing here can be turned back into a picture, so
the reference database this writes carries no copyrighted material itself,
the same way ModAssetScanner.kt that reads it never holds a reference image
either -- only ever a number to compare a mod's own hash against.

Usage:
    python3 tools/generate_mod_scanner_hashes.py [/path/to/scratch]

Writes:
    app/src/main/assets/mod_reference_hashes.json
"""

import json
import os
import subprocess
import sys

from PIL import Image

APP_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app")
OUT_PATH = os.path.join(APP_DIR, "src/main/assets/mod_reference_hashes.json")

# The four games this app reads sprites from a ROM for. pret also keeps
# pokefirered and pokeemerald, but this app has no use for Generation III
# and a mod for it could never collide with a Game Boy sprite anyway.
SOURCES = [
    ("pokered", "pret/pokered"),
    ("pokeyellow", "pret/pokeyellow"),
    ("pokegold", "pret/pokegold"),
    ("pokecrystal", "pret/pokecrystal"),
]

# Same three folders config.yaml's own pret_sources list for these games:
# the Pokémon, the trainers, and the sprite sheets neither of those two
# folders holds on their own.
IMAGE_DIRS = ["gfx/sprites", "gfx/pokemon", "gfx/trainers"]

# 16x16 = 256 bits, matching mod-scanner's own image_rules.hash_size, so a
# mod's own hash and this database's are directly comparable.
HASH_SIZE = 16


def dhash(img: Image.Image) -> str:
    """The same 256-bit difference hash mod-scanner's image_scanner.py computes.

    Composited onto white before greyscale so a transparent sprite's own
    background never collapses to black and swamps the comparison, then
    resized one pixel wider than tall so each of the 256 bits is just
    "is this pixel darker than the one to its left".
    """
    rgba = img.convert("RGBA") if img.mode != "RGBA" else img
    white = Image.new("RGBA", rgba.size, (255, 255, 255, 255))
    grey = Image.alpha_composite(white, rgba).convert("L")
    resized = grey.resize((HASH_SIZE + 1, HASH_SIZE), Image.Resampling.LANCZOS)
    pixels = list(resized.getdata())
    bits = []
    for row in range(HASH_SIZE):
        offset = row * (HASH_SIZE + 1)
        for col in range(HASH_SIZE):
            bits.append(pixels[offset + col + 1] > pixels[offset + col])
    bit_string = "".join("1" if b else "0" for b in bits)
    return "%064x" % int(bit_string, 2)


def clone(repo: str, dest: str) -> None:
    if os.path.isdir(dest):
        return
    subprocess.run(
        ["git", "clone", "--depth", "1", f"https://github.com/{repo}", dest],
        check=True,
    )


def index_source(name: str, root: str, hashes: dict) -> int:
    found = 0
    for image_dir in IMAGE_DIRS:
        base = os.path.join(root, image_dir)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirs, files in os.walk(base):
            for filename in sorted(files):
                if not filename.lower().endswith(".png"):
                    continue
                path = os.path.join(dirpath, filename)
                try:
                    img = Image.open(path)
                    img.load()
                except Exception:
                    continue
                # The same filters pret_fetcher.py applies: an icon slice
                # too small to mean anything, or a solid block with no
                # picture in it at all, has nothing worth hashing.
                if img.width < 16 or img.height < 16:
                    continue
                extrema = img.convert("L").getextrema()
                if extrema[0] == extrema[1]:
                    continue
                rel = os.path.relpath(path, root).replace(os.sep, "/")
                hashes[f"{name}/{rel}"] = dhash(img)
                found += 1
    return found


def main() -> int:
    scratch = sys.argv[1] if len(sys.argv) > 1 else "/tmp/mod-scanner-reference-sources"
    os.makedirs(scratch, exist_ok=True)

    hashes: dict[str, str] = {}
    for name, repo in SOURCES:
        dest = os.path.join(scratch, name)
        print(f"Fetching {repo}...")
        clone(repo, dest)
        found = index_source(name, dest, hashes)
        print(f"  indexed {found} sprites from {name}")

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as handle:
        json.dump(
            {"hashSize": HASH_SIZE, "hashes": dict(sorted(hashes.items()))},
            handle,
            indent=1,
            sort_keys=True,
        )
        handle.write("\n")

    print(f"Wrote {len(hashes)} reference hashes to {OUT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
