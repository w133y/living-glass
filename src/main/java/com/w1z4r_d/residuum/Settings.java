package com.w1z4r_d.residuum;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ObjDoubleConsumer;

/**
 * What the settings screens offer, in one place: pages of groups of switches and sliders, each read from and written
 * to the config. Names are the lang keys residuum.page.*, residuum.group.* and residuum.option.* (with
 * ".tooltip" for the explanation).
 */
public final class Settings {
    /** How a slider's value is shown. */
    public enum Unit {
        SECONDS, PERCENT, BLOCKS, NUMBER, DISTANCE, TIMES;

        /** The value as text: 90 seconds as "1 min 30 s", 0.8 as "0.80" when stepping by hundredths. */
        public String format(double value, double step) {
            return switch (this) {
                case SECONDS -> {
                    int s = (int) Math.round(value);
                    yield s < 60 ? s + " s" : s % 60 == 0 ? s / 60 + " min" : s / 60 + " min " + s % 60 + " s";
                }
                case PERCENT -> Math.round(value * 100) + "%";
                case BLOCKS -> Math.round(value) + (Math.round(value) == 1 ? " block" : " blocks");
                case NUMBER -> step < 1 ? String.format(Locale.ROOT, "%.2f", value) : String.valueOf(Math.round(value));
                case DISTANCE -> value <= 0 ? "As the game" : BLOCKS.format(value, step);
                case TIMES -> Math.round(value) + "×";
            };
        }
    }

    public sealed interface Entry permits Toggle, Slider {
        String key();
    }

    public record Toggle(String key, Function<ResiduumConfig, Boolean> get, BiConsumer<ResiduumConfig, Boolean> set)
            implements Entry {
    }

    public record Slider(String key, double min, double max, double step, Unit unit,
                         ToDoubleFunction<ResiduumConfig> get, ObjDoubleConsumer<ResiduumConfig> set) implements Entry {
        public String format(double value) {
            return unit.format(value, step);
        }

        /** value on the slider's steps, inside its range. */
        public double snap(double value) {
            return Math.clamp(min + Math.round((value - min) / step) * step, min, max);
        }
    }

    public record Group(String key, List<Entry> entries) {
    }

    public record Page(String key, List<Group> groups) {
    }

