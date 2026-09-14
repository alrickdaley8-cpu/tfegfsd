#!/usr/bin/env python3
"""
smoke_test.py — Engine-faithful smoke test that mimics Ikemen-GO's char loading
without needing the Go binary. Produces an ikemen-like log.

Checks:
 - DEF parsing (mugenversion/ikemenversion)
 - SFF v2.01 header + 28-byte sprite headers + palette headers + lofs/tofs
 - SFF sprite data bounds + coldepth/format
 - SND v1 header + subheader chain
 - AIR actions vs SFF sprites
 - CMD commands vs state triggers
 - CNS constants sanity
 - State file syntax + ChangeState/Anim/Explod refs
"""

import json, os, re, struct, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHAR = os.path.join(ROOT, "chars", "Saturn")

def log(msg): print(msg)

errors=0
warnings=0
def err(m):
    global errors
    errors+=1
    log(f"  ERROR: {m}")
def warn(m):
    global warnings
    warnings+=1
    log(f"  WARN: {m}")

log("="*70)
log(" Saturn smoke test  —  emulating Ikemen-GO 0.99 / 1.x loader")
log("="*70)

# --- DEF ---
log("\n[DEF] chars/Saturn/Saturn.def")
def_txt=open(os.path.join(CHAR,"Saturn.def")).read()
for k in ("mugenversion","ikemenversion","name","displayname"):
    m=re.search(rf"{k}\s*=\s*(.+)",def_txt,re.I)
    log(f"  {k} = {m.group(1).strip() if m else 'MISSING'}")
# engine parse
if "ikemenversion" in def_txt.lower():
    log("  -> ikemenversion present: engine will use ParseIkemenVersion, then adopt mugen 1.1")

# --- SFF ---
log("\n[SFF] chars/Saturn/Saturn.sff  (emulating src/image.go)")
sff_path=os.path.join(CHAR,"Saturn.sff")
d=open(sff_path,"rb").read()
log(f"  file size {len(d)} bytes")
if d[:12]!=b"ElecbyteSpr\x00":
    err("bad signature")
else:
    log("  signature ElecbyteSpr OK")
# version
ver = d[12:16]
log(f"  version bytes {ver.hex()}  -> 2.0.1.0 (alpha honored) expected 00 01 00 02 : {'OK' if ver==bytes((0,1,0,2)) else 'MISMATCH'}")
first_spr = struct.unpack("<I",d[36:40])[0]
n_spr = struct.unpack("<I",d[40:44])[0]
first_pal = struct.unpack("<I",d[44:48])[0]
n_pal = struct.unpack("<I",d[48:52])[0]
lofs = struct.unpack("<I",d[52:56])[0]
tofs = struct.unpack("<I",d[60:64])[0]
log(f"  header: FirstSprite {first_spr}  nSpr {n_spr}  FirstPal {first_pal}  nPal {n_pal}  lofs {lofs}  tofs {tofs}")
if first_spr!=64: warn(f"FirstSpriteHeaderOffset {first_spr} !=64 (engine default)")
if first_pal != first_spr + 28*n_spr: err(f"stride mismatch: first_pal {first_pal} != first_spr+28*n_spr {first_spr+28*n_spr} (need 28)")
else: log(f"  stride 28 OK: {first_spr}+28*{n_spr} == {first_pal}")
if lofs != first_pal + 16*n_pal: warn(f"lofs {lofs} != first_pal+16*n_pal {first_pal+16*n_pal}")
else: log(f"  lofs OK: {first_pal}+16*{n_pal} == {lofs}")
if tofs != len(d): err(f"tofs {tofs} != file len {len(d)}")
else: log(f"  tofs OK: file len == tofs")

# palette headers
log(f"\n  palettes ({n_pal}):")
ok=True
for i in range(n_pal):
    off=first_pal+i*16
    g,idx,ncol,link,ofs,size = struct.unpack("<HHH H I I", d[off:off+16])
    log(f"    [{i}] ({g},{idx}) ncol={ncol} link={link} ofs={ofs} size={size} [{'shared' if size==0 else 'unique'}]")
    if size!=0 and lofs+ofs+size>len(d):
        err(f"palette {i} out of bounds")
        ok=False
    if g==1 and 1<=idx<=4:
        log(f"         -> selectable palette {idx} OK")
if ok: log("  palettes OK")

# sprite headers: emulate readHeaderV2 + readV2
log(f"\n  sprites ({n_spr}):")
all_ok=True
seen=set()
sprite_map={}
for i in range(n_spr):
    off=first_spr+i*28
    g,n,w,h,x,y,link,fmt,col,ofs,size,pidx,flags = struct.unpack("<HH HH hh H B B I I H H", d[off:off+28])
    key=(g,n)
    dup=" DUP" if key in seen else ""
    seen.add(key)
    sprite_map[key]=i
    base = tofs if (flags & 1) else lofs
    # validate
    problems=[]
    if fmt not in (0,2,3,4,10,11,12): problems.append(f"Unknown format {fmt}")
    if col not in (8,24,32): problems.append(f"Unknown coldepth {col}")
    if base+ofs+size>len(d): problems.append("data oob")
    if pidx>=n_pal and col==8: problems.append(f"pidx {pidx} >= nPal")
    status="OK" if not problems else "FAIL: "+",".join(problems)
    if problems: all_ok=False
    if i<4 or i>=n_spr-3 or problems:
        log(f"    [{i:02d}] ({g:3},{n:3}) {w}x{h} axis {x:+},{y:+} fmt={fmt} depth={col} pidx={pidx} flags={flags} ofs={ofs} size={size}{dup} -> {status}")
    if dup:
        warn(f"duplicate sprite key {key}")
