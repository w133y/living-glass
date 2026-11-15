package com.w1z4r_d.residuum.gui;

import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.Rival;
import com.w1z4r_d.residuum.Settings;
import com.w1z4r_d.residuum.compat.Compat;
import net.minecraft.network.chat.Component;

/**
 * The live picture beside the settings: the mod's own textures on a little window, wall, ceiling or field, showing what
 * the setting under the mouse does as the settings stand now. Switching an effect off clears it; strengths thin or
 * thicken it; timings play out SPEED times faster: building up, staying, going again. Settings with nothing to see
 * (climate, performance) get a diagram worked out from their values.
 */
final class Preview {
    /**
     * Timings play at least SPEED times faster than in the game, and faster still, in steps of SPEED, where that
     * would leave a build-up, stay or fade longer than LONGEST seconds.
     */
    static final int SPEED = 10;
    private static final double LONGEST = 4;
    /** The window is WIDE x HIGH blocks of TEX texels each. */
    private static final int WIDE = 3, HIGH = 2, TEX = 16;
    private static final int SKY = 0xFF9DBFE8, STORM_SKY = 0xFF7C8794, SNOW = 0xFFEEF3F9, SAND = 0xFFDCCB9A,
            GRASS = 0xFF6E9E4F, GRASS_TINT = 0xFF79C05A, STAINED = 0x6699D8;
    private static final int DROP = 0xC4DCF5, SAND_DUST = 0xEDE3C4, OFF = 0xFFE0796B;
    private static final String NS = "residuum", OVERLAY = "textures/overlay/";
    /** Biomes for the climate table: the lang key's name and the base temperature. */
    private static final String[] BIOMES = {"snowy_plains", "taiga", "forest", "plains", "jungle", "desert"};
    private static final float[] BIOME_TEMPS = {0.0f, 0.25f, 0.7f, 0.8f, 0.95f, 2.0f};

    enum Scene {
        WINTER, FOG, BREATH, WIPE, TINT, FROST, ICE, ROOF, DUST, RAIN, MELT, DEBUG, ASH, BLAST, TORCH, SCORCH, SNOW,
        SHIELD, TUMBLE, TRAMPLE, FIREFLY, CRACK, PRINTS, HOLE, RIME, WALL_SNOW, CAPS, STEAM, CLIMATE, HEAT, RADIUS, COUNT
    }

    private String key = "";
    /** While set (Shift held), the setting shown is drawn at its default rather than as it is set. */
    boolean compare;
    private long since = System.nanoTime();
    /** While paused, the moment it was paused at; else -1. */
    private long pausedAt = -1;

    /** Shows what the setting with this key does, from the start of its cycle when it is another one than before. */
    void show(String key) {
        if (!key.equals(this.key)) {
            this.key = key;
            since = pausedAt >= 0 ? pausedAt : System.nanoTime();
        }
    }

    boolean paused() {
        return pausedAt >= 0;
    }

    void togglePause() {
        if (pausedAt >= 0) {
            since += System.nanoTime() - pausedAt;
            pausedAt = -1;
        } else {
            pausedAt = System.nanoTime();
        }
    }

    private double seconds() {
        return ((pausedAt >= 0 ? pausedAt : System.nanoTime()) - since) / 1.0e9;
    }

    /** The name of the setting shown. */
    Component title() {
        return key.isEmpty() ? Component.translatable("residuum.preview.title")
                : Settings.name(key);
    }

    /** All the setting does, or what the preview is for when no setting is picked. */
    String description() {
        return (key.isEmpty() ? Component.translatable("residuum.config.intro") : Settings.description(key)).getString();
    }

    /** "Default: ..." for a slider. */
    String defaultText() {
        if (Settings.find(key) instanceof Settings.Slider s) {
            return Component.translatable("residuum.preview.default", s.format(s.get().applyAsDouble(Widgets.DEFAULTS)))
                    .getString();
        }
        return "";
    }

    /** Whether the setting, the effect it belongs to or the whole mod is switched off. */
    boolean off() {
        ResiduumConfig c = config();
        // A "leave it to" switch on turns the effect off, not on
        boolean switchedOff = !key.startsWith("leave_") && Settings.find(key) instanceof Settings.Toggle toggle
                && !toggle.get().apply(c);
        return switchedOff || !on(scene(key), c);
    }

    /** Whether what is shown is off, or how much faster than in the game it plays. */
    Component note() {
        if (compare && changed()) {
            return Component.translatable("residuum.preview.comparing");
        }
        Rival rival = Settings.leftTo(key, config());
        if (rival != null) {
            return Component.translatable("residuum.preview.left", rival.modName());
        }
        if (off()) {
            return Component.translatable("residuum.preview.off");
        }
        double[] cycle = timing(key, config());
        return cycle != null ? Component.translatable("residuum.preview.faster", speed(cycle)) : Component.empty();
    }

    /** Which picture a setting is shown on. */
    static Scene scene(String key) {
        return switch (key) {
            case "condensation", "fog_strength", "fog_build", "fog_clear" -> Scene.FOG;
            case "breath_spot" -> Scene.BREATH;
            case "wipe" -> Scene.WIPE;
            case "tint_stained_glass" -> Scene.TINT;
            case "frost", "frost_build", "frost_melt" -> Scene.FROST;
            case "ice", "ice_build", "ice_fogged" -> Scene.ICE;
            case "skylights" -> Scene.ROOF;
            case "sand", "dust_strength", "dust_build" -> Scene.DUST;
            case "raindrops", "drying" -> Scene.RAIN;
            case "melt" -> Scene.MELT;
            case "debug" -> Scene.DEBUG;
            case "ash", "ash_build", "ash_side", "ash_fade" -> Scene.ASH;
            case "explosions", "blast_time" -> Scene.BLAST;
            case "torch_soot" -> Scene.TORCH;
            case "lightning", "lightning_time" -> Scene.SCORCH;
            case "snowballs", "snow" -> Scene.SNOW;
            case "shield_scratches" -> Scene.SHIELD;
            case "tumbleweeds", "tumbleweed_frequency" -> Scene.TUMBLE;
            case "trampling", "leave_grass" -> Scene.TRAMPLE;
            case "fireflies", "firefly_count" -> Scene.FIREFLY;
            case "cracks", "crack_time" -> Scene.CRACK;
            case "arrow_holes" -> Scene.HOLE;
            case "hoarfrost", "hoarfrost_build" -> Scene.RIME;
            case "wall_snow", "wall_snow_storms", "wall_snow_amount", "wall_snow_sides", "wall_snow_build",
                 "wall_snow_melt", "coat_speed" -> Scene.WALL_SNOW;
            case "snow_caps", "snow_caps_stay", "snow_caps_build" -> Scene.CAPS;
            case "steam" -> Scene.STEAM;
            case "footprints", "mob_footprints", "footprint_time", "leave_prints" -> Scene.PRINTS;
            case "comfort_temp", "night_penalty", "rain_penalty" -> Scene.CLIMATE;
            case "heat_radius" -> Scene.HEAT;
            case "scan_radius", "render_distance" -> Scene.RADIUS;
            case "scan_budget", "tracked_windows" -> Scene.COUNT;
            default -> Scene.WINTER;
        };
    }

