#!/usr/bin/env python3
"""Generate the app's Generation I species and move tables from pret/pokered.

The Gen1Recomp save format stores species and moves as pokered constant
names (see gen1recomp tools/extract/pokemon.py and tools/extract/moves.py,
which build their tables the same way from the same files).  Rather than
transcribing 151 species and 165 moves by hand, this script reads the
assembly sources directly so the app's tables provably match the ones the
game builds from a player's ROM.

Usage:
    python3 tools/generate_gen1_data.py /path/to/pokered

Writes:
    app/src/main/java/com/logie/gen1storage/pokemon/Gen1SpeciesTable.kt
    app/src/main/java/com/logie/gen1storage/pokemon/Gen1MoveTable.kt
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
                yield line


def parse_number(token):
    token = token.strip()
    if token.startswith("$"):
        return int(token[1:], 16)
    if token.startswith("%"):
        return int(token[1:], 2)
    return int(token)


def split_args(text):
    return [a.strip() for a in text.split(",")]


def const_block(path, stop_at=None):
    """constants/*.asm const_def / const list, in declaration order."""
    names, value = [], None
    for line in read_asm(path):
        s = line.strip()
        m = re.match(r"const_def(?:\s+(\$?-?\w+))?$", s)
        if m:
            value = parse_number(m.group(1)) if m.group(1) else 0
            continue
        m = re.match(r"const_next\s+(\$?\w+)$", s)
        if m:
            while len(names) < parse_number(m.group(1)):
                names.append(None)
            value = parse_number(m.group(1))
            continue
        m = re.match(r"const_skip(?:\s+(\d+))?$", s)
        if m and value is not None:
            value += int(m.group(1) or 1)
            continue
        m = re.match(r"const\s+(\w+)", s)
        if m and value is not None:
            while len(names) < value:
                names.append(None)
            names.append(m.group(1))
            value += 1
            if stop_at and m.group(1) == stop_at:
                break
    return names


def parse_names(path):
    names = []
    for line in read_asm(path):
        m = re.match(r'dname\s+"([^"]*)"', line.strip())
        if m:
            names.append(m.group(1))
    return names


def parse_base_stats(path):
    out, db_index = {}, 0
    in_tmhm = False
    for line in read_asm(path):
        s = line.strip()
        if re.match(r"tmhm\s", s):
            in_tmhm = True
            continue
        if in_tmhm:
            if s.startswith(("db", "dw", "INCBIN")):
                in_tmhm = False
            else:
                continue
        m = re.match(r"db\s+(.*)$", s)
        if not m:
            continue
        args = split_args(m.group(1))
        if db_index == 0:
            out["dexConst"] = args[0]
        elif db_index == 1:
            st = [parse_number(a) for a in args]
            out["baseStats"] = dict(zip(("hp", "attack", "defense", "speed", "special"), st))
        elif db_index == 2:
            out["types"] = args if args[0] != args[1] else [args[0]]
        elif db_index == 3:
            out["catchRate"] = parse_number(args[0])
        elif db_index == 4:
            out["baseExp"] = parse_number(args[0])
        elif db_index == 6:
            out["growthRate"] = args[0].removeprefix("GROWTH_")
        db_index += 1
    return out


def parse_moves(path):
    """data/moves/moves.asm: `move NAME, EFFECT, POWER, TYPE, ACCURACY, PP`."""
    rows = []
    for line in read_asm(path):
        m = re.match(r"\s*move\s+(.*)$", line)
        if not m:
            continue
        args = split_args(m.group(1))
        rows.append({
            "id": args[0],
            "power": parse_number(args[2]),
            "type": args[3],
            "accuracy": parse_number(args[4]),
            "pp": parse_number(args[5]),
        })
    return rows


def kotlin_string(value):
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return f'"{escaped}"'


def type_label(constant):
    return constant.removesuffix("_TYPE").replace("_", " ")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    pokered = sys.argv[1]
    commit = os.popen(f"git -C {pokered} rev-parse HEAD").read().strip() or "unknown"

    species_order = const_block(os.path.join(pokered, "constants/pokemon_constants.asm"))[1:]
    move_order = const_block(os.path.join(pokered, "constants/move_constants.asm"),
                             stop_at="STRUGGLE")[1:]
    mon_names = parse_names(os.path.join(pokered, "data/pokemon/names.asm"))
    move_names = parse_names(os.path.join(pokered, "data/moves/names.asm"))

    dex_names = const_block(os.path.join(pokered, "constants/pokedex_constants.asm"))
    dex_number = {name: i for i, name in enumerate(dex_names) if name}

    base_dir = os.path.join(pokered, "data/pokemon/base_stats")
    by_dex_const = {}
    for fname in sorted(os.listdir(base_dir)):
        if fname.endswith(".asm"):
            stats = parse_base_stats(os.path.join(base_dir, fname))
            if "dexConst" in stats:
                by_dex_const[stats["dexConst"]] = stats

    species_rows = []
    for index, species in enumerate(species_order, start=1):
        if species is None or species.startswith(("MISSINGNO", "UNUSED", "FOSSIL_", "MON_GHOST")):
            continue
        stats = by_dex_const.get("DEX_" + species)
        if stats is None:
            continue
        name = mon_names[index - 1] if index - 1 < len(mon_names) else species
        base = stats["baseStats"]
        types = [type_label(t) for t in stats["types"]]
        species_rows.append(
            f'    Gen1Species({kotlin_string(species)}, {index}, '
            f'{dex_number.get("DEX_" + species, 0)}, {kotlin_string(name)}, '
            f'{base["hp"]}, {base["attack"]}, {base["defense"]}, {base["speed"]}, '
            f'{base["special"]}, {stats["catchRate"]}, {stats["baseExp"]}, '
            f'{kotlin_string(types[0])}, '
            f'{kotlin_string(types[1]) if len(types) > 1 else "null"}, '
            f'{kotlin_string(stats.get("growthRate") or "MEDIUM_FAST")}),'
        )

    move_rows = []
    by_id = {row["id"]: row for row in parse_moves(os.path.join(pokered, "data/moves/moves.asm"))}
    for index, move in enumerate(move_order, start=1):
        if move is None:
            continue
        row = by_id.get(move)
        if row is None:
            continue
        name = move_names[index - 1] if index - 1 < len(move_names) else move
        move_rows.append(
            f'    Gen1Move({kotlin_string(move)}, {index}, {kotlin_string(name)}, '
            f'{kotlin_string(type_label(row["type"]))}, {row["power"]}, '
            f'{row["accuracy"]}, {row["pp"]}),'
        )

    header = (
        "// GENERATED FILE - do not edit by hand.\n"
        "// Source: pret/pokered @ %s, via tools/generate_gen1_data.py.\n"
        "// Species and move ids are the pokered constant names Gen1Recomp writes\n"
        "// into save.lua (see gen1recomp tools/extract/pokemon.py and moves.py).\n"
        "package com.logie.gen1storage.pokemon\n\n" % commit
    )

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "Gen1SpeciesTable.kt"), "w", encoding="utf-8") as out:
        out.write(header)
        out.write("internal val GEN1_SPECIES_TABLE: List<Gen1Species> = listOf(\n")
        out.write("\n".join(species_rows))
        out.write("\n)\n")

    with open(os.path.join(OUT_DIR, "Gen1MoveTable.kt"), "w", encoding="utf-8") as out:
        out.write(header)
        out.write("internal val GEN1_MOVE_TABLE: List<Gen1Move> = listOf(\n")
        out.write("\n".join(move_rows))
        out.write("\n)\n")

    print(f"species: {len(species_rows)}  moves: {len(move_rows)}")


if __name__ == "__main__":
    main()
