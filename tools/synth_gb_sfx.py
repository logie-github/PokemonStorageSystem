#!/usr/bin/env python3
"""Renders pokered sound effects to WAV through a Game Boy pulse channel.

The note data below is transcribed verbatim from pret/pokered at the commit
the rest of this project pins, and the synthesiser implements the hardware the
cartridge is writing to rather than approximating the result by ear:

  * frequency:  f = 131072 / (2048 - x), x being the 11-bit register value
                the `square_note` macro's fourth argument becomes
  * sweep:      NR10. Every `period / 128` seconds, x -> x +/- (x >> shift).
                A period of 0 disables it; x passing 2047 silences the channel,
                which is what gives SFX_TINK its cut-off.
  * envelope:   NR12. Volume steps by one every `fade / 64` seconds, downward
                for a positive fade and upward for a negative one. A fade of 0
                holds the volume.
  * duty:       NR11 bits 6-7, as a fraction of the period the wave is high.
  * length:     the `square_note` macro's first argument, in frames of 1/60s,
                inclusive — so 4 is five frames.

Usage: python3 tools/synth_gb_sfx.py app/src/main/res/raw
"""

import math
import os
import struct
import sys
import wave

RATE = 22050

# Duty patterns, NR11 bits 6-7: 12.5%, 25%, 50%, 75%.
DUTY = {0: 0.125, 1: 0.25, 2: 0.50, 3: 0.75}

# audio/sfx/tink_1.asm
TINK = {
    "duty": 2,
    "notes": [
        # (sweep_period, sweep_shift, sweep_down, length, volume, fade, x)
        (3, 2, True, 4, 15, 2, 512),
        (2, 2, False, 8, 14, 2, 512),
    ],
}

# audio/sfx/heal_hp_1.asm
HEAL_HP = {
    "duty": 2,
    "notes": [
        (1, 7, False, 15, 15, 0, 1264),
        (1, 7, False, 15, 15, 2, 1616),
    ],
}


def render_note(duty, sweep_period, sweep_shift, sweep_down, length, volume, fade, x):
    """One `square_note`, as the channel would play it."""
    frames = length + 1
    total = int(RATE * frames / 60.0)
    samples = []

    phase = 0.0
    high = DUTY[duty]
    level = volume
    # Sweep and envelope run on their own clocks, not on the note's.
    sweep_every = sweep_period / 128.0 if sweep_period else None
    fade_every = fade / 64.0 if fade else None
    next_sweep = sweep_every
    next_fade = fade_every
    silenced = False

    for i in range(total):
        t = i / RATE

        if sweep_every is not None and t >= next_sweep:
            next_sweep += sweep_every
            if sweep_shift:
                delta = x >> sweep_shift
                x = x - delta if sweep_down else x + delta
                if x > 2047:
                    silenced = True
                if x < 8:
                    x = 8

        if fade_every is not None and t >= next_fade:
            next_fade += fade_every
            level = max(0, min(15, level - 1 if fade > 0 else level + 1))

        if silenced or level == 0:
            samples.append(0)
            continue

        hz = 131072.0 / (2048 - x)
        phase += hz / RATE
        phase -= math.floor(phase)
        amplitude = level / 15.0 * 0.5
        samples.append(amplitude if phase < high else -amplitude)

    return samples


def render(effect):
    out = []
    for note in effect["notes"]:
        out.extend(render_note(effect["duty"], *note))
    # A short fade at the tail so the channel stopping is not a click of its
    # own on hardware that has no such edge.
    tail = int(RATE * 0.004)
    for i in range(min(tail, len(out))):
        out[len(out) - 1 - i] *= i / tail
    return out


def write_wav(path, samples):
    with wave.open(path, "wb") as sink:
        sink.setnchannels(1)
        sink.setsampwidth(2)
        sink.setframerate(RATE)
        sink.writeframes(
            b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32000)) for s in samples)
        )


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/raw"
    os.makedirs(directory, exist_ok=True)
    for name, effect in (("sfx_tink", TINK), ("sfx_heal_hp", HEAL_HP)):
        path = os.path.join(directory, name + ".wav")
        write_wav(path, render(effect))
        print(path)


if __name__ == "__main__":
    main()
