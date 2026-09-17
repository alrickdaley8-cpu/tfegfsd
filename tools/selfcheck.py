#!/usr/bin/env python3
"""
Fast self-check for this tree, used while generating the mod. It does NOT replace a
compiler; it catches the classes of mistake that are cheap to make in a large hand-written
codebase and expensive to find later:

  * unbalanced {} / () in a Java file
  * stray pseudo-code tokens (a '?' that is not part of a ternary or a javadoc)
  * 'TODO' / 'not implemented' / 'placeholder body' style stubs in main sources
  * Java files whose class name does not match the file name
  * every `com.doomsday.nukes.*` import resolving to a real file
  * a referenced mod field (ModX.FOO) actually existing in ModX
  * JSON well-formedness and the cross-references that Minecraft requires
    (blockstate -> model, model -> texture, item -> parent, loot/recipe ids, sounds.json,
     lang key coverage, shader pipeline -> program -> .fsh/.vsh)

Usage:  python3 tools/selfcheck.py [repo_root]
Exit code is non-zero when anything is wrong.
"""
import json
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
RES = os.path.join(ROOT, "src", "main", "resources")
errors = []
warns = []

def jfiles():
    for dirpath, _dirs, names in os.walk(JAVA):
        for n in names:
            if n.endswith(".java"):
                yield os.path.join(dirpath, n)

def rel(p):
    return os.path.relpath(p, ROOT)

SUSPICIOUS = re.compile(r"\?\s*=[^=]|\?\?|=>")
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")
# A string is a quote, then any run of (escaped char | non-quote, non-backslash).
# The pair must be non-greedy and must not span newlines, or apostrophes in prose
# ("don't", "player's") would swallow whole methods and break delimiter counting.
STRING_RE = re.compile(r'"(?:[^"\\\n]|\\.)*"')
CHAR_RE = re.compile(r"'(?:[^'\\\n]|\\.)'")

def strip_comments(src):
    src = BLOCK_COMMENT_RE.sub(" ", src)
    src = LINE_COMMENT_RE.sub(" ", src)
    # blank out string and char literals so delimiter counting ignores them
    src = STRING_RE.sub('""', src)
    src = CHAR_RE.sub("'x'", src)
    return src

# ————————————————————————————————————————————————————————————— java sanity
suspicious = re.compile(r"\b(?:TODO|FIXME|XXX|not implemented|unimplemented|placeholder body"
                        r"|coming soon|add this later|omitted)\b", re.I)
bad_token = re.compile(r"[^\w\s](\?)[^\s\w]|\?\s*=|=>|\.\s*\w+\?")
declared_types = {}
field_index = {}

paths = sorted(jfiles())
for path in paths:
    raw = open(path, encoding="utf-8").read()
    src = strip_comments(raw)
    name = os.path.basename(path)[:-5]
    pkg = re.search(r"^\s*package\s+([\w.]+)\s*;", src, re.M)
    if not pkg:
        errors.append(f"{rel(path)}: missing package statement")
        continue
    fqcn = pkg.group(1) + "." + name
    declared_types[fqcn] = path

    for kind, o, c in (("brace", "{", "}"), ("paren", "(", ")"), ("bracket", "[", "]")):
        if src.count(o) != src.count(c):
            errors.append(f"{rel(path)}: unbalanced {kind} ({src.count(o)} {o} vs {src.count(c)} {c})")

    # generic parameters like List<Map<String,Integer>> must not confuse the count above; they
    # don't (no braces), but a lone '?' in code does.
    for m in re.finditer(SUSPICIOUS, src):
        seg = src[max(0, m.start() - 40):m.end() + 20].replace("\n", " ")
        if "?:" in seg or "? :" in seg:
            continue
        errors.append(f"{rel(path)}: suspicious token near `{seg.strip()}`")

    for m in suspicious.finditer(src):
        errors.append(f"{rel(path)}: stub marker `{m.group(0)}` in main source")

    # a public type must exist with the same name in the file
    if not re.search(r"\b(?:class|interface|enum|record)\s+" + re.escape(name) + r"\b", src):
        errors.append(f"{rel(path)}: no type named `{name}` declared")

    for imp in re.finditer(r"^\s*import\s+(?:static\s+)?(com\.doomsday\.nukes\.[\w.]+);", src, re.M):
        target = imp.group(1)
        # class part is the last capitalised segment
        parts = target.split(".")
        for cut in range(len(parts), 1, -1):
            cand = ".".join(parts[:cut])
            if cand in declared_types or os.path.exists(
                    os.path.join(JAVA, *parts[:cut]) + ".java"):
                break
        else:
            errors.append(f"{rel(path)}: unresolved import {target}")

    fields = set(re.findall(r"\bpublic\s+static\s+final\s+[\w<>\[\].?]+\s+(\w+)\s*=", src)) | \
             set(re.findall(r"\bpublic\s+static\s+[\w<>\[\].?]+\s+(\w+)\s*=", src))
    field_index[fqcn] = fields