    /** Whether the effect of a scene is switched on (the winter window shows whichever of its effects are). */
    private static boolean on(Scene scene, ResiduumConfig c) {
        boolean glass = c.glassOn();
        return c.enabled && switch (scene) {
            case FOG, BREATH, WIPE, TINT -> glass && c.condensation.enabled;
            case FROST, MELT -> glass && c.frost.enabled;
            case ICE -> glass && c.ice.enabled;
            case ROOF -> glass && c.skylights.enabled && c.frost.enabled;
            case DUST -> glass && c.sand.enabled;
            case RAIN -> glass && c.raindrops.enabled;
            case ASH -> c.ash.enabled;
            case BLAST -> c.explosions.enabled;
            case TORCH -> c.torchSoot.enabled;
            case SCORCH -> c.lightning.enabled;
            case SNOW -> c.snowballs.enabled;
            case SHIELD -> c.shieldScratches.enabled;
            case TUMBLE -> c.tumbleweeds.enabled;
            case TRAMPLE -> c.grassOn();
            case FIREFLY -> c.fireflies.enabled;
            case CRACK -> c.cracks.enabled;
            case HOLE -> c.arrowHoles.enabled;
            case RIME -> c.hoarfrost.enabled;
            case WALL_SNOW -> c.wallSnow.enabled;
            case CAPS -> c.snowCaps.enabled;
            case STEAM -> c.steam.enabled;
            case PRINTS -> c.printsOn();
            case DEBUG -> c.debug;
            case WINTER -> glass;
            case CLIMATE, HEAT, RADIUS, COUNT -> true;
        };
    }

    /** For a timing, its build-up, how long it stays and how long it takes to go, in game seconds; else null. */
    private static double[] timing(String key, ResiduumConfig c) {
        ResiduumConfig.Timing t = c.timing;
        return switch (key) {
            case "fog_build", "fog_clear" -> new double[] {t.fogBuildSeconds, 20, t.fogClearSeconds};
            case "frost_build", "frost_melt" -> new double[] {t.frostBuildSeconds, 20, t.frostMeltSeconds};
            case "ice_build" -> new double[] {t.iceBuildSeconds, 20, t.frostMeltSeconds};
            case "ice_fogged" -> new double[] {t.iceFoggedSeconds, 20, t.frostMeltSeconds};
            case "dust_build" -> new double[] {t.dustBuildSeconds, 20, t.iceBuildSeconds};
            case "drying" -> new double[] {0, 30, t.dryingSeconds};
            case "ash_build", "ash_fade" -> new double[] {t.ashBuildSeconds, 20, t.ashFadeSeconds};
            case "ash_side" -> new double[] {t.ashSideBuildSeconds, 20, t.ashFadeSeconds};
            case "blast_time" -> new double[] {0, t.blastSeconds, t.ashFadeSeconds};
            case "lightning_time" -> new double[] {0, t.lightningSeconds * 2 / 3, t.lightningSeconds / 3};
            case "snow" -> new double[] {0, t.snowSeconds * 3 / 4, t.snowSeconds / 4};
            case "melt" -> new double[] {t.meltSeconds, 20, 0};
            case "trampling" -> new double[] {0.2, 2, 20};
            case "cracks", "crack_time", "arrow_holes" -> new double[] {0, t.crackSeconds * 3 / 4, t.crackSeconds / 4};
            case "hoarfrost", "hoarfrost_build" -> new double[] {t.hoarfrostBuildSeconds, 20, 120};
            case "wall_snow", "wall_snow_amount", "wall_snow_sides", "wall_snow_build", "wall_snow_melt" ->
                    new double[] {t.wallSnowBuildSeconds, 20, t.wallSnowMeltSeconds};
            case "snow_caps", "snow_caps_build" -> new double[] {t.capBuildSeconds, 20, t.wallSnowMeltSeconds};
            case "coat_speed" -> new double[] {900 / t.coatSpeed, 20, 900 / t.coatSpeed};
            case "footprints", "mob_footprints", "footprint_time" -> new double[] {0, t.footprintSeconds / 2, t.footprintSeconds / 2};
            default -> null;
        };
    }

    /** How many times faster than in the game a cycle plays (see SPEED). */
    static int speed(double[] cycle) {
        double longest = Math.max(cycle[0], Math.max(cycle[1], cycle[2]));
        return SPEED * (int) Math.max(1, Math.ceil(longest / LONGEST / SPEED));
    }

    /**
     * How far grown an effect is t seconds into the preview: built up over build, kept for hold (and a second more to
     * see it), gone over fade, then a second of nothing; all speed times faster than in the game.
     */
    static double cycle(double t, double build, double hold, double fade, int speed) {
        double up = build / speed, stay = hold / speed + 1, down = fade / speed;
        double at = t % (up + stay + down + 1);
        if (at < up) {
            return at / up;
        }
        at -= up + stay;
        return at < 0 ? 1 : at < down ? 1 - at / down : 0;
    }

    /** Whether the setting shown is set otherwise than its default, so holding Shift shows something else. */
    boolean changed() {
        return Settings.find(key) instanceof Settings.Entry e && !Settings.same(e, ResiduumConfig.get(), Widgets.DEFAULTS);
    }

    /**
     * The settings the picture is drawn with: as they are, but a preset's own for a preset, and the default of the
     * setting shown while comparing.
     */
    private ResiduumConfig config() {
        Settings.Preset preset = Settings.preset(key);
        if (preset != null) {
            return preset.config();
        }
        ResiduumConfig c = ResiduumConfig.get();
        if (compare && Settings.find(key) instanceof Settings.Entry e) {
            ResiduumConfig before = Settings.snapshot(c);
            Settings.copy(e, Widgets.DEFAULTS, before);
            return before;
        }
        return c;
    }

