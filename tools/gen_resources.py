#!/usr/bin/env python3
"""Emit every data-driven resource for Doomsday Nukes (blockstates, models, loot, recipes, lang).

Why a generator instead of hand-written files
--------------------------------------------
The mod has 4 device blocks × 4 orientations, 5 aftermath blocks, 12 items, 8 loot tables and 2
languages. That is ~70 JSON files whose content is 90 % mechanical: a blockstate for a rotatable
block is the same 4 entries with a different y rotation, and an untranslated key in one language is
a visible bug in the other. Generating them keeps those files *consistent by construction* — the
property a hand-edited set loses the first time someone adds a preset and forgets two of the five
places.

What is NOT generated (and why)
------------------------------
* textures — a PNG is a binary format; see ``gen_textures.py``.
* sounds.json, shaders, fabric.mod.json, the mixin config — one-offs where the interesting content is
  the prose/comments and where a mistake is a launch failure, not a typo.

The script is idempotent and safe to re-run: it only ever writes the files it owns.

Usage:  python3 tools/gen_resources.py [--check]
"""

from __future__ import annotations

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")
NS = "doomsday"
ASSETS = os.path.join(RES, "assets", NS)
DATA = os.path.join(RES, "data", NS)

# ———————————————————————————————————————————————————— content tables
# (preset config key -> design numbers used only for tooltips/recipes/README agreement).
PRESETS = {
    "standard": {"yieldKt": 20.0, "blast": 46.0, "crater": 17.0, "cloud": 1.0},
    "little_boy": {"yieldKt": 15.0, "blast": 41.0, "crater": 15.5, "cloud": 0.92},
    "fat_man": {"yieldKt": 21.0, "blast": 47.5, "crater": 17.6, "cloud": 1.06},
    "tsar_bomba": {"yieldKt": 100.0, "blast": 78.0, "crater": 30.0, "cloud": 1.85},
}

AFTERMATH = {
    # id -> (model parent, textures, "cube" kind)
    "vitrified_sand": ("cube_all", ["block/vitrified_sand"]),
    "heated_stone": ("cube_bottom_top", ["block/heated_stone_top", "block/heated_stone", "block/heated_stone"]),
    "scorched_dirt": ("cube_bottom_top", ["block/scorched_dirt_top", "block/scorched_dirt", "block/scorched_dirt"]),
    "charred_log": ("column", ["block/charred_log_top", "block/charred_log", "block/charred_log"]),
}

EQUIPMENT = ["hazmat_goggles", "geiger_counter", "remote_detonator", "iodine_tablet"]

STAGES = [
    # The enum's own keys, in order: DetonationStage.values() is what /doomsday preview suggests and
    # what the stage-change packet carries, so the lang tables are keyed off it exactly — no extra
    # "nice to have" stages, and none missing. There is deliberately no crater/emp stage: cratering
    # belongs to FIREBALL and the EMP tail to FALLOUT in the timeline, so inventing lang keys for them
    # would advertise phases the client never announces.
    ("standby", "Standby"),
    ("arming", "Arming"),
    ("flash", "Flash"),
    ("fireball", "Fireball"),
    ("shockwave", "Shockwave"),
    ("mushroom_cloud", "Mushroom Cloud"),
    ("fallout", "Fallout"),
    ("aftermath", "Aftermath"),
    ("complete", "Complete"),
]

