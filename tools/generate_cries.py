#!/usr/bin/env python3
"""Generates the Pokémon cry tables from pret's own audio data.

A cry is not a recording. It is three channels of note commands, plus a pitch
and a length per species, played through the Game Boy's sound hardware — so
what belongs in this app is the note data and a synthesiser, exactly as the
species and move tables are data rather than screenshots of a stats screen.
Ported once, here, into tables `Gen1CrySynth` plays.

Every cry's channel program is flattened as it is read: `sound_loop`,
`sound_call` and `sound_jump` are expanded into the straight run of notes they
produce, because a cry is a second long and has nowhere to loop to forever.
What comes out is one integer array per channel, which the synthesiser walks.

Usage:
  python3 tools/generate_cries.py <pokered> <pokecrystal> <out-dir>
"""

import os
import re
import sys

# Opcodes, matching Gen1CrySynth's own reader.
OP_DUTY_PATTERN = 0   # one packed byte, four 2-bit duties
OP_DUTY = 1           # one duty, 0-3
OP_PITCH_SWEEP = 2    # period, shift (negative shift means downward)
OP_PITCH_OFFSET = 3   # signed 16-bit added to every frequency after it
OP_SQUARE = 4         # length, volume, fade, frequency
OP_NOISE = 5          # length, volume, fade, NR43 parameter

MAX_STEPS = 8192


def strip(line):
    return line.split(";")[0].strip()


def args(rest):
    return [a.strip() for a in rest.split(",") if a.strip()]


def number(text, consts):
    text = text.strip()
    if text in consts:
        return consts[text]
    if text.startswith("$"):
        return int(text[1:], 16)
    if text.startswith("%"):
        return int(text[1:], 2)
    return int(text, 0)


def read_channel_bodies(paths):
    """Every label in these files, with the command lines that run under it.

    Two shapes the cry files use that a naive reader gets wrong:

      * **Stacked labels.** `Cry_Gligar_Ch8:` sits directly on top of
        `Cry_Cyndaquil_Ch8:` and they share the notes below, because the two
        cries use the same one. Giving the body to the last label only leaves
        the first silent.
      * **Local labels.** `Cry_Girafarig_Ch6` falls into its own `.body`, and
        another channel jumps straight to `Cry_Girafarig_Ch6.body` to skip the
        duty pattern above it. The local label is a second entry into the same
        run of lines rather than a separate one.

    So every line is appended to each body that is currently open, and a label
    opens one without necessarily closing the last.
    """
    bodies = {}
    open_bodies = []
    parent = None
    previous_was_label = False
    for path in paths:
        with open(path) as handle:
            for raw in handle:
                line = strip(raw)
                if not line:
                    continue
                globl = re.match(r"^([A-Za-z_][A-Za-z0-9_]*):+$", line)
                local = re.match(r"^(\.[A-Za-z0-9_]+):+$", line)
                if globl:
                    label = globl.group(1)
                    body = bodies.setdefault(label, [])
                    if previous_was_label:
                        open_bodies.append(body)
                    else:
                        open_bodies = [body]
                    parent = label
                    previous_was_label = True
                    continue
                if local and parent is not None:
                    label = parent + local.group(1)
                    body = bodies.setdefault(label, [])
                    # The parent keeps running through here; this is another
                    # way in, not a different piece of code.
                    open_bodies.append(body)
                    previous_was_label = True
                    continue
                previous_was_label = False
                for body in open_bodies:
                    body.append(line)
    return bodies