    void paint(Painter p, int x, int y, int w, int h) {
        ResiduumConfig c = config();
        Scene scene = scene(key);
        // Whole texels where it fits, so the textures keep their pixels square
        int px = Math.max(1, Math.min(w / (WIDE * TEX), h / (HIGH * TEX)));
        int b = px * TEX;
        int wx = x + (w - b * WIDE) / 2, wy = y + (h - b * HIGH) / 2;
        double t = seconds();
        double[] cycle = timing(key, c);
        float level = cycle == null ? 1 : (float) cycle(t, cycle[0], cycle[1], cycle[2], speed(cycle));
        boolean on = on(scene, c) && !off();
        if (!on) {
            level = 0;
        }
        float fog = level * (float) c.condensation.strength;
        switch (scene) {
            case FOG -> {
                outside(p, Scene.FOG, wx, wy, b, px, "glass");
                fog(p, wx, wy, b, fog, 0xFFFFFF);
            }
            case BREATH -> {
                outside(p, Scene.FOG, wx, wy, b, px, "glass");
                double at = t % 4;
                float open = on && c.breathSpot.enabled ? (float) (at < 0.4 ? at / 0.4 : Math.max(0, 1 - (at - 0.4) / 3)) : 0;
                fogWithHole(p, wx, wy, px, (float) c.condensation.strength * (on ? 1 : 0), (i, j) -> {
                    double dx = (i - 24) / 8.0, dy = (j - 15) / 6.0;
                    return dx * dx + dy * dy < open * (1 + 0.25 * noise(i, j));
                });
            }
            case WIPE -> {
                outside(p, Scene.FOG, wx, wy, b, px, "glass");
                double at = t % 6;
                float reach = (float) Math.min(1, at / 0.8), width = (float) (at < 2 ? 4 : Math.max(0, 4 - (at - 2) * 1.2));
                boolean wipes = on && c.wipe.enabled;
                fogWithHole(p, wx, wy, px, (float) c.condensation.strength * (on ? 1 : 0), (i, j) -> {
                    if (!wipes || i < 6 || i > 6 + reach * 36) {
                        return false;
                    }
                    double centre = 16 + 4 * Math.sin(i * 0.22);
                    return Math.abs(j - centre) < width * (0.85 + 0.3 * noise(i, j));
                });
            }
            case TINT -> {
                outside(p, Scene.FOG, wx, wy, b, px, "light_blue_stained_glass");
                fog(p, wx, wy, b, (float) c.condensation.strength * (on ? 1 : 0),
                        c.tintStainedGlass ? Widgets.mix(0xFFFFFF, STAINED, 0.25f) & 0xFFFFFF : 0xFFFFFF);
            }
            case FROST -> {
                outside(p, Scene.FROST, wx, wy, b, px, "glass");
                frost(p, wx, wy, b, level, WIDE);
            }
            case ICE -> {
                outside(p, Scene.ICE, wx, wy, b, px, "glass");
                ice(p, wx, wy, px, level);
            }
            case ROOF -> roof(p, wx, wy, b, on);
            case DUST -> {
                outside(p, Scene.DUST, wx, wy, b, px, "glass");
                dust(p, wx, wy, b, px, level * (float) c.sand.strength);
            }
            case RAIN -> {
                outside(p, Scene.RAIN, wx, wy, b, px, "glass");
                rain(p, wx, wy, px, level, t);
            }
            case MELT -> melt(p, wx, wy, b, px, t, c, on);
            case DEBUG -> {
                outside(p, Scene.FROST, wx, wy, b, px, "glass");
                frost(p, wx, wy, b, c.glassOn() && c.frost.enabled ? 0.5f : 0, WIDE);
                if (on) {
                    outlines(p, wx, wy, b);
                }
            }
            case ASH -> {
                blocks(p, "stone_bricks", wx, wy, b, 0xFFFFFFFF);
                soot(p, wx, wy, px, level);
            }
            case BLAST -> {
                blocks(p, "stone_bricks", wx, wy, b, 0xFFFFFFFF);
                blast(p, wx, wy, px, level);
            }
            case TORCH -> torch(p, wx, wy, b, px, level);
            case SCORCH -> {
                blocks(p, "grass_block_top", wx, wy, b, GRASS_TINT);
                scorch(p, wx, wy, b, level);
            }
            case SNOW -> {
                blocks(p, "stone_bricks", wx, wy, b, 0xFFFFFFFF);
                splats(p, wx, wy, px, level);
            }
            case SHIELD -> shield(p, x, y, w, h, t, on);
            case TUMBLE -> tumble(p, wx, wy, b, px, t, on ? (float) c.tumbleweeds.frequency : 0);
            case TRAMPLE -> trample(p, wx, wy, b, px, level);
            case FIREFLY -> fireflies(p, wx, wy, b, px, t, on ? (float) c.fireflies.frequency : 0);
            case CRACK -> {
                outside(p, Scene.FOG, wx, wy, b, px, "glass");
                cracked(p, wx, wy, px, level);
            }
            case PRINTS -> prints(p, wx, wy, b, px, t, on, c);
            case HOLE -> {
                blocks(p, "oak_planks", wx, wy, b, 0xFFFFFFFF);
                holes(p, wx, wy, px, level);
            }
            case RIME -> {
                blocks(p, "grass_block_top", wx, wy, b, GRASS_TINT);
                coat(p, wx, wy, b, "rime_sheet", level);
            }
            case WALL_SNOW -> {
                blocks(p, "stone_bricks", wx, wy, b, 0xFFFFFFFF);
                coat(p, wx, wy, b, "wall_snow_sheet", level * (float) Math.min(1.6, c.wallSnow.amount));
            }
            case CAPS -> {
                // Looking down at a row of plank slabs: the snow settles on their tops
                blocks(p, "spruce_planks", wx, wy, b, 0xFFFFFFFF);
                coat(p, wx, wy, b, "wall_snow_sheet", level * 1.4f);
            }
            case STEAM -> steam(p, wx, wy, b, px, t, on);
            case CLIMATE -> climate(p, x, y, w, h, c);
            case HEAT -> heat(p, x, y, w, h, c);
            case RADIUS -> radius(p, x, y, w, h, c);
            case COUNT -> count(p, x, y, w, h, c);
            case WINTER -> {
                outside(p, Scene.ICE, wx, wy, b, px, "glass");
                if (on && c.ice.enabled) {
                    ice(p, wx, wy, px, 0.45f);
                }
                if (on && c.frost.enabled) {
                    frost(p, wx, wy, b, 0.6f, WIDE);
                }
                if (on && c.condensation.enabled) {
                    fog(p, wx, wy, b, 0.55f * (float) c.condensation.strength, 0xFFFFFF);
                }
            }
        }
    }

    private static int alpha(float a, int rgb) {
        return (int) (Math.clamp(a, 0f, 1f) * 255) << 24 | rgb & 0xFFFFFF;
    }

    private static int hash(int i) {
        i *= 0x9E3779B9;
        i ^= i >>> 16;
        i *= 0x85EBCA6B;
        return i ^ i >>> 13;
    }

    /** -1..1, the same for the same texel, changing every texel. */
    private static double noise(int i, int j) {
        return (hash(i * 7919 + j * 104729) & 0xFF) / 127.5 - 1;
    }