    /**
     * By effect: each effect's switch first in its group, then what tunes it; ResiduumConfigScreen dims the rest
     * of a group that starts with a switch while that switch is off.
     */
    public static final List<Page> PAGES = List.of(
            new Page("windows", List.of(
                    new Group("master", entries(
                            toggle("enabled", c -> c.enabled, (c, v) -> c.enabled = v),
                            leave(Rival.GLASS, "leave_glass", c -> c.leave.glass, (c, v) -> c.leave.glass = v))),
                    new Group("fog", List.of(
                            toggle("condensation", c -> c.condensation.enabled, (c, v) -> c.condensation.enabled = v),
                            slider("fog_strength", 0, 2, 0.05, Unit.PERCENT, c -> c.condensation.strength,
                                    (c, v) -> c.condensation.strength = v),
                            seconds("fog_build", 5, 180, 5, c -> c.timing.fogBuildSeconds, (c, v) -> c.timing.fogBuildSeconds = v),
                            seconds("fog_clear", 5, 180, 5, c -> c.timing.fogClearSeconds, (c, v) -> c.timing.fogClearSeconds = v),
                            toggle("breath_spot", c -> c.breathSpot.enabled, (c, v) -> c.breathSpot.enabled = v),
                            toggle("wipe", c -> c.wipe.enabled, (c, v) -> c.wipe.enabled = v),
                            toggle("tint_stained_glass", c -> c.tintStainedGlass, (c, v) -> c.tintStainedGlass = v))),
                    new Group("frost", List.of(
                            toggle("frost", c -> c.frost.enabled, (c, v) -> c.frost.enabled = v),
                            seconds("frost_build", 10, 300, 10, c -> c.timing.frostBuildSeconds, (c, v) -> c.timing.frostBuildSeconds = v),
                            seconds("frost_melt", 5, 180, 5, c -> c.timing.frostMeltSeconds, (c, v) -> c.timing.frostMeltSeconds = v))),
                    new Group("ice", List.of(
                            toggle("ice", c -> c.ice.enabled, (c, v) -> c.ice.enabled = v),
                            seconds("ice_build", 30, 600, 10, c -> c.timing.iceBuildSeconds, (c, v) -> c.timing.iceBuildSeconds = v),
                            seconds("ice_fogged", 10, 600, 10, c -> c.timing.iceFoggedSeconds, (c, v) -> c.timing.iceFoggedSeconds = v))),
                    new Group("dust", List.of(
                            toggle("sand", c -> c.sand.enabled, (c, v) -> c.sand.enabled = v),
                            slider("dust_strength", 0, 2, 0.05, Unit.PERCENT, c -> c.sand.strength, (c, v) -> c.sand.strength = v),
                            seconds("dust_build", 30, 900, 30, c -> c.timing.dustBuildSeconds, (c, v) -> c.timing.dustBuildSeconds = v))),
                    new Group("raindrops", List.of(
                            toggle("raindrops", c -> c.raindrops.enabled, (c, v) -> c.raindrops.enabled = v),
                            seconds("drying", 0, 60, 5, c -> c.timing.dryingSeconds, (c, v) -> c.timing.dryingSeconds = v))),
                    new Group("changes", List.of(
                            seconds("melt", 5, 120, 5, c -> c.timing.meltSeconds, (c, v) -> c.timing.meltSeconds = v),
                            toggle("skylights", c -> c.skylights.enabled, (c, v) -> c.skylights.enabled = v))))),
            new Page("marks", List.of(
                    new Group("ash", List.of(
                            toggle("ash", c -> c.ash.enabled, (c, v) -> c.ash.enabled = v),
                            seconds("ash_build", 1, 60, 1, c -> c.timing.ashBuildSeconds, (c, v) -> c.timing.ashBuildSeconds = v),
                            seconds("ash_side", 5, 300, 5, c -> c.timing.ashSideBuildSeconds, (c, v) -> c.timing.ashSideBuildSeconds = v),
                            seconds("ash_fade", 10, 600, 10, c -> c.timing.ashFadeSeconds, (c, v) -> c.timing.ashFadeSeconds = v))),
                    new Group("explosions", List.of(
                            toggle("explosions", c -> c.explosions.enabled, (c, v) -> c.explosions.enabled = v),
                            seconds("blast_time", 20, 900, 10, c -> c.timing.blastSeconds, (c, v) -> c.timing.blastSeconds = v))),
                    new Group("cracks", List.of(
                            toggle("cracks", c -> c.cracks.enabled, (c, v) -> c.cracks.enabled = v),
                            seconds("crack_time", 10, 600, 10, c -> c.timing.crackSeconds, (c, v) -> c.timing.crackSeconds = v))),
                    new Group("arrow_holes", List.of(
                            toggle("arrow_holes", c -> c.arrowHoles.enabled, (c, v) -> c.arrowHoles.enabled = v))),
                    new Group("lightning", List.of(
                            toggle("lightning", c -> c.lightning.enabled, (c, v) -> c.lightning.enabled = v),
                            seconds("lightning_time", 20, 600, 10, c -> c.timing.lightningSeconds, (c, v) -> c.timing.lightningSeconds = v))),
                    new Group("snowballs", List.of(
                            toggle("snowballs", c -> c.snowballs.enabled, (c, v) -> c.snowballs.enabled = v),
                            seconds("snow", 5, 120, 5, c -> c.timing.snowSeconds, (c, v) -> c.timing.snowSeconds = v))),
                    new Group("torch", List.of(
                            toggle("torch_soot", c -> c.torchSoot.enabled, (c, v) -> c.torchSoot.enabled = v))),
                    new Group("shields", List.of(
                            toggle("shield_scratches", c -> c.shieldScratches.enabled, (c, v) -> c.shieldScratches.enabled = v))))),
            new Page("world", List.of(
                    new Group("grass", entries(
                            toggle("trampling", c -> c.trampling.enabled, (c, v) -> c.trampling.enabled = v),
                            leave(Rival.GRASS, "leave_grass", c -> c.leave.grass, (c, v) -> c.leave.grass = v))),
                    new Group("footprints", entries(
                            toggle("footprints", c -> c.footprints.enabled, (c, v) -> c.footprints.enabled = v),
                            toggle("mob_footprints", c -> c.footprints.mobs, (c, v) -> c.footprints.mobs = v),
                            seconds("footprint_time", 10, 300, 10, c -> c.timing.footprintSeconds,
                                    (c, v) -> c.timing.footprintSeconds = v),
                            leave(Rival.PRINTS, "leave_prints", c -> c.leave.prints, (c, v) -> c.leave.prints = v))),
                    new Group("wall_snow", List.of(
                            toggle("wall_snow", c -> c.wallSnow.enabled, (c, v) -> c.wallSnow.enabled = v),
                            toggle("wall_snow_storms", c -> c.wallSnow.stormsOnly, (c, v) -> c.wallSnow.stormsOnly = v),
                            slider("wall_snow_amount", 0.25, 2, 0.05, Unit.PERCENT, c -> c.wallSnow.amount, (c, v) -> c.wallSnow.amount = v),
                            slider("wall_snow_sides", 0, 1, 0.05, Unit.PERCENT, c -> c.wallSnow.sides, (c, v) -> c.wallSnow.sides = v),
                            seconds("wall_snow_build", 60, 1800, 30, c -> c.timing.wallSnowBuildSeconds,
                                    (c, v) -> c.timing.wallSnowBuildSeconds = v),
                            seconds("wall_snow_melt", 60, 1800, 30, c -> c.timing.wallSnowMeltSeconds,
                                    (c, v) -> c.timing.wallSnowMeltSeconds = v))),
                    new Group("snow_caps", List.of(
                            toggle("snow_caps", c -> c.snowCaps.enabled, (c, v) -> c.snowCaps.enabled = v),
                            toggle("snow_caps_stay", c -> c.snowCaps.stay, (c, v) -> c.snowCaps.stay = v),
                            seconds("snow_caps_build", 60, 1800, 30, c -> c.timing.capBuildSeconds,
                                    (c, v) -> c.timing.capBuildSeconds = v))),
                    new Group("hoarfrost", List.of(
                            toggle("hoarfrost", c -> c.hoarfrost.enabled, (c, v) -> c.hoarfrost.enabled = v),
                            seconds("hoarfrost_build", 60, 1800, 30, c -> c.timing.hoarfrostBuildSeconds,
                                    (c, v) -> c.timing.hoarfrostBuildSeconds = v))),
                    new Group("steam", List.of(
                            toggle("steam", c -> c.steam.enabled, (c, v) -> c.steam.enabled = v))),
                    new Group("fireflies", List.of(
                            toggle("fireflies", c -> c.fireflies.enabled, (c, v) -> c.fireflies.enabled = v),
                            slider("firefly_count", 0.25, 4, 0.25, Unit.PERCENT, c -> c.fireflies.frequency,
                                    (c, v) -> c.fireflies.frequency = v))),
                    new Group("tumbleweeds", List.of(
                            toggle("tumbleweeds", c -> c.tumbleweeds.enabled, (c, v) -> c.tumbleweeds.enabled = v),
                            slider("tumbleweed_frequency", 0.25, 4, 0.25, Unit.PERCENT, c -> c.tumbleweeds.frequency,
                                    (c, v) -> c.tumbleweeds.frequency = v))))),
            new Page("climate", List.of(
                    new Group("temperature", List.of(
                            slider("comfort_temp", 0.3, 1.5, 0.05, Unit.NUMBER, c -> c.climate.comfortTemp, (c, v) -> c.climate.comfortTemp = v),
                            slider("night_penalty", 0, 0.5, 0.05, Unit.NUMBER, c -> c.climate.nightPenalty, (c, v) -> c.climate.nightPenalty = v),
                            slider("rain_penalty", 0, 0.5, 0.05, Unit.NUMBER, c -> c.climate.rainPenalty, (c, v) -> c.climate.rainPenalty = v),
                            slider("heat_radius", 2, 12, 1, Unit.BLOCKS, c -> c.climate.heatScanRadius, (c, v) -> c.climate.heatScanRadius = (int) v))))),
            new Page("performance", List.of(
                    new Group("scanning", List.of(
                            slider("scan_radius", 8, 48, 4, Unit.BLOCKS, c -> c.scan.radius, (c, v) -> c.scan.radius = (int) v),
                            slider("scan_budget", 64, 2048, 64, Unit.NUMBER, c -> c.scan.budgetPerTick, (c, v) -> c.scan.budgetPerTick = (int) v))),
                    new Group("drawing", List.of(
                            slider("render_distance", 0, 512, 16, Unit.DISTANCE, c -> c.render.distance, (c, v) -> c.render.distance = (int) v),
                            slider("tracked_windows", 256, 16384, 256, Unit.NUMBER, c -> c.tracked.windows, (c, v) -> c.tracked.windows = (int) v))),
                    new Group("diagnostics", List.of(
                            toggle("debug", c -> c.debug, (c, v) -> c.debug = v))),
                    new Group("testing", List.of(
                            slider("coat_speed", 1, 60, 1, Unit.TIMES, c -> c.timing.coatSpeed, (c, v) -> c.timing.coatSpeed = v))))));

