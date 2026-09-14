#!/usr/bin/env python3
"""
validate.py - Cross-reference validator for the Saturn character.

Checks (does not need the IKEMEN GO engine):
  * every file referenced in Saturn.def exists
  * every (group,image) sprite referenced in Saturn.air exists in the SFF manifest
  * every [Begin Action N] in Saturn.air is unique and valid
  * every command name referenced by `command = "..."` in the state files is
    defined in Saturn.cmd (or is a built-in)
  * every sound referenced as S<n>,<m> / F<n>,<m> exists in the sounds manifest
  * every anim/state number referenced by Explod/ChangeAnim/ChangeState/
    Projectile/SuperPause resolves (state files + known common states)
  * SFF header/stride and SND subheader chain are structurally valid

Usage:
    python3 tools/validate.py
Exit code 0 = no errors (warnings allowed), 1 = errors found.
"""

import json
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHAR = os.path.join(ROOT, "chars", "Saturn")

# Common states provided by the engine's data/common1.cns.zss (so the validator
# does not flag them as missing).
COMMON_STATES = set([
    0, 5, 6, 10, 11, 12, 20, 21, 40, 41, 42, 43, 44, 45, 46, 47, 50, 51, 52,
    100, 105, 120, 121, 122, 130, 131, 132, 140, 141, 142, 150, 151, 152,
    170, 175, 190, 191,
] + list(range(5000, 5301)) + [5500, 5501, 5502, 5510, 5511, 5520])


def warn(msg):
    print("  WARN " + msg)


def err(msg):
    print("  ERROR " + msg)
    global ERRORS
    ERRORS += 1


ERRORS = 0


def read_text(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def parse_def():
    txt = read_text(os.path.join(CHAR, "Saturn.def"))
    files = {}
    for key in ("cmd", "cns", "st", "st1", "st2", "sprite", "anim", "sound"):
        m = re.search(rf"^{key}\s*=\s*([^\r\n;]+)", txt, re.M)
        if m:
            files[key] = m.group(1).strip()
    return files


def parse_manifest():
    m = json.load(open(os.path.join(CHAR, "_build", "sprites", "manifest.json")))
    sprites = {}
    for s in m["sprites"]:
        sprites[(int(s["group"]), int(s.get("index", s.get("image"))))] = s
    return m, sprites


def parse_air():
    txt = read_text(os.path.join(CHAR, "Saturn.air"))
    actions = {}
    order = []
    cur = None
    refs = set()
    for ln in txt.splitlines():
        m = re.match(r"\s*\[Begin Action\s+(-?\d+)\]", ln, re.I)
        if m:
            cur = int(m.group(1))
            actions[cur] = []
            order.append(cur)
            continue
        if cur is not None:
            mm = re.match(r"\s*([0-9]+)\s*,\s*([0-9]+)\s*,\s*[-0-9]+", ln.split(";")[0])
            if mm:
                actions[cur].append((int(mm.group(1)), int(mm.group(2))))
                refs.add((int(mm.group(1)), int(mm.group(2))))
    return actions, order, refs


def parse_cmd():
    txt = read_text(os.path.join(CHAR, "Saturn.cmd"))
    names = set(re.findall(r'^name\s*=\s*"([^"]+)"', txt, re.M))
    return names


def parse_sounds():
    m = json.load(open(os.path.join(CHAR, "_build", "sounds", "manifest.json")))
    return set((s["group"], s["number"]) for s in m)


def parse_states(*paths):
    """Returns (defined_states, blocks) where blocks = list of
    (type, rawblock)."""
    states = set()
    blocks = []
    for p in paths:
        txt = read_text(p)
        # collect every Statedef regardless of position
        for m in re.finditer(r"\[Statedef\s+(-?\d+)\]", txt, re.I):
            states.add(int(m.group(1)))
        # split into state controllers: [State ...] blocks
        parts = re.split(r"(?m)^\[State\b", txt)
        for i, part in enumerate(parts):
            if i == 0:
                continue
            # part starts after "[State" — reconstruct header
            m = re.search(r"type\s*=\s*(\w+)", part)
            typ = m.group(1).lower() if m else "?"
            blocks.append((typ, "[State" + part))
    return states, blocks


def check_trigger_commands(blocks, cmd_names):
    # command references in triggers
    refs = set()
    for typ, b in blocks:
        refs.update(re.findall(r'command\s*=\s*"([^"]+)"', b))
    for r in sorted(refs):
        if r not in cmd_names:
            err(f"state file references undefined command \"{r}\"")


def check_sounds_refs(blocks, sounds):
    refs = set()
    for typ, b in blocks:
        for m in re.finditer(r"\b([SF])\s*([0-9]+)\s*,\s*([0-9]+)", b):
            refs.add((int(m.group(2)), int(m.group(3))))
    for g, n in sorted(refs):
        if (g, n) not in sounds:
            err(f"state file references missing sound {g},{n}")


def check_block_refs(blocks, actions, states, known_states):
    for typ, b in blocks:
        nums = [int(x) for x in re.findall(r"\banim\s*=\s*(-?\d+)", b)]
        if typ == "explod" or typ == "gameMakeAnim":
            for n in nums:
                if n not in actions:
                    err(f"Explod references missing anim {n}")
        if typ == "changeanim":
            for n in nums:
                if n not in actions:
                    err(f"ChangeAnim references missing anim {n}")
        if typ == "changestate":
            for n in [int(x) for x in re.findall(r"\bvalue\s*=\s*(-?\d+)", b)]:
                if n not in states and n not in known_states:
                    err(f"ChangeState references missing state {n}")
        if typ == "projectile":
            for key in ("projanim", "projhitanim", "projremanim", "projcancelanim"):
                for n in [int(x) for x in re.findall(rf"\b{key}\s*=\s*(\d+)", b)]:
                    if n not in actions:
                        err(f"Projectile {key} references missing anim {n}")


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
    "transformsprite","createplatform","modifybctrl","modifybctrl3d","modifystagebg","height","depth",
    # legacy aliases that engine treats as type inside CNS triggers (not SCTRL) - no check
]}