    /** Sky and the ground outside the window, then the glass. */
    private static void outside(Painter p, Scene scene, int x, int y, int b, int px, String glass) {
        int w = b * WIDE, h = b * HIGH;
        p.fill(x, y, x + w, y + h, scene == Scene.RAIN ? STORM_SKY : SKY);
        int ground = scene == Scene.DUST ? SAND : scene == Scene.RAIN || scene == Scene.FOG ? GRASS : SNOW;
        for (int i = 0; i < WIDE * TEX; i++) {
            // A low rolling line of hills, a texel wide each
            int top = (int) (h * 0.62 + px * (2.5 * Math.sin(i * 0.21) + 1.2 * Math.sin(i * 0.57 + 1)));
            p.fill(x + i * px, y + top, x + (i + 1) * px, y + h, ground);
        }
        blocks(p, glass, x, y, b, 0xFFFFFFFF);
    }

    private static void blocks(Painter p, String texture, int x, int y, int b, int argb) {
        for (int c = 0; c < WIDE; c++) {
            for (int r = 0; r < HIGH; r++) {
                block(p, texture, x + c * b, y + r * b, b, argb);
            }
        }
    }

    private static void block(Painter p, String texture, int x, int y, int b, int argb) {
        p.blit("minecraft", "textures/block/" + texture + ".png", x, y, b, b, 0, 0, TEX, TEX, TEX, TEX, argb);
    }

    /** Which sides of block (c, r) of a window `wide` blocks wide are its frame: bit 1 left, 2 right, 4 bottom, 8 top. */
    private static int mask(int c, int r, int wide) {
        return (c == 0 ? 1 : 0) | (c == wide - 1 ? 2 : 0) | (r == HIGH - 1 ? 4 : 0) | (r == 0 ? 8 : 0);
    }

    /** Fog: a film of mist over the glass with the beads and drops of its texture in it, tinted rgb. */
    private static void fog(Painter p, int x, int y, int b, float amount, int rgb) {
        p.fill(x, y, x + b * WIDE, y + b * HIGH, alpha(0.3f * amount, Widgets.mix(0xF2F6FA, rgb, 0.5f)));
        for (int c = 0; c < WIDE; c++) {
            for (int r = 0; r < HIGH; r++) {
                p.blit(NS, OVERLAY + "condensation_" + (c + r) % 3 + "_" + mask(c, r, WIDE) + ".png", x + c * b, y + r * b,
                        b, b, 0, 0, TEX, TEX, TEX, TEX, alpha(amount, rgb));
            }
        }
    }

    private interface Hole {
        boolean cleared(int i, int j);
    }

    /** Fog over the whole window but where hole clears it, drawn a texel column at a time round the hole. */
    private static void fogWithHole(Painter p, int x, int y, int px, float amount, Hole hole) {
        int b = px * TEX;
        for (int i = 0; i < WIDE * TEX; i++) {
            int c = i / TEX;
            for (int j = 0; j < HIGH * TEX; ) {
                if (hole.cleared(i, j)) {
                    j++;
                    continue;
                }
                int end = j;
                while (end < HIGH * TEX && !hole.cleared(i, end) && end / TEX == j / TEX) {
                    end++;
                }
                int r = j / TEX;
                p.fill(x + i * px, y + j * px, x + (i + 1) * px, y + end * px, alpha(0.3f * amount, 0xF2F6FA));
                p.blit(NS, OVERLAY + "condensation_" + (c + r) % 3 + "_" + mask(c, r, WIDE) + ".png", x + i * px, y + j * px,
                        px, (end - j) * px, i % TEX, j % TEX, 1, end - j, TEX, TEX, alpha(amount, 0xFFFFFF));
                j = end;
            }
        }
    }

    /** Frost grown to amount over the first `wide` columns: four stages, each over a quarter, the next fading in. */
    private static void frost(Painter p, int x, int y, int b, float amount, int wide) {
        float stages = amount * 4;
        int full = (int) stages;
        for (int c = 0; c < wide; c++) {
            for (int r = 0; r < HIGH; r++) {
                frostBlock(p, x + c * b, y + r * b, b, (c + r) % 3, mask(c, r, wide), full, stages - full, 1);
            }
        }
    }

    private static void frostBlock(Painter p, int x, int y, int b, int variant, int mask, int full, float next, float a) {
        String base = OVERLAY + "frost_" + variant + "_" + mask + "_";
        if (full > 0) {
            p.blit(NS, base + Math.min(3, full - 1) + ".png", x, y, b, b, 0, 0, TEX, TEX, TEX, TEX, alpha(a, 0xFFFFFF));
        }
        if (full < 4) {
            p.blit(NS, base + full + ".png", x, y, b, b, 0, 0, TEX, TEX, TEX, TEX, alpha(next * a, 0xFFFFFF));
        }
    }

    /** How far up, in texels, a ragged edge rising from the bottom reaches at column i, for a sheet grown to height. */
    private static int ragged(int i, float height, int seed) {
        if (height <= 0) {
            return 0;
        }
        double wave = 1.4 * Math.sin(i * 0.37 + seed) + 0.8 * Math.sin(i * 0.91 + seed * 3);
        return (int) Math.clamp(Math.round(height + wave), 0, HIGH * TEX);
    }

    /** Outside ice climbing the window from the bottom, a column a texel wide at a time. */
    private static void ice(Painter p, int x, int y, int px, float amount) {
        int stage = Math.min(3, (int) (amount * 4));
        for (int i = 0; i < WIDE * TEX; i++) {
            int up = ragged(i, amount * (HIGH * TEX + 2), 1);
            if (up > 0) {
                int top = HIGH * TEX - up;
                // The bottom HIGH blocks of the eight-block sheet, so it tiles on below the window as in the game
                p.blit(NS, OVERLAY + "ice_sheet_" + stage + ".png", x + i * px, y + top * px, px, up * px, i,
                        (8 - HIGH) * TEX + top, 1, up, 128, 128, 0xFFFFFFFF);
            }
        }
    }

    /** A sandy film over the glass and a drift along the bottom, both as much as amount. */
    private static void dust(Painter p, int x, int y, int b, int px, float amount) {
        p.blit(NS, OVERLAY + "sand_sheet.png", x, y, b * WIDE, b * HIGH, 0, (8 - HIGH) * TEX, WIDE * TEX, HIGH * TEX, 128,
                128, alpha(0.35f * amount, SAND_DUST));
        for (int i = 0; i < WIDE * TEX; i++) {
            int up = ragged(i, amount * 5, 2);
            if (up > 0) {
                int top = HIGH * TEX - up;
                p.blit(NS, OVERLAY + "sand_sheet.png", x + i * px, y + top * px, px, up * px, i, (8 - HIGH) * TEX + top, 1,
                        up, 128, 128, alpha(1, SAND_DUST));
            }
        }
    }

