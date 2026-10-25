package com.w1z4r_d.livingglass.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.w1z4r_d.livingglass.LivingGlassClient;
import com.w1z4r_d.livingglass.LivingGlassConfig;
import com.w1z4r_d.livingglass.compat.Compat;
import com.w1z4r_d.livingglass.glass.GlassState;
import com.w1z4r_d.livingglass.glass.GlassTracker;
import com.w1z4r_d.livingglass.glass.Melt;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * Draws fog, frost and raindrops as separate quads just inside the glass surface.
 * Chunk meshes and block models are never touched.
 */
public final class OverlayRenderer {
    private static final String TEX = "textures/overlay/";
    /** Running drops and clinging beads per wet face. */
    private static final int RUNNING_DROPS = 6;
    private static final int BEADS = 7;
    private static final float PIXEL = 1 / 16f;
    /** Opacity of a running drop's pixels from the head upward along its trail. */
    private static final float[] DROP_TRAIL = {0.9f, 0.6f, 0.35f, 0.15f};
    /** Cool blue-white so drops read as water, not as grey or yellow smudges. */
    private static final int DROP_RGB = 0xC4DCF5;
    /** Drops lying on a top face, and how many splash at once and how often each lands, in seconds. */
    private static final int TOP_BEADS = 6;
    private static final int SPLASHES = 4;
    private static final double SPLASH_CYCLE = 1.3;
    /** Overlays sit just inside the glass, so the glass texture always draws on top of them. */
    private static final float INSET = 0.001f;
    /** Without shaders the emissive render type ignores the lightmap; brightness is baked into vertex colours instead. */
    static final int FULL_BRIGHT = 0xF000F0;
    /**
     * A face with breath or wipe patches (or melting into a new texture) is split into one cell per texture pixel,
     * each with a flat alpha, so patches have pixel edges like the textures.
     */
    static final int GRID = 16;
    /**
     * Cells fainter than this are not drawn at all. Shader packs put a sky reflection on every translucent quad,
     * so nearly invisible cells at the edge of a wiped patch would light up as bright streaks.
     */
    static final float MIN_CELL_ALPHA = 0.06f;
    private static final float WIPE_RADIUS = 0.3f;
    private static final float BREATH_RADIUS = 0.35f;
    private static final float BREATH_ALPHA = 0.7f;
    /** How far neighbouring windows are followed when merging a large window into one surface. */
    private static final int MAX_RUN = 8;
    /** Light and window merging change slowly, so they are recomputed this often instead of every frame. */
    private static final int RENDER_CACHE_TICKS = 20;
    /** Floats per quad: four vertices of x, y, z, u, v, colour, then normal x, y, z and the packed light. */
    private static final int STRIDE = 28;

    /** Texture variants per window-frame mask: bit 1 left, 2 right, 4 bottom, 8 top edge is the frame. */
    private static final int MASKS = 16;
    /** Randomly different textures per mask; combined with mirroring on both axes a face picks one of 12 looks. */
    private static final int VARIANTS = 3;
    private static final FloatArrayList[] fog = lists(VARIANTS * MASKS);
    /** Frost grows in pixel stages from the frame inward; stage textures are supersets of the previous one. */
    private static final int FROST_STAGES = 4;
    private static final FloatArrayList[] frost = lists(VARIANTS * MASKS * FROST_STAGES);
    /** Ice on the outside face, grown in stages like the frost but mostly from the bottom of the window. */
    private static final FloatArrayList[] ice = lists(VARIANTS * MASKS * FROST_STAGES);
    /** How strongly an effect on one side of the glass shows through to a viewer on the other side. */
    private static final float OTHER_SIDE_ALPHA = 0.3f;
    private static final FloatArrayList drops = new FloatArrayList();
    private static final FloatArrayList debug = new FloatArrayList();

    /** Whether this frame's overlays are lit by the lightmap (shader pack active) instead of baked shading. */
    static boolean lit;

    public static int visible;
    public static double renderNanos;

    /** Alpha at a point of the face, x across and y up, both 0..1. */
    private interface AlphaField {
        float at(float x, float y);
    }

    private OverlayRenderer() {
    }

    private static FloatArrayList[] lists(int n) {
        FloatArrayList[] out = new FloatArrayList[n];
        for (int i = 0; i < n; i++) {
            out[i] = new FloatArrayList();
        }
        return out;
    }