# ———————————————————————————————————————————————————————————————— lang
# Keys are shared verbatim between languages, so a missing translation is impossible by
# construction; en_us is the source of truth the AssetValidator diffs against.
EN_US = {
    # creative tab + registration names
    "itemGroup.doomsday.main": "Doomsday Nukes",
    "block.doomsday.nuke_standard": "Standard Nuke",
    "block.doomsday.nuke_little_boy": "Little Boy",
    "block.doomsday.nuke_fat_man": "Fat Man",
    "block.doomsday.nuke_tsar_bomba": "Tsar Bomba",
    "block.doomsday.vitrified_sand": "Vitrified Sand",
    "block.doomsday.heated_stone": "Heated Stone",
    "block.doomsday.scorched_dirt": "Scorched Dirt",
    "block.doomsday.charred_log": "Charred Log",
    "block.doomsday.flash_light": "Flash Light",
    "item.doomsday.nuke_standard": "Standard Nuke",
    "item.doomsday.nuke_little_boy": "Little Boy",
    "item.doomsday.nuke_fat_man": "Fat Man",
    "item.doomsday.nuke_tsar_bomba": "Tsar Bomba",
    "item.doomsday.hazmat_goggles": "Hazmat Goggles",
    "item.doomsday.geiger_counter": "Geiger Counter",
    "item.doomsday.remote_detonator": "Remote Detonator",
    "item.doomsday.iodine_tablet": "Iodine Tablet",
    "item.doomsday.hazmat_goggles.broken": "Your goggles melt.",
    # item tooltips
    "item.doomsday.nuke.yield": "Yield: %s kt",
    "item.doomsday.nuke.blast": "Blast radius: %s blocks",
    "item.doomsday.nuke.timer": "Default timer: %s s",
    "item.doomsday.geiger_counter.desc": "Detects contamination within %s blocks",
    "item.doomsday.remote_detonator.desc": "Right-click a device to link it",
    "item.doomsday.iodine_tablet.desc": "Halves incoming dose for %s s",
    "item.doomsday.hazmat_goggles.desc": "80%% radiation resistance, flash protection",
    # control panel
    "gui.doomsday.control.title": "Device Control: %s",
    "gui.doomsday.control.arm": "Arm",
    "gui.doomsday.control.disarm": "Disarm",
    "gui.doomsday.control.detonate": "Detonate Now",
    "gui.doomsday.control.detonate_locked": "Detonate Now (disabled)",
    "gui.doomsday.control.presets": "Change Device",
    "gui.doomsday.control.preset_locked": "Preset is fixed by the block: place another device",
    "gui.doomsday.control.close": "Close",
    "gui.doomsday.control.idle": "Standing by",
    "gui.doomsday.control.clean_mode": "Griefing is off — visuals only",
    "gui.doomsday.control.emp_suppressed": "EMP suppression active",
    "gui.doomsday.device.armed_banner": "ARMED — T-%s s",
    "gui.doomsday.screen.unavailable": "This screen needs the mod's client classes.",
    # — stage names: one pair per DetonationStage constant (see STAGES above)
    "gui.doomsday.stage.standby": "Standby",
    "gui.doomsday.stage.arming": "Arming",
    "gui.doomsday.stage.flash": "Flash",
    "gui.doomsday.stage.fireball": "Fireball",
    "gui.doomsday.stage.shockwave": "Shockwave",
    "gui.doomsday.stage.mushroom_cloud": "Mushroom cloud",
    "gui.doomsday.stage.fallout": "Fallout",
    "gui.doomsday.stage.aftermath": "Aftermath",
    "gui.doomsday.stage.complete": "Detonation complete",
    "subtitle.doomsday.stage.standby": 'The device waits',
    "subtitle.doomsday.stage.arming": 'The device counts down',
    "subtitle.doomsday.stage.flash": 'A light brighter than the sun',
    "subtitle.doomsday.stage.fireball": 'The air ignites',
    "subtitle.doomsday.stage.shockwave": 'The shockwave arrives',
    "subtitle.doomsday.stage.mushroom_cloud": 'The cloud rises',
    "subtitle.doomsday.stage.fallout": 'Ash begins to fall',
    "subtitle.doomsday.stage.aftermath": 'The sky turns grey',
    "subtitle.doomsday.stage.complete": 'The blast is over',
    # HUD
    "gui.doomsday.hazmat.status": "Goggles: %s%% intact",
    "gui.doomsday.geiger.hint": "Hold a Geiger counter for a live readout",
    "gui.doomsday.geiger.sweep_on": "Sweep mode: wide",
    "gui.doomsday.geiger.sweep_off": "Sweep mode: narrow",
    "gui.doomsday.radiation.exposure": "Exposure %s",
    "gui.doomsday.radiation.iodine_active": "Iodine active",
    "gui.doomsday.iodine.disabled": "Radiation is disabled on this server.",
    "gui.doomsday.iodine.taken": "Iodine protects you for %s s",
    "gui.doomsday.emp.hit": "EMP — systems offline for %s s",
    "gui.doomsday.emp.recovered": "Systems restored",
    # detonator
    "gui.doomsday.detonator.linked": "Linked to %s",
    "gui.doomsday.detonator.linked_to": "Linked to %s, %s, %s in %s",
    "gui.doomsday.detonator.unlinked": "Detonator unlinked",
    "gui.doomsday.detonator.not_linked": "The detonator is not linked to anything.",
    "gui.doomsday.detonator.device_gone": "The device is gone — link cleared.",
    "gui.doomsday.detonator.stale_link": "Link is stale (device re-armed elsewhere) — cleared.",
    "gui.doomsday.detonator.wrong_dimension": "The linked device is in another dimension.",
    "gui.doomsday.detonator.cancelled": "Countdown cancelled",
    "gui.doomsday.detonator.nothing_to_cancel": "Nothing to cancel",
    "gui.doomsday.detonator.cancel_disabled": "Remote cancel is disabled by the server",
    # config screen
    "gui.doomsday.config.title": "Doomsday Nukes Settings",
    "gui.doomsday.config.hint": "Values clamp on save; the file is config/doomsday.json",
    "gui.doomsday.config.save": "Save",
    "gui.doomsday.config.reset": "Reset to defaults",
    "gui.doomsday.config.cancel": "Cancel",
    "gui.doomsday.config.griefing": "Terrain destruction",
    "gui.doomsday.config.flash": "Flash",
    "gui.doomsday.config.clouds": "Mushroom cloud",
    "gui.doomsday.config.fallout": "Fallout",
    "gui.doomsday.config.radiation": "Radiation",
    "gui.doomsday.config.atmosphere": "Atmosphere",
    "gui.doomsday.config.adaptive": "Adaptive quality",
    "gui.doomsday.config.fov": "Camera effects",
    "gui.doomsday.config.remote_disarm": "Remote cancel",
    "gui.doomsday.config.verbose": "Verbose log",
    "gui.doomsday.config.quality": "Quality",
    "gui.doomsday.config.quality.LOW": "Low",
    "gui.doomsday.config.quality.MEDIUM": "Medium",
    "gui.doomsday.config.quality.HIGH": "High",
    "gui.doomsday.config.quality.ULTRA": "Ultra",
    "gui.doomsday.config.terrain_budget": "Blocks/tick",
    "gui.doomsday.config.max_entities": "Visual cap",
    "gui.doomsday.config.density": "Particles",
    "gui.doomsday.config.sound_distance": "Sound reach",
    "gui.doomsday.config.timer": "Default timer",
    # commands
    "command.doomsday.detonated": "%s detonated",
    "command.doomsday.armed": "Armed for %s s",
    "command.doomsday.arm_refused": "The device refused that timer",
    "command.doomsday.disarmed": "Disarmed",
    "command.doomsday.nothing_armed": "Nothing was armed",
    "command.doomsday.no_device": "No device in front of you",
    "command.doomsday.no_devices_armed": "No armed devices",
    "command.doomsday.no_block_entity": "That block has no device data",
    "command.doomsday.needs_player": "Run this as a player",
    "command.doomsday.unknown_stage": "Unknown stage: %s",
    "command.doomsday.unknown_quality": "Unknown quality: %s",
    "command.doomsday.preview": "Previewing stage %s",
    "command.doomsday.emp": "EMP: %s blocks for %s s",
    "command.doomsday.radiation_now": "Exposure is now %s",
    "command.doomsday.radiation_cleared": "Exposure cleared",
    "command.doomsday.iodine": "Iodine active for %s s",
    "command.doomsday.config_reset": "Config reset to defaults",
    "command.doomsday.config_saved": "Config saved",
    "command.doomsday.quality": "Quality set to %s",
    # subtitles for the sound events
    "subtitles.doomsday.siren": "Siren wails",
    "subtitles.doomsday.geiger": "Geiger counter clicks",
    "subtitles.doomsday.nuke_arm": "Device arms",
    "subtitles.doomsday.flash_hiss": "Air ignites",
    "subtitles.doomsday.nuke_distant_boom_delayed": "Distant explosion",
    "subtitles.doomsday.mushroom_rumble": "Thunder rolls",
    "effect.doomsday.radiation": "Radiation Sickness",
}

