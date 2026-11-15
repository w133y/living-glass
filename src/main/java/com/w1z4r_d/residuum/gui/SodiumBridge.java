package com.w1z4r_d.residuum.gui;

import com.w1z4r_d.residuum.ResiduumClient;

/**
 * Tells Sodium's settings screen, when there is one, to read its values afresh. Goes through reflection: those classes
 * are Sodium's own and differ between its versions, and some versions this mod runs on have no Sodium to link against.
 */
final class SodiumBridge {
    private SodiumBridge() {
    }

    /**
     * Our screen changed settings that Sodium's screen under it shows too: it shows them as they are now instead of as
     * they were when it opened. Not while Sodium's screen has changes of its own waiting for Apply, which this would
     * throw away; its Apply writes only what was changed on it anyway.
     */
    static void refresh() {
        try {
            Object config = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager").getField("CONFIG").get(null);
            if (config != null && !(Boolean) config.getClass().getMethod("anyOptionChanged").invoke(config)) {
                config.getClass().getMethod("resetAllOptionsFromBindings").invoke(config);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ResiduumClient.LOGGER.debug("No Sodium settings to refresh", e);
        }
    }
}