def flatten(bodies, label, consts):
    """One channel's commands, with its loops and calls expanded."""
    out = []
    # [label, index, loop counters] — the engine's own call stack, with one
    # counter per `sound_loop` instruction the way wChannelLoopCounters is one
    # per channel.
    stack = [[label, 0, {}]]
    steps = 0
    while stack and steps < MAX_STEPS:
        steps += 1
        frame = stack[-1]
        body = bodies.get(frame[0], [])
        if frame[1] >= len(body):
            stack.pop()
            continue
        line = body[frame[1]]
        here = (frame[0], frame[1])
        frame[1] += 1
        parts = line.split(None, 1)
        name = parts[0]
        rest = parts[1] if len(parts) > 1 else ""

        if name == "square_note":
            a = args(rest)
            out += [OP_SQUARE, number(a[0], consts), number(a[1], consts),
                    number(a[2], consts), number(a[3], consts)]
        elif name == "noise_note":
            a = args(rest)
            out += [OP_NOISE, number(a[0], consts), number(a[1], consts),
                    number(a[2], consts), number(a[3], consts)]
        elif name == "duty_cycle_pattern":
            a = [number(x, consts) for x in args(rest)]
            out += [OP_DUTY_PATTERN, (a[0] << 6) | (a[1] << 4) | (a[2] << 2) | a[3]]
        elif name == "duty_cycle":
            out += [OP_DUTY, number(args(rest)[0], consts)]
        elif name == "pitch_sweep":
            a = args(rest)
            out += [OP_PITCH_SWEEP, number(a[0], consts), number(a[1], consts)]
        elif name == "pitch_offset":
            a = args(rest)
            # Two arguments in pokecrystal: octave and pitch, which together
            # are a signed offset in the same units a frequency is.
            out += [OP_PITCH_OFFSET, number(a[-1], consts)]
        elif name == "sound_loop":
            a = args(rest)
            # `Audio1_sound_loop`: the counter climbs to the count and the
            # block is played once more for each climb, so `sound_loop 2` is
            # three passes rather than two. A jump, not a call: looping by
            # pushing a frame would nest a fresh loop inside every pass.
            count = number(a[0], consts)
            target = a[1]
            done = frame[2].get(here, 0)
            # A count of zero is an infinite loop, which nothing in a cry has
            # and which a flattener cannot honour anyway.
            wanted = count if count else 1
            if done < wanted:
                frame[2][here] = done + 1
                frame[0] = target
                frame[1] = 0
            else:
                frame[2].pop(here, None)
        elif name == "sound_call":
            stack.append([args(rest)[0], 0, {}])
        elif name == "sound_jump":
            frame[0] = args(rest)[0]
            frame[1] = 0
            frame[2] = {}
        elif name == "sound_ret":
            stack.pop()
        # Anything else in a cry body is not something a cry uses.
    return out


def gen1(root):
    consts = {}
    bodies = read_channel_bodies(
        [os.path.join(root, "audio/sfx", name)
         for name in sorted(os.listdir(os.path.join(root, "audio/sfx")))
         if name.startswith("cry")]
    )

    table = []
    with open(os.path.join(root, "data/pokemon/cries.asm")) as handle:
        for raw in handle:
            line = strip(raw)
            match = re.match(r"^mon_cry\s+SFX_CRY_([0-9A-F]{2})\s*,\s*(\S+)\s*,\s*(\S+)$", line)
            if match:
                table.append((int(match.group(1), 16),
                              number(match.group(2), consts),
                              number(match.group(3), consts)))

    cries = []
    for index in range(max(entry[0] for entry in table) + 1):
        channels = []
        for channel in (5, 6, 8):
            label = "SFX_Cry%02X_1_Ch%d" % (index, channel)
            channels.append(flatten(bodies, label, consts) if label in bodies else [])
        cries.append(channels)
    return cries, table


def gen2(root):
    consts = {}
    bodies = read_channel_bodies([os.path.join(root, "audio/cries.asm")])

    order = []
    with open(os.path.join(root, "audio/cry_pointers.asm")) as handle:
        for raw in handle:
            line = strip(raw)
            match = re.match(r"^dba\s+(\S+)$", line)
            if match:
                order.append(match.group(1))

    names = {}
    with open(os.path.join(root, "constants/cry_constants.asm")) as handle:
        index = 0
        for raw in handle:
            line = strip(raw)
            match = re.match(r"^const\s+(CRY_\S+)$", line)
            if match:
                names[match.group(1)] = index
                index += 1

    table = []
    with open(os.path.join(root, "data/pokemon/cries.asm")) as handle:
        for raw in handle:
            line = strip(raw)
            match = re.match(r"^mon_cry\s+(CRY_\S+)\s*,\s*(\S+)\s*,\s*(\S+)$", line)
            if match:
                table.append((names[match.group(1)],
                              number(match.group(2), consts),
                              number(match.group(3), consts)))

    cries = []
    for label in order:
        channels = []
        for channel in (5, 6, 8):
            name = "%s_Ch%d" % (label, channel)
            channels.append(flatten(bodies, name, consts) if name in bodies else [])
        cries.append(channels)
    return cries, table