DE_DE = {
    "itemGroup.doomsday.main": "Doomsday Nukes",
    "block.doomsday.nuke_standard": "Standard-Atombombe",
    "block.doomsday.nuke_little_boy": "Little Boy",
    "block.doomsday.nuke_fat_man": "Fat Man",
    "block.doomsday.nuke_tsar_bomba": "Zarenbombe",
    "block.doomsday.vitrified_sand": "Vitrifizierter Sand",
    "block.doomsday.heated_stone": "Erhitzter Stein",
    "block.doomsday.scorched_dirt": "Verbrannte Erde",
    "block.doomsday.charred_log": "Verkohlter Stamm",
    "block.doomsday.flash_light": "Blitzlicht",
    "item.doomsday.hazmat_goggles": "Strahlenschutzbrille",
    "item.doomsday.geiger_counter": "Geigerzähler",
    "item.doomsday.remote_detonator": "Fernzündung",
    "item.doomsday.iodine_tablet": "Jodtablette",
    "item.doomsday.hazmat_goggles.broken": "Deine Brille schmilzt.",
    "item.doomsday.nuke.yield": "Sprengkraft: %s kt",
    "item.doomsday.nuke.blast": "Sprengradius: %s Blöcke",
    "item.doomsday.nuke.timer": "Standardtimer: %s s",
    "gui.doomsday.control.arm": "Scharf",
    "gui.doomsday.control.disarm": "Entschärfen",
    "gui.doomsday.control.detonate": "Jetzt zünden",
    "gui.doomsday.control.close": "Schließen",
    "gui.doomsday.control.idle": "Bereit",
    "gui.doomsday.device.armed_banner": "SCHARF — T-%s s",
    "gui.doomsday.radiation.exposure": "Strahlenbelastung %s",
    "gui.doomsday.radiation.iodine_active": "Jodschutz aktiv",
    "gui.doomsday.iodine.taken": "Jod schützt %s s",
    "gui.doomsday.emp.hit": "EMP — Systeme für %s s offline",
    "gui.doomsday.detonator.linked": "Mit %s verbunden",
    "gui.doomsday.detonator.unlinked": "Fernzündung getrennt",
    "gui.doomsday.config.title": "Doomsday Nukes — Einstellungen",
    "gui.doomsday.config.save": "Speichern",
    "gui.doomsday.config.reset": "Zurücksetzen",
    "gui.doomsday.config.cancel": "Abbrechen",
    "gui.doomsday.config.griefing": "Geländezerstörung",
    "gui.doomsday.config.flash": "Blitz",
    "gui.doomsday.config.clouds": "Atomwolke",
    "gui.doomsday.config.fallout": "Fallout",
    "gui.doomsday.config.radiation": "Strahlung",
    "gui.doomsday.hazmat.status": "Brille: %s %% intakt",
    "gui.doomsday.radiation.iodine_active": "Jodschutz aktiv",
    "gui.doomsday.detonator.not_linked": "Das Gerät ist nicht verbunden.",
    "gui.doomsday.detonator.device_gone": "Das Gerät ist weg — Verbindung gelöscht.",
    "item.doomsday.nuke.yield": "Sprengkraft: %s kt",
    "item.doomsday.nuke.blast": "Sprengradius: %s Blöcke",
    "item.doomsday.nuke.timer": "Standardtimer: %s s",
    "gui.doomsday.stage.standby": "Bereit",
    "gui.doomsday.stage.arming": "Scharfschalten",
    "gui.doomsday.stage.flash": "Blitz",
    "gui.doomsday.stage.fireball": "Feuerball",
    "gui.doomsday.stage.shockwave": "Druckwelle",
    "gui.doomsday.stage.mushroom_cloud": "Atomwolke",
    "gui.doomsday.stage.fallout": "Fallout",
    "gui.doomsday.stage.aftermath": "Nachwirkung",
    "gui.doomsday.stage.complete": "Zündung abgeschlossen",
    "subtitle.doomsday.stage.arming": "Das Gerät zählt herunter",
    "subtitle.doomsday.stage.flash": "Ein Licht heller als die Sonne",
    "subtitle.doomsday.stage.fireball": "Die Luft entzündet sich",
    "subtitle.doomsday.stage.shockwave": "Die Druckwelle trifft ein",
    "subtitle.doomsday.stage.mushroom_cloud": "Die Wolke steigt",
    "subtitle.doomsday.stage.fallout": "Asche beginnt zu fallen",
    "subtitle.doomsday.stage.aftermath": "Der Himmel wird grau",
    "subtitle.doomsday.stage.complete": "Die Explosion ist vorüber",
    "gui.doomsday.control.presets": "Gerät wechseln",
    "gui.doomsday.control.detonate": "Jetzt zünden",
    "gui.doomsday.control.title": "Gerätesteuerung: %s",
    "gui.doomsday.config.hint": "Werte werden beim Speichern begrenzt; Datei: config/doomsday.json",
    "gui.doomsday.config.atmosphere": "Atmosphäre",
    "gui.doomsday.config.fov": "Kameraeffekte",
    "gui.doomsday.config.adaptive": "Adaptive Qualität",
    "gui.doomsday.config.quality": "Qualität",
    "gui.doomsday.config.quality.LOW": "Niedrig",
    "gui.doomsday.config.quality.MEDIUM": "Mittel",
    "gui.doomsday.config.quality.HIGH": "Hoch",
    "gui.doomsday.config.quality.ULTRA": "Ultra",
    "command.doomsday.detonated": "%s gezündet",
    "command.doomsday.armed": "Scharf für %s s",
    "command.doomsday.disarmed": "Entschärft",
    "effect.doomsday.radiation": "Strahlenkrankheit",
}