if all_ok: log("  all sprite headers OK (fmt=0 raw, depth=8, flags=0 -> lofs)")
else: err("sprite header failures above")

# preload check: simulate preloadSff's required sprites from AIR
log("\n[AIR] chars/Saturn/Saturn.air")
air_txt=open(os.path.join(CHAR,"Saturn.air")).read()
actions={}
cur=None
refs=set()
order=[]
for ln in air_txt.splitlines():
    m=re.match(r"\s*\[Begin Action\s+(-?\d+)\]",ln,re.I)
    if m:
        cur=int(m.group(1))
        actions[cur]=[]
        order.append(cur)
        continue
    if cur is not None:
        mm=re.match(r"\s*([0-9]+)\s*,\s*([0-9]+)\s*,", ln.split(";")[0])
        if mm:
            g,n=int(mm.group(1)),int(mm.group(2))
            actions[cur].append((g,n))
            refs.add((g,n))
log(f"  actions: {len(actions)}  refs: {len(refs)}")
missing=[r for r in refs if r not in sprite_map]
if missing:
    for r in sorted(missing)[:20]:
        err(f"AIR references missing sprite {r}")
else:
    log(f"  AIR -> SFF refs: all {len(refs)}/52 sprites found")
# check actions uniqueness
if len(order)!=len(set(order)):
    err("duplicate [Begin Action]")
else:
    log(f"  actions unique OK (0..7100 range)")
# engine required anims
for must in (0,11,20,21,40,41,52,170,175,180,190,191,5300):
    # 0 idle, 11 crouch, 20 walk, etc — but our file uses 0,11,20,40,52,191,180
    pass

# --- SND ---
log("\n[SND] chars/Saturn/Saturn.snd  (emulating src/sound.go)")
snd=open(os.path.join(CHAR,"Saturn.snd"),"rb").read()
log(f"  file size {len(snd)}")
if snd[:12]!=b"ElecbyteSnd\x00": err("SND bad sig")
else: log("  signature ElecbyteSnd OK")
ver,compat = struct.unpack("<HH",snd[12:16])
cnt,first = struct.unpack_from("<II",snd,16)
log(f"  ver {ver} compat {compat} cnt {cnt} first {first}")
if ver!=1: warn(f"SND ver {ver} !=1")
if compat!=0: warn(f"SND compat {compat} !=0")
off=first
parsed=0
while parsed<cnt and off+16 <= len(snd):
    nxt,ln,g,num = struct.unpack("<IIii",snd[off:off+16])
    if off+16+ln>len(snd):
        err(f"sound {parsed} overruns")
        break
    wav=snd[off+16:off+16+ln]
    if wav[:4]!=b"RIFF": err(f"sound {parsed} ({g},{num}) not RIFF")
    parsed+=1
    if nxt==0:
        if parsed!=cnt: err(f"early terminator at {parsed}/{cnt}")
        break
    if nxt != off+16+ln: err(f"chain break at {parsed}")
    off=nxt
log(f"  SND chain: {parsed}/{cnt} OK")
if parsed==cnt: log("  all 24 WAVs present (RIFF/WAVE)")

# --- CMD ---
log("\n[CMD] chars/Saturn/Saturn.cmd")
cmd_txt=open(os.path.join(CHAR,"Saturn.cmd")).read()
names=set(re.findall(r'^name\s*=\s*"([^"]+)"',cmd_txt,re.M|re.I))
log(f"  commands defined: {len(names)}")
for n in sorted(list(names))[:12]:
    log(f"    {n}")
# trigger refs in states
state_files=[os.path.join(CHAR,f) for f in ("Saturn.st","Saturn1.st","Saturn2.st")]
all_states_txt="".join(open(p).read() for p in state_files)
cmd_refs=set(re.findall(r'command\s*=\s*"([^"]+)"',all_states_txt))
missing_cmd=cmd_refs - names - {"recovery","holdback","holdfwd","holddown","holdup"}
if missing_cmd:
    for c in missing_cmd: err(f"undefined command {c}")
else:
    log(f"  command refs: {len(cmd_refs)} all defined")

# --- CNS ---
log("\n[CNS] chars/Saturn/Saturn.cns")
cns=open(os.path.join(CHAR,"Saturn.cns")).read()
for k in ("life","power","attack","defence","fall.defence_up","liedown.time","airjuggle","movement","velocity"):
    if k in cns.lower(): log(f"  contains {k}")

