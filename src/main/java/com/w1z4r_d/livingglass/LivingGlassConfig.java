package com.w1z4r_d.livingglass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Mod settings, stored in config/livingglass.json. */
public final class LivingGlassConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("livingglass.json");
    private static LivingGlassConfig instance;

    public static final class Toggle {
        public boolean enabled = true;
    }

    public static final class Condensation {
        public boolean enabled = true;
        public double strength = 1.0;
    }

    public static final class Scan {
        public int radius = 24;
        public int budgetPerTick = 512;
    }

    public static final class Render {
        public int radius = 32;
    }

    public static final class Tracked {
        public int max = 2048;
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
        /** How long a snowball splat stays before it is gone. */
        public double snowSeconds = 20;
        /** How long the burn mark of a lightning strike stays on the ground. */
        public double lightningSeconds = 180;
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
    public Toggle ash = new Toggle();
    public Toggle snowballs = new Toggle();
    public Toggle lightning = new Toggle();
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

    public static LivingGlassConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static LivingGlassConfig load() {
        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                LivingGlassConfig loaded = GSON.fromJson(reader, LivingGlassConfig.class);
                if (loaded != null) {
                    loaded.sanitize();
                    return loaded;
                }
            } catch (Exception e) {
                LivingGlassClient.LOGGER.warn("Failed to read config, using defaults", e);
            }
        }
        LivingGlassConfig fresh = new LivingGlassConfig();
        fresh.save();
        return fresh;
    }

    /** Fills sections missing from an older or hand-edited file and clamps values. */
    private void sanitize() {
        if (condensation == null) condensation = new Condensation();
        if (frost == null) frost = new Toggle();
        if (ice == null) ice = new Toggle();
        if (ash == null) ash = new Toggle();
        if (snowballs == null) snowballs = new Toggle();
        if (lightning == null) lightning = new Toggle();
        if (raindrops == null) raindrops = new Toggle();
        if (breathSpot == null) breathSpot = new Toggle();
        if (wipe == null) wipe = new Toggle();
        if (scan == null) scan = new Scan();
        if (render == null) render = new Render();
        if (tracked == null) tracked = new Tracked();
        if (timing == null) timing = new Timing();
        if (climate == null) climate = new Climate();

        condensation.strength = Math.clamp(condensation.strength, 0.0, 2.0);
        scan.radius = Math.clamp(scan.radius, 4, 64);
        scan.budgetPerTick = Math.clamp(scan.budgetPerTick, 16, 8192);
        render.radius = Math.clamp(render.radius, 4, 96);
        tracked.max = Math.clamp(tracked.max, 64, 16384);
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
        timing.lightningSeconds = Math.max(timing.lightningSeconds, 1);
        climate.heatScanRadius = Math.clamp(climate.heatScanRadius, 1, 16);
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LivingGlassClient.LOGGER.warn("Failed to save config", e);
        }
    }
}
