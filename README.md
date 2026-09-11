# Pokémon Storage for Gen1Recomp

An Android PC for [Gen1Recomp](https://github.com/bryanthaboi/gen1recomp)
saves, styled like Bill's PC. Generation I only.

Link it once with the two codes from the game's **SAVE SYNC** dialog. No root,
no Shizuku, no file permissions.

## Features

- **12 boxes of 20**, on top of what your saves already hold.
- **Deposit** from a save's boxes. **Withdraw** back into the cart in the
  machine. Parties are never touched.
- **Select** one Pokémon or several: SELECT in the window that opens on one
  ticks it, then tap any other row to tick it too.
- **Export** the whole PC to a Lua file and **import** it back, in OPTIONS.
  Importing the same file twice leaves one of each.
- **Move** stored Pokémon between boxes. **Release** them, with a log kept on
  the device.
- **Rename** boxes and saves by holding them.
- **Auto-sync** on open and every 30 seconds.
- **Status screens** with stats, types, moves and PP.
- **Sound effects**, and **sprites and cries** downloaded from OPTIONS →
  DOWNLOADS. Tap a sprite to hear it again. Each sound can be turned off in
  OPTIONS → SOUND FX.
- **Seven palettes**, windows on the left or right, full screen, foldable
  aware.

## Controls

Tap a menu row to take it. In the empty space around the windows: swipe to move
the cursor, tap to take it, double tap for OPTIONS, hold to go back.

## Setup

1. Gen1Recomp → **SAVE SYNC** → **Sync now**
2. Note the two codes
3. Here: **OPTIONS → ACCESS SAVE** → enter both → **LINK THIS DEVICE**

Treat the codes like a password.

## The rule

A transfer never loses, duplicates or alters a Pokémon, and never corrupts a
save. See [DESIGN.md](DESIGN.md).

## Credits

No game code or ripped assets ship with this app — sprites and cries are
downloaded on request. Not affiliated with Nintendo, Game Freak or The Pokémon
Company.

[Sprites](https://github.com/ShiraTheMogul/rby-sprites-project) ·
[Cries](https://github.com/PokeAPI/cries) ·
[Font](https://github.com/cooljeanius/pokemon-font) ·
[Game data](https://github.com/pret/pokered) ·
[Saves](https://github.com/bryanthaboi/gen1recomp)

## Build

```sh
./gradlew testDebugUnitTest lintRelease assembleDebug
```
