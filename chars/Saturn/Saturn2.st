;==============================================================================
; Saturn2.st — SUPERS, ULTIMATE, AWAKENING, and round states
;==============================================================================
; States:
;   3000 Elder Star Hellfire   Lv1 super  (QCFx2 + P, cost 1000)
;   3100 Judgment of the Saint Lv2 super  (QCBx2 + P, cost 2000)
;   3200 Elder Star Transcendence Lv3      (QCFx2 + K, cost 3000)
;   3300 Sun's Eclipse         ULTIMATE   (QCBx2 + K, cost 3000, awakened only)
;   3400 Awakening transformation          (D,D + start, cost 2000)
;   3450 Revert to normal
;   180  Win pose (the engine force-changes the winner into state 180; common1
;        does NOT define it, so we must)
;
; Supers give no meter back (getpower = 0) so they cannot chain into themselves;
; each has a SuperPause startup cue and a punishable tail if blocked. Awakening
; applies AttackMulSet 1.2 / DefenceMulSet 1.1 for its duration and is reset on
; revert and at round start (see Saturn.st Statedef -3).
;==============================================================================

;==============================================================================
; 3000 : ELDER STAR HELLFIRE  (Lv1)  — fast multi-hit hell beam
;==============================================================================
[Statedef 3000]
type    = S
movetype= A
physics = S
juggle  = 10
velset  = 0, 0
ctrl    = 0
anim    = 3000
poweradd= 0
sprpriority = 2

[State 3000, Cost]
type = PowerAdd
trigger1 = !time
value = -1000

[State 3000, SuperPause]
type = SuperPause
trigger1 = !time
time = 30
movetime = 30
darken = 1
unhittable = 1
sound = S10, 5

[State 3000, Hell beam]
type = Projectile
trigger1 = time = 32
projid = 3000
projanim = 7061
projhitanim = 7030
projremanim = 7050
projpriority = 6
projremove = 0
projremovetime = 30
projhits = 5
velocity = 9, 0
remvelocity = 0, 0
projsprpriority = 3
projshadow = 0
offset = 40, -80
attr = S, HP
hitflag = MAFD
guardflag = MA
animtype = Hard
damage = 55, 5
priority = 5
pausetime = 6, 6
hitsound = S5, 6
guardsound = S5, 3
sparkxy = 0, -80
guard.sparkxy = 0, -80
ground.type = High
ground.slidetime = 10
ground.hittime = 16
ground.velocity = -4.0
guard.velocity = -4.0
air.animtype = Hard
air.hittime = 16
air.velocity = -4.0, -5.0
air.juggle = 8
getpower = 0, 0
givepower = 0, 0

