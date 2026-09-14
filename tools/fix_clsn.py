#!/usr/bin/env python3
"""
fix_clsn.py - Replace legacy Clsn1/Clsn2 state controllers with OverrideClsn
for IKEMEN GO compatibility (scmap has no Clsn1/Clsn2).

Reads chars/Saturn/*.st files, replaces each invalid block:

  type = Clsn1|Clsn2
  clsn1default = N
  clsn1[0] = x1,y1,x2,y2
  ...

with N separate OverrideClsn controllers:

  type = OverrideClsn
  trigger... (same as original)
  group = Clsn1|Clsn2
  index = N
  rect = x1,y1,x2,y2

Hurtbox blocks in State -2 (statetype based) become persistent via retrigger each tick.
Attack blocks with time = [X,Y] remain windowed.

Also strips clsn*default lines (IKEMEN ignores them; OverrideClsn count is implicit).
"""
import re, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ST_FILES = [ROOT/"chars/Saturn/Saturn.st", ROOT/"chars/Saturn/Saturn1.st", ROOT/"chars/Saturn/Saturn2.st"]

# regex for header
HEADER_RE = re.compile(r'^\s*\[State\s[^\]]+\]\s*$', re.I)
TYPE_RE = re.compile(r'^\s*type\s*=\s*Clsn([12])\s*$', re.I)
CLSN_DEF_RE = re.compile(r'^\s*clsn([12])default\s*=\s*\d+\s*$', re.I)
CLSN_BOX_RE = re.compile(r'^\s*clsn([12])\[(\d+)\]\s*=\s*(.+)\s*$', re.I)
TRIGGER_RE = re.compile(r'^\s*(triggerall|trigger\d+)\s*=', re.I)

def fix_file(path: pathlib.Path):
    text = path.read_text(encoding='utf-8', errors='ignore')
    lines = text.splitlines()
    out = []
    i = 0
    replacements = 0
    while i < len(lines):
        line = lines[i]
        # check if line starts a State block
        if HEADER_RE.match(line):
            # peek block: collect until next header or Statedef or EOF
            hdr = line
            j = i+1
            block_lines = []
            while j < len(lines) and not HEADER_RE.match(lines[j]) and not re.match(r'^\s*\[Statedef', lines[j], re.I):
                block_lines.append(lines[j])
                j+=1
            # check if block is Clsn
            type_idx = -1
            clsn_group = None
            for k, bl in enumerate(block_lines):
                m = TYPE_RE.match(bl)
                if m:
                    type_idx = k
                    clsn_group = m.group(1)  # "1" or "2"
                    break
            if type_idx != -1:
                # This is legacy Clsn block to replace
                # Collect triggers: any triggerall/triggerN lines in block
                triggers = [bl for bl in block_lines if TRIGGER_RE.match(bl)]
                # Collect boxes
                boxes = []  # list of (idx, rect)
                for bl in block_lines:
                    mb = CLSN_BOX_RE.match(bl)
                    if mb:
                        # verify group matches clsn_group? but accept any
                        idx = int(mb.group(2))
                        rect = mb.group(3).strip()
                        boxes.append((idx, rect))
                # If no boxes but default only, skip (no visual effect but invalid)
                # We'll still comment out block
                if not boxes:
                    # No boxes -> replace with Null (remove invalid controller)
                    out.append(hdr)
                    out.append(f"; FIXED: legacy {block_lines[type_idx].strip()} with no boxes removed (was invalid for IKEMEN)")
                    out.append("type = Null")
                    for t in triggers:
                        out.append(t)
                    # preserve comments? skip clsn lines
                    replacements += 1
                else:
                    # Sort boxes by index
                    boxes.sort(key=lambda x: x[0])
                    # Determine group name for OverrideClsn
                    group_name = f"Clsn{clsn_group}"
                    # First box reuses original header name
                    # Subsequent boxes get suffix " B", " C" etc.
                    suffixes = ["", " B", " C", " D", " E", " F", " G", " H"]
                    for b_idx, (box_idx, rect) in enumerate(boxes):
                        suffix = suffixes[b_idx] if b_idx < len(suffixes) else f" {b_idx+1}"
                        header_out = hdr.rstrip()
                        # insert suffix inside [] before closing ]
                        if suffix:
                            if header_out.endswith(']'):
                                header_out = header_out[:-1] + suffix + ']'
                            else:
                                header_out += suffix
                        out.append(header_out)
                        out.append(f"type = OverrideClsn")
                        for t in triggers:
                            out.append(t)
                        out.append(f"group = {group_name}")
                        out.append(f"index = {box_idx}")
                        out.append(f"rect = {rect}")
                        # add blank line between multiple boxes? out will separate by next header
                    replacements += 1
                # advance i to j
                i = j
                continue
            else:
                # not Clsn, copy header and continue per-line copying
                out.append(line)
                i += 1
                continue
        else:
            out.append(line)
            i+=1
    new_text = "\n".join(out) + "\n"
    # sanity: ensure no type = Clsn remains (case-insensitive)
    if re.search(r'type\s*=\s*Clsn', new_text, re.I):
        print(f"WARNING: {path} still contains Clsn after fix", file=sys.stderr)
    path.write_text(new_text, encoding='utf-8')
    return replacements

def main():
    total = 0
    for p in ST_FILES:
        if not p.exists():
            print(f"skip missing {p}")
            continue
        c = fix_file(p)
        print(f"{p}: fixed {c} Clsn blocks")
        total+=c
    print(f"Total fixed: {total}")
    if total==0:
        sys.exit(1)

if __name__ == "__main__":
    main()
