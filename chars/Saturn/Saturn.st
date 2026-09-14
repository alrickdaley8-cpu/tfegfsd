;==============================================================================
; Saturn.st — base systems, AI, intro/taunt, and NORMALS
;==============================================================================
; Variable map (documented for maintainers):
;   var(20)  awakened form flag        (0 = normal, 1 = awakened)
;   var(21)  awakening revert timer / unused spare
;   var(22)  super-cancel window flag  (unused; cancels are trigger-driven)
;   var(30)  AI action cooldown
;   var(31)  AI special-select scratch
;
; Control flow notes:
;   * Hurtbox (CLSN2) is defined once in Statedef -2 and is statetype-aware
;     (Saturn has deliberately LARGE hurtboxes as a boss weakness).
;   * Special/super cancels are handled by the dispatcher in Statedef -2,
;     gated on `movecontact` + command + state ranges, so they cannot loop.
;   * Chains (L->M->H) are forward-only and defined per normal state, so the
;     combo tree is finite (no infinite chains).
;==============================================================================

;------------------------------------------------------------------------------
; Statedef -3 : always-on systems (runs before the current state)
;------------------------------------------------------------------------------
[Statedef -3]

;--- Awakened aura: keep one field-effect explod bound to the player ----------
[State -3, Awakened Aura]
type = Explod
triggerall = var(20) > 0
trigger1 = !NumExplod(7040)
anim = 7040
ID = 7040
postype = p1
pos = 0, -70
facing = 1
vfacing = 1
bindtime = -1
removetime = -1
supermovetime = 9999
pausemovetime = 9999
sprpriority = -5
ownpal = 1
scale = 1.0, 1.0
ignorehitpause = 1

[State -3, Remove Aura]
type = RemoveExplod
triggerall = var(20) = 0
trigger1 = NumExplod(7040)
ID = 7040