# ———————————————————————————————————————————————————————————— helpers
def write(path: str, obj) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    rel = os.path.relpath(path, ROOT)
    if isinstance(obj, str):
        data = obj
    else:
        data = json.dumps(obj, indent=2, ensure_ascii=False) + "\n"
    if CHECK:
        if not os.path.exists(path):
            print(f"MISSING {rel}")
        return
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(data)
    print(f"wrote {rel}")


def jmodel(parent: str, textures: dict, extra: dict | None = None) -> dict:
    out = {"parent": parent}
    if textures:
        out["textures"] = textures
    if extra:
        out.update(extra)
    return out


# The device model: one solid machine inside a single 1×1×1 voxel.
#
# The geometry deliberately stays inside the block's own box. The earlier draft drew a 2-block
# tower from a block-entity renderer, which (a) needed a render API whose signature moves between
# patch versions and (b) made a device occupy one voxel while looking like two — the classic setup
# for "the lamp is on the other side of the wall" style bugs. Everything visible here fits the
# voxel, so culling, light, and hit testing all agree with what you see.
DEVICE_ELEMENTS = [
    {
        "from": [1, 0, 1], "to": [15, 9, 15],
        "faces": {
            "north": {"uv": [1, 7, 15, 16], "texture": "#side"},
            "east": {"uv": [1, 7, 15, 16], "texture": "#side"},
            "south": {"uv": [1, 7, 15, 16], "texture": "#side"},
            "west": {"uv": [1, 7, 15, 16], "texture": "#side"},
            "up": {"uv": [1, 1, 15, 15], "texture": "#top"},
            "down": {"uv": [1, 1, 15, 15], "texture": "#top", "cullface": "down"},
        },
    },
    {
        "from": [3, 9, 3], "to": [13, 13, 13],
        "faces": {
            "north": {"uv": [3, 3, 13, 7], "texture": "#console"},
            "east": {"uv": [3, 3, 13, 7], "texture": "#console"},
            "south": {"uv": [3, 3, 13, 7], "texture": "#console"},
            "west": {"uv": [3, 3, 13, 7], "texture": "#console"},
            "up": {"uv": [3, 3, 13, 13], "texture": "#console"},
        },
    },
    {
        "from": [7, 13, 7], "to": [9, 16, 9],
        "faces": {
            "north": {"uv": [7, 0, 9, 3], "texture": "#top"},
            "east": {"uv": [7, 0, 9, 3], "texture": "#top"},
            "south": {"uv": [7, 0, 9, 3], "texture": "#top"},
            "west": {"uv": [7, 0, 9, 3], "texture": "#top"},
            "up": {"uv": [7, 7, 9, 9], "texture": "#console"},
        },
    },
]


