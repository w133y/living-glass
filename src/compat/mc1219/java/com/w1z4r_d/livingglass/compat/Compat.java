package com.w1z4r_d.livingglass.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** API differences between Minecraft versions (mc1219). */
public final class Compat {
    private static final Map<String, RenderType> RENDER_TYPES = new HashMap<>();
    private static final Map<String, KeyMapping.Category> CATEGORIES = new HashMap<>();
    /** Called from LevelRendererMixin: 1.21.9 Fabric API has no world render event. */
    public static BiConsumer<PoseStack, SubmitNodeCollector> levelRenderCallback;

    private Compat() {
    }

    public static KeyMapping registerKey(String name, int key, String namespace, String category) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, InputConstants.Type.KEYSYM, key,
                category(namespace, category)));
    }

    /** A category can only be registered once, but several keys share it. */
    private static KeyMapping.Category category(String namespace, String path) {
        return CATEGORIES.computeIfAbsent(namespace + ":" + path,
                k -> KeyMapping.Category.register(ResourceLocation.fromNamespaceAndPath(namespace, path)));
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }

    public static void onLevelRender(BiConsumer<PoseStack, SubmitNodeCollector> callback) {
        levelRenderCallback = callback;
    }

    /**
     * Submits translucent quads textured with assets/namespace/path. Unlit: no depth write and no lightmap (shade
     * baked into vertex colour). Lit: lightmapped, for shader packs that would make the unlit type glow.
     */
    public static void submitTranslucent(SubmitNodeCollector collector, PoseStack poseStack, String namespace, String path,
                                         boolean lit, SubmitNodeCollector.CustomGeometryRenderer renderer) {
        RenderType type = RENDER_TYPES.computeIfAbsent((lit ? "lit:" : "") + namespace + ":" + path, k -> lit
                ? RenderType.entityTranslucent(ResourceLocation.fromNamespaceAndPath(namespace, path))
                : RenderType.entityTranslucentEmissive(ResourceLocation.fromNamespaceAndPath(namespace, path)));
        collector.submitCustomGeometry(poseStack, type, renderer);
    }

    public static Vec3 cameraPos(Minecraft minecraft) {
        return minecraft.gameRenderer.getMainCamera().getPosition();
    }

    public static void overlayMessage(Minecraft minecraft, Component message) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(message, true);
        }
    }

    public static TagKey<Block> blockTag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