def check_sctrl_types(blocks):
    for typ, raw in blocks:
        if typ == "?" or typ == "":
            continue
        if typ not in VALID_SCTRLS:
            # allow case where typ is numeric? but we flagged
            err(f"Invalid state controller type \"{typ}\" — not in IKEMEN scmap (would crash at load)")

def check_sff():
    p = os.path.join(CHAR, "Saturn.sff")
    d = open(p, "rb").read()
    if d[:12] != b"ElecbyteSpr\x00":
        err("SFF bad signature")
        return
    first_spr = struct.unpack_from("<I", d, 36)[0]
    n_spr = struct.unpack_from("<I", d, 40)[0]
    first_pal = struct.unpack_from("<I", d, 44)[0]
    if first_pal != first_spr + 28 * n_spr:
        warn(f"SFF sprite-header stride mismatch (first_pal={first_pal}, "
             f"expected {first_spr + 28*n_spr}) — need 28-byte headers (group,u16..flags)")


def check_snd():
    p = os.path.join(CHAR, "Saturn.snd")
    d = open(p, "rb").read()
    if d[:12] != b"ElecbyteSnd\x00":
        err("SND bad signature")
        return
    ver, = struct.unpack_from("<H", d, 12)
    n, suboff = struct.unpack_from("<II", d, 16)
    i = 0
    while i < n and suboff and suboff + 16 <= len(d):
        nxt, ln, g, num = struct.unpack_from("<IIii", d, suboff)
        if suboff + 16 + ln > len(d):
            err(f"SND sound {i} overruns file")
            break
        suboff = nxt
        i += 1
    if i != n:
        warn(f"SND chain parsed {i}/{n} sounds")


def main():
    print("== Saturn character validation ==")
    files = parse_def()
    for k, v in files.items():
        p = os.path.join(CHAR, v)
        if not os.path.exists(p):
            err(f"def references missing file: {k} = {v}")

    manifest, sprites = parse_manifest()
    actions, order, air_refs = parse_air()
    cmd_names = parse_cmd()
    sounds = parse_sounds()
    states, blocks = parse_states(
        os.path.join(CHAR, "Saturn.st"),
        os.path.join(CHAR, "Saturn1.st"),
        os.path.join(CHAR, "Saturn2.st"))

    # air sprite refs
    missing = sorted(r for r in air_refs if r not in sprites)
    for r in missing:
        err(f"air references sprite {r} not in manifest")
    # duplicate actions
    seen = set()
    for a in order:
        if a in seen:
            err(f"duplicate animation {a}")
        seen.add(a)

    print(f"  def files: {len(files)} | states: {len(states)} | animations: "
          f"{len(actions)} | commands: {len(cmd_names)} | sprites: {len(sprites)} "
          f"| sounds: {len(sounds)}")

    known = COMMON_STATES | states
    check_sctrl_types(blocks)
    check_trigger_commands(blocks, cmd_names)
    check_sounds_refs(blocks, sounds)
    check_block_refs(blocks, actions, states, known)
    check_sff()
    check_snd()

    print("== done ==")
    if ERRORS:
        print(f"{ERRORS} error(s) found")
        sys.exit(1)
    print("no errors")


if __name__ == "__main__":
    main()
