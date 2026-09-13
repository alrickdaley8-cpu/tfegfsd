# Saturn — Test Plan

Run `python3 tools/validate.py` first for the offline cross-reference check
(sprites, anims, states, commands, sounds). Then verify in-engine (Training mode
is fastest):

## Load & boot

- [ ] Character selects and loads with no console errors.
- [ ] No "missing sprite / missing anim / missing sound" warnings.
- [ ] Intro plays (state 191) and hands control to idle (state 0).

## Basic movement

- [ ] Idle animation loops (breathing).
- [ ] Walk forward/backward animates and does not jitter.
- [ ] Crouch (down) animates; stand/crouch transitions are clean.
- [ ] Jump (neutral/forward/back), air control, and landing (state 52).
- [ ] Run/hop forward (100) and hop back (105) work.

## Normals

- [ ] 5LP/5MP/5HP/5LK/5HK connect and animate correctly.
- [ ] 6HP is an overhead (opponent must stand-block).
- [ ] 4HP (B+z) reaches at long range.
- [ ] 2LP/2MP/2HP crouch attacks; 2HP launches (air juggle).
- [ ] jLP/jMP/jHK air attacks land correctly.
- [ ] Chains L→M→H work on hit; no chain loops into itself.

## Specials (from neutral)

- [ ] Hell Claw (QCF+P) hits; awakened form adds the second claw.
- [ ] Haoshoku (QCB+P) fires a ground wave; it dies on hit/time (no infinite
      projectiles).
- [ ] Juryoku (QCF+K) overhead launches.
- [ ] Veil Shift (DP+K) teleports behind the opponent, brief invuln, recoverable.
- [ ] Aku no Hado (QCB+K) is invulnerable on startup and bursts; punishable on
      whiff.
- [ ] Gorosei Judgment (B,F+P) hits a wide area and knocks down.

## Supers

- [ ] Lv1 (QCFx2+P) costs 1000 power, fires a multi-hit beam, no meter return.
- [ ] Lv2 (QCBx2+P) costs 2000, wallbounces, EnvShake.
- [ ] Lv3 (QCFx2+K) costs 3000, three phases, screen darkens.
- [ ] Blocked supers are punishable (recovery tail exists).

## Awakening / transformation

- [ ] D,D+start with 2000 power enters Awakening (aura, buffs, invuln).
- [ ] Awakened idle/effects visibly differ from normal.
- [ ] Power drains while awakened and auto-reverts at 0 (no permanent state).
- [ ] D,D+start again reverts early; attack/defence buffs reset.

## Ultimate

- [ ] Sun's Eclipse (QCBx2+K) only works when awakened and at 3000 power.
- [ ] Eclipse has a clear cinematic startup cue; ends in a recoverable state.

## Defense / hit reactions

- [ ] Standing/crouching/air block animations play on guard.
- [ ] Hit reactions (light/medium/hard/back/air) play correctly.
- [ ] Knockdown → liedown → getup returns to a valid controllable state.
- [ ] Dizzy (if it occurs) recovers; no permanent stun.

## KO / victory

- [ ] KO animation plays; character reaches liedead and stays down.
- [ ] Win pose (state 180) plays after a win (the engine forces this state).

## AI

- [ ] CPU Saturn completes a match without getting stuck.
- [ ] AI uses spacing, anti-air, specials, supers, awakening, and teleport.
- [ ] Higher AI level is more aggressive but never gains free resources.
- [ ] AI cannot act while in hitstun/knockdown (no locked behavior).

## Cleanup / stability

- [ ] Afterimage/aura explods from Awakening are removed on revert (no leaks).
- [ ] Projectiles do not accumulate (projremove/projremovetime respected).
- [ ] No infinite combo, corner loop, or self-damage observed after extended
      play.
