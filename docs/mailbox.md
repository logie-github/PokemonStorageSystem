# Notes: moving a Pokémon without two programs writing one save

## Why

Today both programs write the same save file. The app reads it, edits it, writes
it back; the game does the same when it is played. When both have written since
the last sync, one of them loses everything it did — silently, if the play times
happen to match, because `SyncEngine.sameProgress` reads that as "same point in
the playthrough, no fork" and force-uploads the local copy over the remote one.
A Pokémon this app moved into a cartridge that way is afterwards in neither
place.

Nothing in the app can fix that, because the app is not the side that decides.
Every workaround — claiming a minute of play, checking which device wrote it —
turns a silent loss into a prompt, and a pending prompt stops that game syncing
at all until someone answers it.

So: **the game is the only program that ever writes a save.** The app leaves a
note, the game applies it to its own file, and there is no second writer to fork
against.

## The shape of it

```
app                          server                        game
 |                              |                            |
 |-- POST /sync/notes --------->|                            |
 |   {take: box 3 slot 4}       |                            |
 |                              |<---- GET /sync/notes ------|   (on sync)
 |                              |                            |
 |                              |      applies it to its own save,
 |                              |      saves, uploads as normal
 |                              |                            |
 |                              |<-- POST /sync/notes/resolve|
 |                              |    {id, status: applied}   |
 |-- GET /sync/notes ---------->|                            |
 |   sees applied, finishes     |                            |
 |-- POST /sync/notes/clear --->|                            |
```

The app never calls `PUT /sync/save` again.

## The note

```json
{
  "id": "5f2c…",
  "version": "red",
  "playthroughId": "quiet-forest-dawn",
  "kind": "give" | "take",
  "createdAt": 1757808000,

  "mon": "{dvs={15,12,10,9},exp=41874,…}",
  "to": { "party": true } | { "box": 3 },

  "from": { "party": 2 } | { "box": 3, "slot": 4 },
  "expect": {
    "species": "CLEFABLE", "level": 36,
    "otName": "A", "otId": 49363, "exp": 41874
  },

  "status": "pending" | "applied" | "refused",
  "reason": "THAT BOX IS FULL"
}
```

`give` carries `mon` and `to`. `take` carries `from` and `expect`. Slots and
boxes are 1-based, as the save writes them.

**`mon` is a Lua record, not JSON.** It is written by the same encoder that
writes a whole save, so what arrives is shaped exactly like a record read out of
a save file — including any field a mod put there that neither side knows about.
Load it the way a save's contents are loaded.

**`expect` is not optional and is not advisory.** A box slot is a position, and
positions move: between the app reading the save and the game reading the note,
the player may have rearranged that box, evolved what was in it, or released it.
Compare all five fields against what is actually in that spot, and refuse the
note if any disagree. A refusal costs a message; taking the wrong Pokémon costs
a Pokémon.

## Endpoints

| | |
|---|---|
| `POST /sync/notes` | Body is a note. Stores it as `pending`, answers `{"note": {…}}` with it as stored. |
| `GET /sync/notes` | `{"notes": [ … ]}` — every note on the account, in any state. |
| `POST /sync/notes/resolve` | `{"id": …, "status": "applied"\|"refused", "reason": …}`. The game only. |
| `POST /sync/notes/clear` | `{"id": …}`. The app, once it has acted on a settled note. |

Notes belong to the account and are scoped to a playthrough by
`version`/`playthroughId`. The app is written against exactly these four; the
first, second and fourth are in `SyncApi.postNote` / `notes` / `deleteNote`.

## What the game does with one

On sync, for each note addressed to a playthrough this device has:

1. **Skip it if that save is live.** `Trade.commit` already refuses while the
   game is running, and the sync engine's `protectedKey` already refuses to
   write over a loaded save. A note for the save being played waits until the
   player is back in the launcher. That is also when they will next see it.
2. **Open the slot and apply it.** `Trade.openSlot(version, slotId, cartId)`
   gives a handle on a save that is not loaded, and `Trade.commit` writes one
   with the validation and backups an online trade gets. What is *not* reusable
   is the planning either side of it: `Trade.plan` and `Trade.planIncoming` are
   both one-for-one swaps — `planIncoming` refuses an empty target ("that's not
   in the PC") because it needs a Pokémon to send back. A note is one-way, so
   the insert and the remove have to come from the game's own box code, the
   same calls its PC screen makes, with `openSlot`/`commit` around them.
3. **Check `expect` before a `take`.** If it does not match, resolve the note
   `refused` with a reason and change nothing.
4. **Resolve the note, then upload the save.** In that order. A crash between
   them leaves a note saying "applied" and a save that has not gone up yet,
   which the next sync uploads; the reverse order would leave a note that says
   nothing happened over a save where it did, and the app would send it again.
5. **Apply at most once.** The id is the key. A note already resolved is never
   applied a second time, whatever state the queue is in.

## What the app does

Implemented, tested, not yet wired to the transfer screens — see
`transfer/Mailbox.kt` and `MailboxTest`.

- **Giving one out**: the Pokémon stays in the PC, marked with the note id. It
  is still the app's, and it cannot be sent anywhere else, released or traded
  until the note settles. `applied` removes it; `refused` unmarks it.
- **Taking one in**: the record is put in the target box immediately, marked
  with the note id, and is *not the player's yet* — the cartridge holds the only
  real one. `applied` unmarks it; `refused` takes the placeholder back out.
- **Recovery** is `Mailbox.reconcile()`, run on every sync, working off the
  server's list rather than off anything remembered locally. The app can be
  killed at any point: what is in flight is written where both programs can see
  it, and the mark survives a restart.

At no instant does a Pokémon exist twice for real, or nowhere.

## Making it feel instant

The app does not wait for any of this — the transfer completes on screen the
moment the note is posted. What matters is that the game picks it up on the
next thing the player does, which is switching to the game:

- `SyncEngine.noteResumed` already syncs on resume, but skips it within
  `RESUME_MIN_GAP` (60s) of the last sync. Have the state response say whether
  notes are waiting, and bypass the gap when they are.
- The drain happens in the launcher, before a save is loaded, which is where the
  player already is on the way into the game.

That puts the apply in the second between tapping the game's icon and the title
screen. If you want it tighter than that later, the app can fire an Android
intent when it posts, and the game can sync on receiving it.
