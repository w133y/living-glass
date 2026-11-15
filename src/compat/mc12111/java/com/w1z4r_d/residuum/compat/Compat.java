package com.w1z4r_d.residuum.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** API differences between Minecraft versions (mc12111). */
public final class Compat {
    private static final Map<String, RenderType> RENDER_TYPES = new HashMap<>();
    private static final Map<String, KeyMapping.Category> CATEGORIES = new HashMap<>();

    private Compat() {
    }

    public static KeyMapping registerKey(String name, int key, String namespace, String category) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, InputConstants.Type.KEYSYM, key,
                category(namespace, category)));
    }

    /** A category can only be registered once, but several keys share it. */
    private static KeyMapping.Category category(String namespace, String path) {
        return CATEGORIES.computeIfAbsent(namespace + ":" + path,
                k -> KeyMapping.Category.register(Identifier.fromNamespaceAndPath(namespace, path)));
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }

    public static void onLevelRender(BiConsumer<PoseStack, SubmitNodeCollector> callback) {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> callback.accept(context.matrices(), context.commandQueue()));
    }

    /**
     * Submits translucent quads textured with assets/namespace/path, lit by the lightmap. Dense: writing depth, so that
     * what is drawn after them behind them (clouds, weather) stays behind them; else they hide nothing, so water, stained
     * glass and the other overlays behind them still show: drawn as a banner's pattern is.
     */
    public static void submitTranslucent(SubmitNodeCollector collector, PoseStack poseStack, String namespace, String path,
                                         boolean dense, SubmitNodeCollector.CustomGeometryRenderer renderer) {
        RenderType type = RENDER_TYPES.computeIfAbsent((dense ? "dense:" : "") + namespace + ":" + path, k -> dense
                ? RenderTypes.entityTranslucent(Identifier.fromNamespaceAndPath(namespace, path))
                : RenderTypes.entityNoOutline(Identifier.fromNamespaceAndPath(namespace, path)));
        collector.submitCustomGeometry(poseStack, type, renderer);
    }

    /** Submits glowing quads in the colours of their corners, added onto what is behind them like a lightning bolt. */
    public static void submitLightning(SubmitNodeCollector collector, PoseStack poseStack,
                                       SubmitNodeCollector.CustomGeometryRenderer renderer) {
        collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), renderer);
    }

    /** Ctrl+Z, by the key's place on the keyboard as vanilla's copy and paste go (GLFW_KEY_Z). */
    public static boolean isUndo(net.minecraft.client.input.KeyEvent key) {
        return key.input() == 90 && key.hasControlDown() && !key.hasShiftDown();
    }

    /** Arrows of every kind and tridents. */
    public static boolean isArrow(Entity entity) {
        return entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow;
    }

    public static boolean isSnowball(Entity entity) {
        return entity.getType() == EntityType.SNOWBALL;
    }

    public static boolean isLightning(Entity entity) {
        return entity.getType() == EntityType.LIGHTNING_BOLT;
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
                k -> RenderTypes.entityCutoutNoCull(Identifier.fromNamespaceAndPath(namespace, path)));
        collector.submitCustomGeometry(poseStack, type, renderer);
    }

    private static final Direction[] FACES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST};

    private static final Map<Object, com.w1z4r_d.residuum.render.ModelFace.Mask> SPRITE_MASKS = new java.util.IdentityHashMap<>();

    /** Which pixels of a texture are opaque (its first frame), worked out once a texture. */
    private static com.w1z4r_d.residuum.render.ModelFace.Mask mask(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        return SPRITE_MASKS.computeIfAbsent(sprite, s -> {
            int w = sprite.contents().width(), h = sprite.contents().height();
            java.util.BitSet opaque = new java.util.BitSet(w * h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    opaque.set(y * w + x, !sprite.contents().isTransparent(0, x, y));
                }
            }
            return new com.w1z4r_d.residuum.render.ModelFace.Mask(w, h, opaque.cardinality() == w * h ? null : opaque);
        });
    }

    /**
     * The faces of state's block model as the player sees them, each with its corners, where on its texture they
     * fall, and the texture's see-through pixels (see ModelFace).
     */
    public static List<com.w1z4r_d.residuum.render.ModelFace> modelFaces(BlockState state) {
        List<com.w1z4r_d.residuum.render.ModelFace> faces = new ArrayList<>();
        for (BlockModelPart part : Minecraft.getInstance().getBlockRenderer().getBlockModel(state)
                .collectParts(RandomSource.create(42L))) {
            for (Direction cull : FACES) {
                for (BakedQuad quad : part.getQuads(cull)) {
                    net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = quad.sprite();
                    float[] xyz = new float[12], uv = new float[8];
                    float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
                    for (int i = 0; i < 4; i++) {
                        xyz[i * 3] = quad.position(i).x();
                        xyz[i * 3 + 1] = quad.position(i).y();
                        xyz[i * 3 + 2] = quad.position(i).z();
                        long packed = quad.packedUV(i);
                        uv[i * 2] = (net.minecraft.client.model.geom.builders.UVPair.unpackU(packed) - sprite.getU0()) / du;
                        uv[i * 2 + 1] = (net.minecraft.client.model.geom.builders.UVPair.unpackV(packed) - sprite.getV0()) / dv;
                    }
                    faces.add(new com.w1z4r_d.residuum.render.ModelFace(quad.direction(), xyz, uv, mask(sprite)));
                }
            }
        }
        return faces;
    }

    /**
     * The faces of state's block model, each as {direction ordinal, then x, y, z of its four corners} in block-local
     * coordinates: the shape the player sees, which for fences and walls is not the block's outline shape.
     */
    public static List<float[]> modelQuads(BlockState state) {
        List<float[]> quads = new ArrayList<>();
        for (BlockModelPart part : Minecraft.getInstance().getBlockRenderer().getBlockModel(state)
                .collectParts(RandomSource.create(42L))) {
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
        return minecraft.gameRenderer.getMainCamera().position();
    }

    public static void overlayMessage(Minecraft minecraft, Component message) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(message, true);
        }
    }

    public static TagKey<Block> blockTag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(namespace, path));
    }

    /** A widget that draws, each frame, whatever paint draws through the Painter it is given (the settings preview). */
    public static net.minecraft.client.gui.components.AbstractWidget canvas(int x, int y, int width, int height,
                                                                         java.util.function.Consumer<com.w1z4r_d.residuum.gui.Painter> paint) {
        PaintedWidget canvas = new PaintedWidget(x, y, width, height, Component.empty()) {
            @Override
            protected void paint(com.w1z4r_d.residuum.gui.Painter painter, int mouseX, int mouseY) {
                paint.accept(painter);
            }
        };
        canvas.active = false;
        return canvas;
    }

    /** The texture of a shield with no pattern on it. */
    public static final String SHIELD_TEXTURE = "textures/entity/shield_base_nopattern.png";

    /**
     * Wraps the models of the plants that lie down when walked through (see Trampling), so they bend as the chunk is
     * meshed.
     */
    public static void registerTrampling() {
        net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake()
                .register((model, context) -> com.w1z4r_d.residuum.render.Trampling.bends(context.state()) ? new Trampled(model) : model));
    }

    private static final class Trampled extends net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel {
        Trampled(net.minecraft.client.renderer.block.model.BlockStateModel model) {
            super(model);
        }

        @Override
        public void emitQuads(net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter emitter, net.minecraft.world.level.BlockAndTintGetter view, net.minecraft.core.BlockPos pos, BlockState state,
                              RandomSource random, java.util.function.Predicate<Direction> cullTest) {
            boolean upper = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
            int bend = com.w1z4r_d.residuum.render.Trampling.bend(pos, upper);
            if (bend == 0) {
                super.emitQuads(emitter, view, pos, state, random, cullTest);
                return;
            }
            long seed = (upper ? pos.below() : pos).asLong();
            float[] xyz = new float[3];
            emitter.pushTransform(quad -> {
                for (int i = 0; i < 4; i++) {
                    xyz[0] = quad.x(i);
                    xyz[1] = quad.y(i);
                    xyz[2] = quad.z(i);
                    com.w1z4r_d.residuum.render.Trampling.bendVertex(xyz, bend, upper, seed);
                    quad.pos(i, xyz[0], xyz[1], xyz[2]);
                }
                return true;
            });
            try {
                super.emitQuads(emitter, view, pos, state, random, cullTest);
            } finally {
                emitter.popTransform();
            }
        }

        /** Never the same as another plant's: a bent one is not straight, and which are bent changes as you walk. */
        @Override
        public Object createGeometryKey(net.minecraft.world.level.BlockAndTintGetter view, net.minecraft.core.BlockPos pos, BlockState state, RandomSource random) {
            return null;
        }
    }
}
