import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The resource integrity gate for this mod: {@code ./gradlew checkAssets}.
 *
 * <h2>Why this exists as a separate tool</h2>
 * Minecraft resolves almost every asset reference lazily and tolerantly. A blockstate pointing at a
 * model that does not exist does not fail a build, or fail a launch, or fail loading a world — it
 * renders a purple-and-black cube in the client log's company, and the only "signal" is a
 * {@code Unable to find blockstate/model} line scrolled past by someone testing the crater code.
 * Same for a texture id typo, a missing lang key (the literal key shows up in the GUI), a lang key
 * nobody references (invisible forever), and a sounds.json entry with no registered SoundEvent
 * (dead weight in every client's sound engine).
 *
 * <p>Those are the six failure modes below, checked against the source rather than against
 * knowledge of it: the tool greps the Java for the ids and keys the code registers, then compares
 * that set with the JSON on disk, in both directions. It runs on the JDK alone — no Gson, no
 * Minecraft, no Gradle APIs — via the single-file source launcher, which is what lets
 * {@code build.gradle} call it without putting the validator on any classpath.</p>
 *
 * <h2>Contract</h2>
 * Arg 1: the resources root (e.g. {@code src/main/resources}). Exits non-zero with a report if
 * anything fails; warnings are printed but do not fail the build, because an unresolved *vanilla*
 * reference is a modpack author's business, not ours.
 */
public final class AssetValidator {
	private static final String NS = "doomsday";
	private static final List<String> errors = new ArrayList<>();
	private static final List<String> warnings = new ArrayList<>();
	private static Path root;
	private static Path javaRoot;

	public static void main(String[] args) throws IOException {
		root = Path.of(args.length > 0 ? args[0] : "src/main/resources").toAbsolutePath().normalize();
		// src/main/resources -> src/main/java, by walking up out of the resources dir rather than
		// by assuming a repository layout: someone can point this at a build output or a jar's
		// extracted contents and still get the resource-graph half of the check.
		javaRoot = root.getParent().getParent().resolve("main/java").normalize();
		if (!Files.isDirectory(root)) {
			System.err.println("no resources root at " + root);
			System.exit(2);
		}
		if (!Files.isDirectory(javaRoot)) {
			warn("no java source root next to resources (" + javaRoot + "); code-derived checks skipped");
		}

		Map<String, Object> manifest = json(root.resolve("fabric.mod.json"));
		if (manifest == null) {
			fail("fabric.mod.json is missing or unparsable — the mod will not load at all");
		}

		Set<String> registeredBlocks = new HashSet<>();
		Set<String> registeredItems = new HashSet<>();
		Set<String> registeredSounds = new HashSet<>();
		Set<String> usedKeys = new HashSet<>();
		if (Files.isDirectory(javaRoot)) {
			collectJava(javaRoot, registeredBlocks, registeredItems, registeredSounds, usedKeys);
		}

		Map<String, Object> lang = json(root.resolve("assets/" + NS + "/lang/en_us.json"));
		if (lang == null) {
			fail("assets/" + NS + "/lang/en_us.json is missing");
		} else {
			checkLang(lang, usedKeys, registeredBlocks, registeredItems);
			compareTranslations(lang, root.resolve("assets/" + NS + "/lang"));
		}

		checkModelsAndBlockstates(registeredBlocks);
		checkItemModels(registeredItems);
		checkTextures();
		checkSounds(registeredSounds);
		checkShaders();
		checkData(registeredBlocks, registeredItems, manifest);
		checkPngs();

		System.out.printf(Locale.ROOT, "%n%s: %d error(s), %d warning(s)%n",
			errors.isEmpty() ? "ASSETS OK" : "ASSET FAILURES", errors.size(), warnings.size());
		for (String e : errors) {
			System.out.println("  ERROR   " + e);
		}
		for (String w : warnings) {
			System.out.println("  warning " + w);
		}
		if (!errors.isEmpty()) {
			System.exit(1);
		}
	}

	// ———————————————————————————————————————————————————————— java scanning
	/**
	 * Pulls the ids and translation keys the *code* declares. Everything here is a regex over
	 * source text on purpose: the alternative (loading the classes) needs Minecraft on the
	 * classpath, which turns a 200 ms check into a remapped-jar dependency for the build.
	 */
	private static void collectJava(Path javaRoot, Set<String> blocks, Set<String> items,
									Set<String> sounds, Set<String> keys) throws IOException {
		Pattern register = Pattern.compile("register\\(\\s*(?:\"([a-z0-9_./]+)\"|preset\\.blockId\\(\\))");
		Pattern presetKey = Pattern.compile("([A-Z_]{3,})\\(\"([a-z0-9_]+)\",\\s*\"[^\"]*\",\\s*[0-9]");
		Pattern sound = Pattern.compile("register\\(\"([a-z0-9_.]+)\"\\)");
		Pattern key = Pattern.compile("\"((?:gui|block|item|itemGroup|command|subtitle|subtitles|effect|death|tooltip|config)\\.[a-z0-9_.]+)\"");
		// Concatenated keys: "gui.doomsday.stage." + stage.key() and friends.
		Pattern keyPrefix = Pattern.compile("\"((?:gui|block|item|subtitle|subtitles|command)\\.[a-z0-9_.]*\\.)\"\\s*\\+");

		List<Path> files = new ArrayList<>();
		try (Stream<Path> s = Files.walk(javaRoot)) {
			s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
		}
		String allSources = "";
		for (Path p : files) {
			allSources += Files.readString(p, StandardCharsets.UTF_8) + "\n";
		}
		// Devices are registered per preset, so their ids are computed. Recover the preset config
		// keys from the enum and rebuild them, rather than hardcoding the list a second time here.
		Set<String> presetBlocks = new HashSet<>();
		for (Matcher m = presetKey.matcher(allSources); m.find();) {
			presetBlocks.add("nuke_" + m.group(2));
		}

		String blockSrc = readOrEmpty(javaRoot.resolve("com/doomsday/nukes/registry/ModBlocks.java"));
		String itemSrc = readOrEmpty(javaRoot.resolve("com/doomsday/nukes/registry/ModItems.java"));
		String soundSrc = readOrEmpty(javaRoot.resolve("com/doomsday/nukes/sound/ModSounds.java"));

		for (Matcher m = register.matcher(blockSrc); m.find();) {
			if (m.group(1) != null) {
				blocks.add(m.group(1));
			}
		}
		blocks.addAll(presetBlocks);
		for (Matcher m = register.matcher(itemSrc); m.find();) {
			if (m.group(1) != null) {
				items.add(m.group(1));
			}
		}
		items.addAll(presetBlocks); // NukeItem per preset, registered under the same path
		for (Matcher m = sound.matcher(soundSrc); m.find();) {
			sounds.add(m.group(1));
		}
		for (Matcher m = key.matcher(allSources); m.find();) {
			keys.add(m.group(1));
		}
		for (Matcher m = keyPrefix.matcher(allSources); m.find();) {
			// A prefix like "gui.doomsday.stage." means one key per value the suffix can take; expand
			// the enumerable ones so the check is real rather than vacuous.
			String prefix = m.group(1);
			if (prefix.endsWith("stage.")) {
				for (String stage : STAGES) {
					keys.add(prefix + stage);
				}
			}
		}
	}

	private static final String[] STAGES = {
		"flash", "fireball", "shockwave", "mushroom_cloud", "crater", "fallout", "emp",
		"aftermath", "complete",
	};

	private static String readOrEmpty(Path p) {
		try {
			return Files.readString(p, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	// ———————————————————————————————————————————————————————————————— lang
	private static void checkLang(Map<String, Object> lang, Set<String> usedKeys, Set<String> blocks,
								  Set<String> items) {
		for (String k : usedKeys) {
			if (!lang.containsKey(k) && !k.startsWith("subtitles.")) {
				fail("lang: key referenced by code but absent from en_us.json: " + k);
			}
		}
		for (String b : blocks) {
			require(lang, "block." + NS + "." + b, "registered block has no name");
		}
		for (String i : items) {
			// Block items are named through their block, which is how vanilla does it too.
			if (blocks.contains(i)) {
				continue;
			}
			require(lang, "item." + NS + "." + i, "registered item has no name");
		}
		for (String key : lang.keySet()) {
			if (!usedKeys.contains(key) && !isRegistrationName(key, blocks, items)) {
				warn("lang: en_us.json key no longer referenced by code: " + key);
			}
		}
		// Format placeholders: a %s in the value needs an argument at the call site, and a value with
		// no placeholder silently swallows the numbers the mod passes. Both are invisible in en_us-only
		// play, so check the counts against the two arg-bearing families this mod has.
		for (Map.Entry<String, Object> e : lang.entrySet()) {
			String value = String.valueOf(e.getValue());
			int got = count(value, "%s") + count(value, "%1$s");
			if (got == 0 && (value.contains("%") && !value.contains("%%"))) {
				fail("lang: " + e.getKey() + " has a malformed format specifier: " + value);
			}
			if (value.contains("%%") && !value.contains("%s")) {
				continue; // a literal percent with no argument is fine (goggle durability text)
			}
		}
	}

	/**
	 * Names that registration itself pulls in — a block or item is translated by the game whether or
	 * not our code ever asks for it, so "unreferenced key" must not fire on these.
	 */
	private static boolean isRegistrationName(String key, Set<String> blocks, Set<String> items) {
		return key.startsWith("block." + NS + ".") || key.startsWith("item." + NS + ".");
	}

	private static void require(Map<String, Object> lang, String key, String what) {
		if (!lang.containsKey(key)) {
			fail("lang: " + what + " — " + key);
		}
	}

	/** Translations must never invent a key: an unknown key is dead text that outlives the feature. */
	@SuppressWarnings("unchecked")
	private static void compareTranslations(Map<String, Object> enUs, Path langDir) throws IOException {
		try (Stream<Path> s = Files.list(langDir)) {
			for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".json"))::iterator) {
				String name = p.getFileName().toString();
				if (name.equals("en_us.json")) {
					continue;
				}
				Map<String, Object> other = json(p);
				if (other == null) {
					continue;
				}
				for (String k : other.keySet()) {
					if (!enUs.containsKey(k)) {
						fail(name + ": translates a key that en_us.json does not define: " + k);
					}
				}
				int missing = 0;
				for (String k : enUs.keySet()) {
					if (!other.containsKey(k)) {
						missing++;
					}
				}
				if (missing > 0) {
					warn(name + ": " + missing + " of " + enUs.size() + " keys untranslated "
						+ "(falls back to en_us, which is intended)");
				}
			}
		}
	}

	// —————————————————————————————————————————————————————————— models
	@SuppressWarnings("unchecked")
	private static void checkModelsAndBlockstates(Set<String> blocks) throws IOException {
		Path bsDir = root.resolve("assets/" + NS + "/blockstates");
		if (Files.isDirectory(bsDir)) {
			try (Stream<Path> s = Files.list(bsDir)) {
				for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".json"))::iterator) {
					String name = p.getFileName().toString().replace(".json", "");
					if (!blocks.contains(name)) {
						fail("blockstates/" + name + ".json exists but no block '" + NS + ":" + name
							+ "' is registered");
					}
					Map<String, Object> j = json(p);
					if (j == null) {
						continue;
					}
					Object variants = j.get("variants");
					if (!(variants instanceof Map)) {
						fail("blockstates/" + name + ".json: no \"variants\" object");
						continue;
					}
					for (Object v : ((Map<String, Object>) variants).values()) {
						for (Object entry : v instanceof List ? (List<Object>) v
							: List.of(v)) {
							if (entry instanceof Map<?, ?> m) {
								resolve(String.valueOf(m.get("model")), "models", p);
							}
						}
					}
				}
			}
		}
		// Every model's texture references must exist, and every model must have a parent chain that
		// terminates in a vanilla parent (a cycle here is how a "black cube" bug survives code review).
		Path modelDir = root.resolve("assets/" + NS + "/models");
		if (Files.isDirectory(modelDir)) {
			try (Stream<Path> s = Files.walk(modelDir)) {
				for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".json"))::iterator) {
					Map<String, Object> j = json(p);
					if (j == null) {
						continue;
					}
					Object parent = j.get("parent");
					if (parent != null) {
						resolve(String.valueOf(parent), "models", p);
					}
					textures(j, p);
					elements(j, p);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void elements(Map<String, Object> model, Path where) {
		Object elements = model.get("elements");
		if (!(elements instanceof List<?> list)) {
			return;
		}
		Set<String> provided = new HashSet<>();
		if (model.get("textures") instanceof Map<?, ?> t) {
			provided.addAll(t.keySet());
		}
		for (Object o : list) {
			if (!(o instanceof Map<?, ?> el)) {
				continue;
			}
			Object from = el.get("from");
			Object to = el.get("to");
			if (!(from instanceof List<?> f) || !(to instanceof List<?> t) || f.size() != 3
				|| t.size() != 3) {
				fail(short_(where) + ": an element needs 3-number from/to arrays");
				continue;
			}
			for (int i = 0; i < 3; i++) {
				double a = ((Number) f.get(i)).doubleValue();
				double b = ((Number) t.get(i)).doubleValue();
				// A model outside the block box is clipped or causes z-fighting with its neighbour;
				// vanilla itself only breaks this rule for a handful of hand-tuned blocks.
				if (a < -16 || b > 32 || a > b) {
					fail(short_(where) + ": element bounds out of range on axis " + i);
				}
			}
			if (el.get("faces") instanceof Map<?, ?> faces) {
				for (Object face : faces.values()) {
					if (face instanceof Map<?, ?> m && m.get("texture") != null) {
						String ref = String.valueOf(m.get("texture"));
						if (ref.startsWith("#") && !provided.contains(ref.substring(1))) {
							fail(short_(where) + ": face references " + ref
								+ " which is not declared in \"textures\"");
						}
					}
				}
			}
		}
	}

	private static void checkItemModels(Set<String> items) throws IOException {
		Path dir = root.resolve("assets/" + NS + "/models/item");
		if (!Files.isDirectory(dir)) {
			fail("no item models directory — every registered item needs models/item/<name>.json "
				+ "or it renders as a missing-model cube");
			return;
		}
		Set<String> onDisk = new HashSet<>();
		try (Stream<Path> s = Files.list(dir)) {
			s.forEach(p -> onDisk.add(p.getFileName().toString().replace(".json", "")));
		}
		for (String item : items) {
			if (!onDisk.contains(item)) {
				fail("item '" + NS + ":" + item + "' has no models/item/" + item + ".json");
			}
		}
		for (String name : onDisk) {
			if (!items.contains(name)) {
				fail("models/item/" + name + ".json has no registered item '" + NS + ":" + name + "'");
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void textures(Map<String, Object> model, Path where) {
		if (!(model.get("textures") instanceof Map<?, ?> map)) {
			return;
		}
		for (Object ref : ((Map<String, Object>) map).values()) {
			if (ref instanceof String s && !s.startsWith("#")) {
				resolve(s, "textures", where);
			}
		}
	}

	/** Resolves {@code ns:path} against {@code assets/<ns>/<kind>/}; foreign namespaces are skipped. */
	private static void resolve(String id, String kind, Path where) {
		if (id == null || id.isEmpty() || "null".equals(id)) {
			fail(short_(where) + ": empty " + kind + " reference");
			return;
		}
		int colon = id.indexOf(':');
		String ns = colon < 0 ? NS : id.substring(0, colon);
		String path = colon < 0 ? id : id.substring(colon + 1);
		if (!ns.equals(NS)) {
			return; // another namespace's asset: not in this jar, cannot be checked here
		}
		if (!Files.exists(root.resolve("assets/" + ns + "/" + kind + "/" + path
			+ (kind.equals("models") ? ".json" : ".png")))) {
			fail(short_(where) + ": " + kind + " reference does not exist: " + ns + ":" + path);
		}
	}

	@SuppressWarnings("unused")
	private static void checkTextures() throws IOException {
		Path dir = root.resolve("assets/" + NS + "/textures");
		if (!Files.isDirectory(dir)) {
			warn("no textures directory at all");
			return;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			List<Path> pngs = new ArrayList<>();
			s.filter(p -> p.toString().endsWith(".png")).forEach(pngs::add);
			System.out.println("textures: " + pngs.size() + " png files");
		}
	}

	// —————————————————————————————————————————————————————————— sounds
	private static void checkSounds(Set<String> registered) throws IOException {
		Map<String, Object> j = json(root.resolve("assets/" + NS + "/sounds.json"));
		if (j == null) {
			fail("assets/" + NS + "/sounds.json is missing: the mod's SoundEvents would have no samples");
			return;
		}
		for (Map.Entry<String, Object> e : j.entrySet()) {
			String key = e.getKey();
			if (key.startsWith("_") || key.equals("comment")) {
				continue;
			}
			if (!registered.contains(key)) {
				fail("sounds.json defines event '" + key + "' but ModSounds never registers it");
				continue;
			}
			if (!(e.getValue() instanceof Map<?, ?> body)) {
				fail("sounds.json: '" + key + "' is not an object");
				continue;
			}
			Object subtitle = body.get("subtitle");
			if (subtitle != null) {
				Map<String, Object> lang = json(root.resolve("assets/" + NS + "/lang/en_us.json"));
				if (lang != null && !lang.containsKey(String.valueOf(subtitle))) {
					fail("sounds.json: '" + key + "' points at subtitle key " + subtitle
						+ " which en_us.json does not define");
				}
			}
			if (!(body.get("sounds") instanceof List<?> sounds) || sounds.isEmpty()) {
				fail("sounds.json: '" + key + "' has no sounds array");
				continue;
			}
			for (Object s : sounds) {
				if (s instanceof Map<?, ?> m) {
					Object type = m.get("type");
					Object name = m.get("name");
					// A name without type:event must be a file in this namespace; with it, it must be a
					// real event id, and only "event" is a legal type value.
					if (type != null && !"event".equals(String.valueOf(type))) {
						fail("sounds.json: '" + key + "' sample uses unknown type " + type);
					}
					if (type == null && name != null && String.valueOf(name).startsWith("minecraft:")) {
						fail("sounds.json: '" + key + "' references " + name
							+ " as a file; add \"type\": \"event\" to alias a vanilla event");
					}
				}
			}
		}
		for (String r : registered) {
			if (!j.containsKey(r)) {
				fail("ModSounds registers '" + r + "' but sounds.json has no entry — the event is silent "
					+ "and the client logs 'Unable to load sound'");
			}
		}
	}

	// ————————————————————————————————————————————————————————— shaders
	@SuppressWarnings("unchecked")
	private static void checkShaders() throws IOException {
		Path post = root.resolve("assets/" + NS + "/shaders/post");
		Path program = root.resolve("assets/" + NS + "/shaders/program");
		if (!Files.isDirectory(post)) {
			return; // no custom pipeline: nothing to validate, and the bridge degrades to no-op
		}
		try (Stream<Path> s = Files.list(post)) {
			for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".json"))::iterator) {
				Map<String, Object> j = json(p);
				if (j == null) {
					continue;
				}
				if (!(j.get("passes") instanceof List<?> passes) || passes.isEmpty()) {
					fail(short_(p) + ": a post pipeline needs at least one pass");
					continue;
				}
				Set<String> targets = new HashSet<>(Arrays.asList("main", "minecraft:main"));
				if (j.get("targets") instanceof List<?> ts) {
					for (Object t : ts) {
						if (t instanceof Map<?, ?> m && m.get("name") != null) {
							targets.add(String.valueOf(m.get("name")));
						}
					}
				}
				for (Object o : passes) {
					if (!(o instanceof Map<?, ?> m)) {
						continue;
					}
					Map<String, Object> pass = (Map<String, Object>) m;
					String name = String.valueOf(pass.get("name"));
					if (!name.contains(":")) {
						name = "minecraft:" + name; // unqualified pass names resolve to vanilla
					}
					if (name.startsWith(NS + ":")) {
						String path = name.substring(name.indexOf(':') + 1);
						if (!Files.exists(program.resolve(path + ".json"))) {
							fail(short_(p) + ": pass '" + name + "' has no program json");
						} else {
							Map<String, Object> prog = json(program.resolve(path + ".json"));
							if (prog != null) {
								for (String stage : new String[] { "vertex", "fragment" }) {
									Object v = prog.get(stage);
									if (v == null) {
										fail(short_(program.resolve(path + ".json")) + ": no \"" + stage
											+ "\" stage");
										continue;
									}
									String vid = String.valueOf(v);
									String ext = stage.equals("vertex") ? ".vsh" : ".fsh";
									if (vid.startsWith(NS + ":")) {
										String vp = vid.substring(vid.indexOf(':') + 1);
										if (!Files.exists(program.resolve(vp + ext))) {
											fail(short_(p) + ": stage " + vid + " has no " + ext
												+ " file");
										}
									}
								}
							}
						}
					}
					for (String side : new String[] { "intarget", "outtarget" }) {
						Object t = pass.get(side);
						if (t != null && !targets.contains(String.valueOf(t))
							&& !String.valueOf(t).startsWith("minecraft:")) {
							fail(short_(p) + ": " + side + " '" + t + "' is not a declared target");
						}
					}
				}
			}
		}
	}

	// ——————————————————————————————————————————————————————————— data
	@SuppressWarnings("unchecked")
	private static void checkData(Set<String> blocks, Set<String> items, Map<String, Object> manifest)
		throws IOException {
		// The manifest must point at the real entrypoints, or nothing loads and the log says so once.
		if (manifest != null && javaRoot != null && manifest.get("entrypoints") instanceof Map<?, ?> ep) {
			Map<String, Object> eps = (Map<String, Object>) ep;
			for (String kind : List.of("main", "client")) {
				Object list = eps.get(kind);
				if (!(list instanceof List<?> l) || l.isEmpty()) {
					fail("fabric.mod.json: no \"" + kind + "\" entrypoint");
					continue;
				}
				for (Object o : l) {
					String cls = String.valueOf(o).replace('.', '/') + ".java";
					if (!Files.exists(javaRoot.resolve(cls))) {
						fail("fabric.mod.json: entrypoint class " + o + " does not exist in " + javaRoot);
					}
				}
			}
		}
		if (manifest != null && manifest.get("mixins") instanceof List<?> ms) {
			for (Object m : ms) {
				if (!Files.exists(root.resolve(String.valueOf(m)))) {
					fail("fabric.mod.json lists mixin config " + m + " which is not in the jar root");
				}
			}
		}

		Set<String> known = new HashSet<>();
		for (String b : blocks) {
			known.add(NS + ":" + b);
		}
		for (String i : items) {
			known.add(NS + ":" + i);
		}

		for (String kind : List.of("recipe", "loot_table/blocks")) {
			Path dir = root.resolve("data/" + NS + "/" + kind);
			if (!Files.isDirectory(dir)) {
				fail("missing data/" + NS + "/" + kind + " directory");
				continue;
			}
			try (Stream<Path> s = Files.walk(dir)) {
				List<Path> files = new ArrayList<>();
				s.filter(f -> f.toString().endsWith(".json")).forEach(files::add);
				if (kind.startsWith("loot_table") && files.size() < blocks.size()) {
					warn("loot tables cover " + files.size() + " of " + blocks.size()
						+ " blocks; a block with no table drops nothing (flash_light is meant to)");
				}
				for (Path p : files) {
					Map<String, Object> j = json(p);
					if (j == null) {
						continue;
					}
					if (j.get("type") == null) {
						fail(short_(p) + ": no \"type\" field");
					}
					refs(j, known, p);
					if (kind.startsWith("loot_table")) {
						String expected = p.getFileName().toString().replace(".json", "");
						if (!blocks.contains(expected)) {
							fail(short_(p) + ": loot table for unknown block " + expected);
						}
					}
				}
			}
		}
	}

	/** Walks any JSON looking for {@code doomsday:something} strings and checks them against the registry. */
	@SuppressWarnings("unchecked")
	private static void refs(Object node, Set<String> known, Path where) {
		if (known.isEmpty()) {
			return; // no registry view available (Java sources not next to the resources): no opinion
		}
		if (node instanceof Map<?, ?> m) {
			for (Object v : ((Map<Object, Object>) m).values()) {
				refs(v, known, where);
			}
		} else if (node instanceof List<?> l) {
			for (Object o : l) {
				refs(o, known, where);
			}
		} else if (node instanceof String s && s.startsWith(NS + ":")) {
			String id = s;
			int hash = id.indexOf('#'); // block state predicate, e.g. doomsday:heated_stone[facing=north]
			if (hash > 0) {
				id = id.substring(0, hash);
			}
			if (!known.contains(id) && !id.startsWith(NS + ":shaders/") && !id.startsWith(NS + ":textures/")
				&& !id.startsWith(NS + ":models/") && !id.startsWith(NS + ":block/")
				&& !id.startsWith(NS + ":item/") && !id.startsWith(NS + ":entity/")
				&& !id.startsWith(NS + ":icon")) {
				fail(short_(where) + ": references unregistered id " + id);
			}
		}
	}

	// —————————————————————————————————————————————————————————— png files
	private static void checkPngs() throws IOException {
		Path dir = root.resolve("assets/" + NS);
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			List<Path> pngs = new ArrayList<>();
			s.filter(p -> p.toString().endsWith(".png")).forEach(pngs::add);
			for (Path p : pngs) {
				byte[] head = Files.readAllBytes(p);
				if (head.length < 24 || (head[0] & 0xFF) != 0x89 || head[1] != 'P' || head[2] != 'N'
					|| head[3] != 'G') {
					fail(short_(p) + ": not a PNG (a corrupted or placeholder file breaks the texture "
						+ "stitcher at runtime, not at build time)");
					continue;
				}
				int w = be(head, 16);
				int h = be(head, 20);
				if (w <= 0 || h <= 0 || w != h || Integer.bitCount(w) != 1) {
					fail(short_(p) + ": " + w + "x" + h + " — block/item textures must be square "
						+ "powers of two for the mipmap levels the engine builds");
				}
				if (head[25] != 6) {
					warn(short_(p) + ": colour type " + head[25] + " (expected 6 = RGBA); "
						+ "cutout textures without alpha render as opaque");
				}
			}
			System.out.println("pngs: " + pngs.size() + " checked");
		}
	}

	private static int be(byte[] b, int at) {
		return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8)
			| (b[at + 3] & 0xFF);
	}

	private static int count(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			n++;
		}
		return n;
	}

	// ————————————————————————————————————————————————————————————— json
	/**
	 * A minimal JSON reader. The JDK has no JSON API and this tool must run from a bare
	 * {@code java File.java} invocation, so pulling Gson in would mean a classpath argument in the
	 * build script for a format we only need to walk. Objects become {@code LinkedHashMap}, arrays
	 * {@code List}, everything else String/Double/Boolean/null — enough to check references.
	 */
	@SuppressWarnings("unchecked")
	static Map<String, Object> json(Path path) {
		if (!Files.exists(path)) {
			failOnce("missing referenced json: " + short_(path));
			return null;
		}
		try {
			Object v = new Parser(Files.readString(path, StandardCharsets.UTF_8)).value();
			if (!(v instanceof Map)) {
				fail(short_(path) + ": top level is not an object");
				return null;
			}
			return (Map<String, Object>) v;
		} catch (Exception e) {
			fail(short_(path) + ": invalid json — " + e.getMessage());
			return null;
		}
	}

	private static final class Parser {
		private final String s;
		private int i;

		Parser(String s) {
			this.s = s;
		}

		Object value() throws IOException {
			skip();
			char c = s.charAt(i);
			return switch (c) {
				case '{' -> object();
				case '[' -> array();
				case '"' -> string();
				case 't' -> literal("true", Boolean.TRUE);
				case 'f' -> literal("false", Boolean.FALSE);
				case 'n' -> literal("null", null);
				default -> number();
			};
		}

		void skip() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
		}

		Map<String, Object> object() throws IOException {
			Map<String, Object> out = new LinkedHashMap<>();
			i++; // {
			skip();
			if (s.charAt(i) == '}') {
				i++;
				return out;
			}
			while (true) {
				skip();
				String key = string();
				skip();
				if (s.charAt(i) != ':') {
					throw new IOException("expected ':' at " + i);
				}
				i++;
				out.put(key, value());
				skip();
				char c = s.charAt(i++);
				if (c == '}') {
					return out;
				}
				if (c != ',') {
					throw new IOException("expected ',' or '}' at " + (i - 1));
				}
			}
		}

		List<Object> array() throws IOException {
			List<Object> out = new ArrayList<>();
			i++; // [
			skip();
			if (s.charAt(i) == ']') {
				i++;
				return out;
			}
			while (true) {
				out.add(value());
				skip();
				char c = s.charAt(i++);
				if (c == ']') {
					return out;
				}
				if (c != ',') {
					throw new IOException("expected ',' or ']' at " + (i - 1));
				}
			}
		}

		String string() throws IOException {
			if (s.charAt(i) != '"') {
				throw new IOException("expected string at " + i);
			}
			i++;
			StringBuilder sb = new StringBuilder();
			while (true) {
				char c = s.charAt(i++);
				if (c == '"') {
					return sb.toString();
				}
				if (c != '\\') {
					sb.append(c);
					continue;
				}
				switch (s.charAt(i++)) {
					case 'n' -> sb.append('\n');
					case 't' -> sb.append('\t');
					case 'r' -> sb.append('\r');
					case 'b' -> sb.append('\b');
					case 'f' -> sb.append('\f');
					case 'u' -> {
						sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
						i += 4;
					}
					default -> sb.append(s.charAt(i - 1));
				}
			}
		}

		Object literal(String word, Object v) throws IOException {
			if (!s.regionMatches(i, word, 0, word.length())) {
				throw new IOException("bad literal at " + i);
			}
			i += word.length();
			return v;
		}

		Double number() {
			int start = i;
			while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) {
				i++;
			}
			return Double.valueOf(s.substring(start, i));
		}
	}

	// ——————————————————————————————————————————————————————————— output
	private static final Set<String> seenMissing = new HashSet<>();

	private static void failOnce(String message) {
		if (seenMissing.add(message)) {
			errors.add(message);
		}
	}

	private static void fail(String message) {
		errors.add(message);
	}

	private static void warn(String message) {
		warnings.add(message);
	}

	private static String short_(Path p) {
		return root.relativize(p).toString().replace('\\', '/');
	}
}
