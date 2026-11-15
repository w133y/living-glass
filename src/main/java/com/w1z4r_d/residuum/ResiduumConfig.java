package com.w1z4r_d.residuum;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Mod settings, stored in config/residuum.json. */
public final class ResiduumConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("residuum.json");
    /** Where the mod kept its settings as Living Glass: read once, the first time, so nobody's settings are lost. */
    private static final Path OLD_PATH = FabricLoader.getInstance().getConfigDir().resolve("livingglass.json");
    private static ResiduumConfig instance;

    public static final class Toggle {
        public boolean enabled = true;
    }

    public static final class Dust {
        public boolean enabled = true;
        /** How much dust settles, 1 the default. */
        public double strength = 1.0;
    }

    public static final class Condensation {
        public boolean enabled = true;
        public double strength = 1.0;
    }

    /** Snow driven onto walls: on or off, only in thunderstorms (blizzards), and how much, 1 the default. */
    public static final class WallSnow {
        public boolean enabled = true;
        public boolean stormsOnly = true;
        public double amount = 1.0;
        /** How much of it the walls the wind blows along get, of what the walls facing it get. */
        public double sides = 0.35;
    }

    /** Caps of snow on the tops of stairs, slabs, fences and walls: on or off, and whether they stay after the snow. */
    public static final class SnowCaps {
        public boolean enabled = true;
        public boolean stay = true;
    }

    public static final class Footprints {
        public boolean enabled = true;
        /** Mobs leave them too, not only players. */
        public boolean mobs = true;
    }

    public static final class Scan {
        public int radius = 24;
        public int budgetPerTick = 512;
    }

    public static final class Render {
        /** How far effects are drawn, in blocks; 0 is as far as the game draws. */
        public int distance = 0;
    }

    public static final class Tracked {
        public int windows = 8192;
    }

    /** Something that turns up now and then: on or off, and how often, 1 the default. */
    public static final class Occasional {
        public boolean enabled = true;
        public double frequency = 1.0;
    }

    public static final class Timing {
        public double fogBuildSeconds = 40;
        public double fogClearSeconds = 25;
        public double frostBuildSeconds = 90;
        public double frostMeltSeconds = 30;
        public double dryingSeconds = 20;
        /** How long a window face melts into a new frame texture after a neighbouring window is built or broken. */
        public double meltSeconds = 40;
        /** Outside ice build-up, and the faster build-up when the glass is fogged from inside. */
        public double iceBuildSeconds = 180;
        public double iceFoggedSeconds = 90;
        /** Ash under or behind a fire, ash on blocks next to it, and how long ash takes to fade once the fire is out. */
        public double ashBuildSeconds = 10;
        public double ashSideBuildSeconds = 60;
        public double ashFadeSeconds = 120;
        /** How long desert dust takes to settle on a window, from clear glass to fully covered. */
        public double dustBuildSeconds = 360;
        /** How long a snowball splat stays before it is gone. */
        public double snowSeconds = 20;
        /** How long the burn mark of a lightning strike stays on the ground. */
        public double lightningSeconds = 180;
        /** How long the soot an explosion leaves stays before it starts to fade. */
        public double blastSeconds = 240;
        /** How long the crack an arrow or trident leaves in glass stays. */
        public double crackSeconds = 180;
        /** How long a footprint in snow or sand stays. */
        public double footprintSeconds = 60;
        /** How long snow takes to build up on a wall and to fall away again, a cap to build up, and hoarfrost to settle. */
        public double wallSnowBuildSeconds = 900;
        public double wallSnowMeltSeconds = 900;
        public double capBuildSeconds = 900;
        public double hoarfrostBuildSeconds = 540;
        /** For testing: how many times faster hoarfrost, snow on walls and caps of snow come and go. */
        public double coatSpeed = 1;
    }

    /**
     * For each effect another installed mod draws too (see Rival), whether to leave it to that mod: on by default,
     * so the two do not draw it twice.
     */
    public static final class Leave {
        public boolean glass = true;
        public boolean grass = true;
        public boolean prints = true;
    }

    public static final class Climate {
        public double comfortTemp = 0.8;
        public double nightPenalty = 0.15;
        public double rainPenalty = 0.10;
        public int heatScanRadius = 6;
    }

    /** Master switch, also flipped by the toggle keybind. */
    public boolean enabled = true;
    public Condensation condensation = new Condensation();
    public Toggle frost = new Toggle();
    public Toggle ice = new Toggle();
    public Dust sand = new Dust();
    /** Glass in a roof or ceiling counts as a window. */
    public Toggle skylights = new Toggle();
    public Toggle torchSoot = new Toggle();
    public Toggle shieldScratches = new Toggle();
    public Occasional tumbleweeds = new Occasional();
    /** Grass and ferns players walk through lie down. */
    public Toggle trampling = new Toggle();
    /** Fireflies over grass at night, and how many, 1 the default. */
    public Occasional fireflies = new Occasional();
    public Footprints footprints = new Footprints();
    public Toggle cracks = new Toggle();
    /** An arrow pulled out of a block leaves a hole in it. */
    public Toggle arrowHoles = new Toggle();
    /** Hoarfrost on grass and leaves on frosty nights. */
    public Toggle hoarfrost = new Toggle();
    /** Snow driven onto walls that face the wind while it snows. */
    public WallSnow wallSnow = new WallSnow();
    public SnowCaps snowCaps = new SnowCaps();
    /** Steam off water heated by lava or magma close by. */
    public Toggle steam = new Toggle();
    public Toggle ash = new Toggle();
    public Toggle snowballs = new Toggle();
    public Toggle lightning = new Toggle();
    public Toggle explosions = new Toggle();
    public Toggle raindrops = new Toggle();
    public Toggle breathSpot = new Toggle();
    public Toggle wipe = new Toggle();
    public boolean tintStainedGlass = true;
    /** Window face outlines plus timings and values in the action bar. */
    public boolean debug = false;

    public Scan scan = new Scan();
    public Render render = new Render();
    public Tracked tracked = new Tracked();
    public Timing timing = new Timing();
    public Climate climate = new Climate();
    public Leave leave = new Leave();

    /** Whether the effects on windows are drawn: the mod is on and no other mod (Weather Glass) was left them. */
    public boolean glassOn() {
        return enabled && !(Rival.GLASS.present() && leave.glass);
    }

    /** Whether grass is trampled here rather than left to another mod (Interactive Foliage). */
    public boolean grassOn() {
        return enabled && trampling.enabled && !(Rival.GRASS.present() && leave.grass);
    }

    /** Whether footprints are left here rather than left to another mod (Soft Imprints). */
    public boolean printsOn() {
        return enabled && footprints.enabled && !(Rival.PRINTS.present() && leave.prints);
    }

    public static ResiduumConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static ResiduumConfig load() {
        Path from = Files.exists(PATH) ? PATH : OLD_PATH;
        if (Files.exists(from)) {
            try (Reader reader = Files.newBufferedReader(from)) {
                ResiduumConfig loaded = GSON.fromJson(reader, ResiduumConfig.class);
                if (loaded != null) {
                    loaded.sanitize();
                    if (from == OLD_PATH) {
                        loaded.save(); // the old file stays as it was, should anyone go back to Living Glass
                    }
                    return loaded;
                }
            } catch (Exception e) {
                ResiduumClient.LOGGER.warn("Failed to read config, using defaults", e);
            }
        }
        ResiduumConfig fresh = new ResiduumConfig();
        fresh.save();
        return fresh;
    }

    /** Fills sections missing from an older or hand-edited file and clamps values. */
    private void sanitize() {
        if (condensation == null) condensation = new Condensation();
        if (frost == null) frost = new Toggle();
        if (ice == null) ice = new Toggle();
        if (sand == null) sand = new Dust();
        if (skylights == null) skylights = new Toggle();
        if (torchSoot == null) torchSoot = new Toggle();
        if (shieldScratches == null) shieldScratches = new Toggle();
        if (tumbleweeds == null) tumbleweeds = new Occasional();
        if (trampling == null) trampling = new Toggle();
        if (fireflies == null) fireflies = new Occasional();
        if (footprints == null) footprints = new Footprints();
        if (cracks == null) cracks = new Toggle();
        if (arrowHoles == null) arrowHoles = new Toggle();
        if (hoarfrost == null) hoarfrost = new Toggle();
        if (wallSnow == null) wallSnow = new WallSnow();
        if (snowCaps == null) snowCaps = new SnowCaps();
        if (steam == null) steam = new Toggle();
        if (ash == null) ash = new Toggle();
        if (snowballs == null) snowballs = new Toggle();
        if (lightning == null) lightning = new Toggle();
        if (explosions == null) explosions = new Toggle();
        if (raindrops == null) raindrops = new Toggle();
        if (breathSpot == null) breathSpot = new Toggle();
        if (wipe == null) wipe = new Toggle();
        if (scan == null) scan = new Scan();
        if (render == null) render = new Render();
        if (tracked == null) tracked = new Tracked();
        if (timing == null) timing = new Timing();
        if (climate == null) climate = new Climate();
        if (leave == null) leave = new Leave();

        condensation.strength = Math.clamp(condensation.strength, 0.0, 2.0);
        scan.radius = Math.clamp(scan.radius, 4, 64);
        scan.budgetPerTick = Math.clamp(scan.budgetPerTick, 16, 8192);
        render.distance = Math.clamp(render.distance, 0, 512);
        tracked.windows = Math.clamp(tracked.windows, 64, 16384);
        tumbleweeds.frequency = Math.clamp(tumbleweeds.frequency, 0.25, 4.0);
        fireflies.frequency = Math.clamp(fireflies.frequency, 0.25, 4.0);
        timing.fogBuildSeconds = Math.max(timing.fogBuildSeconds, 0.5);
        timing.fogClearSeconds = Math.max(timing.fogClearSeconds, 0.5);
        timing.frostBuildSeconds = Math.max(timing.frostBuildSeconds, 0.5);
        timing.frostMeltSeconds = Math.max(timing.frostMeltSeconds, 0.5);
        timing.dryingSeconds = Math.max(timing.dryingSeconds, 0.0);
        timing.meltSeconds = Math.max(timing.meltSeconds, 0.5);
        timing.iceBuildSeconds = Math.max(timing.iceBuildSeconds, 0.5);
        timing.iceFoggedSeconds = Math.max(timing.iceFoggedSeconds, 0.5);
        timing.ashBuildSeconds = Math.max(timing.ashBuildSeconds, 0.5);
        timing.ashSideBuildSeconds = Math.max(timing.ashSideBuildSeconds, 0.5);
        timing.ashFadeSeconds = Math.max(timing.ashFadeSeconds, 0.5);
        timing.snowSeconds = Math.max(timing.snowSeconds, 1);
        timing.dustBuildSeconds = Math.max(timing.dustBuildSeconds, 0.5);
        sand.strength = Math.clamp(sand.strength, 0.0, 2.0);
        timing.lightningSeconds = Math.max(timing.lightningSeconds, 1);
        timing.blastSeconds = Math.max(timing.blastSeconds, 1);
        timing.crackSeconds = Math.max(timing.crackSeconds, 1);
        timing.footprintSeconds = Math.max(timing.footprintSeconds, 1);
        timing.coatSpeed = Math.clamp(timing.coatSpeed, 1, 60);
        wallSnow.amount = Math.clamp(wallSnow.amount, 0.25, 2.0);
        wallSnow.sides = Math.clamp(wallSnow.sides, 0.0, 1.0);
        timing.wallSnowBuildSeconds = Math.max(timing.wallSnowBuildSeconds, 1);
        timing.wallSnowMeltSeconds = Math.max(timing.wallSnowMeltSeconds, 1);
        timing.capBuildSeconds = Math.max(timing.capBuildSeconds, 1);
        timing.hoarfrostBuildSeconds = Math.max(timing.hoarfrostBuildSeconds, 1);
        climate.heatScanRadius = Math.clamp(climate.heatScanRadius, 1, 16);
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            ResiduumClient.LOGGER.warn("Failed to save config", e);
        }
    }
}