    public static void render(PoseStack poseStack, SubmitNodeCollector collector) {
        Minecraft mc = Minecraft.getInstance();
        LivingGlassConfig cfg = LivingGlassConfig.get();
        Level level = mc.level;
        Entity viewer = mc.getCameraEntity();
        if (!cfg.enabled || level == null || viewer == null) {
            return;
        }
        long start = System.nanoTime();
        // Quads are submitted now but drawn later in this frame, so lists are cleared at the start of the next one
        for (FloatArrayList list : fog) {
            list.clear();
        }
        for (FloatArrayList list : frost) {
            list.clear();
        }
        for (FloatArrayList list : ice) {
            list.clear();
        }
        drops.clear();
        debug.clear();

        Long2ObjectOpenHashMap<GlassState> states = GlassTracker.INSTANCE.states();
        Vec3 cam = Compat.cameraPos(mc);
        Vec3 look = viewer.getViewVector(1.0f);
        double maxDist = cfg.render.radius * (double) cfg.render.radius;
        // Real time keeps the drops sliding smoothly between game ticks
        double seconds = (System.nanoTime() / 1_000_000L % 100_000_000L) / 1000.0;
        long now = level.getGameTime();
        // With a shader pack the unlit type glows, so let the pack light the overlays from the real light levels
        lit = ShaderPacks.inUse();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int count = 0;

        for (GlassState gs : states.values()) {
            p.set(gs.pos);
            double dx = p.getX() + 0.5 - cam.x;
            double dy = p.getY() + 0.5 - cam.y;
            double dz = p.getZ() + 0.5 - cam.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            // ponytail: view-cone cull instead of the real frustum; switch to the frustum if FOV mods cause pop-in
            if (distSq > maxDist || (distSq > 4 && dx * look.x + dy * look.y + dz * look.z < -0.3 * Math.sqrt(distSq))) {
                continue;
            }
            count++;
            if (gs.renderTime < 0 || now - gs.renderTime >= RENDER_CACHE_TICKS) {
                refreshCache(level, states, gs, p, now);
            }

            if (gs.isWindow()) {
                float shade = lit ? 1 : gs.shadeInner;
                int light = lit ? gs.lightInner : FULL_BRIGHT;
                boolean patches = gs.patches;
                Direction in = gs.inner;
                int below = gs.below, above = gs.above, left = gs.left, right = gs.right;
                float seen = seenFrom(cam, p, in, gs);
                // Each face picks a texture variant and mirroring, so droplets and crystals never repeat in a grid;
                // mirroring swaps which sides of the texture are the frame
                int style = faceStyle(gs, in);
                boolean flipU = (style & 4) != 0, flipV = (style & 8) != 0;
                float u0 = flipU ? 1 : 0, u1 = flipU ? 0 : 1, v0 = flipV ? 1 : 0, v1 = flipV ? 0 : 1;
                // The window's frame moved (a neighbour was built or broken): melt from what is shown now
                gs.look.show((style & 3) * MASKS + frameMask(flipU ? right : left, flipU ? left : right,
                        flipV ? above : below, flipV ? below : above));
                int cells = patches || gs.look.melting() ? GRID : 1;
                if (cfg.condensation.enabled && (gs.fog > 0.004f || patches)) {
                    int rgb = cfg.tintStainedGlass ? mix(gs.tint, 0.25f) : 0xFFFFFF;
                    float a = Math.min(1, gs.fog);
                    // Fill from the bottom of the whole window, not of each block
                    float height = below + 1 + above;
                    faceMelt(fog[gs.look.tex], gs.look.melting() ? fog[gs.look.prevTex] : null, gs.look, p, in, gs, 0, 1,
                            cam, u0, v0, u1, v1, cells, rgb, shade, light, (x, y) -> {
                        float h = (below + y) / height;
                        // The denser rim along the frame is baked into the texture variant
                        return seen * (a * (1 - h * (1 - a)) * (1 - wiped(gs, in, x, y)) + BREATH_ALPHA * breathed(gs, x, y));
                    });
                }
                if (cfg.frost.enabled && gs.frost > 0.004f) {
                    stages(frost, gs.look, gs.frost, p, in, gs, cam, u0, v0, u1, v1, cells, shade, light,
                            (x, y) -> seen * (1 - wiped(gs, in, x, y)));
                }
                if (gs.roomMask != 0) {
                    roomSides(gs, p, cam, cfg, shade, light, below, above);
                }
                if (cfg.ice.enabled && gs.ice > 0.004f) {
                    outsideIce(gs, p, cam, below, above, left, right);
                }
                if (cfg.debug) {
                    face(debug, p, gs.inner, gs, 0, 1, cam, 0, 0, 1, 1, 1, 0x00FF00, 1, FULL_BRIGHT, (x, y) -> 0.375f);
                }
            }

            if (gs.rainTop && cfg.raindrops.enabled && gs.drops > 0.004f) {
                topDrops(p, gs, cam, Math.min(1, gs.drops), lit ? 1 : gs.shadeTop, lit ? gs.lightTop : FULL_BRIGHT, seconds);
            }
            if (gs.rainMask != 0 && (cfg.debug || (cfg.raindrops.enabled && gs.drops > 0.004f))) {
                float a = Math.min(1, gs.drops);
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if ((gs.rainMask & (1 << dir.get2DDataValue())) == 0) {
                        continue;
                    }
                    if (cfg.raindrops.enabled && gs.drops > 0.004f) {
                        int i = dir.get2DDataValue();
                        raindrops(p, dir, gs, gs.rainMin[i], gs.rainMax[i], cam, a, lit ? 1 : gs.shadeSide[i],
                                lit ? gs.lightSide[i] : FULL_BRIGHT, seconds);
                    }
                    if (cfg.debug) {
                        face(debug, p, dir, gs, 0, 1, cam, 0, 0, 1, 1, 1, 0x0060FF, 1, FULL_BRIGHT, (x, y) -> 0.375f);
                    }
                }
            }
        }

