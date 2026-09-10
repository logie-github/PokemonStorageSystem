# Pokémon Storage for Gen1Recomp

An Android storage system for [Gen1Recomp](https://github.com/bryanthaboi/gen1recomp)
saves: find your Red, Blue and Yellow playthroughs on the device, look through
the party and the PC, and move Pokémon between those saves and the app's own
boxes. It looks and behaves like Bill's PC, and it works entirely offline.

Generation I only. Generation II and later, other platforms and other emulators
are deliberately out of scope.

## What it does

- Finds Gen1Recomp saves automatically through Shizuku, or through a save
  folder you grant once with the system picker.
- Identifies each save by its contents — game, trainer, trainer ID, slot,
  badges, play time, party and PC counts — never by filename alone.
- Shows party and PC Pokémon on a Generation I status screen, with DVs and stat
  experience behind a DETAILS screen.
- Deposits a Pokémon from a save into the app's twelve boxes of twenty, and
  withdraws one back into any compatible save's party or PC.
- Keeps provenance for everything it stores: which game, trainer, slot and
  place it came from, and when.

## The rule the whole app is built around

**A transfer must never lose, duplicate or silently alter a Pokémon, and must
never leave a valid Gen1Recomp save corrupted.**

Everything else follows from it:

- **Movement, not copying.** Both directions write the destination first, read
  it back, re-validate it as a save, and only then remove the source copy.
- **A journal, not a guess.** Before the first destructive step the app records
  the save's hash as it is now and as it will be once committed. If the process
  dies half way, the save's current hash matches one of the two and says
  whether the write landed. Matching neither means the game wrote the save in
  between — the app then keeps both copies and says so rather than choosing.
- **Concurrency is checked, not assumed.** A save is re-read and re-hashed
  immediately before every commit; if Gen1Recomp changed it since the scan, the
  transfer is refused and the newer progress is left alone.
- **Backups are never traded away.** The previous file is rolled into `.bak`
  only when it is itself readable, so a good backup is never overwritten with a
  corrupt main file.
- **A failed parse is never a licence to rewrite.** Unreadable saves are
  classified — malformed, incomplete, wrong generation, unsupported, read-only,
  readable-only-from-backup — and left as they are.

## How it matches Gen1Recomp

The save format is not guessed anywhere. It is read from upstream:

| This app | Upstream |
| --- | --- |
| `lua/LuaParser.kt`, `lua/LuaWriter.kt` | `src/core/SaveSerializer.lua` — the same restricted grammar, the same deterministic key order, `%q` strings and `tostring` numbers, so output is byte-identical to what the game would write |
| `gen1recomp/Gen1RecompSave.kt` | `src/core/SaveData.lua`, `src/pokemon/Boxes.lua`, `src/pokemon/Party.lua` — `party`, `boxes` (12 × 20), the pre-12-box `box` migration, `currentBox`, `player`, badges from `inventory` |
| `gen1recomp/SaveDiscovery.kt` | `SaveData.lua` save slots — `saves/<version>/slotN.lua` with `.bak`/`.tmp`, the flat `save.lua` / `save_blue.lua` / `save_yellow.lua` names, and the `options.saveSlots` registry |
| `gen1recomp/SaveWriter.kt` | `SaveData.save` — roll the main file into `.bak`, stage as `.tmp`, replace, clear the witness |
| `pokemon/Gen1Stats.kt` | `src/pokemon/Stats.lua` (pokered `home/move_mon.asm` CalcStat) — run only where `BoxMenu.withdraw` runs `Stats.ensure` |
| `pokemon/Gen1SpeciesTable.kt`, `Gen1MoveTable.kt` | Generated from pret/pokered by `tools/generate_gen1_data.py`, the same assembly sources upstream's own extractor reads |

Nothing else about a Pokémon is touched. The raw Lua table is what gets stored
and what gets written back, so fields this app has never heard of — a mod's, an
importer's `typeBytes`, a future upstream addition — survive a round trip
untouched. Provenance is kept beside the Pokémon, never inside it.

## Getting access to your saves

Gen1Recomp keeps its saves in
`Android/data/<game package>/files/save/pokemon-love2d` (LÖVE's external save
directory, from upstream `conf.lua`). Android 11 and later will not hand that
path to an ordinary app, so there are two routes:

1. **Shizuku** — install and start Shizuku, allow this app, and saves are found
   automatically on every launch. Nothing to pick.
2. **The folder picker** — OPTIONS → CHOOSE FOLDER, navigate to the Gen1Recomp
   save folder (or any folder above it) and grant it once. The permission is
   persisted, so later launches scan it without asking again.

## Building

Requires JDK 17 and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest lintRelease assembleDebug
```

Regenerating the Generation I data tables:

```sh
git clone --depth 1 https://github.com/pret/pokered /tmp/pokered
python3 tools/generate_gen1_data.py /tmp/pokered
```

## Releases

GitHub Actions is the only thing that builds a release. `.github/workflows/release.yml`
runs on a `v*` tag or by hand, and produces:

- `Gen1Storage-v<version>.apk`
- `Gen1Storage-v<version>.apk.sha256`

Signing uses repository secrets and nothing else — no keystore, password or key
material is in this repository:

| Secret | What it holds |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The release keystore, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

To create a keystore and its secret:

```sh
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 \
        -validity 10000 -alias gen1storage
base64 -w0 release.jks     # paste into ANDROID_KEYSTORE_BASE64
```

Debug builds sign themselves with the local debug key and need none of this.

## Layout

| Package | Responsibility |
| --- | --- |
| `lua` | The Gen1Recomp save grammar: value model, parser, writer, byte-safe text |
| `saveaccess` | Android storage: the Shizuku binder service and its client, the Storage Access Framework tree, behind one `SaveVolume` interface |
| `gen1recomp` | Save detection, parsing, classification, validation and staged writing |
| `pokemon` | Generation I Pokémon representation, species and move tables, stat maths |
| `storage` | The app's own PC and its provenance records |
| `transfer` | Deposit and withdraw transactions, journalling and crash recovery |
| `ui` | The Generation I interface |

UI code never touches save bytes; it goes through `transfer`.
