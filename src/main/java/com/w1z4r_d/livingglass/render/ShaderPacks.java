package com.w1z4r_d.livingglass.render;

import com.w1z4r_d.livingglass.LivingGlassClient;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/** Whether an Iris shader pack is active, looked up through Iris's public API without depending on it. */
final class ShaderPacks {
    private static final Object API;
    private static final Method IN_USE;

    static {
        Object api = null;
        Method inUse = null;
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            try {
                Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = type.getMethod("getInstance").invoke(null);
                inUse = type.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError e) {
                LivingGlassClient.LOGGER.warn("Iris is loaded but its API could not be reached; overlays may glow with shaders", e);
            }
        }
        API = api;
        IN_USE = inUse;
    }

    private ShaderPacks() {
    }

    static boolean inUse() {
        if (IN_USE == null) {
            return false;
        }
        try {
            return (boolean) IN_USE.invoke(API);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