[State 3000, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 3100 : JUDGMENT OF THE SAINT  (Lv2)  — massive overhead slash
;==============================================================================
[Statedef 3100]
type    = S
movetype= A
physics = S
juggle  = 10
velset  = 0, 0
ctrl    = 0
anim    = 3100
poweradd= 0
sprpriority = 2

[State 3100, Cost]
type = PowerAdd
trigger1 = !time
value = -2000

[State 3100, SuperPause]
type = SuperPause
trigger1 = !time
time = 30
movetime = 30
darken = 1
unhittable = 1
sound = S10, 5

[State 3100, Slash fx]
type = Explod
trigger1 = time = 34
anim = 7000
pos = 46, -110
postype = p1
sprpriority = 4
ownpal = 1
scale = 1.6, 1.6

[State 3100, Attack box]
type = OverrideClsn
trigger1 = time = [34, 38]
group = Clsn1
index = 0
rect = 10, -170, 80, -20
[State 3100, HitDef]
type = HitDef
trigger1 = !time
attr = A, HA
damage = 320, 10
animtype = Hard
guardflag = HA
hitflag = MAF
pausetime = 14, 14
sparkxy = 44, -110
hitsound = S5, 2
guardsound = S5, 3
ground.type = High
ground.slidetime = 18
ground.hittime = 24
ground.velocity = -10.0
guard.velocity = -10.0
air.animtype = Hard
air.hittime = 20
air.velocity = -10.0, -8.0
air.juggle = 8
getpower = 0, 0
givepower = 0, 0
ground.cornerpush.veloff = -8.0
air.cornerpush.veloff = -8.0
fall = 1
fall.recover = 0

[State 3100, EnvShake]
type = EnvShake
trigger1 = movehit = 1
time = 20
freq = 80
ampl = 6

[State 3100, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 44, -110
postype = p1
sprpriority = 5
ownpal = 1
scale = 1.4, 1.4

[State 3100, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 44, -110
postype = p1
sprpriority = 5
ownpal = 1
scale = 1.4, 1.4

[State 3100, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 3200 : ELDER STAR TRANSCENDENCE  (Lv3)  — cinematic three-phase crush
;==============================================================================
[Statedef 3200]
type    = S
movetype= A
physics = S
juggle  = 12
velset  = 0, 0
ctrl    = 0
anim    = 3200
poweradd= 0
sprpriority = 2

[State 3200, Cost]
type = PowerAdd
trigger1 = !time
value = -3000

[State 3200, SuperPause]
type = SuperPause
trigger1 = !time
time = 40
movetime = 40
darken = 2
unhittable = 1
sound = S10, 5

[State 3200, Darken screen]
type = EnvColor
trigger1 = time = 40
value = -40, -40, -40
time = 32
under = 0

[State 3200, Phase 1 fx]
type = Explod
trigger1 = time = 40
anim = 7000
pos = 40, -100
postype = p1
sprpriority = 4
ownpal = 1
scale = 1.3, 1.3

[State 3200, Phase 1 box]
type = OverrideClsn
trigger1 = time = [40, 44]
group = Clsn1
index = 0
rect = 16, -150, 72, -30
[State 3200, Phase 1 HitDef]
type = HitDef
trigger1 = time = 40
attr = S, NA
damage = 110, 6
animtype = Hard
guardflag = MA
hitflag = MAF
pausetime = 10, 10
sparkxy = 42, -95
hitsound = S5, 2
guardsound = S5, 3
ground.type = High
ground.slidetime = 12
ground.hittime = 16
ground.velocity = -5.0
guard.velocity = -5.0
air.animtype = Hard
air.hittime = 16
air.velocity = -5.0, -5.0
air.juggle = 8
getpower = 0, 0
givepower = 0, 0

[State 3200, Phase 2 fx]
type = Explod
trigger1 = time = 48
anim = 7000
pos = 40, -100
postype = p1
sprpriority = 4
ownpal = 1
scale = 1.3, 1.3

[State 3200, Phase 2 box]
type = OverrideClsn
trigger1 = time = [48, 52]
group = Clsn1
index = 0
rect = 16, -150, 72, -30
[State 3200, Phase 2 HitDef]
type = HitDef
trigger1 = time = 48
attr = S, NA
damage = 110, 6
animtype = Hard
guardflag = MA
hitflag = MAF
pausetime = 10, 10
sparkxy = 42, -95
hitsound = S5, 2
guardsound = S5, 3
ground.type = High
ground.slidetime = 12
ground.hittime = 16
ground.velocity = -5.0
guard.velocity = -5.0
air.animtype = Hard
air.hittime = 16
air.velocity = -5.0, -5.0
air.juggle = 8
getpower = 0, 0
givepower = 0, 0

[State 3200, Phase 3 fx]
type = Explod
trigger1 = time = 56
anim = 7040
pos = 40, -100
postype = p1
sprpriority = 4
ownpal = 1
scale = 1.6, 1.6

[State 3200, Phase 3 box]
type = OverrideClsn
trigger1 = time = [56, 60]
group = Clsn1
index = 0
rect = 8, -160, 82, -16
[State 3200, Phase 3 HitDef]
type = HitDef
trigger1 = time = 56
attr = S, NA
damage = 220, 9
animtype = Hard
guardflag = MA
hitflag = MAF
pausetime = 13, 13
sparkxy = 44, -100
hitsound = S5, 8
guardsound = S5, 3
ground.type = High
ground.slidetime = 16
ground.hittime = 22
ground.velocity = -9.0, -4.0
guard.velocity = -9.0
air.animtype = Hard
air.hittime = 20
air.velocity = -9.0, -7.0
air.juggle = 8
getpower = 0, 0
givepower = 0, 0
ground.cornerpush.veloff = -7.0
air.cornerpush.veloff = -7.0
fall = 1
fall.recover = 0

[State 3200, EnvShake]
type = EnvShake
trigger1 = movehit = 1
time = 26
freq = 90
ampl = 8

[State 3200, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 3300 : SUN'S ECLIPSE  (ULTIMATE, awakened only)  — screen-dominating eclipse
;==============================================================================
[Statedef 3300]
type    = S
movetype= A
physics = S
juggle  = 15
velset  = 0, 0
ctrl    = 0
anim    = 3300
poweradd= 0
sprpriority = 2

[State 3300, Cost]
type = PowerAdd
trigger1 = !time
value = -3000

[State 3300, SuperPause]
type = SuperPause
trigger1 = !time
time = 45
movetime = 45
darken = 2
unhittable = 1
sound = S10, 5

[State 3300, Eclipse darkness]
type = EnvColor
trigger1 = time = 45
value = -60, -60, -60
time = 40
under = 0

[State 3300, Black sun]
type = Explod
trigger1 = time = 46
anim = 7081
pos = 0, -170
postype = p1
sprpriority = 3
ownpal = 1
scale = 2.6, 2.6

[State 3300, Rumble]
type = EnvShake
trigger1 = time = 46
time = 40
freq = 60
ampl = 4

[State 3300, Explosion fx]
type = Explod
trigger1 = time = 60
anim = 7040
pos = 40, -100
postype = p1
sprpriority = 4
ownpal = 1
scale = 2.6, 2.6

[State 3300, Attack box]
type = OverrideClsn
trigger1 = time = [60, 64]
group = Clsn1
index = 0
rect = 0, -180, 96, -8
[State 3300, Attack box B]
type = OverrideClsn
trigger1 = time = [60, 64]
group = Clsn1
index = 1
rect = -30, -150, 30, -60
[State 3300, HitDef]
type = HitDef
trigger1 = time = 60
attr = S, HA
damage = 480, 14
animtype = Hard
guardflag = MA
hitflag = MAF
pausetime = 16, 16
sparkxy = 44, -110
hitsound = S5, 8
guardsound = S5, 3
ground.type = High
ground.slidetime = 20
ground.hittime = 26
ground.velocity = -12.0, -6.0
guard.velocity = -12.0
air.animtype = Hard
air.hittime = 22
air.velocity = -12.0, -9.0
air.juggle = 10
getpower = 0, 0
givepower = 0, 0
ground.cornerpush.veloff = -9.0
air.cornerpush.veloff = -9.0
fall = 1
fall.recover = 0

[State 3300, EnvShake hit]
type = EnvShake
trigger1 = movehit = 1
time = 34
freq = 100
ampl = 10

[State 3300, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 44, -110
postype = p1
sprpriority = 5
ownpal = 1
scale = 2.0, 2.0

[State 3300, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 44, -110
postype = p1
sprpriority = 5
ownpal = 1
scale = 2.0, 2.0

[State 3300, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 3400 : AWAKENING TRANSFORMATION  (D,D + start, cost 2000)
;==============================================================================
; Bounded boss state: costs 2000 power, drains while active (see Saturn.st -3)
; and auto-reverts at 0 power. Attack/defence buffs applied here, reset in 3450
; and at round start.
[Statedef 3400]
type    = S
movetype= I
physics = S
velset  = 0, 0
ctrl    = 0
anim    = 3400
poweradd= 0
sprpriority = 2

[State 3400, Cost]
type = PowerAdd
trigger1 = !time
value = -2000

[State 3400, Set awakened flag]
type = VarSet
trigger1 = !time
v = 20
value = 1

[State 3400, Attack buff]
type = AttackMulSet
trigger1 = !time
value = 1.2

[State 3400, Defence buff]
type = DefenceMulSet
trigger1 = !time
value = 1.1

[State 3400, Invuln during transform]
type = NotHitBy
trigger1 = time < 100
value = SCA

[State 3400, Aura]
type = Explod
trigger1 = !time
anim = 7040
pos = 0, -70
postype = p1
bindtime = -1
removetime = 90
sprpriority = -5
ownpal = 1
scale = 1.6, 1.6

[State 3400, Sound]
type = PlaySnd
trigger1 = !time
value = S5, 10
channel = 0

[State 3400, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 3450 : REVERT TO NORMAL
;==============================================================================
[Statedef 3450]
type    = S
movetype= I
physics = S
velset  = 0, 0
ctrl    = 0
anim    = 3450
poweradd= 0
sprpriority = 2

[State 3450, Clear awakened flag]
type = VarSet
trigger1 = !time
v = 20
value = 0

[State 3450, Reset attack]
type = AttackMulSet
trigger1 = !time
value = 1.0

[State 3450, Reset defence]
type = DefenceMulSet
trigger1 = !time
value = 1.0

[State 3450, Sound]
type = PlaySnd
trigger1 = !time
value = S5, 12
channel = 0

[State 3450, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 180 : WIN POSE  (engine-forced; must exist, common1 does not define it)
;==============================================================================
[Statedef 180]
type    = S
ctrl    = 0
anim    = 180
velset  = 0, 0

[State 180, Sound]
type = PlaySnd
trigger1 = !time
value = S10, 4
channel = 0
