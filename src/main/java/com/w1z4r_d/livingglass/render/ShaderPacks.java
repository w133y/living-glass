package com.w1z4r_d.livingglass.render;

import com.w1z4r_d.livingglass.LivingGlassClient;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;

/** Whether an Iris shader pack is active, looked up through Iris's public API without depending on it. */
final class ShaderPacks {
    private static final Object API;
    private static final Method IN_USE;
    /**
     * Iris internals, not its API: the item id it tags geometry with for the pack, and the pack's ids of blocks. Null
     * when they could not be reached (another Iris version), and then geometry is just drawn untagged.
     */
    private static final Object RENDER_STATE, SETTINGS;
    private static final Method GET_ITEM, SET_ITEM, BLOCK_IDS;

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
        Object renderState = null, settings = null;
        Method getItem = null, setItem = null, blockIds = null;
        if (inUse != null) {
            try {
                Class<?> state = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
                Class<?> rendering = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
                renderState = state.getField("INSTANCE").get(null);
                settings = rendering.getField("INSTANCE").get(null);
                getItem = state.getMethod("getCurrentRenderedItem");
                setItem = state.getMethod("setCurrentRenderedItem", int.class);
                blockIds = rendering.getMethod("getBlockStateIds");
            } catch (ReflectiveOperationException | LinkageError e) {
                setItem = null;
            }
        }
        RENDER_STATE = renderState;
        SETTINGS = settings;
        GET_ITEM = getItem;
        SET_ITEM = setItem;
        BLOCK_IDS = blockIds;
    }

    private ShaderPacks() {
    }

    /**
     * Submits geometry as if it were block held as an item: Iris tags what is submitted meanwhile with the pack's id
     * for that block, so a pack with built-in materials (no PBR maps) gives it that block's material. Just submits
     * when the pack has no id for the block or Iris could not be reached.
     */
    @SuppressWarnings("unchecked")
    static void submitAs(BlockState block, Runnable submit) {
        Object previous = null;
        if (SET_ITEM != null) {
            try {
                Object ids = BLOCK_IDS.invoke(SETTINGS);
                if (ids instanceof Object2IntFunction<?> map && map.containsKey(block)) {
                    previous = GET_ITEM.invoke(RENDER_STATE);
                    SET_ITEM.invoke(RENDER_STATE, ((Object2IntFunction<Object>) map).getInt(block));
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                previous = null;
            }
        }
        try {
            submit.run();
        } finally {
            if (previous != null) {
                try {
                    SET_ITEM.invoke(RENDER_STATE, previous);
                } catch (ReflectiveOperationException e) {
                    // the tag stays until Iris resets it for the next item it draws
                }
            }
        }
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