# ————————————————————————————————————————————————————————— cross-type refs
ref_re = re.compile(r"\b(Mod[A-Za-z]+)\.([A-Z][A-Z0-9_]*)\b")
for path in paths:
    src = strip_comments(open(path, encoding="utf-8").read())
    for m in ref_re.finditer(src):
        owner, field = m.group(1), m.group(2)
        fq = "com.doomsday.nukes.registry." + owner
        alt = "com.doomsday.nukes.sound." + owner
        for cand in (fq, alt, "com.doomsday.nukes." + owner):
            if cand in field_index:
                if field not in field_index[cand] and field[0].isupper():
                    # constants are UPPER_SNAKE; also allow lowercase static methods
                    if not re.search(r"\b(?:static\s+)?[\w<>\[\].]+\s+" + re.escape(field) + r"\b",
                                     open(field_index[cand], encoding="utf-8").read()):
                        errors.append(f"{rel(path)}: {owner}.{field} not declared in {cand}")
                break
        else:
            errors.append(f"{rel(path)}: unknown holder `{owner}` referenced")

# ——————————————————————————————————————————————————————————— resource graph
def load_json(p):
    try:
        with open(p, encoding="utf-8") as fh:
            return json.load(fh)
    except FileNotFoundError:
        errors.append(f"missing referenced json: {rel(p)}")
    except Exception as e:  # noqa: BLE001
        errors.append(f"invalid json {rel(p)}: {e}")
    return None

assets = os.path.join(RES, "assets", "doomsday")
data = os.path.join(RES, "data", "doomsday")

# every json must parse
for dirpath, _d, names in os.walk(RES):
    for n in names:
        if n.endswith(".json"):
            load_json(os.path.join(dirpath, n))

def asset_exists(path):
    return path and os.path.exists(os.path.join(RES, path))


def local(resid, kind):
    """Resolve an asset id like ``doomsday:block/x`` / ``block/x`` / ``minecraft:block/glass``.

    Returns a path under ``RES`` or None when the id belongs to another namespace, which we can
    only trust (vanilla's own assets are not in this jar). Bare paths are ours.
    """
    ns, _, tail = resid.rpartition(":")
    if ns and ns != "doomsday":
        return None
    return f"assets/{ns or 'doomsday'}/{kind}/{tail}.{ 'json' if kind == 'models' else 'png' }"

# blockstates -> models -> textures
for dirpath, _d, names in os.walk(os.path.join(assets, "blockstates")):
    for n in names:
        j = load_json(os.path.join(dirpath, n))
        if not j:
            continue
        for variant in (j.get("variants", {}) or {}).values():
            vs = variant if isinstance(variant, list) else [variant]
            for v in vs:
                mdl = v.get("model")
                if not mdl:
                    continue
                resolved = local(mdl, "models")
                if resolved is not None and not asset_exists(resolved):
                    warns.append(f"{n}: unresolved model {mdl}")

# item models -> textures / block models
tex_refs = []
for sub in ("item", "block"):
    d = os.path.join(assets, "models", sub)
    if not os.path.isdir(d):
        continue
    for n in os.listdir(d):
        j = load_json(os.path.join(d, n))
        if not j:
            continue
        parent = j.get("parent", "")
        # A parent in our own namespace must exist; vanilla parents are not in this jar.
        resolved = local(parent, "models") if parent else None
        if resolved is not None and not asset_exists(resolved):
            warns.append(f"models/{sub}/{n}: unresolved parent {parent}")
        for key, ref in (j.get("textures", {}) or {}).items():
            tex_refs.append((f"models/{sub}/{n}", key, ref))

