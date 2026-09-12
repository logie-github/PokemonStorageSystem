#!/usr/bin/env python3
"""Generate the app's Pokédex entries from pret/pokered and pret/pokeyellow.

The entry a Pokémon gets depends on which cartridge it came out of: Red and
Blue share one set of entries and Yellow rewrote most of them, so the app keeps
both and shows whichever belongs to the save a Pokémon was deposited from.

Three files are read from each checkout:

  constants/pokemon_constants.asm   internal index -> constant name, which is
                                    what Gen1Recomp writes into save.lua
  data/pokemon/dex_entries.asm      the pointer table in index order, and each
                                    entry's species classification, height in
                                    feet and inches, and weight in tenths of a
                                    pound
  data/pokemon/dex_text.asm         the entry text itself, as the two pages of
                                    three lines the Pokédex prints

Usage:
    python3 tools/generate_gen1_dex.py /path/to/pokered /path/to/pokeyellow

Writes:
    app/src/main/java/com/logie/gen1storage/pokemon/Gen1DexTable.kt
"""

import os
import re
import sys

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app/src/main/java/com/logie/gen1storage/pokemon",
)


def read_asm(path):
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.split(";")[0].rstrip()
            yield line


def parse_number(token):
    token = token.strip()
    if token.startswith("$"):
        return int(token[1:], 16)
    if token.startswith("%"):
        return int(token[1:], 2)
    return int(token)


def species_by_index(path):
    """constants/pokemon_constants.asm, as internal index -> constant name."""
    names, value = {}, 0
    for line in read_asm(path):
        s = line.strip()
        m = re.match(r"const_def(?:\s+(\$?-?\w+))?$", s)
        if m:
            value = parse_number(m.group(1)) if m.group(1) else 0
            continue
        m = re.match(r"const_next\s+(\$?\w+)$", s)
        if m:
            value = parse_number(m.group(1))
            continue
        # A gap in the index table — MissingNo. Counted, never named.
        if re.match(r"const_skip(\s|$)", s):
            value += 1
            continue
        m = re.match(r"const\s+(\w+)", s)
        if m:
            names[value] = m.group(1)
            value += 1
    return names


def entry_labels(path):
    """The pointer table, as internal index -> `XxxDexEntry` label."""
    labels, index = {}, 0
    started = False
    for line in read_asm(path):
        s = line.strip()
        if s.startswith("PokedexEntryPointers"):
            started = True
            continue
        if not started:
            continue
        if s.startswith("assert_table_length"):
            break
        m = re.match(r"dw\s+(\w+)$", s)
        if m:
            index += 1
            labels[index] = m.group(1)
    return labels


def entry_facts(path):
    """Each `XxxDexEntry` block's classification, height and weight."""
    facts, label = {}, None
    for line in read_asm(path):
        s = line.strip()
        m = re.match(r"(\w+DexEntry):$", s)
        if m:
            label = m.group(1)
            facts[label] = {}
            continue
        if label is None:
            continue
        m = re.match(r'db\s+"([^"]*)@"$', s)
        if m:
            facts[label]["category"] = m.group(1)
            continue
        m = re.match(r"db\s+(\d+)\s*,\s*(\d+)$", s)
        if m:
            facts[label]["feet"] = int(m.group(1))
            facts[label]["inches"] = int(m.group(2))
            continue
        m = re.match(r"dw\s+(\d+)$", s)
        if m:
            facts[label]["weight"] = int(m.group(1))
    return facts


def entry_text(path):
    """`_XxxDexEntry` -> the lines it prints, with a blank line at the page."""
    text, label, lines = {}, None, []
    for line in read_asm(path):
        s = line.strip()
        m = re.match(r"_(\w+DexEntry)::$", s)
        if m:
            if label:
                text[label] = lines
            label = m.group(1)
            lines = []
            continue
        if label is None:
            continue
        if s == "dex":
            text[label] = lines
            label, lines = None, []
            continue
        m = re.match(r'(text|next|page|line|para)\s+"(.*)"$', s)
        if m:
            if m.group(1) == "page":
                # The Pokédex turns the page here; the app draws the break as
                # a blank line rather than as a second screen.
                lines.append("")
            lines.append(m.group(2))
    if label:
        text[label] = lines
    return text


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
    return f'"{escaped}"'


def collect(root):
    labels = entry_labels(os.path.join(root, "data/pokemon/dex_entries.asm"))
    facts = entry_facts(os.path.join(root, "data/pokemon/dex_entries.asm"))
    text = entry_text(os.path.join(root, "data/pokemon/dex_text.asm"))
    species = species_by_index(os.path.join(root, "constants/pokemon_constants.asm"))

    out = {}
    for index, label in labels.items():
        name = species.get(index)
        if not name or name.startswith(("MISSINGNO", "UNUSED", "FOSSIL_", "MON_GHOST")):
            continue
        if label.startswith("MissingNo"):
            continue
        lines = [clean(line) for line in text.get(label, [])]
        if not lines:
            continue
        out[name] = {
            "category": clean(facts.get(label, {}).get("category", "")),
            "feet": facts.get(label, {}).get("feet", 0),
            "inches": facts.get(label, {}).get("inches", 0),
            "weight": facts.get(label, {}).get("weight", 0),
            "lines": lines,
        }
    return out


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    red_root, yellow_root = sys.argv[1], sys.argv[2]
    red = collect(red_root)
    yellow = collect(yellow_root)

    def commit(root):
        return os.popen(f"git -C {root} rev-parse HEAD").read().strip() or "unknown"

    rows = []
    for name in sorted(red):
        entry = red[name]
        other = yellow.get(name)
        red_text = "\n".join(entry["lines"])
        yellow_text = "\n".join(other["lines"]) if other else None
        rows.append(
            "    Gen1DexEntry(\n"
            f"        {kotlin_string(name)},\n"
            f"        {kotlin_string(entry['category'])},\n"
            f"        {entry['feet']}, {entry['inches']}, {entry['weight']},\n"
            f"        {kotlin_string(red_text)},\n"
            f"        {kotlin_string(yellow_text) if yellow_text else 'null'},\n"
            "    ),"
        )

    header = (
        "// GENERATED FILE - do not edit by hand.\n"
        f"// Source: pret/pokered @ {commit(red_root)}\n"
        f"//     and pret/pokeyellow @ {commit(yellow_root)},\n"
        "// via tools/generate_gen1_dex.py.\n"
        "// Species ids are the pokered constant names Gen1Recomp writes into\n"
        "// save.lua. Height is feet and inches and weight is tenths of a pound,\n"
        "// which is how the cartridge stores them.\n"
        "package com.logie.gen1storage.pokemon\n\n"
    )

    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "Gen1DexTable.kt"), "w", encoding="utf-8") as out:
        out.write(header)
        out.write("internal val GEN1_DEX_TABLE: List<Gen1DexEntry> = listOf(\n")
        out.write("\n".join(rows))
        out.write("\n)\n")

    missing = sorted(set(red) - set(yellow))
    print(f"entries: {len(rows)}  yellow-only-missing: {len(missing)}")


if __name__ == "__main__":
    main()
