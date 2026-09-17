# Doomsday Nukes

Tactical nuclear devices for **Minecraft 1.21.1** (Fabric). A placeable, armable device with four
presets and a real countdown, and a detonation that is *staged* rather than faked: a thermal flash
that whitenouts the client and blinds the player, a growing fireball that transmutes the terrain it
touches, a shockwave that arrives later than the light and knocks entities outward, a procedurally
boiled mushroom cloud that lives for minutes, fallout that drifts with the wind and contaminates
the ground, radiation sickness you can treat with iodine and goggles, and an EMP that silences
machines in a bounded zone and then gives them back.

It is also a worked example of one specific architectural problem: **how to make a client-side
spectacle of this size without the server caring about it.** The server simulates a device, a timer,
a contamination field and a bounded terrain edit; it sends *one compact event* per detonation. The
five visual entity types, the particles, the cloud geometry, the shaders and the HUD are all built
locally from that event plus the config. Nothing in the detonation costs a server anything when
nothing is armed.

---

## Contents

- [Install and build](#install-and-build)
- [Playing it](#playing-it)
- [Commands](#commands)
- [Configuration](#configuration)
- [How a detonation works](#how-a-detonation-works)
- [Compatibility and mod interactions](#compatibility-and-mod-interactions)
- [Verification: what the build checks](#verification-what-the-build-checks)
- [Repository layout](#repository-layout)
- [Known limits](#known-limits)

---

## Install and build

Requirements: **JDK 21**, Minecraft **1.21.1**, **Fabric Loader ≥ 0.16.9**, **Fabric API ≥
0.116.0+1.21.1**.

```bash
./gradlew build           # compiles, runs the tests, validates the assets, writes build/libs/*.jar
./gradlew runClient       # a dev client with the mod, Mod Menu and a fresh world in ./run
./gradlew runServer         # a dev dedicated server in ./run-server
./gradlew test              # the unit tests only
./gradlew checkAssets       # the asset/reference gate only (no compile)
```

`gradlew` is a bootstrap launcher rather than the stock wrapper: the repository cannot carry the
binary `gradle-wrapper.jar`, so the script uses a real wrapper if you have generated one, otherwise
downloads the Gradle version pinned in `gradle/wrapper/gradle-wrapper.properties` (8.8) into
`.gradle-dist/`, and only then falls back to a `gradle` on `PATH`. Pinning wins over the system
install on purpose — Loom's behaviour is Gradle-version sensitive enough that "works on my machine"
is not good enough for a build script.

Drop `build/libs/doomsday-nukes-1.0.0.jar` into your `mods/` folder. The sources jar is built
alongside it.

There is no `maven-publish` setup: this mod is distributed as a jar file, not an artifact.

## Playing it

1. Craft or `/give` a device. Four presets exist, and they are four different *blocks*, not one
   block with an NBT tag — so the crater, cloud and fallout of each preset are data, not code paths.

   | Preset | Yield (kt) | Blast (blocks) | Fireball | Shockwave reach | Crater | Cloud scale |
   |---|---|---|---|---|---|---|
   | Standard Nuke | 20 | 46 | 26 | 130 | 17 | 1.00 |
   | Little Boy | 15 | 40 | 22 | 112 | 14 | 0.86 |
   | Fat Man | 21 | 48 | 28 | 136 | 18 | 1.10 |
   | Tsar Bomba | 100 | 92 | 60 | 300 | 40 | 3.20 |

   These are the *base* numbers; `yieldMultiplier` per device in the config scales them, and the
   radii scale with the cube root of yield (a 8× yield doubles the radii, which is what makes a
   100 kt device playable rather than absurd).

2. Place it and right-click. The control panel arms the device with a timer, and the block's own
   blinking lamp, siren and mob-panic behaviour start immediately. Arming is *requested* through the
   server: the panel sends the intent, the device validates it against the config (timer bounds,
   `allowDisarm`, ownership, whether you are in range), and the client renders whatever the device
   actually agreed to. A panel that lied about the state is a bug report; a panel that agrees with
   the block is the design.

3. Detonation. Once the timer fires, the block is gone and the event belongs to the detonation
   system: it walks its own stage timeline (`FLASH → FIREBALL → SHOCKWAVE → MUSHROOM_CLOUD →
   FALLOUT → AFTERMATH → COMPLETE`), applies terrain work inside its per-tick budget, contaminates
   the ground, and broadcasts one small packet per stage change.

4. Deal with the aftermath. Contaminated chunks keep their dose value across relogs and restarts (the
   field is persisted per chunk); the Geiger counter clicks louder as you walk into it; iodine
   tablets halve the incoming dose for their duration; hazmat goggles reduce it further and stop the
   flash from blinding you. Radiation sickness stacks an amplifier up to `radiationMaxAmplifier` and
   recovers slowly on its own.

Remote detonators link to a device by right-clicking it and fire (or, if `remoteCanDisarm`, cancel)
from any distance in the same dimension. The link is stored as `{Dim, X, Y, Z, Serial}` where the
serial is the device's own id, so a device that was mined and replaced under your detonator is
*detected* — the link goes stale and clears itself rather than firing the new block.

Recipes exist for all of it (`data/doomsday/recipe/`); the devices are expensive on purpose, and the
three alternate presets are one shapeless upgrade from the standard device rather than four separate
engineering trees.

## Commands

Permission level 2 for gameplay, 3 for the config sub-trees. Suggestions come from the registry, not
from a hardcoded list.

```
/doomsday detonate <preset> [seconds]      # arm-and-fire at your feet (immediate if no seconds)
/doomsday preview <stage>                  # play one stage locally, for tuning visuals
/doomsday device arm <seconds>             # arm the device you are looking at
/doomsday device disarm                    # … and disarm it, if allowDisarm
/doomsday device list                      # every armed device, with its remaining time
/doomsday emp <radius>                     # a standalone EMP zone
/doomsday radiation status|clear           # your own dose
/doomsday radiation add <amount>           # −100..100, for testing sickness tiers
/doomsday radiation iodine <seconds>
/doomsday quality <low|medium|high|ultra>  # runtime quality tier (level 3)
/doomsday config dump|reset|save           # the config file, from chat (level 3)
/doomsday status                            # detonation count, active effects, packet counters
```

`/doomsday preview` is the reason the visual system can be tuned without a 30-second countdown and a
100-kt detonation each time: it sends the same `StageChangeS2CPacket` the real timeline sends, with a
negative id so clients know it is a preview and never try to build terrain or damage anything.

## Configuration

`config/doomsday.json`, Gson, written atomically. Every value has a documented clamp and an
explicit unit in its name (`Seconds`, `Blocks`, `Kt`, `Ticks`) — there is no bare `duration` or
`radius` anywhere, because unit confusion in a config file becomes a forum post.

Headline groups (see `config/DoomsdayConfig.java` for the full annotated list):

- **device** — `minTimerSeconds` 10, `maxTimerSeconds` 300, `defaultTimerSeconds` 30,
  `allowDisarm`, `remoteCanDisarm`, `griefingEnabled`.
  `griefingEnabled=false` is the "server that allows effects but not destruction" switch: the
  detonation still runs, the flash/shockwave/cloud/radiation all happen, and no block is written.
  It also gates `/doomsday detonate`'s immediate-detonation button and the "Detonate Now" control,
  which is why that button is disabled rather than hidden — a player should be able to see *why*.
- **terrain** — `terrainBlocksPerTick` 900. All block writes go through one queue
  (`world/TerrainWorkQueue`), drained on that budget, so a Tsar Bomba crater takes a few dozen ticks
  to carve rather than freezing the server for three seconds in a single `setBlock` storm.
- **visuals** — `particleDensity`, `maxVisualEntities` 24, `visualLodDistance`, `particleCullDistance`,
  `particleBatchSize`, `maxAshParticles`, `cloudLifetime`, `cloudRisePerSecond`, `capSamples`,
  `skirtSamples`, `stemSegments`, `skyDarkness`, `hazeDensity`, `fogOrangeMix`, `flashWhiteoutSeconds`
  0.20, `desaturateSeconds` 2.80, `flashFovDegrees` 110, `cameraShakeStrength`.
- **quality** — `adaptiveQuality` + `adaptiveBudgetMs` 33 and a `Quality` tier
  (LOW/MEDIUM/HIGH/ULTRA) that sets `particleBudget`, `sampleScale`, `cloudCapSamples`,
  `noiseOctaves` together. Adaptive mode drops the tier for the *next* detonation when the render
  thread measured itself over budget, and recovers when it did not; it never re-tiers mid-animation,
  because a quality change during the cloud's growth is visible in exactly the way people notice.
- **radiation** — `iodineProtectionSeconds` 90, `goggleRadiationMultiplier` 0.2,
  `radiationMaxAmplifier` 4, `radiationSyncIntervalTicks` 20.
- **sound** — `soundDistance` (the reach of the distant boom), and per-event loudness.
- **devices** — a map of per-preset overrides (`yieldMultiplier`, `timerSeconds`, explicit radii,
  `falloutReach`). A non-null override replaces the derived value outright; a multiplier scales it.
  That distinction is the whole reason the map exists.

Mod Menu users get the same screen in-game (`Mod Menu → Doomsday Nukes → Settings`); everyone else
gets `/doomsday config dump` plus the file. The screen writes through `ConfigManager.applyAndSave`,
which re-sanitises before saving — a hand-edited `maxTimerSeconds` of `-5` cannot reach the game.

## How a detonation works

**Device timing.** One scheduled block tick per armed device, delayed by `min(remaining, 40)`. A
countdown is therefore at most 40 ticks of "the server is thinking about it" and, on load, a device
whose deadline already passed detonates immediately (`readNbt` checks `world.getTime()` against the
stored `detonateAtWorldTime` rather than counting down from a restored remainder). The same tick
chain does the throttled 2 Hz mob-panic scan; nothing polls every tick.

**Stages, not keyframes.** `detonation/DetonationTimeline` derives every stage boundary from the
config, and `Detonation` walks it. Stage transitions are the only thing that crosses the network;
each visual system is a function of `(stage, progress)`, so a client that joins mid-detonation can
be dropped into the correct state from a single packet.

**Terrain.** Classification happens in exactly one place (`detonation/DetonationTerrainPlan#classify`)
and writes happen in exactly one place (`TerrainWorkQueue#flush`). Inside `0.55 R` the crust is
removed and the walls are glazed; to `R` it is a lip; from `R` to `1.42 R` is the rim — scorch,
strip, char. Sand becomes `doomsday:vitrified_sand`, stone becomes `doomsday:heated_stone`, dirt
becomes `doomsday:scorched_dirt`, logs become `doomsday:charred_log`, water inside the fireball
volume is removed, flammables are set alight. Nothing in that list is a vanilla block *replaced* with
a stripped-down clone; each aftermath block is a real block with its own drops, light emission and
cool-down behaviour.

**Radiation.** A `Long2FloatOpenHashMap` of chunk-position → dose per dimension, on the server, with
exposure accumulated per player and synced at 20-tick intervals only for players standing in a
non-zero chunk (there is no broadcast of the whole field). `world/RadiationManager` is the only class
that knows the field's shape, and it is deliberately usable from commands, the detonation and the HUD
through four small statics.

**Visual entities.** `fireball`, `shockwave`, `mushroom_cloud`, `fallout`, `cloud_anchor` — five
`EntityType`s in `SpawnGroup.MISC`, `noSummon`-style (they cannot be spawned by a spawn egg), with
`maxTrackingRange` set so a 100 kt cloud is *visible* across the render distance while the device that
made it can be a single block. They exist client-side, built from the event: the server never tracks
them, so the client that sees the cloud is not paying entity-activation cost for it. Each one is
pooled and reaped by `client/DoomsdayVisuals`, capped at `maxVisualEntities`, with concurrent
detonations merged by `maxConcurrentDetonations` (weighted by radius³, so a Tsar Bomba is not pushed
out by three firecrackers).

**Rendering.** A `Quads` helper emits into the vertex consumer directly — rings, dome, stem, cap,
skirt, ash sheet — with `RenderLayer.getEntityTranslucent`/`getBeaconBeam` for the additive pieces.
The cloud silhouette is displaced by `util/NoiseField.fbm` (coherent noise, so neighbouring puffs
move together — a hash would make the cap fizz), and fallout drift uses `NoiseField.curl`, a
divergence-free field, because per-axis noise visibly collects ash into clumps and empties it
elsewhere. There is no `RenderLayer.of` and no custom `ShaderInstance` in the entity path: a
`RenderLayer` built by hand is a per-frame state machine that mods get wrong, and the two things
that genuinely need raw GL state (the additive glow, the emissive flash light) get it through layers
vanilla already owns.

**Camera.** Two injections in one mixin, `mixin/GameRendererMixin`: `getFov` (TAIL, additive) for the
punch and `bobView` (TAIL) for the shake. Both selectors were checked against the yarn javadoc for
the pinned mappings, both read the value vanilla already computed instead of replacing it, and both
return immediately when no detonation is running. Sky and fog are *not* mixed in — the HUD's
`fillGradient` owns the atmosphere, which is why a shader pack and this mod can both be right.

**Post-processing.** `client/shader/PostPipelineBridge` optionally turns on
`doomsday:shaders/post/doomsday_flash.json` (a desaturate-and-lift pass) for the whiteout window, and
turns it off afterwards. Everything about it is defensive, because it is the one part of the mod whose
target is an internal field of `GameRenderer`: the pipeline is reached through `MethodHandle`s resolved
once, the bridge disables itself permanently on the first linkage error, it declines to run when Iris
is present (a shader pack owns the framebuffer), and the shader supplies only what the HUD cannot
(per-pixel desaturation). The fade envelope stays with the HUD quads, which can animate.

**Sound.** `sound/ModSounds` registers six events. `assets/doomsday/sounds.json` **aliases vanilla
events** rather than shipping `.ogg` files, because this repository has no audio pipeline and a sound
event with no file behind it is silent — worth knowing when you hear a familiar `block.bell.use`
behind your siren. Dropping in real recordings is a pure data change: put the file in
`assets/doomsday/sounds/` and point the event at it.

**Compatibility.** `compat/BeaconEmpSupport` restores beacon-ish states after an EMP, and EMP'd
blocks remember their pre-EMP state per block, not per chunk — an EMP that wipes a hopper's contents
is a griefing tool, not an effect. Mod Menu is discovered through its entrypoint and is optional; the
config screen is reachable without it. GeckoLib and Cloth Config are *not* dependencies: the visuals
are procedural and the config screen is built from `ButtonWidget`s, so neither library had a job.

## Compatibility and mod interactions

- **Minecraft versions**: 1.21.1 exactly. `fabric.mod.json` pins `~1.21.1` rather than `>=1.21.1`
  because the mixin selectors, the `TypedActionResult<ItemStack>` item API, the `RegistryEntry`
  `ArmorItem` constructor and the blockstate/model schema in this jar were all checked against 1.21.1
  mappings. A newer patch that moves `bobView(MatrixStack, float)` to `Matrix4f` should refuse to
  load, not crash on the first detonation.
- **Other mods that touch the camera**: additive by construction (see *Camera* above). FOV-changing
  mods keep their change; both are visible.
- **Server-side only**: every client class lives under `client/` and is registered from the client
  entrypoint, so a dedicated server never loads a renderer. There is no client/common source-set split,
  which means the discipline is manual: `@Environment(EnvType.CLIENT)` on every client type, and the
  common code reaches the client only through the two seams in `network/ClientPayloadSender` and
  `gui/ScreenOpener`.
- **Griefing**: `griefingEnabled=false` keeps the spectacle and removes the block writes. It is not a
  permissions system — a device can still kill players and start fires; it is the switch for servers
  that will allow one and not the other.

## Verification: what the build checks

There is no IDE in this project's history, so the repository carries its own gates. Each is
standalone-fast and each exists because a specific class of bug is otherwise invisible until someone
plays the mod.

| Gate | Command | Catches |
|---|---|---|
| Asset validator | `./gradlew checkAssets` → `java -ea tools/AssetValidator.java src/main/resources` | a blockstate pointing at a model that does not exist; a model texture id typo; an item with no `models/item/<name>.json`; a `sounds.json` event that no `SoundEvent` registers (and the reverse); a `subtitle` key missing from `en_us.json`; a lang key no code references; a translation that invents a key; a `doomsday:*` id in a recipe or loot table that is not registered; a non-PNG or non-square-power-of-two texture; an entrypoint class that does not exist in `src/main/java`; a mixin config listing a class with no source file |
| Self-check | `python3 tools/selfcheck.py` | unbalanced brackets, stray pseudo-code tokens, `TODO` bodies in main sources, a class name that does not match its file, `com.doomsday.nukes.*` imports that resolve to nothing, a `ModBlocks.X`/`ModItems.X` field that does not exist, and the whole resource graph |
| Balance check | `python3 tools/balance_check.py` | the structural damage a bulk edit does silently, with no JDK required |
| Unit tests | `./gradlew test` | the curve maths and the stage enum — see below |
| CI | `.github/workflows/build.yml` | all of the above plus the real `javac` against the remapped jar, on every push |

Two notes on the tools, because both are load-bearing:

`tools/AssetValidator.java` uses the JDK's single-file source launcher and a hand-written JSON reader,
so it has no dependencies and does not need the Minecraft jar on a classpath. That is also why it
inspects *source text* rather than loading classes: the alternative would make a 200 ms check depend on
Loom having finished remapping.

`src/test/java` covers only the classes that need no game: `MathUtil` (every envelope, clamp, hash and
blackbody curve the visuals are built from), `NoiseField` (boundedness, tiling at the permutation
period, `fbm`'s amplitude normalisation, and that `curl` is divergence-free and survives a zero
epsilon), and `DetonationStage` (that `index() == ordinal` — it is the wire format — and that the key
set is exactly what the generated lang files assume). Those tests assert properties that are
*derivable from the implementation*, not golden values, so retuning a constant does not break them and
changing an intent does.

`tools/gen_resources.py` regenerates the mechanical half of the assets (blockstates, models, item
models, loot tables, recipes, both lang files) and `tools/gen_textures.py` regenerates every PNG,
deterministically, with no image library. Re-run either after adding a preset or a block; the output is
byte-stable, so a diff in `assets/` means someone changed a palette, not that a generator ran.

## Repository layout

```
src/main/java/com/doomsday/nukes/
  DoomsdayNukes.java      common entrypoint: registries, one END_SERVER_TICK, events, reload
  block/                  NukeBlock (+ the four aftermath blocks and the invisible flash light)
  client/                 client entrypoint, HUD, renderers, particle pool, post bridge, GUIs
  command/                Brigadier tree, all /doomsday subcommands
  compat/                 optional-integration behaviour (beacon-ish EMP restore)
  config/                 DoomsdayConfig (the fields) + ConfigManager (load/save/clamp/derive)
  detonation/             Detonation, DetonationTimeline, DetonationStage, terrain plan, NukePreset
  effect/                 radiation sickness
  entity/                 the five visual entity types
  gui/                    the common→client screen seam
  item/                   devices, goggles, geiger counter, detonator, iodine
  mixin/                  GameRendererMixin (two injections, total)
  network/                payloads, packets, the client→server send seam
  registry/               blocks, items, entity types, effects, sound, creative tab
  sound/                  ModSounds + SoundCuePlanner
  util/                   MathUtil, NoiseField, SpatialUtil, DText
  world/                  RadiationManager, TerrainWorkQueue
src/main/resources/
  fabric.mod.json         ${id}/${version}/${name} expanded from gradle.properties
  doomsday.client.mixins.json
  assets/doomsday/        blockstates, models, textures, lang (en_us, de_de), sounds.json, shaders
  data/doomsday/          loot_table/blocks, recipe
src/test/java/            MathUtilTest, NoiseFieldTest, DetonationStageTest
tools/                    selfcheck.py, balance_check.py, AssetValidator.java, annotate.sh,
                          gen_resources.py, gen_textures.py
```

## Known limits

- **This is a game effect, not a physics simulation.** Fireball radius, shockwave timing and fallout
  spread are tuned to look right at 20 seconds and to survive a 60 tps server; the blackbody colour
  ramp and the inverse-square-ish attenuation curve are approximations that behave monotonically.
- **Audio is aliased from vanilla** (see *Sound* above). Six real recordings would make this mod land
  very differently and would not change a line of Java.
- **`de_de.json` is partial**: the strings a player actually reads (device names, stages, HUD, config
  labels, commands) plus the sound subtitles; the rest falls back to English by design, and the asset
  validator reports the count so it shrinks instead of rotting.
- **No GeckoLib missile model.** `assets/doomsday/geo/` and `animations/` were removed with the
  dependency: an animated missile needs the library as a hard runtime requirement, which the whole
  optional-integration story here is built to avoid. Adding one is a self-contained future feature.
- **Chunk-boundary contamination** is per-chunk by design: a dose that varies *within* a 16×16 area
  would need a much wider sync protocol, and standing at the edge of a crater is not what the effect
  is for.
- The post-processing pass is a static desaturation with a fixed strength — no uniform is written
  per frame — because setting a vanilla post-effect uniform means reaching into a
  `ShaderInstance`'s internals, and a bridge that can only be enabled or disabled is a better trade
  than one that can crash. The animated part of the fade is on the HUD, where it is cheap.

## License

MIT. See `LICENSE`.