# Every value is stored as one character, carried up so the negative ones are
# still positive and nothing lands in the surrogate range a string constant
# cannot hold. CryTables.decode is the only thing that undoes it.
BIAS = 2048


def encode(values):
    out = []
    for value in values:
        point = value + BIAS
        if not 0 < point < 0xD800:
            raise ValueError("value %d does not fit one character" % value)
        out.append("\\u%04x" % point)
    return '"%s"' % "".join(out)


def render(name, package, cries, table, doc):
    """Kotlin that holds the data as strings rather than as array literals.

    Array-of-array literals of this size do not fit: every element is bytecode
    in the object's static initialiser, and a few thousand of them overflow the
    64 KB a method may be. One string constant per channel is one instruction,
    and decoding it costs a pass over a few dozen characters the first time
    that cry is played.
    """
    lines = [
        "package %s" % package,
        "",
        "// Generated by tools/generate_cries.py from pret. Do not edit by hand.",
        "",
        doc,
        "internal object %s {" % name,
        "",
        "    /**",
        "     * Three channel programs per base cry, in order: pulse 1, pulse 2,",
        "     * noise. An empty one is a channel that cry does not use.",
        "     */",
        "    private val PROGRAMS: Array<String> = arrayOf(",
    ]
    for channels in cries:
        for program in channels:
            lines.append("        %s," % encode(program))
    lines += [
        "    )",
        "",
        "    /** Three values per species: the base cry, its pitch, its length. */",
        "    private val ENTRIES: String =",
    ]
    flat = []
    for entry in table:
        flat += list(entry)
    chunk = 400
    pieces = [flat[at:at + chunk] for at in range(0, len(flat), chunk)] or [[]]
    for index, piece in enumerate(pieces):
        joiner = " +" if index < len(pieces) - 1 else ""
        lines.append("        %s%s" % (encode(piece), joiner))
    lines += [
        "",
        "    /** How many base cries there are, which is not how many species. */",
        "    val cryCount: Int get() = PROGRAMS.size / 3",
        "",
        "    /** One base cry's three channels, decoded on demand. */",
        "    fun channels(cry: Int): Array<IntArray>? {",
        "        if (cry < 0 || cry >= cryCount) return null",
        "        return Array(3) { CryTables.decode(PROGRAMS[cry * 3 + it]) }",
        "    }",
        "",
        "    /** A species' row, by the index its own game files it under. */",
        "    fun entry(index: Int): IntArray? {",
        "        val at = index * 3",
        "        if (index < 0 || at + 2 >= ENTRIES.length) return null",
        "        return IntArray(3) { CryTables.valueAt(ENTRIES, at + it) }",
        "    }",
        "}",
        "",
    ]
    return "\n".join(lines)


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 1
    red, crystal, out = sys.argv[1], sys.argv[2], sys.argv[3]
    package = "com.logie.gen1storage.sound"

    cries1, table1 = gen1(red)
    cries2, table2 = gen2(crystal)

    with open(os.path.join(out, "Gen1CryTable.kt"), "w") as handle:
        handle.write(render(
            "Gen1CryTable", package, cries1, table1,
            "/**\n"
            " * Generation I's cries, out of pokered's own `data/pokemon/cries.asm`\n"
            " * and `audio/sfx/cry*.asm`.\n"
            " *\n"
            " * Thirty-eight base cries shared between a hundred and fifty-one\n"
            " * species, each one bent by a pitch and a length. See [CrySynth].\n"
            " */",
        ))
    with open(os.path.join(out, "Gen2CryTable.kt"), "w") as handle:
        handle.write(render(
            "Gen2CryTable", package, cries2, table2,
            "/**\n"
            " * Generation II's cries, out of pokecrystal's `audio/cries.asm` and\n"
            " * `data/pokemon/cries.asm`.\n"
            " *\n"
            " * The same three channels and the same commands as Generation I, with\n"
            " * a sixteen-bit pitch and length rather than a byte each.\n"
            " */",
        ))
    print("gen1: %d cries, %d species" % (len(cries1), len(table1)))
    print("gen2: %d cries, %d species" % (len(cries2), len(table2)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
