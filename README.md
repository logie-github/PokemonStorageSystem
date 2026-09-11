# Pokémon Storage for Gen1Recomp

An Android storage system for [Gen1Recomp](https://github.com/bryanthaboi/gen1recomp)
saves, built to look and behave like Bill's PC.

Link it once with the two codes from the game's **SAVE SYNC** dialog. No
Shizuku, no root, no folder picking, no storage permission — the app reads and
writes saves through the same service the game does, and never touches another
app's files.

Generation I only.

## What it does

**Storage**

- Twelve boxes of twenty, on top of whatever your saves already hold.
- Deposit a Pokémon from a save's party, withdraw one back into any save's
  party or PC.
- Move stored Pokémon between boxes, and rename the boxes.
- Release a Pokémon, with the entry kept in a log on the device so it is
  recoverable by hand.

**Saves**

- Reads every Red, Blue and Yellow playthrough on your account.
- Pick a cartridge from the three games, then from that game's saves — each one
  drawn as a cartridge showing the trainer, their lead Pokémon, badges and
  catches.
- Re-reads the account when the app opens and every thirty seconds it is on
  screen, so nothing goes stale while the game is running.
- Keeps a local backup of every save it writes.

**Looking at Pokémon**

- The Generation I status screen, both pages — stats, types, OT, moves and PP.
- Downloadable sprites, shown in the art of the game a Pokémon came from. Hold
  a sprite to pin that species to a different game's art.
- Cries play when a status page opens.

**Interface**

- The real Generation I window border, drawn from the tiles in the games'
  disassembly, and the Game Boy font.
- Six colour palettes: untinted, Red, Blue, Green, Yellow, and the Game Boy
  Color pastel mix. Sprites are recoloured to match.
- Windows on the left or the right, whichever suits your grip.
- Full screen in any orientation. On a foldable, the status pages take the left
  half and the menu stays usable on the right.

**Controls**

Tap a menu row to take it. You can also drive it from the empty space around
the windows:

| Gesture | |
| --- | --- |
| Swipe | Moves the cursor |
| Tap | Takes whatever the cursor is on |
| Double tap | OPTIONS |
| Tap and hold | Back |

## Getting started

1. In Gen1Recomp, open **SAVE SYNC** and tap **Sync now**.
2. Read off the two eight-digit codes.
3. In this app: **OPTIONS → ACCESS SAVE** → enter both codes → **LINK THIS
   DEVICE**.

Changes made here reach the game on its next sync.

**Treat the codes like a password.** Anyone holding both can read and write
every save on the account. Use **Get new sync codes** in the game if they leak.

## The one rule

A transfer never loses, duplicates or silently alters a Pokémon, and never
leaves a valid save corrupted. Everything else in the app follows from that —
see [DESIGN.md](DESIGN.md) for how.

## Credits

This app ships no game code and no ripped game assets. It is not affiliated
with Nintendo, Game Freak or The Pokémon Company. Full credits are in the app
under **OPTIONS → CREDITS**.

- Sprites — [ShiraTheMogul/rby-sprites-project](https://github.com/ShiraTheMogul/rby-sprites-project)
- Cries — [PokeAPI/cries](https://github.com/PokeAPI/cries)
- Font — [pokemon-font](https://github.com/cooljeanius/pokemon-font) by Superpencil, SIL OFL 1.1
- Game data — [pret/pokered](https://github.com/pret/pokered)
- Saves and sync — [Gen1Recomp](https://github.com/bryanthaboi/gen1recomp)

## Building

JDK 17 and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest lintRelease assembleDebug
```
