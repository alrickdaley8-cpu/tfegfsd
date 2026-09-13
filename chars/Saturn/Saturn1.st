;==============================================================================
; Saturn1.st — SPECIAL MOVES + neutral command execution
;==============================================================================
; States: 1000 Hell Claw, 1050 Haoshoku, 1100 Juryoku, 1150 Veil Shift,
;         1200 Aku no Hado, 1250 Gorosei Judgment
;
; This file also carries [Statedef -1], which maps command inputs to the
; special/super/awakening states while Saturn is in control (the -2 dispatcher
; in Saturn.st handles CANCELS from connected normals; this handles moves done
; from neutral).
;
; Awakened buffs: the transformation (state 3400) sets AttackMulSet 1.2 /
; DefenceMulSet 1.1 and var(20)=1. Awakened-only EXTRA hits below are gated on
; var(20) so the form is stronger but still bounded (it drains power and
; reverts when empty — see Saturn.st Statedef -3).
;
; Safety rules honored throughout:
;   * every state has ctrl=0 and a single exit ChangeState (no self-loops)
;   * HitDefs fire once (trigger1 = !time); extra awakened hits fire once at a
;     fixed time (trigger1 = time = N, triggerall = var(20))
;   * projectiles have projremove + projremovetime (no infinite projectiles)
;   * invulnerability is always a short window (NotHitBy time-gated), never
;     permanent
;==============================================================================