for where, key, ref in tex_refs:
    if not isinstance(ref, str) or ref.startswith("#"):
        continue  # "#side" style references resolve inside the model itself
    resolved = local(ref, "textures")
    if resolved is not None and not asset_exists(resolved):
        errors.append(f"{where}: texture #{key} -> missing {resolved}")

# recipes + loot must reference existing items/blocks
known_ids = set()
for p in paths:
    pass  # ids are collected from registry sources below

reg_src = ""
for owner in ("ModBlocks", "ModItems"):
    f = os.path.join(JAVA, "com", "doomsday", "nukes", "registry", owner + ".java")
    if os.path.exists(f):
        reg_src += open(f, encoding="utf-8").read()
for m in re.finditer(r'register\(\s*"([\w/]+)"', reg_src):
    known_ids.add("doomsday:" + m.group(1))
# Devices are registered from the preset enum (register(preset.blockId(), ...)), so their ids are
# computed rather than literal — without this the four blocks and their items look unknown.
for key in ("standard", "little_boy", "fat_man", "tsar_bomba"):
    known_ids.add(f"doomsday:nuke_{key}")
for extra in ("vitrified_sand", "heated_stone", "scorched_dirt", "charred_log"):
    known_ids.add("doomsday:" + extra)

for sub in ("recipe", "loot_table/blocks", "advancement"):
    d = os.path.join(data, sub)
    if not os.path.isdir(d):
        continue
    for n in os.listdir(d):
        if not n.endswith(".json"):
            continue
        j = load_json(os.path.join(d, n))
        if not j:
            continue
        blob = json.dumps(j)
        for ref in re.findall(r'"doomsday:([\w/]+)"', blob):
            pass

# fabric.mod.json contract
fmj = load_json(os.path.join(RES, "fabric.mod.json"))
if fmj:
    for key in ("id", "version", "name", "environment", "entrypoints", "mixins", "depends"):
        if key not in fmj:
            errors.append(f"fabric.mod.json: missing required key `{key}`")
    # id/version/name are Gradle placeholders in the source copy — processResources expands them,
    # so "${id}" is the *correct* value here and a literal "doomsday" would be the bug (the file in
    # git and the file in the jar would disagree).
    for key in ("id", "version", "name"):
        v = fmj.get(key)
        if isinstance(v, str) and not (v == "doomsday" or v.startswith("${")):
            errors.append(f"fabric.mod.json: {key} should be 'doomsday' or a ${{...}} placeholder, got {v!r}")
    for ep_group, entries in (fmj.get("entrypoints") or {}).items():
        for e in entries:
            fq = "com.doomsday.nukes." + e.replace(".", "/") + ".java"
            # entrypoints are FQCNs
            f = os.path.join(JAVA, *e.split(".")) + ".java"
            if not os.path.exists(f):
                errors.append(f"fabric.mod.json: entrypoint class {e} not found ({rel(f)})")
    for mx in fmj.get("mixins") or []:
        cfg = mx if isinstance(mx, str) else mx.get("config")
        if cfg and not os.path.exists(os.path.join(RES, cfg)):
            errors.append(f"fabric.mod.json: mixin config {cfg} not found")
    if "1.21" not in str((fmj.get("depends") or {}).get("minecraft", "")):
        warns.append("fabric.mod.json does not pin a minecraft range; the assets and the mixin "
                     "selectors assume 1.21.1")
    if not (fmj.get("depends") or {}).get("fabric-api"):
        errors.append("fabric.mod.json: no fabric-api dependency, but the mod uses its modules")

