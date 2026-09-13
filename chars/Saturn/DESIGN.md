# Saturn — Design Document

Fan-made IKEMEN GO character inspired by Saint Jaygarcia Saturn (One Piece).
All assets are original/generated; no copyrighted material is embedded.

## 1. Archetype & philosophy

Saturn is a **heavyweight boss** character: high damage, high durability, long
reach, deliberate (slow) animations, and a timed **Awakening** transformation.
His weaknesses are deliberate:

| Weakness | Implementation |
|---|---|
| Slow startup on big moves | 5HP 10f, Gorosei 20f, supers have SuperPause + long active windup |
| Large hurtboxes | persistent oversized CLSN2 in `Statedef -2` (statetype-aware) |
| Recovery on major attacks | long `0,0` tail frames on heavies and supers |
| Limited mobility | slow walk (2.3), slow back walk (2.1), no dash special |
| Meter-gated power | supers cost 1000/2000/3000, Awakening costs 2000 + drains |
| Timed boss state | Awakening drains 9 power/tick and auto-reverts at 0 power |

No move has permanent invulnerability, permanent super armor, or an unblockable
loop. Every special is guardable (`guardflag` set) and every projectile has
`projremove`/`projremovetime` so it cannot persist or spawn infinitely.

## 2. Moveset summary

### Normals (state == animation number)

| Move | Input | Startup | Active | Recovery | Damage | Notes |
|---|---|---|---|---|---|---|
| 5LP Spider Jab | x | 2 | 3 | 6 | 42 | chains to 5MP/5HP/5LK |
| 5MP Demon Slash | y | 4 | 4 | 8 | 62 | chains to 5HP |
| 5HP Elder Claw | z | 10 | 4 | 15 | 90 | hard knockdown |
| 5LK Low Sweep | a | 4 | 3 | 7 | 40 | low, chains to 5HK |
| 5HK Dread Stomp | b | 9 | 4 | 15 | 92 | hard knockdown |
| 6HP Demon Fang | c | 13 | 4 | 13 | 80 | **overhead** (must stand-block) |
| 4HP Demon Lance | B + z | 6 | 5 | 11 | 68 | long-range command poke |
| 2LP | d+x / d+a | 2 | 3 | 6 | 36 | low, chains to 2MP |
| 2MP | d+y / d+b | 5 | 3 | 8 | 58 | low, chains to 2HP |
| 2HP Elder Uppercut | d+z / d+c | 7 | 5 | 13 | 84 | **launcher** / anti-air |
| jLP | air x / a | 4 | 3 | 8 | 42 | |
| jMP | air y / b | 6 | 4 | 9 | 60 | |
| jHK Falling Fang | air z / c | 7 | 4 | 10 | 82 | dive, hard knockdown |

### Specials (states 1000–1250)

| Move | Input | Cost | Startup | Notes |
|---|---|---|---|---|
| Hell Claw | QCF+P | — | 8 | claw strike; awakened: extra 2nd claw |
| Haoshoku | QCB+P | — | 12 | ground shockwave projectile (low block) |
| Juryoku | QCF+K | — | 16 | overhead gravity crush; launches |
| Veil Shift | DP+K | — | 6 | teleport behind opponent (no damage) |
| Aku no Hado | QCB+K | — | 6 | reversal burst; short full invuln, punishable |
| Gorosei Judgment | B,F+P | — | 20 | huge overhead claw, hard knockdown |

### Supers / ultimate (states 3000–3300)

| Move | Input | Cost | Notes |
|---|---|---|---|
| Elder Star Hellfire (Lv1) | QCFx2+P | 1000 | fast 5-hit hell beam projectile |
| Judgment of the Saint (Lv2) | QCBx2+P | 2000 | massive overhead slash, wallbounce |
| Elder Star Transcendence (Lv3) | QCFx2+K | 3000 | 3-phase cinematic crush + screen darken |
| Sun's Eclipse (ULT) | QCBx2+K | 3000 | awakened only; screen-wide eclipse explosion |

### Awakening (states 3400/3450)

`D,D + start` while `power >= 2000` enters Awakening: costs 2000 power, sets
`AttackMulSet 1.2` / `DefenceMulSet 1.1`, grants full invulnerability during
the transform, and shows an aura field. While awakened, power drains 9/tick and
the form auto-reverts (state 3450) at 0 power. `D,D + start` again reverts
early. Buffs are reset on revert and at round start (`Statedef -3`).

