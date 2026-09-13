# Saturn — Sprite Mapping (auto-generated)

The user's sprite sheet `ChatGPT Image Sep 13, 2026, 07_09_56 PM.png` is
black/dark-navy **line art** on a white background, laid out as **6 rows × 3
columns** of animation strips. The pipeline (`tools/colorize_and_build.py`):

1. slices each strip into individual figures (`chars/Saturn/_build/figures/`,
   names `r<row>c<col>_f<idx>.png`),
2. removes the white background and **colorizes** the line art (black-violet
   outline + white head/beard + violet robe),
3. auto-maps figures to animation slots (table below) and rebuilds `Saturn.sff`.

Because the sheet has no machine-readable labels, the figure→slot mapping below
is a **best-guess**. To correct it, edit `SLOT_MAP` in
`tools/colorize_and_build.py` and re-run `python3 tools/colorize_and_build.py`.

## Figure IDs (reading order)

The sheet grid: rows `r0`–`r5` (top→bottom), columns `c0`–`c2` (left→right).
Each figure is `r<row>c<col>_f<idx>` (left→right within its strip).

| Sheet region | Figures | Assumed category |
|---|---|---|
| r0c0 | f00–f01 | idle |
| r0c1 | f00–f09 | walk cycle |
| r0c2 | f00–f08 | run / dash |
| r1c0 | f00–f07 | punches / standing normals |
| r1c1 | f00–f05 | more attacks |
| r1c2 | f00–f05 | more attacks |
| r2c0 | f00–f05 | kicks / crouch normals |
| r2c1 | f00–f05 | more attacks |
| r2c2 | f00–f02 | more attacks |
| r3c0 | f00–f06 | crouch / jump / guard / hurt |
| r3c1 | f00–f06 | air normals |
| r3c2 | f00–f06 | more air |
| r4c0 | f00–f08 | specials |
| r4c1 | f00–f04 | awakened |
| r4c2 | f00–f06 | specials / transform |
| r5c0 | f00–f06 | intro / win / taunt |
| r5c1 | f00–f05 | supers |
| r5c2 | f00–f01 | knockdown / KO |

## Current slot assignments

| Slot (group,index) | Animation | Figure used |
|---|---|---|
| 0,0 | idle A | r0c0_f00 |
| 0,1 | idle B | r0c0_f01 |
| 0,2 | walk A | r0c1_f00 |
| 0,3 | walk B | r0c1_f01 |
| 0,4 | crouch | r3c0_f00 |
| 0,5 | jump | r3c0_f01 |
| 0,6 | fall | r3c0_f02 |
| 0,8 | guard | r3c0_f03 |
| 0,10 | hurt stand | r3c0_f04 |
| 0,11 | hurt crouch | r3c0_f05 |
| 0,12 | hurt air | r3c0_f06 |
| 0,13 | lie down | r5c2_f00 |
| 0,20 | 5LP | r1c0_f00 |
| 0,21 | 5MP | r1c0_f01 |
| 0,22 | 5HP | r1c0_f02 |
| 0,23 | 5LK | r1c0_f03 |
| 0,24 | 5HK | r1c0_f04 |
| 0,25 | 6HP overhead | r1c0_f05 |
| 0,26 | 4HP lance | r1c0_f06 |
| 0,30 | 2LP | r2c0_f00 |
| 0,31 | 2MP | r2c0_f01 |
| 0,32 | 2HP launcher | r2c0_f02 |
| 0,40 | jLP | r3c1_f00 |
| 0,41 | jMP | r3c1_f01 |
| 0,42 | jHK | r3c1_f02 |
| 0,50 | intro | r5c0_f00 |
| 0,51 | win | r5c0_f01 |
| 0,52 | taunt | r5c0_f02 |
| 0,60 | Hell Claw | r4c0_f00 |
| 0,61 | Haoshoku | r4c0_f01 |
| 0,62 | Juryoku | r4c0_f02 |
| 0,63 | Veil Shift | r4c0_f03 |
| 0,64 | Aku no Hado | r4c0_f04 |
| 0,65 | Gorosei Judgment | r4c0_f05 |
| 0,70 | Lv1 | r5c1_f00 |
| 0,71 | Lv2 | r5c1_f01 |
| 0,72 | Lv3 | r5c1_f02 |
| 0,73 | Ultimate | r5c1_f03 |
| 0,80 | awakened idle A | r4c1_f00 |
| 0,81 | awakened idle B | r4c1_f01 |
| 0,82 | transform | r4c2_f00 |

## How to correct a frame

Tell me something like **"slot 0,4 (crouch) should be r2c1_f03"** — or edit the
`SLOT_MAP` dictionary directly. Re-run:

```bash
python3 tools/colorize_and_build.py
```

## Visual reference

* `chars/Saturn/_build/figures/colorized_contact_sheet.png` — every figure,
  colorized, labeled `r<c>f<idx>`.
* `chars/Saturn/_build/sprites/preview.png` — the final 41 body frames as they
  appear in-game (labeled `group,index`).

## Note on the art

The sheet is line art, so the colorizer applies a flat 3-tone palette (outline /
white head+beard / violet robe). If you can supply a **colored** sheet (or
colorized frames), drop it in `chars/Saturn/source/` and run
`python3 tools/ingest_sprite.py --input <file>` to use it directly.