    /** Drops running down the glass and beads sitting on it, fewer as they dry. */
    private static void rain(Painter p, int x, int y, int px, float amount, double t) {
        int texels = WIDE * TEX;
        for (int k = 0; k < 40 * amount; k++) {
            int h = hash(k);
            int column = Math.floorMod(h, texels), row = Math.floorMod(h >>> 8, HIGH * TEX);
            p.fill(x + column * px, y + row * px, x + (column + 1) * px, y + (row + 1) * px, alpha(0.6f, DROP));
        }
        if (amount < 1) {
            return; // the rain has stopped: what is left only dries
        }
        for (int k = 0; k < 12; k++) {
            int h = hash(k + 1000);
            double speed = 0.2 + (h >>> 8 & 0xFF) / 255.0 * 0.4;
            double row = ((t * speed + (h >>> 16 & 0xFF) / 255.0) % 1.3) * HIGH * TEX - 2;
            int column = Math.floorMod(h, texels);
            if (row >= 0 && row < HIGH * TEX - 2) {
                int yy = y + (int) (row * px);
                p.fill(x + column * px, yy, x + (column + 1) * px, yy + 2 * px, alpha(0.85f, DROP));
            }
        }
    }

    /**
     * A window two blocks wide that a third column of glass joins: the frost of the old frame melts into the frame of the
     * wider window over the frame melt time, then it starts over.
     */
    private static void melt(Painter p, int x, int y, int b, int px, double t, ResiduumConfig c, boolean on) {
        double melt = c.timing.meltSeconds / speed(timing("melt", c));
        double at = t % (melt + 3);
        boolean joined = at >= 1;
        float share = (float) Math.clamp((at - 1) / melt, 0, 1);
        outside(p, Scene.FROST, x, y, b, px, "glass");
        for (int r = 0; r < HIGH; r++) {
            if (!joined) {
                block(p, "stone_bricks", x + 2 * b, y + r * b, b, 0xFFFFFFFF);
            }
            if (!on) {
                continue;
            }
            for (int col = 0; col < WIDE; col++) {
                int bx = x + col * b, by = y + r * b, variant = (col + r) % 3;
                if (col < 2) {
                    frostBlock(p, bx, by, b, variant, mask(col, r, 2), 2, 0, 1 - share);
                }
                if (joined) {
                    frostBlock(p, bx, by, b, variant, mask(col, r, WIDE), 2, 0, share);
                }
            }
        }
    }

    /** The debug outline round each face of the window. */
    private static void outlines(Painter p, int x, int y, int b) {
        for (int c = 0; c < WIDE; c++) {
            for (int r = 0; r < HIGH; r++) {
                int x0 = x + c * b + 1, y0 = y + r * b + 1, x1 = x0 + b - 2, y1 = y0 + b - 2;
                p.fill(x0, y0, x1, y0 + 1, 0xFF00FF00);
                p.fill(x0, y1 - 1, x1, y1, 0xFF00FF00);
                p.fill(x0, y0, x0 + 1, y1, 0xFF00FF00);
                p.fill(x1 - 1, y0, x1, y1, 0xFF00FF00);
            }
        }
    }

    /** Looking up at a roof of planks with a strip of glass in it, frosted underneath. */
    private static void roof(Painter p, int x, int y, int b, boolean on) {
        for (int c = 0; c < WIDE; c++) {
            for (int r = 0; r < HIGH; r++) {
                int bx = x + c * b, by = y + r * b;
                if (c == 1) {
                    p.fill(bx, by, bx + b, by + b, SKY);
                    block(p, "glass", bx, by, b, 0xFFFFFFFF);
                    if (on) {
                        frostBlock(p, bx, by, b, r, 1 | 2 | (r == 1 ? 4 : 8), 3, 0, 1);
                    }
                } else {
                    block(p, "oak_planks", bx, by, b, 0xFFB0B0B0);
                }
            }
        }
    }

    /** Soot climbing a wall from the ground in a ragged dome, highest in the middle, a texel column at a time. */
    private static void soot(Painter p, int x, int y, int px, float amount) {
        int texels = WIDE * TEX;
        for (int i = 0; i < texels; i++) {
            float across = (i - texels / 2f) / (texels * 0.55f);
            float tongue = 3 * (hash(i / 3) & 0xFF) / 255f; // flames lick up further in places
            int up = ragged(i, amount * ((1 - across * across) * (HIGH * TEX - 4) + tongue), 3);
            if (up > 0) {
                int top = HIGH * TEX - up;
                p.blit(NS, OVERLAY + "ash_sheet.png", x + i * px, y + top * px, px, up * px, i, top, 1, up, 64, 64,
                        alpha(Math.min(1, amount * 1.5f), 0xFFFFFF));
            }
        }
    }

    /** An explosion's soot: black round the middle of the wall, thinning out raggedly, by runs of equal texels. */
    private static void blast(Painter p, int x, int y, int px, float amount) {
        for (int j = 0; j < HIGH * TEX; j++) {
            for (int i = 0; i < WIDE * TEX; ) {
                float a = blastAt(i, j, amount);
                int end = i + 1;
                while (end < WIDE * TEX && blastAt(end, j, amount) == a) {
                    end++;
                }
                if (a > 0) {
                    p.blit(NS, OVERLAY + "ash_sheet.png", x + i * px, y + j * px, (end - i) * px, px, i, j, end - i, 1, 64,
                            64, alpha(a, 0xFFFFFF));
                }
                i = end;
            }
        }
    }

    private static float blastAt(int i, int j, float amount) {
        double d = Math.hypot((i - 24) / 22.0, (j - 17) / 15.0) + 0.12 * noise(i / 2, j / 2);
        return Math.round(Math.clamp((1 - d) * 2.2, 0, 1) * amount * 8) / 8f;
    }

    /** Looking up at a stone ceiling over a torch: the round smudge of soot its flame leaves there. */
    private static void torch(Painter p, int x, int y, int b, int px, float amount) {
        blocks(p, "stone", x, y, b, 0xFFFFFFFF);
        for (int j = 4; j < HIGH * TEX - 4; j++) {
            for (int i = 12; i < WIDE * TEX - 12; i++) {
                double d = Math.hypot(i - 23.5, j - 15.5) / 9 + 0.1 * noise(i, j);
                float a = (float) Math.clamp(1 - d, 0, 1) * 0.85f * amount;
                if (a > 0.02f) {
                    p.fill(x + i * px, y + j * px, x + (i + 1) * px, y + (j + 1) * px, alpha(a, 0x1A1714));
                }
            }
        }
        // The flame below, seen from under the ceiling, and its glow
        p.fill(x + 22 * px, y + 14 * px, x + 26 * px, y + 18 * px, 0x60FFC860);
        p.fill(x + 23 * px, y + 15 * px, x + 25 * px, y + 17 * px, 0xFFFFD27A);
    }