## 3. Combo routes

Chains are **forward-only** (L→M→H); the cancel dispatcher in `Statedef -2`
only fires on `movecontact`, so no chain can loop back into itself.

Example routes (all confirmed against frame data):

1. **Basic chain** — `5LP → 5MP → 5HP`
2. **Low chain** — `2LP → 2MP → 2HP (launcher)`
3. **Launcher juggle** — `2HP → jump → jMP → jHK`
4. **Ground-to-special** — `5LP → 5MP → 5HP → (cancel) QCF+P Hell Claw`
5. **Ranged confirm** — `5HP → (cancel) QCB+P Haoshoku`
6. **Overhead mix** — `6HP (overhead) → 5LP → 5MP`
7. **Corner super** — `5LP → 5MP → 5HP → (cancel) QCFx2+P Lv1`
8. **Full meter launcher** — `2HP → jMP → jHK → land → QCF+K Juryoku → QCFx2+K Lv3`
9. **Awakened route** — `5LP → 5MP → 5HP → Hell Claw (2 hits) → Lv1`

Damage scaling: heavier hits have higher `air.juggle`/knockdown so juggles end
naturally; `fall = 1` on knockdowns prevents relaunch loops. Supers give
`getpower = 0`, so meter cannot be farmed by super chains.

## 4. Sprite & animation numbering

Sprite file `Saturn.sff`:

* Group **0** = body frames (fixed 128x168 canvas, axis at 64,160 so frames
  never jitter):
  * 0–6,8,10–13 : idle/walk/crouch/jump/guard/hurt/liedown
  * 20–26 : standing normals, 30–32 : crouching, 40–42 : air
  * 50/51/52 : intro / win / taunt
  * 60–65 : specials, 70–73 : supers/ultimate
  * 80–82 : awakened idle A/B + transform
* Group **100** = effects (0 claw slash, 1 orb, 2 shockwave, 3 impact,
  4 aura field, 5 smoke, 6 beam, 7 spark, 8 gravity orb, 9 eye flash, 10 flash)

Animations (Saturn.air): reserved/engine actions (0–195, 5000–5300) plus
attack actions 200–799, specials 1000–1250, supers 3000–3300, transformation
3400/3450/3500, and effect animations 7000–7100 plus looping projectile
animations 7011/7021/7061/7081.

## 5. Hitbox design

* `CLSN1` (attack) boxes are placed per-move and sized to the reach of each
  attack; big boss moves (Gorosei, supers) get wide boxes matching the VFX.
* `CLSN2` (hurt) boxes are deliberately **large** and persistent (defined once
  in `Statedef -2`, statetype-aware), which is Saturn's main weakness.
* Projectiles carry their own embedded hitdefs with `projhits`/`projremove`
  bounds.

## 6. Visual effects

Dark violet/magenta aura language (additive blend `A`), red claw arcs, black
"sun"/gravity orbs, screen darkening (`EnvColor`) and shake (`EnvShake`) on
supers. All original, generated from the effect palette ramp.

## 7. AI design

The AI executor lives in `Statedef -2` (Saturn.st), gated by `ailevel > 0` and a
cooldown (`var(30)`). It handles spacing (approach/retreat), anti-air (2HP),
guard under pressure, mid-range specials, long-range gravity, super use at
1000/3000 power, awakening, and teleport escapes. Probability thresholds scale
with `ailevel`, so higher difficulties are more competent without free
resources. The AI only acts when `ctrl = 1`, so it can never interrupt its own
moves or act from hitstun (no "locked" behavior).

## 8. State safety checklist

* Every attack state: `ctrl = 0`, single exit `ChangeState` to 0/11/52.
* HitDefs fire once (`trigger1 = !time`); awakened extra hits fire at fixed
  times (`triggerall = var(20)`, `trigger1 = time = N`).
* Projectiles: `projremove` + `projremovetime` + `projhits` bounds.
* Invulnerability: always short `NotHitBy` windows (teleport, burst startup,
  transform), never permanent.
* No self-damage, no helper spam (no `Helper` controllers used at all), no
  corner loops (knockdown `fall = 1`, corner push enabled).