;------------------------------------------------------------------------------
; Statedef -1 : neutral command execution
; Order matters: supers are checked BEFORE specials (so a QCFx2 input is not
; eaten by the QCF special), awakening before taunt, and specials before plain
; buttons (so QCF+x doesn't come out as a standing normal).
;------------------------------------------------------------------------------
[Statedef -1]

;--- Supers (highest priority) ------------------------------------------------
[State -1, Elder Star Hellfire]
type = ChangeState
value = 3000
triggerall = command = "QCFx2_x" || command = "QCFx2_y" || command = "QCFx2_z"
triggerall = ctrl
triggerall = statetype != A
triggerall = power >= 1000
trigger1 = 1

[State -1, Judgment of the Saint]
type = ChangeState
value = 3100
triggerall = command = "QCBx2_x" || command = "QCBx2_y" || command = "QCBx2_z"
triggerall = ctrl
triggerall = statetype != A
triggerall = power >= 2000
trigger1 = 1

[State -1, Elder Star Transcendence]
type = ChangeState
value = 3200
triggerall = command = "QCFx2_a" || command = "QCFx2_b" || command = "QCFx2_c"
triggerall = ctrl
triggerall = statetype != A
triggerall = power >= 3000
trigger1 = 1

[State -1, Sun's Eclipse]
type = ChangeState
value = 3300
triggerall = command = "QCBx2_a" || command = "QCBx2_b" || command = "QCBx2_c"
triggerall = ctrl
triggerall = statetype != A
triggerall = power >= 3000
triggerall = var(20) > 0
trigger1 = 1

;--- Awakening / revert --------------------------------------------------------
[State -1, Awaken]
type = ChangeState
value = 3400
triggerall = command = "AWAKEN"
triggerall = ctrl
triggerall = statetype != A
triggerall = var(20) = 0
triggerall = power >= 2000
trigger1 = 1

[State -1, Revert]
type = ChangeState
value = 3450
triggerall = command = "AWAKEN"
triggerall = ctrl
triggerall = statetype != A
triggerall = var(20) > 0
trigger1 = 1

;--- Specials ------------------------------------------------------------------
[State -1, Hell Claw]
type = ChangeState
value = 1000
triggerall = command = "QCF_x" || command = "QCF_y" || command = "QCF_z"
triggerall = ctrl
triggerall = statetype != A
trigger1 = 1

[State -1, Haoshoku]
type = ChangeState
value = 1050
triggerall = command = "QCB_x" || command = "QCB_y" || command = "QCB_z"
triggerall = ctrl
triggerall = statetype != A
trigger1 = 1

[State -1, Juryoku]
type = ChangeState
value = 1100
triggerall = command = "QCF_a" || command = "QCF_b" || command = "QCF_c"
triggerall = ctrl
triggerall = statetype != A
trigger1 = 1

[State -1, Veil Shift]
type = ChangeState
value = 1150
triggerall = command = "DP_a" || command = "DP_b" || command = "DP_c"
triggerall = ctrl
triggerall = statetype != A
trigger1 = 1

[State -1, Aku no Hado]
type = ChangeState
value = 1200
triggerall = command = "QCB_a" || command = "QCB_b" || command = "QCB_c"
triggerall = ctrl
triggerall = statetype != A
trigger1 = 1

[State -1, Gorosei Judgment]
type = ChangeState
value = 1250
triggerall = command = "BF_x" || command = "BF_y" || command = "BF_z"
triggerall = ctrl
triggerall = statetype != A
trigger1 = 1

;--- Command normal : 4HP Demon Lance ------------------------------------------
[State -1, Demon Lance]
type = ChangeState
value = 260
triggerall = command = "back_z"
triggerall = ctrl
triggerall = statetype = S
trigger1 = 1

;--- Taunt (double-tap start) --------------------------------------------------
[State -1, Taunt]
type = ChangeState
value = 195
triggerall = command = "taunt"
triggerall = ctrl
triggerall = statetype = S
trigger1 = 1

;--- Standing normals ----------------------------------------------------------
[State -1, 5LP]
type = ChangeState
value = 200
triggerall = command = "x"
triggerall = ctrl
triggerall = statetype = S
trigger1 = 1

[State -1, 5MP]
type = ChangeState
value = 210
triggerall = command = "y"
triggerall = ctrl
triggerall = statetype = S
trigger1 = 1

[State -1, 5HP]
type = ChangeState
value = 220
triggerall = command = "z"
triggerall = ctrl
triggerall = statetype = S
trigger1 = 1

[State -1, 5LK]
type = ChangeState
value = 230
triggerall = command = "a"
triggerall = ctrl
triggerall = statetype = S
trigger1 = 1

[State -1, 5HK]
type = ChangeState
value = 240
triggerall = command = "b"
triggerall = ctrl
triggerall = statetype = S
trigger1 = 1

[State -1, 6HP overhead]
type = ChangeState
value = 250
triggerall = command = "c"
triggerall = ctrl
triggerall = statetype = S
trigger1 = 1

;--- Crouching normals ----------------------------------------------------------
[State -1, 2LP]
type = ChangeState
value = 400
triggerall = command = "x" || command = "a"
triggerall = ctrl
triggerall = statetype = C
trigger1 = 1

[State -1, 2MP]
type = ChangeState
value = 410
triggerall = command = "y" || command = "b"
triggerall = ctrl
triggerall = statetype = C
trigger1 = 1

[State -1, 2HP launcher]
type = ChangeState
value = 420
triggerall = command = "z" || command = "c"
triggerall = ctrl
triggerall = statetype = C
trigger1 = 1

;--- Air normals ----------------------------------------------------------------
[State -1, jLP]
type = ChangeState
value = 600
triggerall = command = "x" || command = "a"
triggerall = ctrl
triggerall = statetype = A
trigger1 = 1

[State -1, jMP]
type = ChangeState
value = 610
triggerall = command = "y" || command = "b"
triggerall = ctrl
triggerall = statetype = A
trigger1 = 1

[State -1, jHK]
type = ChangeState
value = 620
triggerall = command = "z" || command = "c"
triggerall = ctrl
triggerall = statetype = A
trigger1 = 1

;==============================================================================
; 1000 : HELL CLAW  (QCF + P)  — demon claw strike
;==============================================================================
; startup 8, active 4, recovery 10. Awakened: extra follow-up claw at t=14.
[Statedef 1000]
type    = S
movetype= A
physics = S
juggle  = 6
velset  = 0, 0
ctrl    = 0
anim    = 1000
poweradd= 20
sprpriority = 2

[State 1000, Claw slash fx]
type = Explod
trigger1 = time = 8
anim = 7000
pos = 42, -90
postype = p1
sprpriority = 4
ownpal = 1

[State 1000, Attack box]
type = Clsn1
trigger1 = time = [8, 11]
clsn1default = 1
clsn1[0] = 20, -120, 66, -40

[State 1000, HitDef]
type = HitDef
trigger1 = !time
attr = S, NA
damage = 100, 6
animtype = Hard
guardflag = MA
hitflag = MAF
pausetime = 10, 10
sparkxy = 44, -84
hitsound = S5, 2
guardsound = S5, 3
ground.type = High
ground.slidetime = 14
ground.hittime = 18
ground.velocity = -6.0
guard.velocity = -6.0
air.animtype = Hard
air.hittime = 16
air.velocity = -6.0, -6.0
air.juggle = 6
getpower = 80, 40
givepower = 40, 20
ground.cornerpush.veloff = -4.0
air.cornerpush.veloff = -4.0
fall = 1
fall.recover = 0

;--- awakened: follow-up claw ------------------------------------------------
[State 1000, Awakened Claw fx]
type = Explod
triggerall = var(20) > 0
trigger1 = time = 14
anim = 7000
pos = 46, -96
postype = p1
sprpriority = 4
ownpal = 1

[State 1000, Awakened attack box]
type = Clsn1
triggerall = var(20) > 0
trigger1 = time = [14, 16]
clsn1default = 1
clsn1[0] = 22, -124, 70, -30

[State 1000, Awakened HitDef]
type = HitDef
triggerall = var(20) > 0
trigger1 = time = 14
attr = S, NA
damage = 70, 4
animtype = Hard
guardflag = MA
hitflag = MAF
pausetime = 8, 8
sparkxy = 48, -90
hitsound = S5, 2
guardsound = S5, 3
ground.type = High
ground.slidetime = 12
ground.hittime = 16
ground.velocity = -5.0, -3.0
guard.velocity = -5.0
air.animtype = Hard
air.hittime = 14
air.velocity = -5.0, -5.0
air.juggle = 6
getpower = 40, 20
givepower = 20, 10
ground.cornerpush.veloff = -3.0
air.cornerpush.veloff = -3.0
fall = 1

[State 1000, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 44, -84
postype = p1
sprpriority = 5
ownpal = 1

[State 1000, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 44, -84
postype = p1
sprpriority = 5
ownpal = 1

[State 1000, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 1050 : HAOSHOKU  (QCB + P)  — shockwave pressure (ground projectile)
;==============================================================================
; startup 12, fires a ground-travelling shockwave ring; recovery 12.
[Statedef 1050]
type    = S
movetype= A
physics = S
juggle  = 8
velset  = 0, 0
ctrl    = 0
anim    = 1050
poweradd= 20
sprpriority = 2

[State 1050, Charge fx]
type = Explod
trigger1 = time = 6
anim = 7040
pos = 0, -70
postype = p1
sprpriority = 3
ownpal = 1
scale = 0.6, 0.6

[State 1050, Shockwave projectile]
type = Projectile
trigger1 = time = 12
projid = 1050
projanim = 7021
projhitanim = 7030
projremanim = 7050
projpriority = 5
projremove = 1
projremovetime = 42
velocity = 6, 0
remvelocity = 0, 0
projsprpriority = 3
projshadow = 0
offset = 30, -20
attr = S, SP
hitflag = MAF
guardflag = MA
animtype = Medium
damage = 90, 6
priority = 4
pausetime = 8, 8
hitsound = S5, 6
guardsound = S5, 3
sparkxy = 0, -40
guard.sparkxy = 0, -40
ground.type = Low
ground.slidetime = 14
ground.hittime = 16
ground.velocity = -5.0
guard.velocity = -5.0
air.animtype = Medium
air.hittime = 16
air.velocity = -4.0, -5.0
air.juggle = 6
getpower = 60, 30
givepower = 30, 15
fall = 1

;--- awakened: second, faster wave -------------------------------------------
[State 1050, Awakened Shockwave]
type = Projectile
triggerall = var(20) > 0
trigger1 = time = 16
projid = 1051
projanim = 7021
projhitanim = 7030
projremanim = 7050
projpriority = 5
projremove = 1
projremovetime = 36
velocity = 8, 0
remvelocity = 0, 0
projsprpriority = 3
projshadow = 0
offset = 30, -20
attr = S, SP
hitflag = MAF
guardflag = MA
animtype = Medium
damage = 60, 4
priority = 4
pausetime = 7, 7
hitsound = S5, 6
guardsound = S5, 3
sparkxy = 0, -40
guard.sparkxy = 0, -40
ground.type = Low
ground.slidetime = 12
ground.hittime = 14
ground.velocity = -4.0
guard.velocity = -4.0
air.animtype = Medium
air.hittime = 14
air.velocity = -3.5, -4.5
air.juggle = 6
getpower = 30, 15
givepower = 15, 8
fall = 1

[State 1050, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 1100 : JURYOKU  (QCF + K)  — gravity crush (overhead launcher)
;==============================================================================
; startup 16, active 6, recovery 14. Must be blocked standing (overhead) and
; launches airborne opponents; ground opponents are crushed down then relaunched.
[Statedef 1100]
type    = S
movetype= A
physics = S
juggle  = 8
velset  = 0, 0
ctrl    = 0
anim    = 1100
poweradd= 20
sprpriority = 2

[State 1100, Gravity orb fx]
type = Explod
trigger1 = time = 16
anim = 7081
pos = 40, -70
postype = p1
sprpriority = 4
ownpal = 1

[State 1100, Attack box]
type = Clsn1
trigger1 = time = [16, 21]
clsn1default = 1
clsn1[0] = 18, -150, 66, -60

[State 1100, HitDef]
type = HitDef
trigger1 = !time
attr = A, HA
damage = 120, 7
animtype = Hard
guardflag = HA
hitflag = MAF
pausetime = 11, 11
sparkxy = 40, -90
hitsound = S5, 5
guardsound = S5, 3
ground.type = High
ground.slidetime = 0
ground.hittime = 22
ground.velocity = -3.0, -8.0
guard.velocity = -6.0
air.animtype = Up
air.hittime = 22
air.velocity = -3.0, -9.0
air.juggle = 6
yaccel = 0.5
getpower = 90, 45
givepower = 45, 22
ground.cornerpush.veloff = 0
air.cornerpush.veloff = 0

[State 1100, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 40, -90
postype = p1
sprpriority = 5
ownpal = 1

[State 1100, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 40, -90
postype = p1
sprpriority = 5
ownpal = 1

[State 1100, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 1150 : VEIL SHIFT  (DP + K)  — teleport behind opponent
;==============================================================================
; Repositioning tool. Brief invulnerability during the shift; punishable on
; landing (recovery ~6). No damage.
[Statedef 1150]
type    = S
movetype= I
physics = S
velset  = 0, 0
ctrl    = 0
anim    = 1150
poweradd= 0
sprpriority = 2

[State 1150, Invuln]
type = NotHitBy
trigger1 = time = [0, 9]
value = SCA

[State 1150, Departure smoke]
type = Explod
trigger1 = time = 4
anim = 7050
pos = 0, -40
postype = p1
sprpriority = 4
ownpal = 1

[State 1150, Face opponent]
type = Turn
trigger1 = time = 5

[State 1150, Shift behind]
type = PosAdd
trigger1 = time = 6
x = p2dist x + 60

[State 1150, Arrival smoke]
type = Explod
trigger1 = time = 7
anim = 7050
pos = 0, -40
postype = p1
sprpriority = 4
ownpal = 1

[State 1150, Face opponent]
type = Turn
trigger1 = time = 7

[State 1150, Sound]
type = PlaySnd
trigger1 = time = 5
value = S5, 12
channel = 0

[State 1150, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 1200 : AKU NO HADO  (QCB + K)  — defensive burst (reversal)
;==============================================================================
; startup 6 (fully invulnerable), active 4, recovery 8. Surrounding burst that
; knocks nearby opponents away; punishable on whiff.
[Statedef 1200]
type    = S
movetype= A
physics = S
juggle  = 6
velset  = 0, 0
ctrl    = 0
anim    = 1200
poweradd= 10
sprpriority = 2

[State 1200, Invuln startup]
type = NotHitBy
trigger1 = time = [0, 5]
value = SCA

[State 1200, Burst fx]
type = Explod
trigger1 = time = 6
anim = 7040
pos = 0, -70
postype = p1
sprpriority = 4
ownpal = 1
scale = 1.2, 1.2

[State 1200, Attack box]
type = Clsn1
trigger1 = time = [6, 9]
clsn1default = 1
clsn1[0] = -28, -120, 28, -20
clsn1[1] = 28, -110, 64, -30

[State 1200, HitDef]
type = HitDef
trigger1 = !time
attr = S, NA
damage = 95, 6
animtype = Hard
guardflag = MA
hitflag = MAF
pausetime = 10, 10
sparkxy = 0, -70
hitsound = S5, 8
guardsound = S5, 3
ground.type = High
ground.slidetime = 16
ground.hittime = 20
ground.velocity = -7.0
guard.velocity = -7.0
air.animtype = Hard
air.hittime = 18
air.velocity = -7.0, -6.0
air.juggle = 6
getpower = 80, 40
givepower = 40, 20
ground.cornerpush.veloff = -5.0
air.cornerpush.veloff = -5.0
fall = 1
fall.recover = 0

[State 1200, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 0, -70
postype = p1
sprpriority = 5
ownpal = 1

[State 1200, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 0, -70
postype = p1
sprpriority = 5
ownpal = 1

[State 1200, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; 1250 : GOROSEI JUDGMENT  (B,F + P)  — large-area boss claw
;==============================================================================
; startup 20, active 6, recovery 18. Huge overhead claw slam; hard knockdown.
[Statedef 1250]
type    = S
movetype= A
physics = S
juggle  = 8
velset  = 0, 0
ctrl    = 0
anim    = 1250
poweradd= 25
sprpriority = 2

[State 1250, Claw fx]
type = Explod
trigger1 = time = 20
anim = 7000
pos = 46, -110
postype = p1
sprpriority = 4
ownpal = 1
scale = 1.3, 1.3

[State 1250, Attack box]
type = Clsn1
trigger1 = time = [20, 25]
clsn1default = 1
clsn1[0] = 14, -160, 74, -20

[State 1250, HitDef]
type = HitDef
trigger1 = !time
attr = A, HA
damage = 135, 8
animtype = Hard
guardflag = HA
hitflag = MAF
pausetime = 13, 13
sparkxy = 44, -100
hitsound = S5, 2
guardsound = S5, 3
ground.type = High
ground.slidetime = 16
ground.hittime = 22
ground.velocity = -8.0
guard.velocity = -8.0
air.animtype = Hard
air.hittime = 18
air.velocity = -8.0, -7.0
air.juggle = 6
getpower = 100, 50
givepower = 50, 25
ground.cornerpush.veloff = -6.0
air.cornerpush.veloff = -6.0
fall = 1
fall.recover = 0

[State 1250, Shockwave]
type = Explod
trigger1 = movehit = 1
anim = 7020
pos = 50, 0
postype = p1
sprpriority = 3
ownpal = 1
scale = 1.4, 1.4

[State 1250, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 44, -100
postype = p1
sprpriority = 5
ownpal = 1

[State 1250, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 44, -100
postype = p1
sprpriority = 5
ownpal = 1

[State 1250, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1
