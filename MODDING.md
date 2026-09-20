# Making a mod

A mod changes how the app **looks**. It can never change what it does.

There is **no programming language**. A mod is a zip file containing one
text file and some pictures. Nothing in it is ever executed.

```
my-mod.zip
├── mod.json        ← required, must be at the top of the zip
├── background.png  ← optional
├── ball.png        ← optional
├── border.png      ← optional
└── font.ttf        ← optional
```

Import it in the app: **OPTIONS → MODS → IMPORT MOD**, then pick your
palette under **OPTIONS → PALETTES**.

---

## mod.json

Everything is optional except `name`. Leave a field out and the app uses
its own.

| Field | What it does | Values |
|---|---|---|
| `name` | Shown in the MODS list | text |
| `author` | Shown under the name | text |
| `palette` | The four shades + sprite tint | see below |
| `chrome` | Menu and text colours | see below |
| `background` | Picture behind everything | see below |
| `ball` | The spinning ball in the corner | see below |
| `borderAsset` | Window frame picture | filename |
| `fontAsset` | Your own font | filename (`.ttf` / `.otf`) |
| `smoothing` | `true` for glow/gradient art, `false` for pixel art | `true` / `false` (default `false`) |

Colours are `"#RRGGBB"`, or `"#AARRGGBB"` if you want transparency.

### palette

Used for the sprite recolour and the default background.

| Field | What it does | Default |
|---|---|---|
| `id` | Internal name, must be unique | auto |
| `label` | Shown in the PALETTES list | the mod's name |
| `darkest` | Sprite outlines | — |
| `dark` | Sprite shadows | — |
| `light` | Sprite midtones | — |
| `lightest` | Sprite highlights | — |
| `surround` | Screen edge | — |
| `tintsSprites` | Recolour Pokémon art to these shades | `true` |
| `opacity` | How solid windows are | `1` (min `0.35`) |

All five colours are required, or the palette is ignored.

### chrome

Overrides individual colours. Use this for a dark theme — the palette
alone always draws windows light with dark text.

| Field | What it colours |
|---|---|
| `ink` | Main text |
| `panel` | Window fill |
| `shadow` | Secondary text |
| `muted` | Dim text and lines |
| `surround` | Screen edge |
| `barFill` | Top bar background |
| `barText` | Top bar text |
| `hpGreen` `hpYellow` `hpRed` | HP bar, in its three states |

### background

```json
"background": { "asset": "background.png", "fit": "cover" }
```

| `fit` | Result |
|---|---|
| `cover` | Fills the screen, keeps its shape, crops the overhang (default) |
| `tile` | Repeats |
| `stretch` | Squashed to fit exactly |

### ball

```json
"ball": { "asset": "ball.png", "spins": true }
```

A square picture of a **whole** ball. Its centre sits on the bottom-right
corner of the screen, so only the top-left quarter shows. One full turn
takes 12 seconds. `spins: false` holds it still.

### borderAsset

A square picture cut into a **3×3 grid**:

```
┌───┬───┬───┐
│ ↖ │ ─ │ ↗ │   corners drawn once
├───┼───┼───┤   edges repeated along the side
│ │ │   │ │ │   middle never drawn — leave it empty
├───┼───┼───┤
│ ↙ │ ─ │ ↘ │
└───┴───┴───┘
```

---

## Example

```json
{
  "name": "Neon Noir",
  "author": "You",
  "smoothing": true,

  "palette": {
    "id": "neon_noir",
    "label": "NEON NOIR",
    "darkest": "#050810",
    "dark": "#12507E",
    "light": "#3FC1FF",
    "lightest": "#FFE04D",
    "surround": "#05070A",
    "tintsSprites": true,
    "opacity": 0.86
  },

  "chrome": {
    "ink": "#FFD21E",
    "panel": "#070B14",
    "shadow": "#3FC1FF",
    "barFill": "#03050A",
    "barText": "#3FC1FF"
  },

  "background": { "asset": "background.png", "fit": "cover" },
  "ball": { "asset": "ball.png", "spins": true },
  "borderAsset": "border.png"
}
```

---

## What gets rejected

Every mod is scanned before it installs. If anything fails, **nothing** is
installed and the app lists what was wrong.

- **Ripped game art.** Pictures are compared against the official
  decompilation sprite sheets. Close matches are refused — draw your own.
- **ROMs and game files.** Any ROM, disc image or packed game archive,
  by extension or by its file header.
- **Broken zips.** Huge archives, zip bombs, or paths pointing outside
  the mod's own folder.

## Notes

- The layout grid is tuned to the built-in font. A very differently
  shaped font still works, it just sits less neatly.
- Use `smoothing: true` if your art has glow or gradients, otherwise it
  gets shredded into bands when scaled.
