# Saturn — Known Limitations

This is a fan-made character; the items below are the honest, current gaps.

## 1. Visuals are line art (colorized, auto-mapped)

The user's sprite sheet (`chars/Saturn/source/clean_sheet.png`) is **black-and-white
line art**, so the pipeline applies a flat 3-tone palette (dark outline / white
head+beard / violet robe) via `tools/colorize_and_build.py`. The sheet has no
machine-readable labels, so the figure→animation mapping in `MAPPING.md` is a
**best-guess** — review `chars/Saturn/_build/sprites/preview.png` and
`chars/Saturn/_build/figures/colorized_contact_sheet.png`, then tell the author
which frame goes where (or edit `SLOT_MAP` and re-run the tool). A colored
sheet would let `tools/ingest_sprite.py` be used directly for full-color art.

## 2. Audio is synthesized placeholders

All 24 sounds are original, procedurally generated WAVs (hits, whooshes,
explosions, roars). No copyrighted voice/music is embedded. Voice lines for
Saturn (roars, taunts) are synthesized approximations; replace with legally
sourced recordings if desired.

## 3. Not validated against a live IKEMEN GO install

No IKEMEN GO installation exists in this workspace, so the character has been
validated only by static cross-reference (`tools/validate.py`) and against the
engine's compiler/source (`src/compiler.go`, `src/image.go`, `src/sound.go`).
It has not been booted in-engine here. Follow TESTPLAN.md on your install.

## 4. Win/lose/round integration

* State 180 (win) is provided because the engine forces winners into it and
  `data/common1.cns.zss` does not define it.
* Lose (170) and draw (175) come from common1; their animations are minimal
  (single hurt frame).

## 5. No storyboard / intro text

`intro.storyboard` and `ending.storyboard` are left empty in `Saturn.def`.

## 6. Balance tuning

Frame data is deliberate but has not been play-tested against the full cast.
Damage values are tuned for a 1150-life boss against ~1000-life standard
characters; adjust `damage`/`life` in `Saturn.cns` and the HitDefs as needed.

## 7. Awakening buffs are global multipliers

Awakening uses `AttackMulSet`/`DefenceMulSet` (1.2 / 1.1) plus var-gated extra
hits. They are reset on revert and at round start; if your build exposes a leak,
the reset lives in `Saturn.st` `Statedef -3`.

## 8. Projectile/effect visuals are approximate

Effects (claw arcs, shockwaves, the "black sun") are generated shapes, not
hand-drawn sprites. They match the dark-violet theme but can be swapped for
custom art via the effect palette ramp in `tools/ingest_sprite.py`.
