#!/usr/bin/env python3
"""Generate the app's Generation II move table from pret/pokecrystal.

Generation II did not merely add moves to Generation I's list: it rewrote a
good many of the originals. KARATE CHOP became FIGHTING, GUST became FLYING,
BITE became DARK, and a spread of powers, accuracies and PP moved with them.
A move is therefore read against the generation of the Pokémon that knows it,
and the two tables sit side by side exactly as the species tables do.

Usage:
    python3 tools/generate_gen2_moves.py /path/to/pokecrystal

Writes:
    app/src/main/java/com/logie/gen1storage/pokemon/Gen2MoveTable.kt
"""

import os
import re
import sys

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app/src/main/java/com/logie/gen1storage/pokemon",
)


def move_ids(root):
    """constants/move_constants.asm, from MOVE 1 up to NUM_ATTACKS.

    The battle animations carry on in the same const list past the last move,
    so the list is cut where the file says the moves end.
    """
    path = os.path.join(root, "constants/move_constants.asm")
    names, value = [], None
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.split(";")[0].strip()
            if line.startswith("DEF NUM_ATTACKS"):
                break
            m = re.match(r"const_def(?:\s+(-?\d+))?$", line)
            if m:
                value = int(m.group(1)) if m.group(1) else 0
                continue
            m = re.match(r"const\s+(\w+)", line)
            if m and value is not None:
                if value >= 1:
                    names.append(m.group(1))
                value += 1
    return names


def move_names(root):
    path = os.path.join(root, "data/moves/names.asm")
    out = []
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            m = re.match(r'\s*li\s+"([^"]*)"', raw)
            if m:
                out.append(m.group(1))
    return out


def moves(root):
    """data/moves/moves.asm — one `move` line per move, in id order."""
    path = os.path.join(root, "data/moves/moves.asm")
    out = []
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.split(";")[0].strip()
            if not line.startswith("move "):
                continue
            args = [a.strip() for a in line[len("move "):].split(",")]
            out.append(
                {
                    "id": args[0],
                    "effect": args[1],
                    "power": int(args[2]),
                    "type": "PSYCHIC" if args[3] == "PSYCHIC_TYPE" else args[3],
                    "accuracy": int(args[4].replace("percent", "").strip()),
                    "pp": int(args[5]),
                }
            )
    return out


def kotlin_string(value):
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return '"%s"' % escaped


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    root = sys.argv[1]

    ids = move_ids(root)
    names = move_names(root)
    rows_in = moves(root)
    if not (len(ids) == len(names) == len(rows_in)):
        raise SystemExit(
            "counts disagree: %d ids, %d names, %d moves" % (len(ids), len(names), len(rows_in))
        )
    for index, row in enumerate(rows_in):
        if row["id"] != ids[index]:
            raise SystemExit(
                "move %d is %s in the table and %s in the constants"
                % (index + 1, row["id"], ids[index])
            )

    rows = [
        '    Gen2Move({id}, {index}, {name}, {type}, {power}, {accuracy}, {pp}),'.format(
            id=kotlin_string(row["id"]),
            index=index + 1,
            name=kotlin_string(names[index]),
            type=kotlin_string(row["type"]),
            power=row["power"],
            accuracy=row["accuracy"],
            pp=row["pp"],
        )
        for index, row in enumerate(rows_in)
    ]

    head = os.popen("git -C %s rev-parse HEAD" % root).read().strip()
    out = os.path.join(OUT_DIR, "Gen2MoveTable.kt")
    with open(out, "w", encoding="utf-8") as handle:
        handle.write("// GENERATED FILE - do not edit by hand.\n")
        handle.write("// Source: pret/pokecrystal @ %s, via tools/generate_gen2_moves.py.\n" % head)
        handle.write("// Move ids are the pokecrystal constant names Gen1Recomp writes into\n")
        handle.write("// a Generation II save.lua.\n")
        handle.write("package com.logie.gen1storage.pokemon\n\n")
        handle.write("internal val GEN2_MOVE_TABLE: List<Gen2Move> = listOf(\n")
        handle.write("\n".join(rows))
        handle.write("\n)\n")
    print("wrote %s (%d moves)" % (out, len(rows)))


if __name__ == "__main__":
    main()