;--- Awakened power drain (limits the form's duration safely) -----------------
[State -3, Awakening Drain]
type = PowerAdd
trigger1 = var(20) > 0
trigger1 = RoundState = 2
value = -9

;--- Auto-revert when the awakened form runs dry ------------------------------
[State -3, Revert Check]
type = ChangeState
triggerall = var(20) > 0
trigger1 = power = 0
value = 3450
ctrl = 0

;--- Normalize awakened buffs at round start (guards against leaks) ------------
[State -3, Normalize attack]
type = AttackMulSet
triggerall = var(20) = 0
trigger1 = roundstate = 0
value = 1.0

[State -3, Normalize defence]
type = DefenceMulSet
triggerall = var(20) = 0
trigger1 = roundstate = 0
value = 1.0

;------------------------------------------------------------------------------
; Statedef -2 : always-on systems (runs after the current state)
;------------------------------------------------------------------------------
[Statedef -2]

;--- Persistent hurtbox (boss weakness: large boxes) --------------------------
[State -2, Hurtbox Stand]
type = OverrideClsn
trigger1 = statetype = S
group = Clsn2
index = 0
rect = -30,   0, 30, -152
[State -2, Hurtbox Stand B]
type = OverrideClsn
trigger1 = statetype = S
group = Clsn2
index = 1
rect = -20, -40, 20, -110
[State -2, Hurtbox Crouch]
type = OverrideClsn
trigger1 = statetype = C
group = Clsn2
index = 0
rect = -32,   0, 32,  -96
[State -2, Hurtbox Air]
type = OverrideClsn
trigger1 = statetype = A
group = Clsn2
index = 0
rect = -28,   0, 28, -140
[State -2, Hurtbox Lie]
type = OverrideClsn
trigger1 = statetype = L
group = Clsn2
index = 0
rect = -62,   0, 62,  -44
[State -2, SC HellClaw]
type = ChangeState
value = 1000
triggerall = ctrl = 0
triggerall = stateno = [200, 620]
triggerall = movecontact
trigger1 = command = "QCF_x"
trigger2 = command = "QCF_y"
trigger3 = command = "QCF_z"

[State -2, SC Haoshoku]
type = ChangeState
value = 1050
triggerall = ctrl = 0
triggerall = stateno = [200, 620]
triggerall = movecontact
trigger1 = command = "QCB_x"
trigger2 = command = "QCB_y"
trigger3 = command = "QCB_z"

[State -2, SC Juryoku]
type = ChangeState
value = 1100
triggerall = ctrl = 0
triggerall = stateno = [200, 620]
triggerall = movecontact
trigger1 = command = "QCF_a"
trigger2 = command = "QCF_b"
trigger3 = command = "QCF_c"

[State -2, SC VeilShift]
type = ChangeState
value = 1150
triggerall = ctrl = 0
triggerall = stateno = [200, 620]
triggerall = movecontact
trigger1 = command = "DP_a"
trigger2 = command = "DP_b"
trigger3 = command = "DP_c"

[State -2, SC AkuNoHado]
type = ChangeState
value = 1200
triggerall = ctrl = 0
triggerall = stateno = [200, 620]
triggerall = movecontact
trigger1 = command = "QCB_a"
trigger2 = command = "QCB_b"
trigger3 = command = "QCB_c"

[State -2, SC GoroseiJudgment]
type = ChangeState
value = 1250
triggerall = ctrl = 0
triggerall = stateno = [200, 620]
triggerall = movecontact
trigger1 = command = "BF_x"
trigger2 = command = "BF_y"
trigger3 = command = "BF_z"

;--- super cancel: normals -> Lv1 --------------------------------------------
[State -2, SC Super1 from normal]
type = ChangeState
value = 3000
triggerall = ctrl = 0
triggerall = stateno = [200, 620]
triggerall = movecontact
triggerall = power >= 1000
trigger1 = command = "QCFx2_x"
trigger2 = command = "QCFx2_y"
trigger3 = command = "QCFx2_z"

;--- super cancel: specials -> Lv1 -------------------------------------------
[State -2, SC Super1 from special]
type = ChangeState
value = 3000
triggerall = ctrl = 0
triggerall = stateno = [1000, 1999]
triggerall = movecontact
triggerall = power >= 1000
trigger1 = command = "QCFx2_x"
trigger2 = command = "QCFx2_y"
trigger3 = command = "QCFx2_z"

;==============================================================================
; AI EXECUTOR  (active only when ailevel > 0, i.e. CPU-controlled)
;==============================================================================
; Movement is a glide (VelSet in -2, which runs after the stand state), with a
; walk animation swap.  Actions are gated by a cooldown so the AI is deliberate
; instead of spamming, and scale up with the AI level.
[State -2, AI cooldown tick]
type = VarAdd
triggerall = ailevel > 0
trigger1 = ctrl = 1
trigger1 = var(30) > 0
v = 30
value = -1

[State -2, AI cooldown reset]
type = VarSet
triggerall = ailevel > 0
trigger1 = stateno = 0
trigger1 = prevstateno >= 200
v = 30
value = 14 - ailevel

;--- movement ---
[State -2, AI approach]
type = VelSet
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = p2dist x > 150
x = 2.3

[State -2, AI retreat]
type = VelSet
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = p2dist x < 55
triggerall = p2movetype != H
x = -2.1

[State -2, AI walk anim]
type = ChangeAnim
triggerall = ailevel > 0
triggerall = ctrl = 1
trigger1 = vel x > 0
value = 20
trigger2 = vel x < 0
value = 21

[State -2, AI idle anim]
type = ChangeAnim
triggerall = ailevel > 0
triggerall = ctrl = 1
trigger1 = vel x = 0
trigger1 = anim != [20, 21]
value = 0

;--- action selection (only when cooldown expires and in control) -------------
[State -2, AI block under pressure]
type = ChangeState
value = 130
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = var(30) = 0
triggerall = p2dist x < 80
triggerall = p2movetype = A
trigger1 = random < 80 + ailevel * 8

[State -2, AI anti-air launcher]
type = ChangeState
value = 420
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = var(30) = 0
triggerall = p2statetype = A
triggerall = p2dist x < 90
triggerall = p2dist y > -140
trigger1 = random < 60 + ailevel * 8

[State -2, AI close chain opener]
type = ChangeState
value = 200
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = var(30) = 0
triggerall = p2statetype != A
triggerall = p2dist x < 95
triggerall = p2movetype != H
trigger1 = random < 45 + ailevel * 6

[State -2, AI mid-range special]
type = ChangeState
value = 1050
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = var(30) = 0
triggerall = p2statetype != A
triggerall = p2dist x = [100, 320]
trigger1 = random < 25 + ailevel * 4

[State -2, AI long-range gravity]
type = ChangeState
value = 1100
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = var(30) = 0
triggerall = p2dist x > 320
trigger1 = random < 30 + ailevel * 4

[State -2, AI super 1]
type = ChangeState
value = 3000
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = var(30) = 0
triggerall = power >= 1000
triggerall = p2dist x < 200
triggerall = p2movetype != A
trigger1 = random < 20 + ailevel * 3

[State -2, AI super 3]
type = ChangeState
value = 3200
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = var(30) = 0
triggerall = power >= 3000
triggerall = p2dist x < 200
triggerall = p2life > 0
trigger1 = random < 8 + ailevel * 2

[State -2, AI awaken]
type = ChangeState
value = 3400
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = var(30) = 0
triggerall = var(20) = 0
triggerall = power >= 2000
triggerall = p2dist x > 120
trigger1 = random < 12 + ailevel * 2

[State -2, AI teleport escape]
type = ChangeState
value = 1150
triggerall = ailevel > 0
triggerall = ctrl = 1
triggerall = var(30) = 0
triggerall = p2movetype = A
triggerall = p2dist x < 70
trigger1 = random < 30 + ailevel * 4

;==============================================================================
; INTRO (override of common state 191)
;==============================================================================
[Statedef 191]
type    = S
ctrl    = 0
anim    = 190
velset  = 0, 0

[State 191, Charge sound]
type = PlaySnd
trigger1 = !time
value = S5, 7
channel = 0

[State 191, End]
type = ChangeState
trigger1 = time >= 55
value = 0
ctrl = 1

;==============================================================================
; TAUNT (override of state 195)
;==============================================================================
[Statedef 195]
type    = S
physics = S
anim    = 195
velset  = 0, 0
ctrl    = 0

[State 195, Sound]
type = PlaySnd
trigger1 = !time
value = S10, 0
channel = 0

[State 195, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; NORMALS — STANDING
;==============================================================================

;------------------------------------------------------------------------------
; 200 : 5LP — Spider Jab  (startup 2, active 3, recovery 6)
;------------------------------------------------------------------------------
[Statedef 200]
type    = S
movetype= A
physics = S
juggle  = 4
velset  = 0, 0
ctrl    = 0
anim    = 200
poweradd= 24
sprpriority = 2

[State 200, Attack box]
type = OverrideClsn
trigger1 = time = [2, 4]
group = Clsn1
index = 0
rect = 20, -100, 62, -68
[State 200, HitDef]
type = HitDef
trigger1 = !time
attr = S, NA
damage = 42, 3
animtype = Light
guardflag = MA
hitflag = MAF
pausetime = 7, 7
sparkxy = 40, -80
hitsound = S5, 0
guardsound = S5, 3
ground.type = High
ground.slidetime = 8
ground.hittime = 12
ground.velocity = -2.6
guard.velocity = -3.0
air.animtype = Light
air.hittime = 12
air.velocity = -2.6, -3.5
air.juggle = 4
getpower = 40, 20
givepower = 20, 10
ground.cornerpush.veloff = 0
air.cornerpush.veloff = 0

[State 200, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 40, -80
postype = p1
sprpriority = 5
ownpal = 1

[State 200, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 40, -80
postype = p1
sprpriority = 5
ownpal = 1

[State 200, Chain -> 5MP]
type = ChangeState
value = 210
triggerall = movecontact
trigger1 = command = "y"

[State 200, Chain -> 5HP]
type = ChangeState
value = 220
triggerall = movecontact
trigger1 = command = "z"

[State 200, Chain -> 5LK]
type = ChangeState
value = 230
triggerall = movecontact
trigger1 = command = "a"

[State 200, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;------------------------------------------------------------------------------
; 210 : 5MP — Demon Slash  (startup 4, active 4, recovery 8)
;------------------------------------------------------------------------------
[Statedef 210]
type    = S
movetype= A
physics = S
juggle  = 4
velset  = 0, 0
ctrl    = 0
anim    = 210
poweradd= 28
sprpriority = 2

[State 210, Attack box]
type = OverrideClsn
trigger1 = time = [4, 7]
group = Clsn1
index = 0
rect = 24, -112, 68, -48
[State 210, HitDef]
type = HitDef
trigger1 = !time
attr = S, NA
damage = 62, 4
animtype = Medium
guardflag = MA
hitflag = MAF
pausetime = 9, 9
sparkxy = 44, -80
hitsound = S5, 1
guardsound = S5, 3
ground.type = High
ground.slidetime = 10
ground.hittime = 14
ground.velocity = -3.4
guard.velocity = -3.6
air.animtype = Medium
air.hittime = 14
air.velocity = -3.4, -4.5
air.juggle = 5
getpower = 50, 25
givepower = 25, 12
ground.cornerpush.veloff = 0
air.cornerpush.veloff = 0

[State 210, Slash effect]
type = Explod
trigger1 = time = 4
anim = 7000
pos = 46, -80
postype = p1
sprpriority = 4
ownpal = 1

[State 210, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 44, -80
postype = p1
sprpriority = 5
ownpal = 1

[State 210, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 44, -80
postype = p1
sprpriority = 5
ownpal = 1

[State 210, Chain -> 5HP]
type = ChangeState
value = 220
triggerall = movecontact
trigger1 = command = "z"

[State 210, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;------------------------------------------------------------------------------
; 220 : 5HP — Elder Claw  (startup 10, active 4, recovery 15)
;------------------------------------------------------------------------------
[Statedef 220]
type    = S
movetype= A
physics = S
juggle  = 4
velset  = 0, 0
ctrl    = 0
anim    = 220
poweradd= 34
sprpriority = 2

[State 220, Attack box]
type = OverrideClsn
trigger1 = time = [10, 13]
group = Clsn1
index = 0
rect = 16, -142, 64, -26
[State 220, HitDef]
type = HitDef
trigger1 = !time
attr = S, NA
damage = 90, 6
animtype = Hard
guardflag = MA
hitflag = MAF
pausetime = 12, 12
sparkxy = 40, -90
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
air.fall = 1
air.juggle = 6
getpower = 70, 35
givepower = 35, 17
ground.cornerpush.veloff = -4.0
air.cornerpush.veloff = -4.0
fall = 1
fall.recover = 0

[State 220, Claw effect]
type = Explod
trigger1 = time = 10
anim = 7000
pos = 40, -100
postype = p1
sprpriority = 4
ownpal = 1

[State 220, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 40, -90
postype = p1
sprpriority = 5
ownpal = 1

[State 220, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 40, -90
postype = p1
sprpriority = 5
ownpal = 1

[State 220, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;------------------------------------------------------------------------------
; 230 : 5LK — Low Sweep  (startup 4, active 3, recovery 7)
;------------------------------------------------------------------------------
[Statedef 230]
type    = S
movetype= A
physics = S
juggle  = 4
velset  = 0, 0
ctrl    = 0
anim    = 230
poweradd= 24
sprpriority = 2

[State 230, Attack box]
type = OverrideClsn
trigger1 = time = [4, 6]
group = Clsn1
index = 0
rect = 18, -44, 58, -8
[State 230, HitDef]
type = HitDef
trigger1 = !time
attr = S, NA
damage = 40, 3
animtype = Light
guardflag = L
hitflag = MAF
pausetime = 7, 7
sparkxy = 40, -30
hitsound = S5, 0
guardsound = S5, 3
ground.type = Low
ground.slidetime = 8
ground.hittime = 12
ground.velocity = -2.6
guard.velocity = -3.0
air.animtype = Light
air.hittime = 12
air.velocity = -2.6, -3.5
air.juggle = 4
getpower = 40, 20
givepower = 20, 10
ground.cornerpush.veloff = 0
air.cornerpush.veloff = 0

[State 230, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 40, -30
postype = p1
sprpriority = 5
ownpal = 1

[State 230, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 40, -30
postype = p1
sprpriority = 5
ownpal = 1

[State 230, Chain -> 5HK]
type = ChangeState
value = 240
triggerall = movecontact
trigger1 = command = "b"
trigger2 = command = "c"

[State 230, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;------------------------------------------------------------------------------
; 240 : 5HK — Dread Stomp  (startup 9, active 4, recovery 15)
;------------------------------------------------------------------------------
[Statedef 240]
type    = S
movetype= A
physics = S
juggle  = 4
velset  = 0, 0
ctrl    = 0
anim    = 240
poweradd= 34
sprpriority = 2

[State 240, Attack box]
type = OverrideClsn
trigger1 = time = [9, 12]
group = Clsn1
index = 0
rect = 26, -112, 76, -38
[State 240, HitDef]
type = HitDef
trigger1 = !time
attr = S, NA
damage = 92, 6
animtype = Hard
guardflag = MA
hitflag = MAF
pausetime = 12, 12
sparkxy = 50, -70
hitsound = S5, 2
guardsound = S5, 3
ground.type = High
ground.slidetime = 15
ground.hittime = 18
ground.velocity = -7.0
guard.velocity = -6.5
air.animtype = Hard
air.hittime = 16
air.velocity = -7.0, -5.5
air.fall = 1
air.juggle = 6
getpower = 70, 35
givepower = 35, 17
ground.cornerpush.veloff = -5.0
air.cornerpush.veloff = -5.0
fall = 1
fall.recover = 0

[State 240, Shockwave]
type = Explod
trigger1 = movehit = 1
anim = 7020
pos = 50, 0
postype = p1
sprpriority = 3
ownpal = 1

[State 240, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 50, -70
postype = p1
sprpriority = 5
ownpal = 1

[State 240, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 50, -70
postype = p1
sprpriority = 5
ownpal = 1

[State 240, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;------------------------------------------------------------------------------
; 250 : 6HP — Demon Fang (overhead; startup 13, active 4, recovery 13)
;   Implemented as a short airborne state so the engine treats it as an
;   overhead (must be blocked standing).
;------------------------------------------------------------------------------
[Statedef 250]
type    = A
movetype= A
physics = N
juggle  = 4
velset  = 0, 0
ctrl    = 0
anim    = 250
poweradd= 30
sprpriority = 2

[State 250, Stay grounded]
type = PosSet
trigger1 = pos y < 0
y = 0

[State 250, Attack box]
type = OverrideClsn
trigger1 = time = [13, 16]
group = Clsn1
index = 0
rect = 18, -152, 62, -96
[State 250, HitDef]
type = HitDef
trigger1 = !time
attr = A, HA
damage = 80, 5
animtype = Hard
guardflag = HA
hitflag = MAF
pausetime = 10, 10
sparkxy = 40, -120
hitsound = S5, 2
guardsound = S5, 3
ground.type = High
ground.slidetime = 12
ground.hittime = 16
ground.velocity = -4.0, -2.0
guard.velocity = -5.0
air.animtype = Hard
air.hittime = 15
air.velocity = -4.0, -5.0
air.juggle = 6
getpower = 60, 30
givepower = 30, 15
ground.cornerpush.veloff = -3.0
air.cornerpush.veloff = -3.0

[State 250, Claw effect]
type = Explod
trigger1 = time = 13
anim = 7000
pos = 40, -120
postype = p1
sprpriority = 4
ownpal = 1

[State 250, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 40, -120
postype = p1
sprpriority = 5
ownpal = 1

[State 250, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 40, -120
postype = p1
sprpriority = 5
ownpal = 1

[State 250, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;------------------------------------------------------------------------------
; 260 : 4HP — Demon Lance (command poke; startup 6, active 5, recovery 11)
;------------------------------------------------------------------------------
[Statedef 260]
type    = S
movetype= A
physics = S
juggle  = 4
velset  = 0, 0
ctrl    = 0
anim    = 260
poweradd= 28
sprpriority = 2

[State 260, Attack box]
type = OverrideClsn
trigger1 = time = [6, 10]
group = Clsn1
index = 0
rect = 28, -92, 96, -58
[State 260, HitDef]
type = HitDef
trigger1 = !time
attr = S, NA
damage = 68, 4
animtype = Medium
guardflag = MA
hitflag = MAF
pausetime = 9, 9
sparkxy = 60, -75
hitsound = S5, 1
guardsound = S5, 3
ground.type = High
ground.slidetime = 11
ground.hittime = 14
ground.velocity = -4.0
guard.velocity = -4.0
air.animtype = Medium
air.hittime = 14
air.velocity = -4.0, -4.0
air.juggle = 5
getpower = 50, 25
givepower = 25, 12
ground.cornerpush.veloff = 0
air.cornerpush.veloff = 0

[State 260, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 60, -75
postype = p1
sprpriority = 5
ownpal = 1

[State 260, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 60, -75
postype = p1
sprpriority = 5
ownpal = 1

[State 260, End]
type = ChangeState
trigger1 = animtime = 0
value = 0
ctrl = 1

;==============================================================================
; NORMALS — CROUCHING
;==============================================================================

;------------------------------------------------------------------------------
; 400 : 2LP  (startup 2, active 3, recovery 6)
;------------------------------------------------------------------------------
[Statedef 400]
type    = C
movetype= A
physics = C
juggle  = 4
velset  = 0, 0
ctrl    = 0
anim    = 400
poweradd= 22
sprpriority = 2

[State 400, Attack box]
type = OverrideClsn
trigger1 = time = [2, 4]
group = Clsn1
index = 0
rect = 18, -72, 54, -38
[State 400, HitDef]
type = HitDef
trigger1 = !time
attr = C, NA
damage = 36, 3
animtype = Light
guardflag = L
hitflag = MAF
pausetime = 7, 7
sparkxy = 36, -55
hitsound = S5, 0
guardsound = S5, 3
ground.type = Low
ground.slidetime = 8
ground.hittime = 12
ground.velocity = -2.5
guard.velocity = -2.8
air.animtype = Light
air.hittime = 12
air.velocity = -2.5, -3.5
air.juggle = 4
getpower = 40, 20
givepower = 20, 10
ground.cornerpush.veloff = 0
air.cornerpush.veloff = 0

[State 400, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 36, -55
postype = p1
sprpriority = 5
ownpal = 1

[State 400, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 36, -55
postype = p1
sprpriority = 5
ownpal = 1

[State 400, Chain -> 2MP]
type = ChangeState
value = 410
triggerall = movecontact
trigger1 = command = "y"

[State 400, End]
type = ChangeState
trigger1 = animtime = 0
value = 11
ctrl = 1

;------------------------------------------------------------------------------
; 410 : 2MP  (startup 5, active 3, recovery 8)
;------------------------------------------------------------------------------
[Statedef 410]
type    = C
movetype= A
physics = C
juggle  = 4
velset  = 0, 0
ctrl    = 0
anim    = 410
poweradd= 26
sprpriority = 2

[State 410, Attack box]
type = OverrideClsn
trigger1 = time = [5, 7]
group = Clsn1
index = 0
rect = 20, -92, 60, -38
[State 410, HitDef]
type = HitDef
trigger1 = !time
attr = C, NA
damage = 58, 4
animtype = Medium
guardflag = L
hitflag = MAF
pausetime = 9, 9
sparkxy = 40, -60
hitsound = S5, 1
guardsound = S5, 3
ground.type = Low
ground.slidetime = 10
ground.hittime = 14
ground.velocity = -3.4
guard.velocity = -3.4
air.animtype = Medium
air.hittime = 14
air.velocity = -3.4, -4.5
air.juggle = 5
getpower = 50, 25
givepower = 25, 12
ground.cornerpush.veloff = 0
air.cornerpush.veloff = 0

[State 410, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 40, -60
postype = p1
sprpriority = 5
ownpal = 1

[State 410, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 40, -60
postype = p1
sprpriority = 5
ownpal = 1

[State 410, Chain -> 2HP]
type = ChangeState
value = 420
triggerall = movecontact
trigger1 = command = "z"

[State 410, End]
type = ChangeState
trigger1 = animtime = 0
value = 11
ctrl = 1

;------------------------------------------------------------------------------
; 420 : 2HP — Elder Uppercut (LAUNCHER / anti-air; startup 7, active 5, rec 13)
;------------------------------------------------------------------------------
[Statedef 420]
type    = C
movetype= A
physics = C
juggle  = 6
velset  = 0, 0
ctrl    = 0
anim    = 420
poweradd= 30
sprpriority = 2

[State 420, Attack box]
type = OverrideClsn
trigger1 = time = [7, 11]
group = Clsn1
index = 0
rect = 8, -152, 46, -78
[State 420, Attack box B]
type = OverrideClsn
trigger1 = time = [7, 11]
group = Clsn1
index = 1
rect = 12, -130, 40, -80
[State 420, HitDef]
type = HitDef
trigger1 = !time
attr = C, NA
damage = 84, 5
animtype = Up
guardflag = MA
hitflag = MAF
pausetime = 10, 10
sparkxy = 24, -110
hitsound = S5, 2
guardsound = S5, 3
ground.type = High
ground.slidetime = 0
ground.hittime = 20
ground.velocity = -3.0, -7.5
guard.velocity = -4.5
air.animtype = Up
air.hittime = 20
air.velocity = -3.0, -8.0
air.juggle = 4
yaccel = 0.5
getpower = 60, 30
givepower = 30, 15
ground.cornerpush.veloff = 0
air.cornerpush.veloff = 0

[State 420, Launcher aura]
type = Explod
trigger1 = time = 7
anim = 7040
pos = 0, -90
postype = p1
sprpriority = 3
ownpal = 1
scale = 0.6, 0.6

[State 420, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 24, -110
postype = p1
sprpriority = 5
ownpal = 1

[State 420, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 24, -110
postype = p1
sprpriority = 5
ownpal = 1

[State 420, End]
type = ChangeState
trigger1 = animtime = 0
value = 11
ctrl = 1

;==============================================================================
; NORMALS — AIR
;==============================================================================

;------------------------------------------------------------------------------
; 600 : jLP  (startup 4, active 3, recovery 8)
;------------------------------------------------------------------------------
[Statedef 600]
type    = A
movetype= A
physics = N
juggle  = 4
ctrl    = 0
anim    = 600
poweradd= 20
sprpriority = 2

[State 600, Attack box]
type = OverrideClsn
trigger1 = time = [4, 6]
group = Clsn1
index = 0
rect = 14, -120, 52, -88
[State 600, HitDef]
type = HitDef
trigger1 = !time
attr = A, NA
damage = 42, 3
animtype = Light
guardflag = HA
hitflag = MAFD
pausetime = 7, 7
sparkxy = 34, -100
hitsound = S5, 0
guardsound = S5, 3
ground.type = High
ground.slidetime = 8
ground.hittime = 12
ground.velocity = -2.6
guard.velocity = -3.0
air.animtype = Light
air.hittime = 12
air.velocity = -2.6, -3.5
air.juggle = 4
getpower = 40, 20
givepower = 20, 10
air.cornerpush.veloff = 0

[State 600, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 34, -100
postype = p1
sprpriority = 5
ownpal = 1

[State 600, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 34, -100
postype = p1
sprpriority = 5
ownpal = 1

[State 600, Land]
type = ChangeState
trigger1 = vel y > 0
trigger1 = pos y >= 0
value = 52

;------------------------------------------------------------------------------
; 610 : jMP  (startup 6, active 4, recovery 9)
;------------------------------------------------------------------------------
[Statedef 610]
type    = A
movetype= A
physics = N
juggle  = 4
ctrl    = 0
anim    = 610
poweradd= 24
sprpriority = 2

[State 610, Attack box]
type = OverrideClsn
trigger1 = time = [6, 9]
group = Clsn1
index = 0
rect = 16, -132, 58, -88
[State 610, HitDef]
type = HitDef
trigger1 = !time
attr = A, NA
damage = 60, 4
animtype = Medium
guardflag = HA
hitflag = MAFD
pausetime = 9, 9
sparkxy = 38, -110
hitsound = S5, 1
guardsound = S5, 3
ground.type = High
ground.slidetime = 10
ground.hittime = 14
ground.velocity = -3.4
guard.velocity = -3.6
air.animtype = Medium
air.hittime = 14
air.velocity = -3.4, -4.5
air.juggle = 5
getpower = 50, 25
givepower = 25, 12
air.cornerpush.veloff = 0

[State 610, Slash effect]
type = Explod
trigger1 = time = 6
anim = 7000
pos = 40, -110
postype = p1
sprpriority = 4
ownpal = 1

[State 610, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 38, -110
postype = p1
sprpriority = 5
ownpal = 1

[State 610, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 38, -110
postype = p1
sprpriority = 5
ownpal = 1

[State 610, Land]
type = ChangeState
trigger1 = vel y > 0
trigger1 = pos y >= 0
value = 52

;------------------------------------------------------------------------------
; 620 : jHK — Falling Fang (dive kick; startup 7, active 4, recovery 10)
;------------------------------------------------------------------------------
[Statedef 620]
type    = A
movetype= A
physics = N
juggle  = 6
ctrl    = 0
anim    = 620
poweradd= 28
sprpriority = 2

[State 620, Attack box]
type = OverrideClsn
trigger1 = time = [7, 10]
group = Clsn1
index = 0
rect = 8, -64, 58, -18
[State 620, HitDef]
type = HitDef
trigger1 = !time
attr = A, NA
damage = 82, 5
animtype = Hard
guardflag = HA
hitflag = MAFD
pausetime = 10, 10
sparkxy = 30, -40
hitsound = S5, 2
guardsound = S5, 3
ground.type = High
ground.slidetime = 12
ground.hittime = 16
ground.velocity = -5.0
guard.velocity = -5.0
air.animtype = Hard
air.hittime = 15
air.velocity = -5.0, -5.0
air.fall = 1
air.juggle = 6
getpower = 60, 30
givepower = 30, 15
air.cornerpush.veloff = -3.0
fall = 1
fall.recover = 0

[State 620, Hit spark]
type = Explod
trigger1 = movehit = 1
anim = 7030
pos = 30, -40
postype = p1
sprpriority = 5
ownpal = 1

[State 620, Guard spark]
type = Explod
trigger1 = moveguarded = 1
anim = 7030
pos = 30, -40
postype = p1
sprpriority = 5
ownpal = 1

[State 620, Land]
type = ChangeState
trigger1 = vel y > 0
trigger1 = pos y >= 0
value = 52
