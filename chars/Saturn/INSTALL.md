# Saturn — Installation Guide

## Requirements

* **IKEMEN GO** 0.99 / 1.x (uses `ikemenversion = 0.99` in the DEF). The
  character also targets classic M.U.G.E.N-compatible concepts where the engine
  supports them.
* No external fonts, screenpacks, or plugins are required. The character is
  fully self-contained.

## Quick install

1. Copy the **whole `Saturn` folder** into your IKEMEN GO `chars/` directory:

   ```
   Ikemen-GO/
     chars/
       Saturn/
         Saturn.def
         Saturn.cmd
         Saturn.cns
         Saturn.st
         Saturn1.st
         Saturn2.st
         Saturn.air
         Saturn.sff
         Saturn.snd
         _build/         (source sprites/sounds — not needed at runtime)
         DESIGN.md
         INSTALL.md
         TESTPLAN.md
         LIMITATIONS.md
   ```

2. Add Saturn to your select list. In IKEMEN GO this is usually
   `select.def` in the active screenpack, or the `[Select Info]` /
   `[Characters]` section of your system file. Example:

   ```
   [Characters]
   Saturn, stages/stage.def, order=1
   ```

   (Adjust `order=` to taste; `select.def` syntax varies by screenpack.)

3. Launch the engine and pick Saturn in Arcade/VS/Training.

## Rebuilding assets (optional — only if you change source art/audio)

The runtime files `Saturn.sff` and `Saturn.snd` are already built and committed.
If you edit the source assets under `chars/Saturn/_build/`, regenerate with:

```bash
python3 tools/gen_sounds.py      # regenerate _build/sounds/*.wav
python3 tools/build_snd.py       # repack Saturn.snd
python3 tools/saturn_assets.py   # regenerate placeholder sprites (only if needed)
python3 tools/build_sff.py       # repack Saturn.sff
```

Or run the convenience script:

```bash
bash tools/build.sh
```

## Using your own Saturn sprite (important)

The character currently ships with **procedurally generated placeholder
sprites**. To build the sprite set from your own AI-generated Saturn image
(single render **or** sprite sheet):

```bash
python3 tools/ingest_sprite.py --input path/to/saturn.png --preview
```

The tool auto-detects single vs. sheet, removes a solid background, normalizes
frames to the shared canvas (no blurry upscaling), derives the full animation
frame set, builds palettes, and repacks `Saturn.sff`. See
`tools/ingest_sprite.py --help` for `--cols/--rows/--canvas/--axis` overrides.
Frames it derives (or can only approximate) are labelled in
`_build/sprites/manifest.json` (`kind` / `placeholder`) — review and replace
them with real keyframes when available.

## First-run check

If a sprite/anim/sound fails to load, IKEMEN GO logs a warning to the console /
`debug.log`. Run `python3 tools/validate.py` from the repository root for an
offline cross-reference check of every sprite, anim, state, command, and sound
reference.
