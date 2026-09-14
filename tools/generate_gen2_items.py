#!/usr/bin/env python3
"""Generate the item order and the Time Capsule's catch-rate table.

A Generation I Pokémon has no held item; byte seven of its party struct is its
catch rate. Generation II reads that byte as the held item, so a Pokémon
traded forward arrives holding whatever item sits at its catch rate's index —
except for eleven values the game rewrites, which is what
data/items/catch_rate_items.asm is for.

Usage:
    python3 tools/generate_gen2_items.py /path/to/pokecrystal

Writes:
    app/src/main/java/com/logie/gen1storage/pokemon/Gen2ItemTable.kt
"""

import os
import re
import sys

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app/src/main/java/com/logie/gen1storage/pokemon",
)


def item_order(root):
    """Every item id by index, the whole 0..255 of it.

    The list does not stop at NUM_ITEMS: the TMs, HMs and move tutors carry on
    in the same const list through `add_tm`, `add_hm` and `add_mt`, and a
    catch rate can land on any of them.
    """
    path = os.path.join(root, "constants/item_constants.asm")
    names, value = [], None
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.split(";")[0].strip()
            m = re.match(r"const_def(?:\s+(-?\d+))?$", line)
            if m:
                value = int(m.group(1)) if m.group(1) else 0
                continue
            m = re.match(r"(const|add_tm|add_hm)\s+(\w+)", line)
            if m and value is not None:
                prefix = {"const": "", "add_tm": "TM_", "add_hm": "HM_"}[m.group(1)]
                while len(names) < value:
                    names.append(None)
                names.append(prefix + m.group(2))
                value += 1
                continue
            # add_mt defines a tutor move rather than a new item id.
    return names


def catch_rate_items(root):
    path = os.path.join(root, "data/items/catch_rate_items.asm")
    order = item_order(root)
    by_name = {name: index for index, name in enumerate(order) if name}
    out = {}
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.split(";")[0].strip()
            m = re.match(r"db\s+(-?\w+),\s*(\w+)$", line)
            if not m:
                continue
            key, item = m.group(1), m.group(2)
            if key == "0":
                continue
            if key == "-1":
                index = 255
            elif key in by_name:
                index = by_name[key]
            else:
                raise SystemExit("unknown catch rate key %s" % key)
            out[index] = item
    return out


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    root = sys.argv[1]
    order = item_order(root)
    replacements = catch_rate_items(root)
    head = os.popen("git -C %s rev-parse HEAD" % root).read().strip()

    out = os.path.join(OUT_DIR, "Gen2ItemTable.kt")
    with open(out, "w", encoding="utf-8") as handle:
        handle.write("// GENERATED FILE - do not edit by hand.\n")
        handle.write("// Source: pret/pokecrystal @ %s, via tools/generate_gen2_items.py.\n" % head)
        handle.write("package com.logie.gen1storage.pokemon\n\n")
        handle.write("/** Every item by its index, which is what a held-item byte holds. */\n")
        handle.write("internal val GEN2_ITEM_ORDER: List<String?> = listOf(\n")
        for index, name in enumerate(order):
            handle.write("    %s, // %02x\n" % ('"%s"' % name if name else "null", index))
        handle.write(")\n\n")
        handle.write("/**\n")
        handle.write(" * The catch rates Generation II rewrites rather than reading as an item\n")
        handle.write(" * index, from data/items/catch_rate_items.asm.\n")
        handle.write(" */\n")
        handle.write("internal val GEN2_CATCH_RATE_ITEMS: Map<Int, String> = mapOf(\n")
        for index in sorted(replacements):
            handle.write("    %d to \"%s\",\n" % (index, replacements[index]))
        handle.write(")\n")
    print("wrote %s (%d items, %d rewrites)" % (out, len(order), len(replacements)))


if __name__ == "__main__":
    main()