    /** Every setting, page by page. */
    public static final List<Entry> ALL = PAGES.stream().flatMap(p -> p.groups().stream())
            .flatMap(g -> g.entries().stream()).toList();

    /**
     * The few settings Sodium's own settings screen shows, from the pages above; the rest are on the screen of our own
     * (see SodiumConfig).
     */
    public static final List<Group> QUICK = List.of(
            quick("master", "enabled"),
            quick("windows", "condensation", "frost", "ice", "sand", "raindrops"),
            quick("marks", "ash", "explosions", "cracks", "lightning", "snowballs"),
            quick("world", "trampling", "footprints", "fireflies", "tumbleweeds"),
            quick("drawing", "render_distance"));

    /**
     * A ready set of settings: the defaults with a few changed. Names and what they are for are the lang keys
     * residuum.option.KEY (and ".tooltip").
     */
    public record Preset(String key, Consumer<ResiduumConfig> tune) {
        public void apply(ResiduumConfig c) {
            copy(new ResiduumConfig(), c);
            tune.accept(c);
        }

        /** This preset as a config of its own. */
        public ResiduumConfig config() {
            ResiduumConfig mine = new ResiduumConfig();
            tune.accept(mine);
            return mine;
        }

        /** Whether c is exactly this preset. */
        public boolean matches(ResiduumConfig c) {
            ResiduumConfig mine = config();
            return ALL.stream().allMatch(e -> same(e, mine, c));
        }
    }

