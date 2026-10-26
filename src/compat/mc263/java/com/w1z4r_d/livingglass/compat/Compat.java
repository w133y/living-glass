package com.w1z4r_d.livingglass.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** API differences between Minecraft versions (mc263). */
public final class Compat {
    private static final Map<String, RenderType> RENDER_TYPES = new HashMap<>();
    private static final Map<String, KeyMapping.Category> CATEGORIES = new HashMap<>();

    private Compat() {
    }

    public static KeyMapping registerKey(String name, int key, String namespace, String category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, key,
                category(namespace, category)));
    }

    /** A category can only be registered once, but several keys share it. */
    private static KeyMapping.Category category(String namespace, String path) {
        return CATEGORIES.computeIfAbsent(namespace + ":" + path,
                k -> KeyMapping.Category.register(Identifier.fromNamespaceAndPath(namespace, path)));
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.gui.setScreen(screen);
    }

    public static void onLevelRender(BiConsumer<PoseStack, SubmitNodeCollector> callback) {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> callback.accept(context.poseStack(), context.submitNodeCollector()));
    }

    /**
     * Submits translucent quads textured with assets/namespace/path. Unlit: no depth write and no lightmap (shade
     * baked into vertex colour). Lit: lightmapped, for shader packs that would make the unlit type glow.
     */
    public static void submitTranslucent(SubmitNodeCollector collector, PoseStack poseStack, String namespace, String path,
                                         boolean lit, SubmitNodeCollector.CustomGeometryRenderer renderer) {
        RenderType type = RENDER_TYPES.computeIfAbsent((lit ? "lit:" : "") + namespace + ":" + path, k -> lit
                ? RenderTypes.entityTranslucent(Identifier.fromNamespaceAndPath(namespace, path))
                : RenderTypes.entityTranslucentEmissive(Identifier.fromNamespaceAndPath(namespace, path)));
        collector.submitCustomGeometry(poseStack, type, renderer);
    }

    public static boolean isSnowball(Entity entity) {
        return entity.getType() == EntityTypes.SNOWBALL;
    }

    public static boolean isLightning(Entity entity) {
        return entity.getType() == EntityTypes.LIGHTNING_BOLT;
    }

    /**
     * Submits quads textured with assets/namespace/path drawn right on a block surface, like the vanilla block
     * cracks: depth-biased instead of lifted off the surface, lit by the lightmap, cut out where alpha is low.
     */
    public static void submitDecal(SubmitNodeCollector collector, PoseStack poseStack, String namespace, String path,
                                   SubmitNodeCollector.CustomGeometryRenderer renderer) {
        RenderType type = RENDER_TYPES.computeIfAbsent("decal:" + namespace + ":" + path,
                k -> RenderTypes.textPolygonOffset(Identifier.fromNamespaceAndPath(namespace, path)));
        collector.submitCustomGeometry(poseStack, type, renderer);
    }

    /**
     * Geometry that multiplies what is under it, like the cracks of a block being broken: the block's own texture
     * shows through, darkened or lightened by the texture's colour (mid grey changes nothing).
     */
    public static void submitMultiply(SubmitNodeCollector collector, PoseStack poseStack, String namespace, String path,
                                      SubmitNodeCollector.CustomGeometryRenderer renderer) {
        RenderType type = RENDER_TYPES.computeIfAbsent("multiply:" + namespace + ":" + path,
                k -> RenderTypes.crumbling(Identifier.fromNamespaceAndPath(namespace, path)));
        collector.submitCustomGeometry(poseStack, type, renderer);
    }

    /** Opaque cut-out geometry lit like an entity: under shaders it is drawn with the solid world, which packs reflect. */
    public static void submitSolid(SubmitNodeCollector collector, PoseStack poseStack, String namespace, String path,
                                   SubmitNodeCollector.CustomGeometryRenderer renderer) {
        RenderType type = RENDER_TYPES.computeIfAbsent("solid:" + namespace + ":" + path,
                k -> RenderTypes.entityCutout(Identifier.fromNamespaceAndPath(namespace, path)));
        collector.submitCustomGeometry(poseStack, type, renderer);
    }

    private static final Direction[] FACES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST};

    /**
     * The faces of state's block model, each as {direction ordinal, then x, y, z of its four corners} in block-local
     * coordinates: the shape the player sees, which for fences and walls is not the block's outline shape.
     */
    public static List<float[]> modelQuads(BlockState state) {
        List<float[]> quads = new ArrayList<>();
        List<BlockStateModelPart> parts = new ArrayList<>();
        Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state)
                .collectParts(RandomSource.create(42L), parts);
        for (BlockStateModelPart part : parts) {
            for (Direction cull : FACES) {
                for (BakedQuad quad : part.getQuads(cull)) {
                    float[] q = new float[13];
                    q[0] = quad.direction().ordinal();
                    for (int i = 0; i < 4; i++) {
                        Vector3fc p = quad.position(i);
                        q[1 + i * 3] = p.x();
                        q[2 + i * 3] = p.y();
                        q[3 + i * 3] = p.z();
                    }
                    quads.add(q);
                }
            }
        }
        return quads;
    }

    public static Vec3 cameraPos(Minecraft minecraft) {
        return minecraft.gameRenderer.mainCamera().position();
    }

    public static void overlayMessage(Minecraft minecraft, Component message) {
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(message);
        }
    }

    public static TagKey<Block> blockTag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(namespace, path));
    }
}