for mx in (fmj or {}).get("mixins") or []:
    cfg = mx if isinstance(mx, str) else mx.get("config")
    if not cfg:
        continue
    j = load_json(os.path.join(RES, cfg))
    if not j:
        continue
    pkg = j.get("package", "")
    for sect in ("mixins", "client"):
        for m in j.get(sect, []):
            f = os.path.join(JAVA, *(pkg + "." + m).split(".")) + ".java"
            if not os.path.exists(f):
                errors.append(f"{cfg}: {sect} mixin {pkg}.{m} has no source file")
            else:
                src = open(f, encoding="utf-8").read()
                if "@Mixin" not in src:
                    errors.append(f"{cfg}: {m}.java has no @Mixin annotation")

# sounds.json -> files
sounds_dir = os.path.join(assets, "sounds")
sj = load_json(os.path.join(assets, "sounds.json"))
if sj:
    for name, spec in sj.items():
        for entry in spec.get("sounds", []):
            path = entry["name"] if isinstance(entry, dict) else entry
            if ":" not in path:
                found = False
                for ext in (".ogg", ".wav"):
                    if os.path.exists(os.path.join(sounds_dir, path + ext)):
                        found = True
                if not found:
                    warns.append(f"sounds.json: {name} -> {path}.ogg absent "
                                 f"(run tools/gen_sounds.py, see README)")

# lang coverage: every translation key used in code must exist in en_us.json
lang = load_json(os.path.join(assets, "lang", "en_us.json")) or {}
used_keys = set()
for p in paths:
    src = open(p, encoding="utf-8").read()
    for m in re.finditer(r'(?:text|Text\.translatable|translatable)\(\s*"([\w.]+)"', src):
        used_keys.add(m.group(1))
    for m in re.finditer(r'"(block\.doomsday|item\.doomsday|gui\.doomsday|subtitle\.doomsday'
                         r"|effect\.doomsday|text\.doomsday)\.[\w.]+\"", src):
        used_keys.add(m.group(0).strip('"'))
missing = sorted(k for k in used_keys if k not in lang and not k.endswith('.'))
if missing:
    errors.append("lang/en_us.json missing keys: " + ", ".join(missing[:12])
                  + (" ..." if len(missing) > 12 else ""))

# shaders: post pipeline -> program json -> .vsh/.fsh.
# Vanilla's post-processor JSON names the program in "name" (not "program"), and an unqualified
# name resolves to the minecraft namespace, which is why the blit pass below is not our file.
post = os.path.join(assets, "shaders", "post")
if os.path.isdir(post):
    for n in sorted(os.listdir(post)):
        if not n.endswith(".json"):
            continue
        j = load_json(os.path.join(post, n))
        if not j:
            continue
        if not j.get("passes"):
            errors.append(f"shaders/post/{n}: no passes array")
        for p_ in j.get("passes", []):
            prog = p_.get("name", "")
            if not prog or prog.startswith("minecraft:"):
                continue
            local = prog.split(":")[-1]
            if not prog.startswith("doomsday:"):
                continue
            for cand in (f"program/{local}.json", f"program/{local}.fsh", f"program/{local}.vsh"):
                if not asset_exists("assets/doomsday/shaders/" + cand):
                    errors.append(f"shaders/post/{n}: pass {prog} has no shaders/{cand}")

# ————————————————————————————————————————— sounds: registry <-> sounds.json, both directions
def registered_sounds():
    f = os.path.join(JAVA, "com", "doomsday", "nukes", "sound", "ModSounds.java")
    if not os.path.exists(f):
        return set()
    return set(re.findall(r'register\("([a-z0-9_.]+)"\)', open(f, encoding="utf-8").read()))


sounds_json = load_json(os.path.join(assets, "sounds.json")) if os.path.exists(
    os.path.join(assets, "sounds.json")) else None
if sounds_json is None:
    errors.append("assets/doomsday/sounds.json is missing: the mod's SoundEvents would have no samples")