    public static final List<Preset> PRESETS = List.of(
            new Preset("preset_subtle", c -> {
                c.condensation.strength = 0.6;
                c.sand.strength = 0.6;
                c.tumbleweeds.enabled = false;
                c.fireflies.frequency = 0.5;
                c.timing.ashFadeSeconds = 60;
                c.timing.blastSeconds = 120;
                c.timing.snowSeconds = 10;
                c.timing.lightningSeconds = 90;
                c.timing.crackSeconds = 60;
                c.timing.footprintSeconds = 30;
                c.footprints.mobs = false;
            }),
            new Preset("preset_standard", c -> {
            }),
            new Preset("preset_maximum", c -> {
                c.condensation.strength = 1.4;
                c.sand.strength = 1.5;
                c.tumbleweeds.frequency = 2;
                c.fireflies.frequency = 2;
                c.timing.ashFadeSeconds = 300;
                c.timing.blastSeconds = 600;
                c.timing.snowSeconds = 40;
                c.timing.lightningSeconds = 360;
                c.timing.crackSeconds = 480;
                c.timing.footprintSeconds = 180;
                c.scan.radius = 32;
                c.tracked.windows = 16384;
            }),
            new Preset("preset_low_end", c -> {
                c.scan.radius = 12;
                c.scan.budgetPerTick = 256;
                c.render.distance = 48;
                c.tracked.windows = 2048;
                c.tumbleweeds.enabled = false;
                c.trampling.enabled = false;
                c.fireflies.frequency = 0.5;
                c.footprints.mobs = false;
            }));

