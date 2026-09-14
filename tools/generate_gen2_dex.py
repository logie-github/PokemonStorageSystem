#!/usr/bin/env python3
"""Generate the app's Generation II Pokédex entries from pret's checkouts.

Each of the three cartridges prints its own words. Gold and Silver keep theirs
in one repository under data/pokemon/dex_entries/gold and .../silver, and
Crystal rewrote most of them again in a repository of its own, so all three are
read and kept side by side — a Pokémon is read in the words of the game it came
out of, which is the same rule the Generation I table follows for Red and
Yellow.

Each entry file holds the classification, the height as feet*100 + inches, the
weight in tenths of a pound, and the two pages of three lines the Pokédex
prints.

Usage:
    python3 tools/generate_gen2_dex.py /path/to/pokegold /path/to/pokecrystal

Writes:
    app/src/main/java/com/logie/gen1storage/pokemon/Gen2DexTable.kt
"""

import os
import re
import sys

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app/src/main/java/com/logie/gen1storage/pokemon",
)


def species_order(root):
    """The base_stats include list, which is the species order the games use."""
    path = os.path.join(root, "data/pokemon/base_stats.asm")
    out = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            m = re.match(r'\s*INCLUDE\s+"data/pokemon/base_stats/([^"]+)\.asm"', line)
            if m:
                out.append(m.group(1))
    return out


def species_id(root, stem):
    """The constant at the top of a species' base_stats file."""
    path = os.path.join(root, "data/pokemon/base_stats/%s.asm" % stem)
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            m = re.match(r"\s*db\s+(\w+)\s*;", line)
            if m:
                return m.group(1)
    raise SystemExit("no species constant in %s" % path)


def parse_entry(path):
    """One dex entry file: its classification, size, and the lines it prints."""
    category, height, weight, lines = None, None, None, []
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.rstrip()
            m = re.match(r'\s*db\s+"([^"]*)@"\s*;\s*species name', line)
            if m:
                category = m.group(1)
                continue
            m = re.match(r"\s*dw\s+(\d+)\s*,\s*(\d+)", line)
            if m:
                height, weight = int(m.group(1)), int(m.group(2))
                continue
            m = re.match(r'\s*(db|next|page)\s+"(.*)"\s*$', line)
            if m:
                # The Pokédex turns the page here; the app draws the break as
                # a blank line rather than as a second screen, exactly as the
                # Generation I table does.
                if m.group(1) == "page":
                    lines.append("")
                lines.append(m.group(2))
    if category is None or height is None:
        raise SystemExit("%s: no classification or size" % path)
    return category, height, weight, lines


def clean(line):
    """The cartridge's own text escapes, as the app's font can show them."""
    return (
        line.replace("<PKMN>", "POKéMON")
        .replace("<PLAYER>", "PLAYER")
        .replace("<RIVAL>", "RIVAL")
        .replace("<POKE>", "POKé")
        .replace("#", "POKé")
        .replace("@", "")
    )


def kotlin_string(value):
    escaped = (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
    )
    return '"%s"' % escaped


def entries(root, folder, stems):
    out = {}
    for stem in stems:
        path = os.path.join(root, "data/pokemon/dex_entries", folder, "%s.asm" % stem) \
            if folder else os.path.join(root, "data/pokemon/dex_entries", "%s.asm" % stem)
        category, height, weight, lines = parse_entry(path)
        out[stem] = {
            "category": clean(category),
            "feet": height // 100,
            "inches": height % 100,
            "weight": weight,
            "text": "\n".join(clean(line) for line in lines),
        }
    return out


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    gold_root, crystal_root = sys.argv[1], sys.argv[2]

    stems = species_order(crystal_root)
    ids = [species_id(crystal_root, stem) for stem in stems]
    gold = entries(gold_root, "gold", stems)
    silver = entries(gold_root, "silver", stems)
    crystal = entries(crystal_root, None, stems)

    def page(entry):
        # The three cartridges do not only differ in their words: GOLD calls
        # NATU a LITTLEBIRD where CRYSTAL gives it the space, GOLD has ENTEI
        # four inches taller than CRYSTAL does, and SILVER does the same to
        # TYRANITAR. So each game's page is kept whole rather than one set of
        # facts with three texts hung off it.
        return "Gen2DexPage({cat}, {feet}, {inches}, {weight}, {text})".format(
            cat=kotlin_string(entry["category"]),
            feet=entry["feet"], inches=entry["inches"], weight=entry["weight"],
            text=kotlin_string(entry["text"]),
        )

    rows = []
    for index, stem in enumerate(stems):
        rows.append(
            "    Gen2DexEntry(\n"
            "        {id},\n"
            "        {gold},\n"
            "        {silver},\n"
            "        {crystal},\n"
            "    ),".format(
                id=kotlin_string(ids[index]),
                gold=page(gold[stem]),
                silver=page(silver[stem]),
                crystal=page(crystal[stem]),
            )
        )

    gold_head = os.popen("git -C %s rev-parse HEAD" % gold_root).read().strip()
    crystal_head = os.popen("git -C %s rev-parse HEAD" % crystal_root).read().strip()
    out = os.path.join(OUT_DIR, "Gen2DexTable.kt")
    with open(out, "w", encoding="utf-8") as handle:
        handle.write("// GENERATED FILE - do not edit by hand.\n")
        handle.write("// Source: pret/pokegold @ %s and\n" % gold_head)
        handle.write("//         pret/pokecrystal @ %s,\n" % crystal_head)
        handle.write("//         via tools/generate_gen2_dex.py.\n")
        handle.write("package com.logie.gen1storage.pokemon\n\n")
        handle.write("internal val GEN2_DEX_TABLE: List<Gen2DexEntry> = listOf(\n")
        handle.write("\n".join(rows))
        handle.write("\n)\n")
    print("wrote %s (%d entries)" % (out, len(rows)))


if __name__ == "__main__":
    main()