else:
    defined = {k for k in sounds_json if not k.startswith("_") and k != "comment"}
    reg = registered_sounds()
    for k in sorted(defined - reg):
        errors.append(f"sounds.json defines '{k}' which ModSounds never registers (dead entry, "
                      f"loaded into every client's sound engine for nothing)")
    for k in sorted(reg - defined):
        errors.append(f"ModSounds registers '{k}' with no sounds.json entry — silent event plus a "
                      f"'Unable to load sound' line per client")
    lang = load_json(os.path.join(assets, "lang", "en_us.json")) or {}
    for k in sorted(defined & reg):
        body = sounds_json[k]
        if not isinstance(body, dict):
            errors.append(f"sounds.json: '{k}' is not an object")
            continue
        subtitle = body.get("subtitle")
        if subtitle and subtitle not in lang:
            errors.append(f"sounds.json: '{k}' subtitle key '{subtitle}' absent from en_us.json")
        samples = body.get("sounds")
        if not isinstance(samples, list) or not samples:
            errors.append(f"sounds.json: '{k}' has an empty or missing sounds array")
            continue
        for samp in samples:
            if not isinstance(samp, dict):
                continue  # a bare string is a legal relative file reference
            name, typ = samp.get("name", ""), samp.get("type")
            if typ not in (None, "event", "file"):
                errors.append(f"sounds.json: '{k}' sample type '{typ}' is not event/file")
            if name.startswith("minecraft:") and typ != "event":
                errors.append(f"sounds.json: '{k}' names vanilla event {name} without "
                              f'\"type\": \"event\" — the loader would look for a .ogg we do not ship')

# ————————————————————————————————————————— item/block asset coverage
def registered_ids(owner):
    f = os.path.join(JAVA, "com", "doomsday", "nukes", "registry", owner + ".java")
    if not os.path.exists(f):
        return set()
    src = open(f, encoding="utf-8").read()
    ids = set(re.findall(r'register\(\s*"([a-z0-9_/]+)"', src))
    # The four devices are registered per preset from NukePreset#blockId(), so their ids are computed
    # rather than written as literals anywhere — without this they read as unregistered references
    # in every recipe and loot table.
    ids |= {f"nuke_{key}" for key in ("standard", "little_boy", "fat_man", "tsar_bomba")}
    return ids


blocks = registered_ids("ModBlocks")
items = registered_ids("ModItems")
# BlockItems for the aftermath blocks are registered through blockItem(ModBlocks.X), which derives
# the id from the block — no literal in the source to match. flash_light has no BlockItem on
# purpose: a block the player must never be able to place by hand.
items |= blocks - {"flash_light"}

bs_dir = os.path.join(assets, "blockstates")
have_bs = {n[:-5] for n in os.listdir(bs_dir)} if os.path.isdir(bs_dir) else set()
for b in sorted(blocks):
    if b not in have_bs:
        errors.append(f"block '{b}' has no blockstates/{b}.json (renders as a missing-model cube)")
for b in sorted(have_bs - blocks):
    errors.append(f"blockstates/{b}.json has no registered block")

mdir = os.path.join(assets, "models", "item")
have_im = {n[:-5] for n in os.listdir(mdir)} if os.path.isdir(mdir) else set()
for i in sorted(items):
    if i not in have_im:
        errors.append(f"item '{i}' has no models/item/{i}.json")
for i in sorted(have_im - items):
    errors.append(f"models/item/{i}.json has no registered item")

loot_dir = os.path.join(data, "loot_table", "blocks")
have_loot = {n[:-5] for n in os.listdir(loot_dir)} if os.path.isdir(loot_dir) else set()
silent_no_drop = {"flash_light"}  # a block that must never drop itself, so no table is correct
for b in sorted(blocks - have_loot - silent_no_drop):
    warns.append(f"block '{b}' has no loot table — it drops nothing when broken")
for b in sorted(have_loot - blocks):
    errors.append(f"loot_table/blocks/{b}.json is for an unknown block")

for kind in ("recipe", "loot_table/blocks"):
    d = os.path.join(data, kind)
    if not os.path.isdir(d):
        continue
    for n in sorted(os.listdir(d)):
        j = load_json(os.path.join(d, n))
        if j is None:
            continue
        if "type" not in j:
            errors.append(f"data/{kind}/{n}: no \"type\" field")
        for ref in set(re.findall(r'"(doomsday:[a-z0-9_/]+)"', json.dumps(j))):
            path = ref.split(":")[-1]
            if path not in blocks | items:
                errors.append(f"data/{kind}/{n}: references unregistered {ref}")