    /** The effect each setting tunes that another installed mod may draw instead (see Rival). */
    private static final Map<String, Rival> RIVALS = new HashMap<>();

    static {
        for (Page page : PAGES) {
            for (Group group : page.groups()) {
                Rival rival = page.key().equals("windows") && !group.key().equals("master") ? Rival.GLASS
                        : group.key().equals("grass") ? Rival.GRASS : group.key().equals("footprints") ? Rival.PRINTS : null;
                if (rival != null) {
                    group.entries().forEach(e -> RIVALS.put(e.key(), rival));
                }
            }
        }
        RIVALS.put("leave_glass", Rival.GLASS);
    }

    private Settings() {
    }

    /**
     * The mod this setting's effect is left to just now, or null while Residuum draws it. For a "leave it to"
     * switch itself, the mod while that switch is on.
     */
    public static Rival leftTo(String key, ResiduumConfig c) {
        Rival rival = RIVALS.get(key);
        boolean leave = rival != null && rival.present() && switch (rival) {
            case GLASS -> c.leave.glass;
            case GRASS -> c.leave.grass;
            case PRINTS -> c.leave.prints;
        };
        return leave ? rival : null;
    }

    /** A setting's name, and what it does (lang keys residuum.option.KEY and its ".tooltip"). */
    public static Component name(String key) {
        return Component.translatable("residuum.option." + key, modOf(key));
    }

    public static Component description(String key) {
        return Component.translatable("residuum.option." + key + ".tooltip", modOf(key));
    }

    /** For a "leave it to" setting, the name of that mod, which its text puts in. */
    private static String modOf(String key) {
        return switch (key) {
            case "leave_glass" -> Rival.GLASS.modName();
            case "leave_grass" -> Rival.GRASS.modName();
            case "leave_prints" -> Rival.PRINTS.modName();
            default -> "";
        };
    }

    /** Whether a setting is the same in two configs (sliders to within half a step). */
    public static boolean same(Entry e, ResiduumConfig a, ResiduumConfig b) {
        return switch (e) {
            case Toggle t -> t.get().apply(a).equals(t.get().apply(b));
            case Slider s -> Math.abs(s.get().applyAsDouble(a) - s.get().applyAsDouble(b)) < s.step() / 2;
        };
    }

    /** Sets every setting of to as it is in from. */
    public static void copy(ResiduumConfig from, ResiduumConfig to) {
        for (Entry e : ALL) {
            copy(e, from, to);
        }
    }

    public static void copy(Entry e, ResiduumConfig from, ResiduumConfig to) {
        switch (e) {
            case Toggle t -> t.set().accept(to, t.get().apply(from));
            case Slider s -> s.set().accept(to, s.get().applyAsDouble(from));
        }
    }

    public static ResiduumConfig snapshot(ResiduumConfig c) {
        ResiduumConfig copy = new ResiduumConfig();
        copy(c, copy);
        return copy;
    }

    /** Marks settings text pasted from someone, so other text in the clipboard is told apart. */
    private static final String CODE_MARK = "residuum", OLD_CODE_MARK = "livingglass";
    private static final int CODE_MAX = 16384;

    /** The settings that differ from the defaults, as one line of JSON to paste to someone. */
    public static String code(ResiduumConfig c) {
        ResiduumConfig defaults = new ResiduumConfig();
        JsonObject out = new JsonObject();
        out.addProperty(CODE_MARK, 1);
        for (Entry e : ALL) {
            if (!same(e, c, defaults)) {
                switch (e) {
                    case Toggle t -> out.addProperty(e.key(), t.get().apply(c));
                    case Slider s -> out.addProperty(e.key(), s.get().applyAsDouble(c));
                }
            }
        }
        return out.toString();
    }