        for (int variant = 0; variant < VARIANTS; variant++) {
            for (int mask = 0; mask < MASKS; mask++) {
                int tex = variant * MASKS + mask;
                submit(collector, poseStack, "condensation_" + variant + "_" + mask, fog[tex]);
                for (int stage = 0; stage < FROST_STAGES; stage++) {
                    submit(collector, poseStack, "frost_" + variant + "_" + mask + "_" + stage, frost[tex * FROST_STAGES + stage]);
                    submit(collector, poseStack, "ice_" + variant + "_" + mask + "_" + stage, ice[tex * FROST_STAGES + stage]);
                }
            }
        }
        DecalRenderer.render(poseStack, collector, level, cam, cfg);
        submit(collector, poseStack, "white", drops);
        submit(collector, poseStack, "white", debug);

        visible = count;
        renderNanos = renderNanos * 0.95 + (System.nanoTime() - start) * 0.05;
    }

    /**
     * Fog and frost on the room-facing side faces (or their room-side part next to a pane). They follow the main
     * face's amounts; the texture is cropped to the span so its frame edge stays on the room side.
     */
    private static void roomSides(GlassState gs, BlockPos p, Vec3 cam, LivingGlassConfig cfg, float shade, int light,
                                  int below, int above) {
        float height = below + 1 + above;
        float a = Math.min(1, gs.fog);
        float grown = gs.frost * FROST_STAGES;
        int full = Math.min(FROST_STAGES, (int) grown);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int i = dir.get2DDataValue();
            if ((gs.roomMask & (1 << i)) == 0) {
                continue;
            }
            float min = gs.roomMin[i], max = gs.roomMax[i];
            float seen = seenFrom(cam, p, dir, gs);
            // Own look per side face, so it does not copy the main face next to it
            int style = faceStyle(gs, dir);
            boolean flipV = (style & 8) != 0;
            // A strip beside a pane joins the pane window to this block's face, so it has no frame across;
            // a whole side face sticking into the room keeps its frame on both sides
            int across = max - min < 1 ? 1 : 0;
            int tex = (style & 3) * MASKS + frameMask(across, across, flipV ? above : below, flipV ? below : above);
            float v0 = flipV ? 1 : 0, v1 = flipV ? 0 : 1;
            int cells = gs.patches ? GRID : 1;
            if (cfg.condensation.enabled && gs.fog > 0.004f) {
                int rgb = cfg.tintStainedGlass ? mix(gs.tint, 0.25f) : 0xFFFFFF;
                face(fog[tex], p, dir, gs, min, max, cam, 0, v0, 1, v1, cells, rgb, shade, light,
                        (x, y) -> seen * a * (1 - (below + y) / height * (1 - a)) * (1 - wiped(gs, dir, x, y)));
            }
            if (cfg.frost.enabled && gs.frost > 0.004f) {
                if (full > 0) {
                    face(frost[tex * FROST_STAGES + full - 1], p, dir, gs, min, max, cam, 0, v0, 1, v1, cells, 0xFFFFFF, shade,
                            light, (x, y) -> seen * (1 - wiped(gs, dir, x, y)));
                }
                if (full < FROST_STAGES) {
                    float partial = grown - full;
                    face(frost[tex * FROST_STAGES + full], p, dir, gs, min, max, cam, 0, v0, 1, v1, cells, 0xFFFFFF, shade,
                            light, (x, y) -> seen * partial * (1 - wiped(gs, dir, x, y)));
                }
            }
        }
    }

    /**
     * Stable random look of one face: bits 0-1 the texture variant (0..VARIANTS-1), bit 2 mirror across,
     * bit 3 mirror upside down.
     */
    private static int faceStyle(GlassState gs, Direction dir) {
        int h = hash(gs.seed ^ (dir.get2DDataValue() + 1) * 0x45D9F3B);
        return Math.floorMod(h >>> 4, VARIANTS) | (h & 12);
    }

    /** Frame mask from the window runs on each side of the face (0 = this side is the frame). */
    private static int frameMask(int left, int right, int below, int above) {
        return (left == 0 ? 1 : 0) | (right == 0 ? 2 : 0) | (below == 0 ? 4 : 0) | (above == 0 ? 8 : 0);
    }

    /**
     * Ice on the outside face of a window. It mirrors the inside face, so the window's left and right swap; it melts
     * into a new frame texture on its own like the inside face does.
     */
    private static void outsideIce(GlassState gs, BlockPos p, Vec3 cam, int below, int above, int left, int right) {
        Direction out = gs.inner.getOpposite();
        int style = faceStyle(gs, out);
        boolean flipU = (style & 4) != 0, flipV = (style & 8) != 0;
        float u0 = flipU ? 1 : 0, u1 = flipU ? 0 : 1, v0 = flipV ? 1 : 0, v1 = flipV ? 0 : 1;
        // Seen from outside, the room's right is on the left
        int outLeft = right, outRight = left;
        gs.iceLook.show((style & 3) * MASKS + frameMask(flipU ? outRight : outLeft, flipU ? outLeft : outRight,
                flipV ? above : below, flipV ? below : above));
        float seen = seenFrom(cam, p, out, gs);
        stages(ice, gs.iceLook, gs.ice, p, out, gs, cam, u0, v0, u1, v1, gs.iceLook.melting() ? GRID : 1,
                lit ? 1 : gs.shadeOuter, lit ? gs.lightOuter : FULL_BRIGHT, (x, y) -> seen);
    }

    /** Draws the last fully grown stage plus the next one fading in, melting between textures with look. */
    private static void stages(FloatArrayList[] lists, Melt look, float amount, BlockPos p, Direction dir, GlassState gs,
                               Vec3 cam, float u0, float v0, float u1, float v1, int cells, float shade, int light,
                               AlphaField alpha) {
        float grown = amount * FROST_STAGES;
        int full = Math.min(FROST_STAGES, (int) grown);
        if (full > 0) {
            int stage = full - 1;
            faceMelt(lists[look.tex * FROST_STAGES + stage], look.melting() ? lists[look.prevTex * FROST_STAGES + stage] : null,
                    look, p, dir, gs, 0, 1, cam, u0, v0, u1, v1, cells, 0xFFFFFF, shade, light, alpha);
        }
        if (full < FROST_STAGES) {
            float partial = grown - full;
            faceMelt(lists[look.tex * FROST_STAGES + full], look.melting() ? lists[look.prevTex * FROST_STAGES + full] : null,
                    look, p, dir, gs, 0, 1, cam, u0, v0, u1, v1, cells, 0xFFFFFF, shade, light,
                    (x, y) -> partial * alpha.at(x, y));
        }
    }

    /** 1 when the camera is on the side the face looks out to, OTHER_SIDE_ALPHA when it sees the face through the glass. */
    private static float seenFrom(Vec3 cam, BlockPos p, Direction dir, GlassState gs) {
        double half = gs.pane ? GlassState.PANE_HALF : 0.5;
        double side = (cam.x - p.getX() - 0.5) * dir.getStepX() + (cam.z - p.getZ() - 0.5) * dir.getStepZ() - half;
        return side >= 0 ? 1 : OTHER_SIDE_ALPHA;
    }

    /** Recomputes light and window merging for one glass block; staggered by seed so refreshes spread over frames. */
    private static void refreshCache(Level level, Long2ObjectOpenHashMap<GlassState> states, GlassState gs,
                                     BlockPos p, long now) {
        gs.renderTime = now + Math.floorMod(gs.seed, 10);
        if (gs.isWindow()) {
            gs.shadeInner = shade(level, p.relative(gs.inner));
            gs.lightInner = packedLight(level, p.relative(gs.inner));
            gs.shadeOuter = shade(level, p.relative(gs.inner.getOpposite()));
            gs.lightOuter = packedLight(level, p.relative(gs.inner.getOpposite()));
            // Neighbouring windows of the same room merge into one surface
            int rx = -gs.inner.getStepZ(), rz = gs.inner.getStepX();
            gs.below = run(states, gs, p, 0, -1, 0);
            gs.above = run(states, gs, p, 0, 1, 0);
            gs.left = run(states, gs, p, -rx, 0, -rz);
            gs.right = run(states, gs, p, rx, 0, rz);
        }
        if (gs.rainTop) {
            gs.shadeTop = shade(level, p.above());
            gs.lightTop = packedLight(level, p.above());
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int i = dir.get2DDataValue();
            if ((gs.rainMask & (1 << i)) != 0) {
                gs.shadeSide[i] = shade(level, p.relative(dir));
                gs.lightSide[i] = packedLight(level, p.relative(dir));
            }
        }
    }

    /**
     * Raindrops on one wet face: a few drops, one pixel wide, sliding down with a fading trail and resting
     * between runs, plus beads that cling to the glass and slowly come and go.
     */
    private static void raindrops(BlockPos pos, Direction dir, GlassState gs, float min, float max, Vec3 cam,
                                  float amount, float shade, int light, double seconds) {
        int seed = gs.seed * 31 + dir.get2DDataValue();
        if (gs.pane) {
            Direction right = dir.getClockWise();
            min = Math.max(min, gs.paneConnects(right.getOpposite()) ? 0 : 0.5f - GlassState.PANE_HALF);
            max = Math.min(max, gs.paneConnects(right) ? 1 : 0.5f + GlassState.PANE_HALF);
        }
        int columns = Math.round((max - min) * 16);
        if (columns <= 0) {
            return;
        }
        for (int k = 0; k < RUNNING_DROPS; k++) {
            int h = hash(seed + k * 0x632BE5AB);
            float x = min + (Math.floorMod(h, columns)) * PIXEL;
            float speed = 0.35f + ((h >>> 8) & 0xFF) / 255f * 0.5f; // blocks per second
            float rest = 0.4f + ((h >>> 16) & 0xFF) / 255f * 1.2f;  // pause before the next run, in blocks of travel
            float cycle = 1 + DROP_TRAIL.length * PIXEL + rest;
            float travel = (float) ((seconds * speed + ((h >>> 24) & 0xFF) / 255.0 * cycle) % cycle);
            // Snap to the texture's pixel grid: each drop steps down one pixel at a time, at its own pace
            int headRow = 15 - (int) (travel * 16);
            for (int t = 0; t < DROP_TRAIL.length; t++) {
                int row = headRow + t;
                if (row >= 0 && row < 16) {
                    int c = argb(amount * DROP_TRAIL[t], DROP_RGB, shade);
                    rect(drops, pos, dir, gs, cam, x, x + PIXEL, row * PIXEL, (row + 1) * PIXEL, c, c, light);
                }
            }
        }
        for (int k = 0; k < BEADS; k++) {
            int h = hash(seed ^ (k + 1) * 0x2545F491);
            float x = min + Math.floorMod(h, columns) * PIXEL;
            float y = ((h >>> 8) & 15) * PIXEL;
            // Beads fade in and out in a few steps, not smoothly, to match the pixel look
            float pulse = Math.round(2 + 2 * Math.sin(seconds * 0.6 + ((h >>> 16) & 0xFF))) / 4f;
            int c = argb(amount * 0.6f * pulse, DROP_RGB, shade);
            rect(drops, pos, dir, gs, cam, x, x + PIXEL, y, y + PIXEL, c, c, light);
        }
    }

    static int hash(int h) {
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        return h ^ (h >>> 16);
    }

    /** One small quad on a face, x and y in face coordinates; bottom and top colours. */
    private static void rect(FloatArrayList out, BlockPos pos, Direction dir, GlassState gs, Vec3 cam,
                             float x0, float x1, float y0, float y1, int bottom, int top, int light) {
        float nx = dir.getStepX();
        float nz = dir.getStepZ();
        float offset = (gs.pane ? GlassState.PANE_HALF : 0.5f) - INSET;
        float rx = -nz;
        float rz = nx;
        float ox = (float) (pos.getX() + 0.5 + nx * offset - rx * 0.5 - cam.x);
        float oy = (float) (pos.getY() - cam.y);
        float oz = (float) (pos.getZ() + 0.5 + nz * offset - rz * 0.5 - cam.z);
        vertex(out, ox + rx * x0, oy + y0, oz + rz * x0, bottom);
        vertex(out, ox + rx * x1, oy + y0, oz + rz * x1, bottom);
        vertex(out, ox + rx * x1, oy + y1, oz + rz * x1, top);
        vertex(out, ox + rx * x0, oy + y1, oz + rz * x0, top);
        out.add(nx);
        out.add(0f);
        out.add(nz);
        out.add(Float.intBitsToFloat(light));
    }

    /** Raindrops landing on the top face: beads that come and go, and short splashes at random pixels. */
    private static void topDrops(BlockPos pos, GlassState gs, Vec3 cam, float amount, float shade, int light, double seconds) {
        int seed = gs.seed * 31 + 7;
        for (int k = 0; k < TOP_BEADS; k++) {
            int h = hash(seed ^ (k + 1) * 0x2545F491);
            float pulse = Math.round(2 + 2 * Math.sin(seconds * 0.6 + ((h >>> 16) & 0xFF))) / 4f;
            topPixel(pos, cam, h & 15, (h >>> 4) & 15, argb(amount * 0.6f * pulse, DROP_RGB, shade), light);
        }
        for (int k = 0; k < SPLASHES; k++) {
            int h0 = hash(seed + k * 0x632BE5AB);
            double t = seconds / SPLASH_CYCLE + ((h0 >>> 8) & 0xFF) / 255.0;
            long round = (long) Math.floor(t);
            double phase = t - round;
            if (phase >= 0.3) {
                continue; // dry until the next drop lands
            }
            // A new random spot each time, fading out in two steps
            int h = hash(h0 ^ (int) round * 0x9E3779B9);
            float alpha = phase < 0.12 ? 0.9f : 0.45f;
            topPixel(pos, cam, h & 15, (h >>> 4) & 15, argb(amount * alpha, DROP_RGB, shade), light);
        }
    }

    /** One pixel on the top face of the block, column px and row pz of a 16x16 grid. */
    private static void topPixel(BlockPos pos, Vec3 cam, int px, int pz, int color, int light) {
        float x = (float) (pos.getX() + px * PIXEL - cam.x);
        float y = (float) (pos.getY() + 1 - INSET - cam.y);
        float z = (float) (pos.getZ() + pz * PIXEL - cam.z);
        vertex(drops, x, y, z, color);
        vertex(drops, x, y, z + PIXEL, color);
        vertex(drops, x + PIXEL, y, z + PIXEL, color);
        vertex(drops, x + PIXEL, y, z, color);
        drops.add(0f);
        drops.add(1f);
        drops.add(0f);
        drops.add(Float.intBitsToFloat(light));
    }

    /** One untextured vertex (the white texture is sampled at its centre). */
    private static void vertex(FloatArrayList out, float x, float y, float z, int color) {
        out.add(x);
        out.add(y);
        out.add(z);
        out.add(0.5f);
        out.add(0.5f);
        out.add(Float.intBitsToFloat(color));
    }

    /** Counts contiguous windows facing the same room, starting next to pos and stepping by (sx, sy, sz). */
    private static int run(Long2ObjectOpenHashMap<GlassState> states, GlassState gs, BlockPos pos, int sx, int sy, int sz) {
        int n = 0;
        while (n < MAX_RUN) {
            int k = n + 1;
            GlassState next = states.get(BlockPos.asLong(pos.getX() + sx * k, pos.getY() + sy * k, pos.getZ() + sz * k));
            // Panes and full glass blocks in one row of the wall form a single window
            if (next == null || next.inner != gs.inner) {
                break;
            }
            n++;
        }
        return n;
    }

    /** How much of the fog is wiped away at a point, 0..1. */
    private static float wiped(GlassState gs, Direction face, float x, float y) {
        int f = face.get2DDataValue();
        float w = 0;
        for (int i = 0; i < GlassState.MAX_WIPES; i++) {
            if (gs.wipe[i] > 0 && gs.wipeFace[i] == f) {
                w = Math.max(w, gs.wipe[i] * bump(x - gs.wipeX[i], y - gs.wipeY[i], WIPE_RADIUS));
            }
        }
        return w;
    }

    /** Fog added by breath patches at a point, 0..1. */
    private static float breathed(GlassState gs, float x, float y) {
        float b = 0;
        for (int i = 0; i < GlassState.MAX_BREATHS; i++) {
            if (gs.breath[i] > 0) {
                b = Math.max(b, gs.breath[i] * bump(x - gs.breathX[i], y - gs.breathY[i], BREATH_RADIUS));
            }
        }
        return b;
    }

    /** Soft round falloff: 1 at the centre, 0 at radius and beyond. */
    private static float bump(float dx, float dy, float radius) {
        float t = (dx * dx + dy * dy) / (radius * radius);
        return t >= 1 ? 0 : (1 - t) * (1 - t);
    }

    /** Adds the given face of the block (or pane) as cells x cells quads, slightly behind the surface. */
    private static void face(FloatArrayList out, BlockPos pos, Direction dir, GlassState gs, float spanMin, float spanMax, Vec3 cam,
                             float u0, float v0, float u1, float v1, int cells, int rgb, float shade, int light, AlphaField alpha) {
        faceMelt(out, null, null, pos, dir, gs, spanMin, spanMax, cam, u0, v0, u1, v1, cells, rgb, shade, light, alpha);
    }

    /**
     * Like face(), but while a melt is running (oldOut not null) each cell goes either to the new texture's list or
     * to the old one's, switching over in a fixed random order as melt goes from 0 to 1.
     */
    private static void faceMelt(FloatArrayList out, FloatArrayList oldOut, Melt look, BlockPos pos, Direction dir,
                                 GlassState gs, float spanMin, float spanMax, Vec3 cam, float u0, float v0, float u1,
                                 float v1, int cells, int rgb, float shade, int light, AlphaField alpha) {
        float nx = dir.getStepX();
        float nz = dir.getStepZ();
        float offset = (gs.pane ? GlassState.PANE_HALF : 0.5f) - INSET;
        // Right-hand horizontal axis of the face, see GlassState.faceX
        float rx = -nz;
        float rz = nx;
        // A pane only covers its centre post plus the arms that connect to something
        // Only the given span of the face, e.g. the part of a side face left uncovered by a pane next to it
        float xMin = spanMin, xMax = spanMax;
        if (gs.pane) {
            Direction right = dir.getClockWise();
            xMin = Math.max(xMin, gs.paneConnects(right.getOpposite()) ? 0 : 0.5f - GlassState.PANE_HALF);
            xMax = Math.min(xMax, gs.paneConnects(right) ? 1 : 0.5f + GlassState.PANE_HALF);
        }
        float ox = (float) (pos.getX() + 0.5 + nx * offset - rx * 0.5 - cam.x);
        float oy = (float) (pos.getY() - cam.y);
        float oz = (float) (pos.getZ() + 0.5 + nz * offset - rz * 0.5 - cam.z);
        float xStep = (xMax - xMin) / cells;
        float yStep = 1f / cells;
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < cells; j++) {
                float x0 = xMin + i * xStep, x1 = x0 + xStep, y0 = j * yStep, y1 = y0 + yStep;
                FloatArrayList target = out;
                if (oldOut != null) {
                    float rank = (hash(gs.seed + i * 131 + j * 7919) & 0xFFFF) / 65536f;
                    if ((look.reversed ? 1 - rank : rank) >= look.blend) {
                        target = oldOut;
                    }
                }
                int c00, c10, c11, c01;
                if (cells == 1) {
                    // Whole face: alpha varies smoothly across it (the bottom-up fog gradient)
                    c00 = argb(alpha.at(x0, y0), rgb, shade);
                    c10 = argb(alpha.at(x1, y0), rgb, shade);
                    c11 = argb(alpha.at(x1, y1), rgb, shade);
                    c01 = argb(alpha.at(x0, y1), rgb, shade);
                } else {
                    float a = alpha.at((x0 + x1) / 2, (y0 + y1) / 2);
                    if (a < MIN_CELL_ALPHA) {
                        continue;
                    }
                    c00 = c10 = c11 = c01 = argb(a, rgb, shade);
                }
                corner(target, ox, oy, oz, rx, rz, x0, y0, u0, v0, u1, v1, c00);
                corner(target, ox, oy, oz, rx, rz, x1, y0, u0, v0, u1, v1, c10);
                corner(target, ox, oy, oz, rx, rz, x1, y1, u0, v0, u1, v1, c11);
                corner(target, ox, oy, oz, rx, rz, x0, y1, u0, v0, u1, v1, c01);
                target.add(nx);
                target.add(0f);
                target.add(nz);
                target.add(Float.intBitsToFloat(light));
            }
        }
    }

    private static void corner(FloatArrayList out, float ox, float oy, float oz, float rx, float rz, float x, float y,
                               float u0, float v0, float u1, float v1, int color) {
        out.add(ox + rx * x);
        out.add(oy + y);
        out.add(oz + rz * x);
        out.add(u0 + (u1 - u0) * x);
        out.add(v1 + (v0 - v1) * y); // v grows downward in the texture
        out.add(Float.intBitsToFloat(color));
    }

    static void submit(SubmitNodeCollector collector, PoseStack poseStack, String texture, FloatArrayList quads) {
        if (quads.isEmpty()) {
            return;
        }
        Compat.submitTranslucent(collector, poseStack, LivingGlassClient.MOD_ID, TEX + texture + ".png", lit,
                (pose, consumer) -> emit(quads, pose, consumer));
    }

    static void emit(FloatArrayList q, PoseStack.Pose pose, VertexConsumer vc) {
        float[] a = q.elements();
        for (int i = 0; i + STRIDE <= q.size(); i += STRIDE) {
            float nx = a[i + 24], ny = a[i + 25], nz = a[i + 26];
            int light = Float.floatToRawIntBits(a[i + 27]);
            for (int v = i; v < i + 24; v += 6) {
                vc.addVertex(pose, a[v], a[v + 1], a[v + 2]).setColor(Float.floatToRawIntBits(a[v + 5]))
                        .setUv(a[v + 3], a[v + 4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(pose, nx, ny, nz);
            }
        }
    }

    /** Brightness 0..1 of the air block next to the face, close to what the vanilla lightmap gives. */
    static float shade(Level level, BlockPos pos) {
        // Night dims sky light to a fifth instead of removing it, as the vanilla lightmap does
        float daylight = 1 - 0.8f * level.getSkyDarken() / 11f;
        float sky = curve(level.getBrightness(LightLayer.SKY, pos) / 15f) * daylight;
        float block = curve(level.getBrightness(LightLayer.BLOCK, pos) / 15f);
        float l = Math.max(sky, block);
        // Default 50% gamma brightening plus a little ambient light
        float inv = 1 - l;
        l = 0.5f * l + 0.5f * (1 - inv * inv * inv * inv);
        return 0.08f + 0.92f * l;
    }

    /** Lightmap coordinates of a block, as vanilla packs them for rendering. */
    static int packedLight(Level level, BlockPos pos) {
        return level.getBrightness(LightLayer.SKY, pos) << 20 | level.getBrightness(LightLayer.BLOCK, pos) << 4;
    }

    /** Vanilla light level to brightness curve. */
    private static float curve(float l) {
        return l / (4 - 3 * l);
    }

    static int argb(float alpha, int rgb, float shade) {
        int r = (int) (((rgb >> 16) & 0xFF) * shade);
        int g = (int) (((rgb >> 8) & 0xFF) * shade);
        int b = (int) ((rgb & 0xFF) * shade);
        return (int) (Math.clamp(alpha, 0f, 1f) * 255) << 24 | r << 16 | g << 8 | b;
    }

    /** Blends white toward rgb by amount, for a subtle stained-glass tint. */
    private static int mix(int rgb, float amount) {
        int r = (int) (255 + (((rgb >> 16) & 0xFF) - 255) * amount);
        int g = (int) (255 + (((rgb >> 8) & 0xFF) - 255) * amount);
        int b = (int) (255 + ((rgb & 0xFF) - 255) * amount);
        return r << 16 | g << 8 | b;
    }
}
