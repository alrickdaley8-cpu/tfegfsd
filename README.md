# tfegfsd — Saturn (IKEMEN GO fan character)

A complete, self-contained fan-made fighting-game character inspired by
**Saint Jaygarcia Saturn** (One Piece), built for **IKEMEN GO 0.99 / 1.x**.

## Layout

```
chars/Saturn/          <- the character (drop this folder into chars/)
  Saturn.def            definition (info/files/palettes)
  Saturn.cmd            commands (movement, buttons, specials, supers, awakening)
  Saturn.cns            base data / physics
  Saturn.st             systems (-2/-3), AI, intro/taunt, all normals
  Saturn1.st            specials + neutral command execution (Statedef -1)
  Saturn2.st            supers, ultimate, awakening, win state
  Saturn.air            every animation
  Saturn.sff            packed sprites (52 sprites, 5 palettes)
  Saturn.snd            packed sounds (24 generated effects/voices)
  _build/               source art/audio + manifests (regenerable)
  DESIGN.md             moveset, frame data, combo routes, design notes
  INSTALL.md            installation + asset rebuild instructions
  TESTPLAN.md           in-engine test checklist
  LIMITATIONS.md        known gaps and remaining asset work
tools/                 asset pipeline (Python 3)
  ingest_sprite.py      build the sprite set from the user's own Saturn image
  saturn_assets.py      placeholder sprite generator
  gen_sounds.py         synthesize the 24 sound effects
  build_sff.py          SFF v2.01 packer
  build_snd.py          SND v1 packer
  validate.py           offline cross-reference validator
  build.sh              one-command rebuild
```

## Quick start

```bash
# repack sounds + sprites and validate (safe, never overwrites your art)
bash tools/build.sh

# build the sprite set from your own AI-generated Saturn image
python3 tools/ingest_sprite.py --input path/to/saturn.png --preview

# offline cross-reference check
python3 tools/validate.py
```

Then copy `chars/Saturn/` into your IKEMEN GO `chars/` directory and add
`Saturn` to your `select.def`. See `chars/Saturn/INSTALL.md`.

> **Note on the visual asset:** the character uses the user's own AI-generated
> Saturn sprite sheet (`chars/Saturn/source/clean_sheet.png`), which is line art.
> The pipeline colorizes it (violet robe / white beard / dark outline) and maps
> frames to animations. See `chars/Saturn/MAPPING.md` for the frame mapping and
> how to correct it, and `tools/colorize_and_build.py` to rebuild.