    /** A lightning burn mark, seen from above, its heart in the middle of the window. */
    private static void scorch(Painter p, int x, int y, int b, float amount) {
        // The texture is four blocks of mark in a block of clear margin each way; its middle goes on the window's
        int u = 48 - WIDE * TEX / 2, v = 48 - HIGH * TEX / 2;
        p.blit(NS, OVERLAY + "lightning_scorch_0.png", x, y, b * WIDE, b * HIGH, u, v, WIDE * TEX, HIGH * TEX, 96, 96,
                alpha(amount, 0xFFFFFF));
        p.blit(NS, OVERLAY + "lightning_embers_0.png", x, y, b * WIDE, b * HIGH, u, v, WIDE * TEX, HIGH * TEX, 96, 96,
                alpha(amount * amount, 0xFFFFFF));
    }

    /** Three snowball splats on a wall. */
    private static void splats(Painter p, int x, int y, int px, float amount) {
        int[][] at = {{10, 11}, {27, 21}, {38, 9}};
        for (int k = 0; k < at.length; k++) {
            // Each texture is a splat in a block of clear margin, 46 texels across with its middle at 23
            p.blit(NS, OVERLAY + "snow_" + k + ".png", x + (at[k][0] - 23) * px, y + (at[k][1] - 23) * px, 46 * px,
                    46 * px, 0, 0, 46, 46, 46, 46, alpha(amount, 0xFFFFFF));
        }
    }

    /** The front of a shield, and an axe's gash across it that goes as the shield comes back up after 5 seconds. */
    private static void shield(Painter p, int x, int y, int w, int h, double t, boolean on) {
        int px = Math.max(1, Math.min((w - 8) / 12, (h - 8) / 22));
        int sx = x + (w - 12 * px) / 2, sy = y + (h - 22 * px) / 2;
        // The plate's front in the shield texture: 12 x 22 texels from (1, 1)
        p.blit("minecraft", Compat.SHIELD_TEXTURE, sx, sy, 12 * px, 22 * px, 1, 1, 12, 22, 64, 64, 0xFFFFFFFF);
        double at = t % 7;
        float a = !on || at < 0.2 ? 0 : at < 5 ? 1 : (float) Math.max(0, 1 - (at - 5));
        if (a > 0) {
            p.scissor(sx, sy, sx + 12 * px, sy + 22 * px);
            p.blit(NS, OVERLAY + "shield_scratch_0.png", sx + 6 * px - 23 * px, sy + 10 * px - 23 * px, 46 * px, 46 * px, 0, 0,
                    46, 46, 46, 46, alpha(a, 0xFFFFFF));
            p.endScissor();
        }
    }

    /** Tumbleweeds rolling and hopping across a desert, more of them the more often they come. */
    private static void tumble(Painter p, int x, int y, int b, int px, double t, float frequency) {
        int w = b * WIDE, h = b * HIGH;
        p.fill(x, y, x + w, y + h, SKY);
        int ground = y + (int) (h * 0.72);
        for (int i = 0; i < WIDE * TEX; i++) {
            int top = ground + (int) (px * 1.5 * Math.sin(i * 0.17));
            p.fill(x + i * px, top, x + (i + 1) * px, y + h, SAND);
        }
        if (frequency <= 0) {
            return;
        }
        double every = 5 / frequency, across = 4;
        int size = 12 * px;
        for (int k = (int) Math.floor((t - across) / every); k * every <= t; k++) {
            double age = t - k * every;
            if (k < 0 || age < 0 || age > across) {
                continue;
            }
            float cx = x - size + (float) (age / across) * (w + 2 * size);
            float hop = (float) Math.abs(Math.sin(age * 3.1)) * 5 * px;
            float cy = ground - size / 2f - hop;
            p.rotate(cx, cy, (float) (age * 5));
            p.blit(NS, OVERLAY + (k % 2 == 0 ? "tumbleweed.png" : "tumbleweed_dry.png"), Math.round(cx - size / 2f),
                    Math.round(cy - size / 2f), size, size, 0, 0, 16, 16, 16, 16, 0xFFFFFFFF);
            p.pop();
        }
    }

    /**
     * Seen from the side, a row of grass a player walks through, into the picture: the plants each side of the path
     * are pushed apart, then stand back up. Each plant is drawn a texel row at a time, every row leaning further over
     * and lower down the higher it is up the plant.
     */
    private static void trample(Painter p, int x, int y, int b, int px, float amount) {
        int w = b * WIDE, h = b * HIGH;
        p.fill(x, y, x + w, y + h, SKY);
        int ground = y + h - 5 * px;
        block(p, "grass_block_side", x, ground, b, 0xFFFFFFFF);
        block(p, "grass_block_side", x + b, ground, b, 0xFFFFFFFF);
        block(p, "grass_block_side", x + 2 * b, ground, b, 0xFFFFFFFF);
        p.fill(x, ground, x + w, ground + px, GRASS_TINT);
        int plants = 7;
        for (int k = 0; k < plants; k++) {
            int left = x + (k * (WIDE * TEX - 14) / (plants - 1)) * px;
            // The middle three are walked through; each leans away from the path in the middle
            float share = Math.abs(k - plants / 2) <= 1 ? amount : 0;
            int side = k < plants / 2 ? -1 : k > plants / 2 ? 1 : (hash(k) & 1) * 2 - 1;
            float lean = 0.35f * share * side, sink = 0.3f * share;
            for (int row = 0; row < TEX; row++) {
                float up = (TEX - row) / (float) TEX;
                int dx = Math.round(lean * up * TEX * px);
                int top = ground - Math.round((TEX - row) * (1 - sink) * px);
                int bottom = ground - Math.round((TEX - row - 1) * (1 - sink) * px);
                if (bottom > top) {
                    p.blit("minecraft", "textures/block/short_grass.png", left + dx, top, TEX * px, bottom - top, 0, row,
                            TEX, 1, TEX, TEX, GRASS_TINT);
                }
            }
        }
    }

    /** Holes three arrows left in a plank wall. */
    private static void holes(Painter p, int x, int y, int px, float amount) {
        int[][] at = {{12, 10}, {30, 19}, {37, 8}};
        for (int k = 0; k < at.length; k++) {
            // Each texture is a mark in a clear margin, 36 texels across with its middle at 18
            p.blit(NS, OVERLAY + "arrow_hole_" + k + ".png", x + (at[k][0] - 18) * px, y + (at[k][1] - 18) * px, 36 * px,
                    36 * px, 0, 0, 36, 36, 36, 36, alpha(amount, 0xFFFFFF));
        }
    }

    /** A coat grown to amount over the whole picture: a corner of its sheet, which is four blocks across. */
    private static void coat(Painter p, int x, int y, int b, String sheet, float amount) {
        p.blit(NS, OVERLAY + sheet + ".png", x, y, b * WIDE, b * HIGH, 0, 0, WIDE * TEX, HIGH * TEX, 64, 64,
                alpha(amount, 0xFFFFFF));
    }