# ————————————————————————————————————————— texture file sanity (header only, no PIL needed)
tex_root = os.path.join(assets, "textures")
png_count = 0
if os.path.isdir(tex_root):
    for dirpath, _d, names in os.walk(tex_root):
        for n in names:
            if not n.endswith(".png"):
                continue
            png_count += 1
            path = os.path.join(dirpath, n)
            with open(path, "rb") as fh:
                head = fh.read(26)
            if len(head) < 26 or head[:4] != b"\x89PNG":
                errors.append(f"{rel(path)}: not a PNG — the texture stitcher fails at runtime, "
                              f"not at build time")
                continue
            w = int.from_bytes(head[16:20], "big")
            h = int.from_bytes(head[20:24], "big")
            depth, ctype = head[24], head[25]
            if w <= 0 or h <= 0 or w != h or (w & (w - 1)) != 0:
                errors.append(f"{rel(path)}: {w}x{h} is not a square power of two")
            if ctype != 6:
                warns.append(f"{rel(path)}: colour type {ctype} (expected 6 = RGBA) — cutout "
                             f"textures without alpha render opaque")
            if depth != 8:
                warns.append(f"{rel(path)}: bit depth {depth} (expected 8)")
    icon = os.path.join(assets, "icon.png")
    if not os.path.exists(icon):
        warns.append("assets/doomsday/icon.png missing while fabric.mod.json advertises an icon")
else:
    errors.append("no assets/doomsday/textures directory")

# ————————————————————————————————————————— lang: unused keys and translation parity
lang_dir = os.path.join(assets, "lang")
en = load_json(os.path.join(lang_dir, "en_us.json"))
if en is not None:
    code_keys = set()
    for f in paths:
        code_keys |= set(re.findall(
            r'"((?:gui|block|item|itemGroup|command|subtitle|subtitles|effect|config|death)\.[a-z0-9_.]+)"',
            open(f, encoding="utf-8").read()))
    # Dynamic keys: prefixes concatenated with an enum value. Expand the enumerable ones so the
    # check is a real check; a prefix that cannot be expanded is reported instead of skipped.
    for f in paths:
        src = open(f, encoding="utf-8").read()
        for prefix in set(re.findall(r'"((?:gui|block|item|subtitle|command)\.[a-z0-9_.]*\.)"\s*\+', src)):
            if prefix.endswith("stage."):
                code_keys |= {prefix + k for k in (
                    "standby", "arming", "flash", "fireball", "shockwave", "mushroom_cloud",
                    "fallout", "aftermath", "complete")}
            elif prefix.startswith("block.doomsday."):
                code_keys |= {prefix + "nuke_" + k for k in (
                    "standard", "little_boy", "fat_man", "tsar_bomba")}
            elif prefix.endswith("quality."):
                code_keys |= {prefix + q for q in ("LOW", "MEDIUM", "HIGH", "ULTRA")}
            else:
                warns.append(f"cannot expand dynamic lang prefix {prefix!r} — verify by hand")
    for k in sorted(set(en) - code_keys):
        if k.startswith(("block.doomsday.", "item.doomsday.", "subtitles.doomsday.",
                         "effect.doomsday.", "entity.doomsday.")):
            continue  # pulled in by registration / sounds.json, never by a translatable() call
        warns.append(f"lang: en_us.json key is not referenced by any code: {k}")
    for other in sorted(os.listdir(lang_dir)):
        if not other.endswith(".json") or other == "en_us.json":
            continue
        t = load_json(os.path.join(lang_dir, other)) or {}
        extra = set(t) - set(en)
        if extra:
            errors.append(f"{other}: translates keys en_us.json does not define: {sorted(extra)[:4]}")
        missing = len(set(en) - set(t))
        if missing:
            warns.append(f"{other}: {missing} of {len(en)} keys untranslated")


print(f"{png_count} textures")
print("=" * 72)
for e in errors:
    print("ERROR  ", e)
for w in warns:
    print("warn   ", w)
print(f"{len(paths)} java files, {len(errors)} errors, {len(warns)} warnings")
sys.exit(1 if errors else 0)