    /**
     * Settings pasted from code(): the defaults with what it lists, into c. Text from anyone, so it is checked: values
     * of the wrong kind and unknown keys are skipped, numbers are kept to their slider's range and steps. Returns how
     * many settings it set, or -1 (c untouched) when the text is not Residuum settings.
     */
    public static int paste(String text, ResiduumConfig c) {
        if (text == null || text.length() > CODE_MAX) {
            return -1;
        }
        JsonObject in;
        try {
            JsonElement parsed = JsonParser.parseString(text.strip());
            // Settings copied from Living Glass carry its name and are taken just the same
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has(CODE_MARK) && !parsed.getAsJsonObject().has(OLD_CODE_MARK)) {
                return -1;
            }
            in = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return -1; // not JSON at all
        }
        ResiduumConfig next = new ResiduumConfig();
        int set = 0;
        for (Entry e : ALL) {
            if (!(in.get(e.key()) instanceof JsonPrimitive v)) {
                continue;
            }
            if (e instanceof Toggle t && v.isBoolean()) {
                t.set().accept(next, v.getAsBoolean());
                set++;
            } else if (e instanceof Slider s && v.isNumber() && Double.isFinite(v.getAsDouble())) {
                s.set().accept(next, s.snap(v.getAsDouble()));
                set++;
            }
        }
        copy(next, c);
        return set;
    }

    /** Dev self-check, run in the dev game: settings survive code() and paste(), and bad text changes nothing. */
    public static void checkCode() {
        ResiduumConfig a = PRESETS.get(2).config();
        a.wipe.enabled = false;
        ResiduumConfig b = new ResiduumConfig();
        check(paste(code(a), b) > 0 && ALL.stream().allMatch(e -> same(e, a, b)), "settings code round trip");
        check(paste("{\"residuum\":1,\"fog_strength\":99,\"wipe\":\"no\",\"x\":1}", b) == 1
                && b.condensation.strength == 2.0 && b.wipe.enabled, "pasted values are checked");
        b.wipe.enabled = false;
        check(paste("not json {", b) == -1 && paste("{\"fog_strength\":1}", b) == -1 && !b.wipe.enabled,
                "other text is refused");
        check(paste("{\"livingglass\":1,\"wipe\":false}", b) == 1 && !b.wipe.enabled, "settings copied from Living Glass");
        check(PRESETS.get(1).matches(new ResiduumConfig()) && !PRESETS.get(1).matches(a), "presets are told apart");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException("Settings self-check failed: " + what);
        }
    }

    private static List<Entry> entries(Entry... entries) {
        return Arrays.stream(entries).filter(Objects::nonNull).toList();
    }

    /** A "leave it to" switch, there only while the other mod is installed. */
    private static Toggle leave(Rival rival, String key, Function<ResiduumConfig, Boolean> get,
                                BiConsumer<ResiduumConfig, Boolean> set) {
        return rival.present() ? new Toggle(key, get, set) : null;
    }

    private static Group quick(String key, String... entries) {
        return new Group(key, Arrays.stream(entries).map(Settings::entry).toList());
    }

    /** The setting with this key, from whichever page it is on. */
    public static Entry entry(String key) {
        Entry e = find(key);
        if (e == null) {
            throw new IllegalArgumentException(key);
        }
        return e;
    }

    /** The setting with this key, or null for a key that is not a setting (a preset, copy or paste). */
    public static Entry find(String key) {
        return ALL.stream().filter(e -> e.key().equals(key)).findFirst().orElse(null);
    }

    /** The preset with this key, or null. */
    public static Preset preset(String key) {
        return PRESETS.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }

    private static Toggle toggle(String key, Function<ResiduumConfig, Boolean> get, BiConsumer<ResiduumConfig, Boolean> set) {
        return new Toggle(key, get, set);
    }

    private static Slider slider(String key, double min, double max, double step, Unit unit,
                                 ToDoubleFunction<ResiduumConfig> get, ObjDoubleConsumer<ResiduumConfig> set) {
        return new Slider(key, min, max, step, unit, get, set);
    }

    private static Slider seconds(String key, double min, double max, double step,
                                  ToDoubleFunction<ResiduumConfig> get, ObjDoubleConsumer<ResiduumConfig> set) {
        return new Slider(key, min, max, step, Unit.SECONDS, get, set);
    }
}