    /**
     * Seen from the side: lava under a pond with a block of stone between, and steam rising off the warm water in
     * wisps that drift up, spread and fade.
     */
    private static void steam(Painter p, int x, int y, int b, int px, double t, boolean on) {
        int w = b * WIDE, h = b * HIGH;
        p.fill(x, y, x + w, y + h, 0xFF1B2230);
        int water = y + h - b + 2 * px;
        for (int c = 0; c < WIDE; c++) {
            p.blit("minecraft", "textures/block/lava_still.png", x + c * b, y + h - b / 2, b, b / 2, 0, 0, TEX, TEX / 2, TEX,
                    TEX * 20, 0xFFFFFFFF);
            p.blit("minecraft", "textures/block/water_still.png", x + c * b, water, b, y + h - b / 2 - water, 0, 0, TEX, TEX / 2,
                    TEX, TEX * 32, 0xFF3F76E4);
        }
        p.fill(x, y + h - b / 2 - px, x + w, y + h - b / 2, 0xFF6E6E6E);
        if (!on) {
            return;
        }
        for (int k = 0; k < 16; k++) {
            int hsh = hash(k + 501);
            double life = 2.5 + (hsh & 0xFF) / 255.0 * 1.5, age = (t + (hsh >>> 8 & 0xFF) / 255.0 * life) % life;
            float share = (float) (age / life);
            int cx = x + (Math.floorMod(hsh >>> 16, WIDE * TEX - 6) + 3) * px + (int) (2 * px * Math.sin(age * 2 + k));
            int cy = water - (int) (share * (water - y) * 0.9);
            int size = px * (1 + (int) (share * 3));
            float a = 0.55f * (share < 0.15f ? share / 0.15f : 1 - share);
            p.fill(cx - size, cy - px, cx + size, cy + px, alpha(a, 0xE8EEF4));
            p.fill(cx - px, cy - size, cx + px, cy + size, alpha(a, 0xE8EEF4));
        }
    }

    /** Two arrows' cracks in the window, each round where it went in. */
    private static void cracked(Painter p, int x, int y, int px, float amount) {
        int[][] at = {{15, 12}, {33, 20}};
        for (int k = 0; k < at.length; k++) {
            // Each texture is a web 20 texels across in a clear margin, 52 texels across with its middle at 26
            p.blit(NS, OVERLAY + "crack_" + k + ".png", x + (at[k][0] - 26) * px, y + (at[k][1] - 26) * px, 52 * px,
                    52 * px, 0, 0, 52, 52, 52, 52, alpha(amount, 0xFFFFFF));
        }
    }

    /**
     * Snow seen from above, someone walking across it from the bottom left: each footprint goes down as they step,
     * then fades; and a fox trotting along behind, when mobs leave prints too.
     */
    private static void prints(Painter p, int x, int y, int b, int px, double t, boolean on, ResiduumConfig c) {
        blocks(p, "snow", x, y, b, 0xFFFFFFFF);
        if (!on) {
            return;
        }
        double life = Math.max(1, c.timing.footprintSeconds / speed(timing("footprints", c)));
        int steps = 14;
        double every = 0.45, walk = (t % (steps * every + life)) / every;
        for (int walker = 0; walker < (c.footprints.mobs ? 2 : 1); walker++) {
            for (int k = 0; k < steps && k <= walk - walker * 2; k++) {
                double age = (walk - walker * 2 - k) * every / life;
                float a = age < 0.5 ? 1 : (float) Math.max(0, (1 - age) / 0.5);
                // Along a gentle curve, feet either side of it
                double along = k / (double) steps;
                int side = k % 2 == 0 ? -1 : 1;
                int cx = 4 + (int) (along * 40), cy = (walker == 0 ? 25 : 19) - (int) (along * 14 + 2 * Math.sin(along * 5))
                        + (walker == 0 ? side : 0);
                int texture = walker == 0 ? (side > 0 ? 1 : 0) : 2;
                p.blit(NS, OVERLAY + "print_" + texture + ".png", x + (cx - 20) * px, y + (cy - 20) * px, 40 * px, 40 * px, 0,
                        0, 40, 40, 40, 40, alpha(a, 0xFFFFFF));
            }
        }
    }

    /**
     * A meadow at night with fireflies over it, more the more of them there are: each glows up and dims again over a
     * few seconds, flickering as it goes, and drifts about where it is.
     */
    private static void fireflies(Painter p, int x, int y, int b, int px, double t, float count) {
        int w = b * WIDE, h = b * HIGH, texels = HIGH * TEX;
        for (int j = 0; j < texels; j++) {
            p.fill(x, y + j * px, x + w, y + (j + 1) * px, Widgets.mix(0xFF0B1230, 0xFF26305A, j / (float) texels));
        }
        int ground = y + h - 3 * px;
        p.fill(x, ground, x + w, y + h, 0xFF16240F);
        for (int k = 0; k < 7; k++) {
            int left = x + (k * (WIDE * TEX - 12) / 6) * px - 2 * px;
            p.blit("minecraft", "textures/block/short_grass.png", left, ground - 13 * px, TEX * px, TEX * px, 0, 0, TEX, TEX,
                    TEX, TEX, 0xFF2C4A26);
        }
        for (int k = 0; k < Math.round(12 * count); k++) {
            int hsh = hash(k + 31);
            double life = 3 + (hsh >>> 4 & 0xFF) / 255.0 * 3, phase = (hsh >>> 12 & 0xFF) / 255.0;
            double age = (t / life + phase) % 1;
            float glow = (float) Math.sin(Math.PI * age) * (0.75f + 0.25f * (float) Math.sin(t * 9 + k));
            double fx = Math.floorMod(hsh, WIDE * TEX - 4) + 2 + 2.5 * Math.sin(t * 0.7 + k);
            double fy = 3 + Math.floorMod(hsh >>> 20, texels - 9) + 1.5 * Math.sin(t * 0.9 + k * 2);
            int cx = x + (int) fx * px, cy = y + (int) fy * px;
            // A soft cross of light round a bright speck, no square of it
            int halo = alpha(0.3f * glow, 0xC8FF70);
            p.fill(cx - px, cy, cx, cy + px, halo);
            p.fill(cx + px, cy, cx + 2 * px, cy + px, halo);
            p.fill(cx, cy - px, cx + px, cy, halo);
            p.fill(cx, cy + px, cx + px, cy + 2 * px, halo);
            p.fill(cx, cy, cx + px, cy + px, alpha(glow, 0xF4FF9A));
        }
    }