def main() -> int:
    global CHECK
    # — blockstates: 4 device blocks, facing only.
    #
    # ARMED is intentionally *not* a model state. Toggling a blockstate model rebuilds the chunk
    # section, so an "armed" variant would mean every armed device recompiles geometry on the beat
    # it blinks. The pulse is delivered by particles (NukeBlock#randomDisplayTick) and by the HUD
    # banner instead, at no rebuild cost.
    for preset in PRESETS:
        block = f"nuke_{preset}"
        variants = {}
        for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            variants[f"facing={facing}"] = {"model": f"{NS}:block/{block}", "y": y}
        write(os.path.join(ASSETS, "blockstates", f"{block}.json"),
              {"variants": variants})
        write(os.path.join(ASSETS, "models", "block", f"{block}.json"),
              jmodel("minecraft:block/block",
                     {
                         "side": f"{NS}:block/{block}",
                         "top": f"{NS}:block/{block}_top",
                         "console": f"{NS}:block/{block}_console",
                         "particle": f"{NS}:block/{block}",
                     },
                     {"elements": DEVICE_ELEMENTS, "ambientocclusion": True,
                      "render_type": "minecraft:cutout"}))
        write(os.path.join(ASSETS, "models", "item", f"{block}.json"),
              {"parent": f"{NS}:block/{block}",
               "display": {
                   "gui": {"rotation": [30, 45, 0], "translation": [0, 0, 0], "scale": [0.62, 0.62, 0.62]},
                   "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
                   "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
               }})
        loot = {
            "type": "minecraft:block",
            "pools": [{
                "rolls": 1,
                "entries": [{"type": "minecraft:item", "name": f"{NS}:{block}"}],
                "conditions": [{"condition": "minecraft:survives_explosion"}],
            }],
        }
        # A device that detonated must not drop itself: the detonation path removes the block with
        # the "no drop" flag, so no loot condition is needed here — and adding `match_tool` style
        # tricks would silently stop legitimate picks from dropping it in adventure maps.
        write(os.path.join(DATA, "loot_table", "blocks", f"{block}.json"), loot)

    # — aftermath blocks
    for block, (kind, tex) in AFTERMATH.items():
        if kind == "cube_all":
            model = jmodel("minecraft:block/cube_all", {"all": tex[0]})
        elif kind == "column":
            model = jmodel("minecraft:block/cube_column", {"end": tex[0], "side": tex[1]})
        else:
            model = jmodel("minecraft:block/cube_bottom_top",
                           {"top": tex[0], "side": tex[1], "bottom": tex[2]})
        write(os.path.join(ASSETS, "blockstates", f"{block}.json"),
              {"variants": {"": {"model": f"{NS}:block/{block}"}}})
        write(os.path.join(ASSETS, "models", "block", f"{block}.json"), model)
        write(os.path.join(ASSETS, "models", "item", f"{block}.json"),
              {"parent": f"{NS}:block/{block}"})
        write(os.path.join(DATA, "loot_table", "blocks", f"{block}.json"), {
            "type": "minecraft:block",
            "pools": [{
                "rolls": 1,
                "entries": [{"type": "minecraft:item", "name": f"{NS}:{block}"}],
                "conditions": [{"condition": "minecraft:survives_explosion"}],
            }],
        })

    # — flash light: invisible by design, but it still needs a blockstate + model or the game logs
    # a missing-model line for every one placed. The model has no elements, so it renders nothing.
    write(os.path.join(ASSETS, "blockstates", "flash_light.json"),
          {"variants": {"": {"model": f"{NS}:block/flash_light"}}})
    write(os.path.join(ASSETS, "models", "block", "flash_light.json"),
          {"parent": "minecraft:block/block", "textures": {"particle": "minecraft:block/glass"},
           "elements": [], "ambientocclusion": False})

    # — equipment item models
    for item in EQUIPMENT:
        write(os.path.join(ASSETS, "models", "item", f"{item}.json"),
              jmodel("minecraft:item/generated", {"layer0": f"{NS}:item/{item}"}))

    # — recipes. Deliberately craftable but *expensive and deliberate*: a device should never
    # appear by accident while building a house.
    write(os.path.join(DATA, "recipe", "nuke_standard.json"), {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": ["igi", "sps", "rir"],
        "key": {
            "i": "minecraft:iron_ingot", "g": "minecraft:glass_bottle",
            "s": "minecraft:smooth_stone", "p": "minecraft:piston",
            "r": "minecraft:redstone_block",
        },
        "result": {"item": f"{NS}:nuke_standard", "count": 1},
    })
    write(os.path.join(DATA, "recipe", "hazmat_goggles.json"), {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": ["ggg", "g g"],
        "key": {"g": "minecraft:glass_pane"},
        "result": {"item": f"{NS}:hazmat_goggles", "count": 1},
        "group": "doomsday",
    })
    write(os.path.join(DATA, "recipe", "geiger_counter.json"), {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": [" r ", "ici", " a "],
        "key": {
            "r": "minecraft:redstone", "c": "minecraft:copper_ingot",
            "i": "minecraft:iron_nugget", "a": "minecraft:amethyst_shard",
        },
        "result": {"item": f"{NS}:geiger_counter", "count": 1},
    })
    write(os.path.join(DATA, "recipe", "remote_detonator.json"), {
        "type": "minecraft:crafting_shaped",
        "category": "redstone",
        "pattern": ["bt ", "lew"],
        "key": {
            "b": "minecraft:stone_button", "t": "minecraft:redstone_torch",
            "l": "minecraft:lever", "e": "minecraft:ender_pearl",
            "w": "minecraft:weighted_pressure_plate",
        },
        "result": {"item": f"{NS}:remote_detonator", "count": 1},
    })
    write(os.path.join(DATA, "recipe", "iodine_tablet.json"), {
        "type": "minecraft:crafting_shapeless",
        "category": "food",
        "ingredients": [
            "minecraft:glistering_melon_slice", "minecraft:kelp",
            "minecraft:glass_bottle", "minecraft:sugar",
        ],
        "result": {"item": f"{NS}:iodine_tablet", "count": 3},
    })
    # Preset variants: each is a device plus the ingredient that "flavours" it, so all four are
    # reachable in survival without inventing four separate engineering trees. The additions are
    # real vanilla items only — there is no uranium in Minecraft, and a recipe that references a
    # non-existent id is a datapack load error, not a missing texture.
    for preset, extra in {
        # "refined": quartz reads as processed material without inventing an element to balance
        "little_boy": "minecraft:quartz_block",
        "fat_man": "minecraft:magma_block",
        "tsar_bomba": "minecraft:netherite_block",
    }.items():
        write(os.path.join(DATA, "recipe", f"nuke_{preset}.json"), {
            "type": "minecraft:crafting_shapeless",
            "category": "equipment",
            "ingredients": [f"{NS}:nuke_standard", extra],
            "result": {"item": f"{NS}:nuke_{preset}", "count": 1},
        })

    # — lang
    for code, table in (("en_us", EN_US), ("de_de", DE_DE)):
        write(os.path.join(ASSETS, "lang", f"{code}.json"),
              json.dumps(table, indent=2, ensure_ascii=False, sort_keys=True) + "\n")

    # — a machine-readable list of the textures the models reference, for gen_textures + validator
    needed = sorted({
        f"textures/{t}"
        for preset in PRESETS
        for t in (f"block/nuke_{preset}.png", f"block/nuke_{preset}_top.png",
                  f"block/nuke_{preset}_console.png")
    } | {
        f"textures/{t}.png"
        for _, (_, tex) in AFTERMATH.items()
        for t in tex
    } | {f"textures/item/{i}.png" for i in EQUIPMENT} | {
        "textures/entity/glow.png", "textures/entity/cloud_puff.png",
        "textures/entity/ash_puff.png", "textures/icon.png",
    })
    # Outside src/main/resources on purpose: a manifest for the texture generator is a build
    # input, and shipping it inside the jar would just bloat the mod.
    write(os.path.join(ROOT, "build", "generated", "required-textures.json"),
          {"textures": needed})
    return 0


CHECK = False

if __name__ == "__main__":
    CHECK = "--check" in sys.argv
    sys.exit(main())
