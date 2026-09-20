# Making a mod

A mod changes how the app looks. There is no code and no scripting — a mod
is a zip with one text file and some pictures in it.

```
my-mod.zip
├── mod.json     (required)
├── background.png
├── ball.png
├── border.png
└── font.ttf
```

Import it with **OPTIONS → MODS → IMPORT MOD**, then pick it under
**OPTIONS → PALETTES**.

## mod.json

Only `name` is required. Leave anything out and the app uses its own.

```json
{
  "name": "Neon Noir",
  "author": "You",
  "smoothing": true,

  "palette": {
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
  "borderAsset": "border.png",
  "fontAsset": "font.ttf"
}
```

Colours are `#RRGGBB`, or `#AARRGGBB` for transparency.

**palette** — recolours the Pokémon sprites: `darkest` outlines, `dark`
shadows, `light` midtones, `lightest` highlights, `surround` the screen
edge. `opacity` is how solid windows are, from `0.35` to `1`.

**chrome** — exact menu colours, and the only way to get a dark theme:
`ink` text, `panel` window fill, `shadow` secondary text, `muted` dim
lines, `surround` screen edge, `barFill` and `barText` the top bar,
`hpGreen` `hpYellow` `hpRed` the HP bar.

**background** — `fit` is `cover`, `tile` or `stretch`.

**ball** — a square picture of a whole ball. It sits in the bottom-right
corner, so only its top-left quarter shows.

**borderAsset** — a square picture cut into a 3×3 grid: four corners, four
edges that repeat, and an empty middle.

**smoothing** — `true` if your art has glow or gradients, `false` for
pixel art.

## What gets rejected

Mods are scanned on import. If anything fails, nothing is installed and
the app tells you what was wrong.

- Ripped game art — pictures are compared against the official
  decompilation sprites. Draw your own.
- ROMs and other game files.
- Broken or oversized zips.