    /**
     * Which biomes fog their windows: per biome, by day, at night and in rain, a little pane as fogged as it would be
     * with the climate settings as they are.
     */
    private void climate(Painter p, int x, int y, int w, int h, ResiduumConfig c) {
        int rows = BIOMES.length + 1, rowH = Math.max(10, h / rows), cell = Math.min(rowH - 2, 14);
        String[] heads = {"residuum.preview.day", "residuum.preview.night", "residuum.preview.rain"};
        int column = cell + 4;
        for (String head : heads) {
            column = Math.max(column, Widgets.font().width(Component.translatable(head)) + 4);
        }
        int nameW = w - 3 * column;
        // The column the setting shown changes stands out
        int lit = key.equals("night_penalty") ? 1 : key.equals("rain_penalty") ? 2 : -1;
        for (int k = 0; k < 3; k++) {
            p.text(Component.translatable(heads[k]), x + nameW + k * column, y + 2, k == lit ? Widgets.ACCENT : Widgets.SUB, false);
        }
        for (int i = 0; i < BIOMES.length; i++) {
            int ry = y + (i + 1) * rowH;
            p.text(Component.literal(Widgets.fit(Component.translatable("biome.minecraft." + BIOMES[i]).getString(), nameW - 4)),
                    x + 2, ry + (cell - 8) / 2, Widgets.TEXT, false);
            for (int k = 0; k < 3; k++) {
                float temp = BIOME_TEMPS[i] - (k == 1 ? (float) c.climate.nightPenalty : 0)
                        - (k == 2 && !BIOMES[i].equals("desert") ? (float) c.climate.rainPenalty : 0);
                float cold = Math.clamp(((float) c.climate.comfortTemp - temp) / (float) c.climate.comfortTemp, 0f, 1f);
                int cx = x + nameW + k * column;
                p.fill(cx, ry, cx + cell, ry + cell, k == 1 ? 0xFF2A3550 : SKY);
                block(p, "glass", cx, ry, cell, 0xFFFFFFFF);
                if (c.glassOn() && c.condensation.enabled && cold > 0) {
                    p.blit(NS, OVERLAY + "condensation_0_15.png", cx, ry, cell, cell, 0, 0, TEX, TEX, TEX, TEX,
                            alpha(cold, 0xFFFFFF));
                    p.fill(cx, ry, cx + cell, ry + cell, alpha(0.75f * cold, 0xF2F6FA));
                }
            }
        }
    }

    /** A room from above, the window in its wall: how far into it heat (a furnace, a fire) is felt to warm the glass. */
    private static void heat(Painter p, int x, int y, int w, int h, ResiduumConfig c) {
        int r = c.climate.heatScanRadius, span = 2 * 12 + 1;
        int cell = Math.max(2, Math.min((w - 4) / span, (h - 14) / span));
        int ox = x + (w - cell * span) / 2, oy = y + 12;
        int mid = 12;
        p.text(Component.literal(Settings.Unit.BLOCKS.format(r, 1)), x + 2, y + 1, Widgets.ACCENT, false);
        for (int i = 0; i < span; i++) {
            for (int j = 0; j < span; j++) {
                boolean in = Math.hypot(i - mid, j - mid) <= r;
                int colour = i == 0 ? 0xFF9DBFE8 : in ? 0xFF3A2A20 : 0xFF232830;
                p.fill(ox + i * cell, oy + j * cell, ox + (i + 1) * cell - 1, oy + (j + 1) * cell - 1, colour);
            }
        }
        int[][] heaters = {{5, 9}, {9, 18}, {16, 6}, {20, 15}};
        for (int[] at : heaters) {
            boolean in = Math.hypot(at[0] - mid, at[1] - mid) <= r;
            p.blit("minecraft", "textures/block/furnace_front_on.png", ox + at[0] * cell - cell, oy + at[1] * cell - cell,
                    cell * 3, cell * 3, 0, 0, TEX, TEX, TEX, TEX, in ? 0xFFFFFFFF : 0x60FFFFFF);
        }
        p.fill(ox + mid * cell - 1, oy + mid * cell - 1, ox + (mid + 1) * cell + 1, oy + (mid + 1) * cell + 1, Widgets.ACCENT);
    }

    /** You in the middle of a map, the windows round you, those within reach lit: how far windows are found or drawn. */
    private void radius(Painter p, int x, int y, int w, int h, ResiduumConfig c) {
        boolean scan = "scan_radius".equals(key);
        double max = scan ? 48 : 512, value = scan ? c.scan.radius : c.render.distance;
        double reach = value <= 0 ? max : value;
        int size = Math.min(w - 4, h - 14), ox = x + (w - size) / 2, oy = y + 12;
        p.text(Component.literal(scan ? Settings.Unit.BLOCKS.format(value, 1) : Settings.Unit.DISTANCE.format(value, 1)),
                x + 2, y + 1, Widgets.ACCENT, false);
        p.fill(ox, oy, ox + size, oy + size, 0xFF1F2A22);
        float scale = (float) (size / 2.0 / max);
        int cx = ox + size / 2, cy = oy + size / 2;
        // The reach, a disc drawn a row at a time
        int rr = (int) Math.min(size / 2.0, reach * scale);
        for (int dy = -rr; dy <= rr; dy++) {
            int half = (int) Math.sqrt((double) rr * rr - (double) dy * dy);
            p.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, 0xFF2E4A36);
        }
        for (int k = 0; k < 60; k++) {
            int hsh = hash(k + 77);
            int wx = ox + 2 + Math.floorMod(hsh, size - 4), wy = oy + 2 + Math.floorMod(hsh >>> 9, size - 4);
            boolean in = Math.hypot(wx - cx, wy - cy) <= rr;
            p.fill(wx - 1, wy - 1, wx + 1, wy + 1, in ? 0xFFBFE3FF : 0xFF4A5560);
        }
        p.fill(cx - 2, cy - 2, cx + 2, cy + 2, Widgets.ACCENT);
    }

    /** A bar of how much of its range a count is set to, and the count. */
    private void count(Painter p, int x, int y, int w, int h, ResiduumConfig c) {
        boolean budget = "scan_budget".equals(key);
        Settings.Slider s = (Settings.Slider) Settings.entry(budget ? "scan_budget" : "tracked_windows");
        double value = s.get().applyAsDouble(c);
        p.text(Component.literal(s.format(value)), x + 2, y + 1, Widgets.ACCENT, false);
        int cells = 24, cell = Math.max(3, (w - 4) / cells);
        float share = (float) ((value - s.min()) / (s.max() - s.min()));
        for (int k = 0; k < cells; k++) {
            int cx = x + 2 + k * cell;
            int top = y + 14 + Math.round((h - 20) * (1 - (k + 1) / (float) cells));
            p.fill(cx, top, cx + cell - 1, y + h - 4, k < Math.ceil(share * cells) ? Widgets.ACCENT : 0xFF2C3440);
        }
    }
}