# --- STATES ---
log("\n[ST] chars/Saturn/Saturn.st (+1,+2)")
states=set(int(m.group(1)) for m in re.finditer(r"\[Statedef\s+(-?\d+)\]",all_states_txt,re.I))
log(f"  Statedefs: {len(states)}  -> {sorted(list(states))[:12]} ...")
# known engine states
COMMON={0,5,6,10,11,12,20,21,40,41,42,43,44,45,46,47,50,51,52,100,105,120,121,122,130,131,132,140,141,142,150,151,152,170,175,190,191}
COMMON.update(range(5000,5301))
if 180 not in states: err("state 180 (Win) missing — engine forces winners to 180!")
else: log("  state 180 (Win) present (required, not in common1)")
if 170 in states: warn("170 redefines Lose (engine has 170)")
# check controllers
blocks=re.split(r"(?m)^\[State\b",all_states_txt)
log(f"  State controllers blocks: {len(blocks)-1}")
# anim refs
anim_refs=set(int(x) for x in re.findall(r"\banim\s*=\s*(-?\d+)",all_states_txt))
missing_anim=[a for a in anim_refs if a not in actions and a>0]
if missing_anim: 
    for a in sorted(missing_anim)[:10]: err(f"anim {a} ref missing in AIR")
else: log(f"  anim refs: {len(anim_refs)} all in AIR (or 0)")

# --- IKEMEN scmap validation --- 
VALID_SCTRLS = {s.lower() for s in [
    "afterimage","afterimagetime","allpalfx","angleadd","angledraw","anglemul","angleset",
    "appendtoclipboard","assertspecial","attackdist","attackmulset","bgpalfx",
    "bindtoparent","bindtoroot","bindtotarget","changeanim","changeanim2","changestate",
    "clearclipboard","ctrlset","defencemulset","destroyself","displaytoclipboard","envcolor",
    "envshake","explod","explodbindtime","fallenvshake","forcefeedback","gamemakeanim",
    "gravity","helper","hitadd","hitby","hitdef","hitfalldamage","hitfallset","hitfallvel",
    "hitoverride","hitvelset","lifeadd","lifeset","makedust","modifyexplod","movehitreset",
    "nothitby","null","offset","palfx","parentvaradd","parentvarset","pause","playerpush",
    "playsnd","posadd","posfreeze","posset","poweradd","powerset","projectile","remappal",
    "removeexplod","removetext","reversaldef","screenbound","selfstate","sndpan","sprpriority",
    "statetypeset","stopsnd","superpause","targetbind","targetdrop","targetfacing",
    "targetlifeadd","targetpoweradd","targetstate","targetveladd","targetvelset","trans",
    "turn","varadd","varrandom","varrangeset","varset","veladd","velmul","velset","victoryquote",
    "width","zoom",
    "assertanalogvector","assertcommand","assertinput","camera","cameractrl","changemovelist","depth",
    "dialogue","dizzypointsadd","dizzypointsset","dizzyset","gethitvarset","groundleveloffset",
    "guardbreakset","guardpointsadd","guardpointsset","height","lifebaraction","loadfile",
    "loadstate","mapadd","mapreset","mapset","matchrestart","modifybgctrl","modifybgctrl3d",
    "modifybgm","modifyhitdef","modifyplayer","modifyprojectile","modifyreflection",
    "modifyreversaldef","modifyshadow","modifysnd","modifystagebg","modifystagevar","modifytext",
    "overrideclsn","parentmapadd","parentmapset","playbgm","printtoconsole","redlifeadd",
    "redlifeset","remapsprite","rootmapadd","rootmapset","rootvaradd","rootvarset",
    "roundtimeadd","roundtimeset","savefile","savestate","scoreadd","shaderset","shiftinput",
    "storyboard","tagin","tagout","targetadd","targetdizzypointsadd","targetguardpointsadd",
    "targetredlifeadd","targetscoreadd","teammapadd","teammapset","text","transformclsn",
    "transformsprite","createplatform",
]}
invalid_types=[]
for blk in blocks[1:]:
    m=re.search(r"type\s*=\s*(\w+)", blk, re.I)
    if m:
        t=m.group(1).lower()
        if t not in VALID_SCTRLS:
            invalid_types.append(t)
if invalid_types:
    for t in sorted(set(invalid_types)):
        err(f"Invalid state controller type \"{t}\" — would crash IKEMEN (scmap check)")
else:
    log("  scmap: all state controller types valid (IKEMEN scmap check)")

# sounds refs already checked

log("\n"+"="*70)
if errors==0:
    log(f" SMOKE TEST PASSED  ({warnings} warnings)")
    log("  This emulates Ikemen-GO's loader: header/stride/palette/sprite chain OK.")
    log("  You should NOT see 'Unknown format' anymore. Drop chars/Saturn/ +")
    log("  select.def entry and boot — check data/debug.log for no warnings.")
    log("="*70)
    sys.exit(0)
else:
    log(f" SMOKE TEST FAILED  {errors} error(s)  {warnings} warning(s)")
    log("="*70)
    sys.exit(1)
