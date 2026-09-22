"""The "director": reads a natural-language prompt and decides which world,
which palette accents, how much energy, and what camera move to use — plus a
cinematic prompt enhancement (used as metadata and for cloud engines)."""
from __future__ import annotations

import hashlib
import re

# keyword → scene table (first strong match wins)
SCENE_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("nebula",    ("space", "galaxy", "nebula", "cosmos", "universe", "star", "astronaut", "planet", "interstellar")),
    ("liquid",    ("liquid", "chrome", "metal", "mercury", "oil", "holographic", "iridescent", "abstract", "fluid", "marble", "silk", "psychedelic")),
    ("neon",      ("synthwave", "retrowave", "vaporwave", "80s", "arcade", "grid", "outrun", "retro future", "tron")),
    ("fire",      ("fire", "flame", "ember", "lava", "burn", "phoenix", "volcano", "inferno", "campfire", "explosion", "dragon")),
    ("ocean",     ("ocean", "sea", "underwater", "water", "reef", "whale", "deep blue", "marine", "dive", "tide")),
    ("ink",       ("ink", "watercolor", "sumi", "calligraphy", "paper", "bloom", "stain", "minimal", "zen")),
    ("aurora",    ("aurora", "northern light", "polar", "arctic night", "mountain night", "snow night", "winter night")),
    ("cityrain",  ("rain", "city", "tokyo", "cyberpunk", "noir", "street", "neon sign", "blade runner", "downtown", "urban", "night city")),
    ("dunes",     ("desert", "dune", "sahara", "sand", "oasis", "mirage", "west", "drought")),
    ("datastream",("matrix", "code", "hacker", "data", "digital rain", "terminal", "glitch", "cyber space", "ai mind", "binary")),
    ("clouds",    ("cloud", "sky", "heaven", "dream", "fly", "air", "bird", "daydream", "ether", "soft")),
    ("fireflies", ("firefl", "forest", "magic", "enchanted", "garden", "night woods", "fairy", "mystic", "mushroom", "jungle night")),
]

# word → accent color (rgb 0..1)
COLOR_WORDS: list[tuple[tuple[str, ...], tuple[float, float, float]]] = [
    (("red", "crimson", "scarlet", "blood"), (0.9, 0.12, 0.15)),
    (("orange", "amber", "sunset", "gold", "golden"), (1.0, 0.6, 0.15)),
    (("yellow", "lemon"), (1.0, 0.9, 0.3)),
    (("green", "emerald", "jade", "toxic"), (0.2, 0.9, 0.4)),
    (("teal", "turquoise", "aqua"), (0.15, 0.85, 0.75)),
    (("cyan", "electric blue", "ice", "icy"), (0.3, 0.85, 1.0)),
    (("blue", "azure", "sapphire"), (0.2, 0.45, 1.0)),
    (("purple", "violet", "lavender", "lilac"), (0.6, 0.35, 0.95)),
    (("magenta", "pink", "fuchsia", "rose", "hot pink"), (1.0, 0.3, 0.65)),
    (("white", "silver", "pearl"), (0.9, 0.92, 0.95)),
    (("black", "void", "obsidian"), (0.1, 0.1, 0.14)),
]

ENERGY_WORDS_HI = ("fast", "rush", "storm", "chaos", "explosive", "racing", "wild", "frantic",
                   "violent", "hurricane", "speed", "burst", "energy", "intense")
ENERGY_WORDS_LO = ("calm", "slow", "gentle", "peaceful", "serene", "soft", "quiet", "still",
                   "meditat", "dreamy", "lazy", "tranquil", "ambient", "floating")

NIGHT_WORDS = ("night", "dark", "midnight", "nocturne", "moon", "shadow")
DAY_WORDS = ("day", "sunny", "bright", "morning", "golden hour", "sunrise", "sunset light")

DEFAULT_ORDER = ("nebula", "clouds", "liquid", "fireflies", "aurora", "cityrain")

_SHOT_TYPES = ("wide establishing shot", "macro detail shot", "cinematic medium shot",
               "aerial drift shot", "moody close-up")
_LIGHTING = ("volumetric lighting", "soft diffused glow", "dramatic chiaroscuro",
             "rim-lit atmosphere", "bioluminescent ambience")
_LENS = ("shot on 35mm", "anamorphic lens", "shallow depth of field", "long exposure feel")


def _hash(s: str) -> int:
    return int(hashlib.sha256(s.encode()).hexdigest()[:12], 16)


def analyze(prompt: str, seed: int | None = None) -> dict:
    p = " " + re.sub(r"[^a-z0-9 ]", " ", prompt.lower()) + " "
    h = _hash(prompt if seed is None else f"{prompt}|{seed}")

    scene, hits = None, []
    for sid, words in SCENE_KEYWORDS:
        for w in words:
            if w in p:
                hits.append((sid, len(w)))
                break
    if hits:
        hits.sort(key=lambda x: -x[1])
        scene = hits[0][0]
    if scene is None:
        scene = DEFAULT_ORDER[h % len(DEFAULT_ORDER)]

    accents = [c for words, c in COLOR_WORDS for w in words if w in p]
    if not accents:
        # deterministic pleasant triad: spin a hue wheel off the prompt hash
        import colorsys
        h0 = (h % 360) / 360.0
        accents = [colorsys.hls_to_rgb(h0, 0.55, 0.95),
                   colorsys.hls_to_rgb((h0 + 0.13) % 1.0, 0.5, 0.9),
                   colorsys.hls_to_rgb((h0 + 0.55) % 1.0, 0.45, 0.9)]

    hi = sum(w in p for w in ENERGY_WORDS_HI)
    lo = sum(w in p for w in ENERGY_WORDS_LO)
    energy = min(1.0, max(0.0, 0.5 + 0.28 * hi - 0.3 * lo + ((h >> 8) % 10 - 5) * 0.01))

    if any(w in p for w in NIGHT_WORDS):
        night = 0.85
    elif any(w in p for w in DAY_WORDS):
        night = 0.1
    else:
        night = 0.55

    enhanced = enhance(prompt, h)
    return {"scene": scene, "accents": [list(map(float, a)) for a in accents],
            "energy": float(energy), "night": float(night), "enhanced": enhanced}


def enhance(prompt: str, h: int | None = None) -> str:
    h = _hash(prompt) if h is None else h
    shot = _SHOT_TYPES[h % len(_SHOT_TYPES)]
    light = _LIGHTING[(h >> 4) % len(_LIGHTING)]
    lens = _LENS[(h >> 8) % len(_LENS)]
    p = prompt.strip().rstrip(".")
    return f"{shot} of {p}, {light}, {lens}, rich color grade, subtle film grain, 24fps cinematic motion"
