# Pokémon Storage for Gen1Recomp

An Android storage system for [Gen1Recomp](https://github.com/bryanthaboi/gen1recomp)
saves: read your Red, Blue and Yellow playthroughs straight off the game's own
save sync, look through the party and the PC, and move Pokémon between those
saves and the app's own boxes. It looks and behaves like Bill's PC.

Link it once with the two codes from the game's SAVE SYNC dialog. There is no
Shizuku, no root, no folder picking and no storage permission — the app never
touches another app's files.

Generation I only. Generation II and later, other platforms and other emulators
are deliberately out of scope.

## What it does

- Links to your Gen1Recomp account with the two codes from SAVE SYNC, exactly
  as another copy of the game would.
- Lists every Red, Blue and Yellow playthrough on the account, and sets aside
  anything that is not Generation I rather than misreading it.
- Identifies each save by its contents — game, trainer, trainer ID, badges,
  play time, party and PC counts.
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
- **Concurrency is checked twice.** A save is re-read and re-hashed immediately
  before every commit, and the upload then carries the `baseRev` it was read
  at, so the *server* rejects a stale write even if the app's own check raced.
  `force` exists in the protocol and is never sent.
- **The unknown outcome is a real state.** A network call can fail after the
  server acted. That is reported as unknown rather than assumed either way, and
  the journal settles it on the next launch.
- **Backups stay on this device.** The blob being replaced is written to app
  storage before any upload, so the previous state of a playthrough is
  recoverable without the server and without a network.
- **A failed parse is never a licence to rewrite.** Unreadable saves are
  classified — malformed, incomplete, wrong generation, unsupported, read-only,
  readable-only-from-backup — and left as they are.

## How it matches Gen1Recomp

The save format is not guessed anywhere. It is read from upstream:

| This app | Upstream |
| --- | --- |
| `lua/LuaParser.kt`, `lua/LuaWriter.kt` | `src/core/SaveSerializer.lua` — the same restricted grammar, the same deterministic key order, `%q` strings and `tostring` numbers, so output is byte-identical to what the game would write |
| `gen1recomp/Gen1RecompSave.kt` | `src/core/SaveData.lua`, `src/pokemon/Boxes.lua`, `src/pokemon/Party.lua` — `party`, `boxes` (12 × 20), the pre-12-box `box` migration, `currentBox`, `player`, badges from `inventory` |
| `sync/SyncApi.kt` | `src/sync/SyncClient.lua` — the same endpoints, field names and `x-sync-account` / `x-sync-token` headers |
| `sync/SaveRepository.kt` | `src/sync/SyncEngine.lua` — a save travels as its own Lua source (`blob = source` from `SaveData.readSlotSource`), identified by `<version>/<meta.playthroughId>` |
| `pokemon/Gen1Stats.kt` | `src/pokemon/Stats.lua` (pokered `home/move_mon.asm` CalcStat) — run only where `BoxMenu.withdraw` runs `Stats.ensure` |
| `pokemon/Gen1SpeciesTable.kt`, `Gen1MoveTable.kt` | Generated from pret/pokered by `tools/generate_gen1_data.py`, the same assembly sources upstream's own extractor reads |

Nothing else about a Pokémon is touched. The raw Lua table is what gets stored
and what gets written back, so fields this app has never heard of — a mod's, an
importer's `typeBytes`, a future upstream addition — survive a round trip
untouched. Provenance is kept beside the Pokémon, never inside it.

## Getting access to your saves

1. In Gen1Recomp, open **SAVE SYNC** and make sure the device is linked. Tap
   **Sync now** so the account has your current saves.
2. Read off the two eight-digit codes.
3. In this app: **ACCESS SAVE** → enter both codes → **LINK THIS DEVICE**.

This device then appears in the game's device list, and the app reads and
writes saves through the same service the game does. A change made here reaches
the game on its next sync — when its launcher opens, a few seconds after a
save, or every few minutes while it runs.

**The codes are a credential.** Anyone holding both can link a device and read
and write every save on the account, so treat them like a password and use
**Get new sync codes** in the game if they leak. The app stores the account id
and device token it receives in its own private storage, and keeps all four out
of the debug report.

Some things follow from saves living on a server rather than on the device:

- The sync service is run by the Gen1Recomp project, not by this app. If it is
  unreachable, saves cannot be read or written — your storage boxes and local
  backups stay available regardless.
- Unlinking this device from the game cuts off access until it is linked again.
  It does not touch anything already in the app's boxes.

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
| `sync` | The save-sync client, the linked account's credentials, and the save catalogue with its local backups |
| `gen1recomp` | Save parsing, classification and validation |
| `pokemon` | Generation I Pokémon representation, species and move tables, stat maths |
| `storage` | The app's own PC and its provenance records |
| `transfer` | Deposit and withdraw transactions, journalling and crash recovery |
| `ui` | The Generation I interface |

UI code never touches save bytes; it goes through `transfer`. Everything above
`sync/SyncTransport.kt` is testable without a network — the test suite drives
the real client against a fake server that enforces the same revision rules,
including the case where a write lands but the reply is lost.
