;==============================================================================
; Saturn.cmd — command definitions
;==============================================================================
; Input conventions (IKEMEN GO / M.U.G.E.N):
;   U D F B  = up / down / forward / back (directions)
;   x y z    = light / medium / heavy punch
;   a b c    = light / medium / heavy kick
;   s        = start
;   ~        = "release previous direction" (allows tap inputs)
;   /        = alternate (either side of the slash)
;   $        = charge/hold (not used here; commands are taps)
;
; Specials:
;   Hell Claw          QCF + P  (236P)   — demon claw strike
;   Haoshoku           QCB + P  (214P)   — shockwave pressure attack
;   Juryoku            QCF + K  (236K)   — gravity crush
;   Veil Shift         DP  + K  (623K)   — teleport
;   Aku no Hado        QCB + K  (214K)   — defensive burst
;   Gorosei Judgment   B,F   + P (46P)   — large-area boss claw
; Supers / Ultimate:
;   Elder Star Hellfire      QCFx2 + P  (236236P)  Lv1
;   Judgment of the Saint    QCBx2 + P  (214214P)  Lv2
;   Elder Star Transcendence QCFx2 + K  (236236K)  Lv3
;   Sun's Eclipse            QCBx2 + K  (214214K)  ULT (awakened only)
; Awakening:
;   Down, Down + start  (D,D,s)
;==============================================================================

[Remap]
x = x
y = y
z = z
a = a
b = b
c = c
s = s

[Defaults]
command.time = 15
command.buffer.time = 1

;--- Movement (explicit so movement is always defined) -----------------------
[Command]
name = "fwd"
command = F
time = 1

[Command]
name = "back"
command = B
time = 1

[Command]
name = "up"
command = U
time = 1

[Command]
name = "down"
command = D
time = 1

[Command]
name = "holdfwd"
command = /$F
time = 1

[Command]
name = "holdback"
command = /$B
time = 1

[Command]
name = "holdup"
command = /$U
time = 1

[Command]
name = "holddown"
command = /$D
time = 1

[Command]
name = "s"
command = s
time = 1

;--- Simple attack buttons (x/y/z = punches L/M/H, a/b/c = kicks L/M/H) ---------
[Command]
name = "x"
command = x
time = 1

[Command]
name = "y"
command = y
time = 1

[Command]
name = "z"
command = z
time = 1

[Command]
name = "a"
command = a
time = 1

[Command]
name = "b"
command = b
time = 1

[Command]
name = "c"
command = c
time = 1

;--- Command normals / taunt ---------------------------------------------------
[Command]
name = "back_z"
command = ~B, z
time = 15

[Command]
name = "taunt"
command = ~s, s
time = 20


;--- Specials ----------------------------------------------------------------
[Command]
name = "QCF_x"
command = ~D, DF, F, x
time = 15

[Command]
name = "QCF_y"
command = ~D, DF, F, y
time = 15

[Command]
name = "QCF_z"
command = ~D, DF, F, z
time = 15

[Command]
name = "QCB_x"
command = ~D, DB, B, x
time = 15

[Command]
name = "QCB_y"
command = ~D, DB, B, y
time = 15

[Command]
name = "QCB_z"
command = ~D, DB, B, z
time = 15

[Command]
name = "QCF_a"
command = ~D, DF, F, a
time = 15

[Command]
name = "QCF_b"
command = ~D, DF, F, b
time = 15

[Command]
name = "QCF_c"
command = ~D, DF, F, c
time = 15

[Command]
name = "DP_a"
command = ~F, D, DF, a
time = 15

[Command]
name = "DP_b"
command = ~F, D, DF, b
time = 15

[Command]
name = "DP_c"
command = ~F, D, DF, c
time = 15

[Command]
name = "QCB_a"
command = ~D, DB, B, a
time = 15

[Command]
name = "QCB_b"
command = ~D, DB, B, b
time = 15

[Command]
name = "QCB_c"
command = ~D, DB, B, c
time = 15

[Command]
name = "BF_x"
command = ~B, F, x
time = 20

[Command]
name = "BF_y"
command = ~B, F, y
time = 20

[Command]
name = "BF_z"
command = ~B, F, z
time = 20

;--- Supers ------------------------------------------------------------------
[Command]
name = "QCFx2_x"
command = ~D, DF, F, D, DF, F, x
time = 25

[Command]
name = "QCFx2_y"
command = ~D, DF, F, D, DF, F, y
time = 25

[Command]
name = "QCFx2_z"
command = ~D, DF, F, D, DF, F, z
time = 25

[Command]
name = "QCBx2_x"
command = ~D, DB, B, D, DB, B, x
time = 25

[Command]
name = "QCBx2_y"
command = ~D, DB, B, D, DB, B, y
time = 25

[Command]
name = "QCBx2_z"
command = ~D, DB, B, D, DB, B, z
time = 25

[Command]
name = "QCFx2_a"
command = ~D, DF, F, D, DF, F, a
time = 25

[Command]
name = "QCFx2_b"
command = ~D, DF, F, D, DF, F, b
time = 25

[Command]
name = "QCFx2_c"
command = ~D, DF, F, D, DF, F, c
time = 25

[Command]
name = "QCBx2_a"
command = ~D, DB, B, D, DB, B, a
time = 25

[Command]
name = "QCBx2_b"
command = ~D, DB, B, D, DB, B, b
time = 25

[Command]
name = "QCBx2_c"
command = ~D, DB, B, D, DB, B, c
time = 25

;--- Awakening ---------------------------------------------------------------
[Command]
name = "AWAKEN"
command = ~D, D, s
time = 20
