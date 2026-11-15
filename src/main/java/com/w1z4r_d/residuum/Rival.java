package com.w1z4r_d.residuum;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.Set;

/**
 * Other mods that draw something this one does too. While one is installed, that effect of ours steps aside for it,
 * unless the player switches its "leave it to" setting off (ResiduumConfig.Leave).
 */
public enum Rival {
    // shortcut: Weather Glass is only on CurseForge, so its id is unchecked; it is also found by the name it shows
    GLASS("Weather Glass", "weatherglass", "weather_glass", "weather-glass"),
    GRASS("MC2 - Interactive Foliage", "mc2_interactivefoliage"),
    PRINTS("Soft Imprints", "softimprints");

    /** The installed mod's own name, or null when it is not installed. */
    private final String found;

    Rival(String name, String... ids) {
        Set<String> known = Set.of(ids);
        String seen = null;
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            ModMetadata m = mod.getMetadata();
            if (known.contains(m.getId()) || m.getName().equalsIgnoreCase(name)) {
                seen = m.getName();
            }
        }
        this.found = seen;
    }

    public boolean present() {
        return found != null;
    }

    /** The name the installed mod goes by, for the settings screen. */
    public String modName() {
        return found == null ? "" : found;
    }
}
