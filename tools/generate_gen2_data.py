#!/usr/bin/env python3
"""Generate the app's Generation II species table from pret/pokecrystal.

The sibling of tools/generate_gen1_data.py, and for the same reason: the
Gen1Recomp Generation II save stores a Pokemon's species as a pokecrystal
constant name, so the app's table is read out of the assembly rather than
transcribed, and provably matches the one the game builds from a ROM.

Generation II splits Special into Special Attack and Special Defense, and
rebalanced a number of Generation I species along the way.  The two tables
therefore live side by side rather than one replacing the other: a Pokemon
is read with its own generation's numbers.

Usage:
    python3 tools/generate_gen2_data.py /path/to/pokecrystal

Writes:
    app/src/main/java/com/logie/gen1storage/pokemon/Gen2SpeciesTable.kt
"""

import os
import re
import sys

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "app/src/main/java/com/logie/gen1storage/pokemon")


def read_asm(path):
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.split(";")[0].rstrip()
            if line.strip():
                yield line.strip()


def includes(root):
    """The base_stats files, in the order the game's table lists them."""
    path = os.path.join(root, "data/pokemon/base_stats.asm")
    out = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            m = re.match(r'\s*INCLUDE\s+"([^"]+)"', line)
            if m:
                out.append(m.group(1))
    return out


def names(root):
    """PokemonNames, which is indexed by the species constant."""
    path = os.path.join(root, "data/pokemon/names.asm")
    out = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            m = re.match(r'\s*dname\s+"([^"]*)"', line)
            if m:
                out.append(m.group(1))
    return out


# PSYCHIC is spelled PSYCHIC_TYPE in the assembly because PSYCHIC is also a
# move; the app says PSYCHIC, as Generation I's table does.
def type_name(token):
    return "PSYCHIC" if token == "PSYCHIC_TYPE" else token


# A species' likelihood of being female, as a byte out of 255 -
# constants/pokemon_data_constants.asm, `percent EQUS "* $ff / 100"`. Read
# out of the constant name rather than computed, so a mistake in either place
# would show up as a mismatch instead of agreeing with itself.
GENDER_RATIOS = {
    "GENDER_F0": 0,
    "GENDER_F12_5": 31,
    "GENDER_F25": 63,
    "GENDER_F50": 127,
    "GENDER_F75": 191,
    "GENDER_F100": 254,
    "GENDER_UNKNOWN": 255,
}


def parse_base_stats(path):
    lines = list(read_asm(path))
    out = {}

    m = re.match(r"db\s+(\w+)$", lines[0])
    if not m:
        raise SystemExit("no species constant at the top of %s" % path)
    out["id"] = m.group(1)

    for line in lines[1:]:
        m = re.match(r"db\s+(\d+),\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)$", line)
        if m and "stats" not in out:
            (out["hp"], out["attack"], out["defense"], out["speed"],
             out["specialAttack"], out["specialDefense"]) = [int(x) for x in m.groups()]
            out["stats"] = True
            continue
        m = re.match(r"db\s+([A-Z_0-9]+),\s*([A-Z_0-9]+)$", line)
        if m and "type1" not in out and not m.group(1).startswith("NO_ITEM"):
            out["type1"] = type_name(m.group(1))
            out["type2"] = type_name(m.group(2))
            continue
        m = re.match(r"db\s+(GENDER_\w+)$", line)
        if m:
            if m.group(1) not in GENDER_RATIOS:
                raise SystemExit("%s: unknown gender constant %s" % (path, m.group(1)))
            out["gender"] = GENDER_RATIOS[m.group(1)]
            continue
        m = re.match(r"db\s+(\d+)$", line)
        if m:
            if "catchRate" not in out:
                out["catchRate"] = int(m.group(1))
            elif "baseExp" not in out:
                out["baseExp"] = int(m.group(1))
            continue
        m = re.match(r"db\s+GROWTH_(\w+)$", line)
        if m:
            out["growth"] = m.group(1)
            continue

    for key in ("id", "hp", "type1", "catchRate", "baseExp", "growth", "gender"):
        if key not in out:
            raise SystemExit("%s: missing %s" % (path, key))
    return out


def kotlin_string(text):
    return '"%s"' % text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    root = sys.argv[1]

    head = os.popen("git -C %s rev-parse HEAD" % root).read().strip()
    display = names(root)
    rows = []
    for index, include in enumerate(includes(root), start=1):
        stats = parse_base_stats(os.path.join(root, include))
        second = stats["type2"] if stats["type2"] != stats["type1"] else None
        rows.append(
            "    Gen2Species({id}, {dex}, {name}, {hp}, {atk}, {dfn}, {spd}, {sat}, {sdf}, "
            "{catch}, {exp}, {t1}, {t2}, {growth}, {gender}),".format(
                id=kotlin_string(stats["id"]),
                dex=index,
                name=kotlin_string(display[index - 1]),
                hp=stats["hp"], atk=stats["attack"], dfn=stats["defense"],
                spd=stats["speed"], sat=stats["specialAttack"], sdf=stats["specialDefense"],
                catch=stats["catchRate"], exp=stats["baseExp"],
                t1=kotlin_string(stats["type1"]),
                t2=kotlin_string(second) if second else "null",
                growth=kotlin_string(stats["growth"]),
                gender=stats["gender"],
            )
        )

    out = os.path.join(OUT_DIR, "Gen2SpeciesTable.kt")
    with open(out, "w", encoding="utf-8") as handle:
        handle.write("// GENERATED FILE - do not edit by hand.\n")
        handle.write("// Source: pret/pokecrystal @ %s, via tools/generate_gen2_data.py.\n" % head)
        handle.write("// Species ids are the pokecrystal constant names Gen1Recomp writes into\n")
        handle.write("// a Generation II save.lua (see gen1recomp tools/rom_manifest_*.json,\n")
        handle.write("// constants.speciesOrder).\n")
        handle.write("package com.logie.gen1storage.pokemon\n\n")
        handle.write("internal val GEN2_SPECIES_TABLE: List<Gen2Species> = listOf(\n")
        handle.write("\n".join(rows))
        handle.write("\n)\n")
    print("wrote %s (%d species)" % (out, len(rows)))


if __name__ == "__main__":
    main()
