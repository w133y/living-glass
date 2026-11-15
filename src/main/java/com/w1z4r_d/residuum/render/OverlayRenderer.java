package com.w1z4r_d.residuum.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.w1z4r_d.residuum.ResiduumClient;
import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.compat.Compat;
import com.w1z4r_d.residuum.glass.GlassState;
import com.w1z4r_d.residuum.glass.GlassTracker;
import com.w1z4r_d.residuum.glass.Melt;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.IntFunction;
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
    /**
     * Mist this thick or more gathers into drops that run down the glass (see Drips), as many as MIST_CHANCE of the
     * slots for them in a full fog; the trails they wipe are this clear, fresh.
     */
    private static final float MIST_RUNS = 0.6f, MIST_CHANCE = 0.5f, MIST_CLEAR = 0.75f;
    /** The rim of a drop of mist, a little darker than the mist round it. */
    private static final int MIST_RIM = 0xB2C2D4;
    /** How clear each pixel of the face being drawn has been wiped by running drops of mist, row by row from the bottom. */
    private static final float[] CLEAR = new float[16 * 16];
    private static boolean cleared;
    /**
     * Snowflakes stuck to a face open to a snowfall: this many at a time, twice as many in a storm, each staying FLAKE_MIN
     * to FLAKE_MAX seconds before it melts and the next lands elsewhere; snow white.
     */
    private static final int FLAKES = 6;
    private static final double FLAKE_MIN = 6, FLAKE_MAX = 16;
    /** How long a flake takes to show once it lands, and to melt away at the end of its stay, in seconds. */
    private static final double FLAKE_LAND = 0.5, FLAKE_MELT = 3;
    private static final int FLAKE_RGB = 0xF4F8FF;
    /** Drops lying on a top face, and how many splash at once and how often each lands, in seconds. */
    private static final int TOP_BEADS = 6;
    private static final int SPLASHES = 4;
    private static final double SPLASH_CYCLE = 1.3;
    /**
     * Overlays sit just inside the glass, so the glass texture always draws on top of them, each layer at a depth of its
     * own so that no two share a plane and flicker against each other: drops and flakes nearest the surface,
     * the ice thicker along the frame, then frost and outside ice behind them, fog and desert dust deepest. Two stages of frost drawn at once share a plane,
     * cut into the same quads, so neither hides the other whichever is drawn first.
     */
    private static final float INSET = 0.001f, RIM_INSET = 0.0015f, FROST_INSET = 0.002f, FOG_INSET = 0.003f;
    /** The depth of the layer being drawn by faceMelt; render runs on one thread. */
    private static float inset = INSET;
    /** Without shaders overlays take the lightmap at full brightness; their brightness is baked into vertex colours. */
    static final int FULL_BRIGHT = 0xF000F0;
    /**
     * A face with breath or wipe patches (or melting into a new texture) is split into one cell per texture pixel,
     * each with a flat alpha, so patches have pixel edges like the textures.
     */
    static final int GRID = 16;
    /** Cells per face side far away (see View.DETAIL_DIST_SQ), and the cells for the window being drawn. */
    private static final int FAR_GRID = 4;
    private static int detail = GRID;
    /**
     * Cells fainter than this are not drawn at all. Shader packs put a sky reflection on every translucent quad,
     * so nearly invisible cells at the edge of a wiped patch would light up as bright streaks.
     */
    static final float MIN_CELL_ALPHA = 0.06f;
    private static final float WIPE_RADIUS = 0.3f;
    private static final float BREATH_RADIUS = 0.35f;
    private static final float BREATH_ALPHA = 0.7f;
    /** Light and window merging change slowly, so they are recomputed this often instead of every frame. */
    private static final int RENDER_CACHE_TICKS = 20;
    /** Floats per quad: four vertices of x, y, z, u, v, colour, then normal x, y, z and the packed light. */
    static final int STRIDE = 28;

    /** Texture variants per window-frame mask: bit 1 left, 2 right, 4 bottom, 8 top edge is the frame. */
    private static final int MASKS = 16;
    /** Randomly different textures per mask; combined with mirroring on both axes a face picks one of 12 looks. */
    private static final int VARIANTS = 3;
    private static final FloatArrayList[] fog = lists(VARIANTS * MASKS);
    /** Frost grows in pixel stages from the frame inward; stage textures are supersets of the previous one. */
    private static final int FROST_STAGES = 4;
    private static final FloatArrayList[] frost = lists(VARIANTS * MASKS * FROST_STAGES);
    /**
     * Frost and outside ice are dense enough to hide what is drawn later behind them (clouds, weather) when seen from
     * their own side. Frost is drawn without, like fog, when seen faintly from the far side, so it never hides water or
     * stained glass behind it, and as the stage growing in, so while faint it never hides fog behind it (the game draws
     * textures in no set order).
     */
    private static final FloatArrayList[] frostFaint = lists(VARIANTS * MASKS * FROST_STAGES);
    /** Outside frost: one seamless sheet over ICE_SHEET x ICE_SHEET blocks, in ICE_STAGES growth stages. */
    private static final int ICE_SHEET = 8;
    private static final int ICE_STAGES = 4;
    private static final FloatArrayList[] iceSheet = lists(ICE_STAGES), iceFar = lists(ICE_STAGES);
    /** Dust settled on windows in deserts: the same kind of sheet, in greys so the sand's colour tints it, in one stage. */
    private static final FloatArrayList[] sandSheet = lists(1);
    /** How strongly the thinner deposit of desert dust, above the thick one along the bottom, shows. */
    private static final float DUST_LIGHT = 0.55f;
    /** How high desert dust's deposit along the bottom of a window reaches, in pixels per unit of dust. */
    private static final float DUST_DEPOSIT = 80;
    /** How much thicker dust lies on a roof window than the film on a wall's, having nowhere to slide off to. */
    private static final float DUST_FLAT = 3;
    /**
     * How long each cell of a face takes to fade from one texture into the next as it melts (into a new frame, or ice
     * into its next stage).
     */
    static final double FADE_SECONDS = 3;
    /**
     * How far outside ice and desert dust grow while a pixel they reach fades in, in pixels: one, so they grow on
     * smoothly instead of a pixel at a time.
     */
    private static final float FADE_PIXELS = 1;
    /**
     * About how opaque the pixels are that two textures melting into each other share, so those keep their opacity
     * through the melt instead of thinning halfway (see fadeOut).
     */
    private static final float MELT_KEEP = 0.75f;
    /**
     * A drift of desert dust shades deeper toward the bottom of the window, down to DRIFT_DEEP darker DRIFT_DEPTH pixels
     * below its crest, and its crest catches the light, up to DRIFT_CREST as bright less a pixel's deepening.
     */
    private static final float DRIFT_DEEP = 0.22f, DRIFT_DEPTH = 8, DRIFT_CREST = 1.15f;
    /** How strongly desert dust films the whole window for its amount: barely noticeable at the day's amount. */
    private static final float DUST_FILM = 1f;
    /**
     * Outside ice lies thicker along the frame of the window: up to ICE_RIM more opaque at the frame, thinning out over
     * ICE_RIM_WIDTH pixels, each pixel up to ICE_RIM_RAGGED pixels sooner than the next.
     */
    private static final float ICE_RIM = 0.85f, ICE_RIM_WIDTH = 5, ICE_RIM_RAGGED = 2;
    /** How many pixels up or down a column of the ragged front may sit. */
    private static final float ICE_RAGGED = 1.5f;
    /** Reused for the dissolve between two growth stages; render runs on one thread. */
    private static final Melt ICE_STEP = new Melt();
    /** How strongly an effect on one side of the glass shows through to a viewer on the other side. */
    private static final float OTHER_SIDE_ALPHA = 0.3f;
    /** Light colour of overlays that a shader pack lights itself. */
    private static final int WHITE = 0xFFFFFF;
    private static final FloatArrayList drops = new FloatArrayList();
    /** Raindrops: lit by the direction of the face they run down, unlike the film on the glass. */
    private static final FloatArrayList rain = new FloatArrayList();
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
        ResiduumConfig cfg = ResiduumConfig.get();
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
        for (FloatArrayList list : frostFaint) {
            list.clear();
        }
        for (FloatArrayList list : iceSheet) {
            list.clear();
        }
        for (FloatArrayList list : iceFar) {
            list.clear();
        }
        for (FloatArrayList list : sandSheet) {
            list.clear();
        }
        drops.clear();
        rain.clear();
        debug.clear();

        Long2ObjectOpenHashMap<GlassState> states = GlassTracker.INSTANCE.states();
        Vec3 cam = Compat.cameraPos(mc);
        View.update(mc, viewer, cam);
        // Real time keeps the drops sliding smoothly between game ticks
        double seconds = (System.nanoTime() / 1_000_000L % 100_000_000L) / 1000.0;
        long now = level.getGameTime();
        // With a shader pack the unlit type glows, so let the pack light the overlays from the real light levels
        lit = ShaderPacks.inUse();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int count = 0;

        // A pixel of a face fades between two textures over this share of a frame change rather than switching at once
        float frameFade = (float) (FADE_SECONDS / cfg.timing.meltSeconds);
        // Left to another mod (see Rival): no window is drawn, but the marks and tumbleweeds after this still are
        for (GlassState gs : cfg.glassOn() ? states.values() : java.util.List.<GlassState>of()) {
            p.set(gs.pos);
            // ponytail: view-cone cull instead of the real frustum; switch to the frustum if FOV mods cause pop-in
            if (!View.sees(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 1)) {
                continue;
            }
            count++;
            detail = View.distanceSq(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) > View.DETAIL_DIST_SQ ? FAR_GRID : GRID;
            gs.look.fade = frameFade;
            if (gs.renderTime < 0 || now - gs.renderTime >= RENDER_CACHE_TICKS) {
                refreshCache(level, states, gs, p, now);
            }

            if (gs.isWindow()) {
                int shade = shading(gs.inner);
                // The film on the glass is lit by the brighter side of it, as light comes through from both
                int light = brighter(gs.lightInner, gs.lightOuter);
                boolean patches = gs.patches;
                Direction in = gs.inner;
                int below = gs.below, above = gs.above, left = gs.left, right = gs.right;
                boolean inside = seenFrom(cam, p, in, gs) == 1;
                float seen = inside ? 1 : OTHER_SIDE_ALPHA;
                // Each face picks a texture variant and mirroring, so droplets and crystals never repeat in a grid;
                // mirroring swaps which sides of the texture are the frame
                int style = faceStyle(gs, in);
                boolean flipU = (style & 4) != 0, flipV = (style & 8) != 0;
                float u0 = flipU ? 1 : 0, u1 = flipU ? 0 : 1, v0 = flipV ? 1 : 0, v1 = flipV ? 0 : 1;
                // The window's frame moved (a neighbour was built or broken): melt from what is shown now
                gs.look.show((style & 3) * MASKS + frameMask(flipU ? right : left, flipU ? left : right,
                        flipV ? above : below, flipV ? below : above));
                // Heavy mist gathers into drops that run down the glass and wipe trails through it
                boolean runs = cfg.condensation.enabled && gs.fog > MIST_RUNS && detail == GRID && inside
                        && in.getAxis().isHorizontal() && mistRuns(p, in, gs, cam, shade, light, seconds);
                int cells = patches || gs.look.melting() ? detail : 1;
                if (cfg.condensation.enabled && (gs.fog > 0.004f || patches)) {
                    int rgb = cfg.tintStainedGlass ? mix(gs.tint, 0.25f) : 0xFFFFFF;
                    float a = Math.min(1, gs.fog);
                    // Fill from the bottom of the whole window, not of each block
                    float height = below + 1 + above;
                    boolean flat = !in.getAxis().isHorizontal();
                    inset = FOG_INSET;
                    faceMelt(fog[gs.look.tex], gs.look.melting() ? fog[gs.look.prevTex] : null, gs.look, p, in, gs, 0, 1,
                            cam, u0, v0, u1, v1, runs ? detail : cells, rgb, shade, light, (x, y) -> {
                        // A roof window has no bottom for fog to gather at: as thick as halfway up a wall's
                        float h = flat ? 0.5f : (below + y) / height;
                        // The denser rim along the frame is baked into the texture variant
                        // Wipes and breath only where there are any: this runs for every pixel of the face
                        float mist = a * (1 - h * (1 - a)) * (patches ? 1 - wiped(gs, in, x, y) : 1)
                                * (runs ? 1 - MIST_CLEAR * clear(x, y) : 1);
                        return seen * (patches ? mist + BREATH_ALPHA * breathed(gs, x, y) : mist);
                    }, null);
                }
                if (cfg.frost.enabled && gs.frost > 0.004f) {
                    stages(inside ? frost : frostFaint, gs.look, gs.frost, p, in, gs, cam, u0, v0, u1, v1, cells, shade, light,
                            (x, y) -> patches ? seen * (1 - wiped(gs, in, x, y)) : seen);
                }
                if (gs.roomMask != 0) {
                    roomSides(gs, p, cam, cfg, light, below, above);
                }
                if (cfg.ice.enabled && gs.ice > 0.004f) {
                    outsideCoat(states, gs, p, cam, iceSheet, gs.ice, gs.iceFront, false, 0xFFFFFF);
                }
                if (cfg.sand.enabled && gs.sand > 0.004f) {
                    outsideCoat(states, gs, p, cam, sandSheet, gs.sand, 0, true, gs.sandColor);
                }
                if (cfg.debug) {
                    inset = INSET;
                    face(debug, p, gs.inner, gs, 0, 1, cam, 0, 0, 1, 1, 1, 0x00FF00, WHITE, FULL_BRIGHT, (x, y) -> 0.375f);
                }
            }

            if (gs.rainTop && cfg.raindrops.enabled && gs.drops > 0.004f) {
                topDrops(p, gs, cam, Math.min(1, gs.drops), shading(Direction.UP), gs.lightTop, seconds);
            }
            if (gs.rainMask != 0 && (cfg.debug || (cfg.raindrops.enabled && (gs.drops > 0.004f || gs.flakes > 0.004f)))) {
                float a = Math.min(1, gs.drops);
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if ((gs.rainMask & (1 << dir.get2DDataValue())) == 0) {
                        continue;
                    }
                    int i = dir.get2DDataValue();
                    if (cfg.raindrops.enabled && gs.drops > 0.004f) {
                        raindrops(p, dir, gs, gs.rainMin[i], gs.rainMax[i], cam, a, shading(dir), gs.lightSide[i], seconds);
                    }
                    if (cfg.raindrops.enabled && gs.flakes > 0.004f && detail == GRID) {
                        flakes(p, dir, gs, gs.rainMin[i], gs.rainMax[i], cam, gs.flakes, level.getThunderLevel(1),
                                shading(dir), gs.lightSide[i], seconds);
                    }
                    if (cfg.debug) {
                        inset = INSET;
                        face(debug, p, dir, gs, 0, 1, cam, 0, 0, 1, 1, 1, 0x0060FF, WHITE, FULL_BRIGHT, (x, y) -> 0.375f);
                    }
                }
            }
        }

        for (int variant = 0; variant < VARIANTS; variant++) {
            for (int mask = 0; mask < MASKS; mask++) {
                int tex = variant * MASKS + mask;
                submit(collector, poseStack, "condensation_" + variant + "_" + mask, fog[tex], true, false);
                for (int stage = 0; stage < FROST_STAGES; stage++) {
                    String name = "frost_" + variant + "_" + mask + "_" + stage;
                    submit(collector, poseStack, name, frost[tex * FROST_STAGES + stage], true, true);
                    submit(collector, poseStack, name, frostFaint[tex * FROST_STAGES + stage], true, false);
                }
            }
        }
        DecalRenderer.render(poseStack, collector, level, cam, cfg);
        if (cfg.lightning.enabled) {
            RodArcs.render(poseStack, collector, level, cam);
        }
        Tumbleweeds.render(poseStack, collector, level, cam, cfg);
        Fireflies.render(poseStack, collector, level, cam);
        for (int stage = 0; stage < ICE_STAGES; stage++) {
            submit(collector, poseStack, "ice_sheet_" + stage, iceSheet[stage], false, true);
            submit(collector, poseStack, "ice_sheet_" + stage, iceFar[stage], false, false);
        }
        // Dust is lit as evenly as the film on the glass, its colour already shaded like the face of a block
        submit(collector, poseStack, "sand_sheet", sandSheet[0], true, false);
        submit(collector, poseStack, "white", drops, true, false);
        submit(collector, poseStack, "white", rain, false, false);
        submit(collector, poseStack, "white", debug, true, false);

        visible = count;
        renderNanos = renderNanos * 0.95 + (System.nanoTime() - start) * 0.05;
    }

    /**
     * Fog and frost on the room-facing side faces (or their room-side part next to a pane). They follow the main
     * face's amounts; the texture is cropped to the span so its frame edge stays on the room side.
     */
    private static void roomSides(GlassState gs, BlockPos p, Vec3 cam, ResiduumConfig cfg, int light, int below,
                                  int above) {
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
            int cells = gs.patches ? detail : 1, shade = shading(dir);
            if (cfg.condensation.enabled && gs.fog > 0.004f) {
                int rgb = cfg.tintStainedGlass ? mix(gs.tint, 0.25f) : 0xFFFFFF;
                inset = FOG_INSET;
                face(fog[tex], p, dir, gs, min, max, cam, 0, v0, 1, v1, cells, rgb, shade, light,
                        (x, y) -> seen * a * (1 - (below + y) / height * (1 - a)) * (1 - wiped(gs, dir, x, y)));
            }
            if (cfg.frost.enabled && gs.frost > 0.004f) {
                float partial = grown - full, keep = full < FROST_STAGES ? fadeOut(partial) : 1;
                inset = FROST_INSET;
                if (full > 0) {
                    face((seen == 1 ? frost : frostFaint)[tex * FROST_STAGES + full - 1], p, dir, gs, min, max, cam, 0, v0, 1, v1,
                            cells, 0xFFFFFF, shade,
                            light, (x, y) -> seen * keep * (1 - wiped(gs, dir, x, y)));
                }
                if (full < FROST_STAGES) {
                    face(frostFaint[tex * FROST_STAGES + full], p, dir, gs, min, max, cam, 0, v0, 1, v1, cells, 0xFFFFFF, shade,
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
     * A coat on the outside face of a window, frost or desert dust, cut from one seamless sheet by world position so it
     * runs across block edges. It rises from the bottom of the whole window with a ragged front (front, see
     * GlassState.iceFront), one texture pixel per cell, and covers the window when amount is 1; dust instead films
     * the whole window faintly, with a thick deposit along the bottom and a thinner one above it, both edged with
     * sharp peaks (see peaks), as high on any window, each pixel fading in as they grow past it. Between ice's growth
     * stages
     * each cell switches to the denser sheet on its own. Beside a pane, which sits further back, it wraps round onto
     * the outer part of the block's side face, mirrored at the corner, so it runs on into the pane's.
     */
    private static void outsideCoat(Long2ObjectOpenHashMap<GlassState> states, GlassState gs, BlockPos p,
                                    Vec3 cam, FloatArrayList[] sheet, float amount, float front, boolean dust, int rgb) {
        Direction out = gs.inner.getOpposite();
        boolean flat = !out.getAxis().isHorizontal();
        // The face's right-hand axis in the world, and how far up the face this block is: on a roof window east and
        // north (see faceMelt)
        Direction across = flat ? Direction.EAST : out.getClockWise();
        boolean acrossPositive = across.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        int along = across.getAxis() == Direction.Axis.X ? p.getX() : p.getZ();
        int up = flat ? -p.getZ() : p.getY();
        // This block's square of the sheet: u follows the world along the face, v follows the world downward
        float uBase = Math.floorMod(along, ICE_SHEET) / (float) ICE_SHEET;
        float vTop = (ICE_SHEET - 1 - Math.floorMod(up, ICE_SHEET)) / (float) ICE_SHEET;
        float tile = 1f / ICE_SHEET;
        float u0 = acrossPositive ? uBase : uBase + tile, u1 = acrossPositive ? uBase + tile : uBase;
        // From inside it shows only faintly through the glass
        float seen = seenFrom(cam, p, out, gs);
        float film = Math.min(1, amount * DUST_FILM);
        ResiduumConfig.Timing timing = ResiduumConfig.get().timing;
        // How far the ice's front climbs while one pixel fades in
        float iceFade = FADE_PIXELS / 16;
        // The coat of the block a given way along the face (this one, or a corner block it runs on into)
        IntFunction<AlphaField> coatAt = a -> (x, y) -> {
            if (flat) {
                // On a roof window nothing climbs from a bottom: frost spreads by its stages, dust lies evenly
                return dust ? Math.min(1, film * DUST_FLAT) : 1;
            }
            int column = pixelColumn(a, acrossPositive, x);
            if (!dust) {
                // Ragged front: each pixel column of the window reaches a little higher or lower
                float ragged = ((hash(column * 0x9E3779B9) & 7) - 3.5f) / 16 * ICE_RAGGED;
                return Math.clamp((front + ragged - y) / iceFade, 0f, 1f);
            }
            // Pixels above the bottom of the window, gs.glassBelow blocks below this one
            float above = (y + gs.glassBelow) * 16;
            float drift = drift(column);
            // A drift is a thin skin of dust along its top, deepening toward the bottom of the window
            float thick = grownPast(amount, above, drift)
                    * (0.75f + 0.25f * Math.clamp(1 - above / (amount * DUST_DEPOSIT * drift), 0f, 1f));
            float thin = grownPast(amount, above, 0.3f + 0.7f * peaks(column, 0x2C1B3F, 9));
            return Math.max(film, Math.max(thick, DUST_LIGHT * thin));
        };
        AlphaField covered = coatAt.apply(along);
        // Drifts of dust shade deeper toward the bottom of the window, and their crest catches the light
        IntFunction<AlphaField> toneAt = a -> !dust || flat ? null : (x, y) -> {
            float depth = amount * DUST_DEPOSIT * drift(pixelColumn(a, acrossPositive, x)) - (y + gs.glassBelow) * 16;
            // The crest brightens as a pixel grows in, then hands on to the next one up rather than jumping a row
            return 1 - DRIFT_DEEP * Math.clamp(depth / DRIFT_DEPTH, 0f, 1f)
                    + (DRIFT_CREST - 1) * Math.max(0, 1 - Math.abs(depth - FADE_PIXELS));
        };
        AlphaField tone = toneAt.apply(along);
        FloatArrayList list, old = null;
        int stage = 0;
        if (dust) {
            list = sheet[0]; // dust grows by its edges and film, not by denser stages that would pop in cell by cell
        } else {
            float grown = Math.min(amount * ICE_STAGES, ICE_STAGES - 0.001f);
            stage = (int) grown;
            ICE_STEP.prevTex = stage;
            ICE_STEP.tex = Math.min(ICE_STAGES - 1, stage + 1);
            ICE_STEP.blend = grown - stage;
            ICE_STEP.fade = (float) (FADE_SECONDS * ICE_STAGES / timing.iceBuildSeconds);
            if (seen < 1) {
                sheet = iceFar;
            }
            list = sheet[ICE_STEP.tex];
            if (ICE_STEP.tex != stage) {
                old = sheet[stage];
            }
        }
        // Dust shows how lit the glass is from either side, so a lit room at night shows through it rather than black
        int shade = shading(out), light = dust ? brighter(gs.lightOuter, gs.lightInner) : gs.lightOuter;
        // A coat lying evenly, on a roof window or as ice that has climbed right over the face, and not between growth
        // stages, is one quad rather than one per pixel
        boolean even = old == null && (flat || !dust && front - 3.5f / 16 * ICE_RAGGED - 1 >= iceFade);
        inset = dust ? FOG_INSET : FROST_INSET;
        faceMelt(list, old, ICE_STEP, p, out, gs, 0, 1, cam, u0, vTop, u1, vTop + tile, even ? 1 : detail, rgb, shade, light,
                (x, y) -> seen * covered.at(x, y), tone);
        // Along the frame of the window ice lies thicker: its densest sheet once more, from another part of it so that
        // it fills the gaps, thinning out raggedly a few pixels in. Not where the coat runs on round a corner
        int wraps = gs.pane ? 0 : gs.outsideMask;
        int frame = dust || flat || detail != GRID ? 0
                : (gs.right == 0 && (wraps & 1 << across.getOpposite().get2DDataValue()) == 0 ? 1 : 0)
                | (gs.left == 0 && (wraps & 1 << across.get2DDataValue()) == 0 ? 2 : 0)
                | (gs.below == 0 ? 4 : 0) | (gs.above == 0 ? 8 : 0);
        if (frame != 0) {
            float ru = Math.floorMod(along + ICE_SHEET / 2, ICE_SHEET) / (float) ICE_SHEET;
            float rv = (ICE_SHEET - 1 - Math.floorMod(up + ICE_SHEET / 2, ICE_SHEET)) / (float) ICE_SHEET;
            inset = RIM_INSET;
            // With the faint layers: it thins out to nothing, and must not hide the ice behind it
            face(iceFar[ICE_STAGES - 1], p, out, gs, 0, 1, cam, acrossPositive ? ru : ru + tile, rv,
                    acrossPositive ? ru + tile : ru, rv + tile, GRID, rgb, shade, light, (x, y) -> {
                float d = 16 * Math.min(Math.min((frame & 1) != 0 ? x : 1, (frame & 2) != 0 ? 1 - x : 1),
                        Math.min((frame & 4) != 0 ? y : 1, (frame & 8) != 0 ? 1 - y : 1));
                float ragged = (hash(gs.seed + (int) (x * 16) * 131 + (int) (y * 16) * 7919) & 0xFF) / 255f * ICE_RIM_RAGGED;
                return seen * covered.at(x, y) * ICE_RIM * Math.clamp(1 - (d + ragged) / ICE_RIM_WIDTH, 0f, 1f);
            });
        }
        // A window that turns a corner of glass coats the corner block's face in its plane too, running on into it
        for (int k = 0; k < 2 && !gs.pane && !flat; k++) {
            if ((gs.corners & 1 << k) != 0) {
                Direction side = k == 0 ? across : across.getOpposite();
                int a = along + side.getStepX() + side.getStepZ();
                float cu = Math.floorMod(a, ICE_SHEET) / (float) ICE_SHEET;
                AlphaField coat = coatAt.apply(a);
                faceMelt(list, old, ICE_STEP, p.relative(side), out, gs, 0, 1, cam, acrossPositive ? cu : cu + tile, vTop,
                        acrossPositive ? cu + tile : cu, vTop + tile, even ? 1 : detail, rgb, shade, light,
                        (x, y) -> seen * coat.at(x, y), toneAt.apply(a));
            }
        }
        if (gs.pane || flat) {
            return;
        }
        for (Direction side : new Direction[] {across, across.getOpposite()}) {
            // A side face out in the open, where the window ends at a corner of the building, takes the coat round it
            boolean open = (gs.outsideMask & 1 << side.get2DDataValue()) != 0;
            GlassState next = states.get(p.relative(side).asLong());
            if (!open && (next == null || !next.pane || next.inner != gs.inner)) {
                continue;
            }
            // On either side face 1 - x is the outer face's x mirrored at the corner; the outer edge is at x = 0 on
            // the across side and at x = 1 on the other, and a strip beside a pane ends where the pane's face begins
            boolean outerAtZero = side == across;
            float seenSide = open ? seenFrom(cam, p, side, gs) : Math.min(seen, seenFrom(cam, p, side, gs));
            faceMelt(list, old, ICE_STEP, p, side, gs, 0, 1, cam, u1, vTop, u0, vTop + tile, open && even ? 1 : detail, rgb,
                    shading(side), light, (x, y) -> open || (outerAtZero ? x : 1 - x) < 0.5f - GlassState.PANE_HALF
                            ? seenSide * covered.at(1 - x, y) : 0,
                    tone == null ? null : (x, y) -> tone.at(1 - x, y));
        }
    }

    /** The pixel column of the window in the world at x across a face, so drifts and fronts run on across blocks. */
    private static int pixelColumn(int along, boolean acrossPositive, float x) {
        return Math.floorMod(along * 16 + (int) ((acrossPositive ? x : 1 - x) * 16), 1 << 20);
    }

    /** How high the thick drift of desert dust reaches at a pixel column, as a share of DUST_DEPOSIT pixels per unit. */
    private static float drift(int column) {
        return 0.15f + 0.45f * peaks(column, 0x51ED27, 6);
    }

    /**
     * How far a dust deposit reaching share of DUST_DEPOSIT pixels up per unit of dust has grown past a pixel above
     * pixels up: 0 until it reaches the pixel, then up to 1 as it grows FADE_PIXELS higher.
     */
    private static float grownPast(float amount, float above, float share) {
        return Math.clamp((amount * DUST_DEPOSIT * share - above) / FADE_PIXELS, 0f, 1f);
    }

    /**
     * Sharp peaks along a window, 0..1 at a pixel column, like flames: every width columns a curve climbs from a valley
     * to a tip late in the stretch, then drops straight to the next valley; tips and valleys are of random heights.
     */
    private static float peaks(int column, int seed, int width) {
        int cell = Math.floorDiv(column, width);
        int h = hash(cell * 0x9E3779B9 ^ seed);
        float x = Math.floorMod(column, width) + 0.5f;
        float tip = width * (0.55f + 0.35f * (h & 255) / 255f);
        float top = 0.45f + 0.55f * ((h >>> 8) & 255) / 255f;
        float from = valley(cell, seed), to = valley(cell + 1, seed);
        if (x < tip) {
            float t = x / tip;
            return from + (top - from) * t * (float) Math.sqrt(t);
        }
        return top + (to - top) * (x - tip) / (width - tip);
    }

    private static float valley(int cell, int seed) {
        return 0.3f * (hash(cell * 0x2545F491 ^ seed) & 255) / 255f;
    }

    /** The brighter of two packed lights, sky and block light each on its own. */
    private static int brighter(int a, int b) {
        return Math.max(a >>> 20 & 15, b >>> 20 & 15) << 20 | Math.max(a >>> 4 & 15, b >>> 4 & 15) << 4;
    }

    /**
     * Draws the last fully grown stage melting into the next one as it grows in (see fadeOut), melting between textures
     * with look.
     */
    private static void stages(FloatArrayList[] lists, Melt look, float amount, BlockPos p, Direction dir, GlassState gs,
                               Vec3 cam, float u0, float v0, float u1, float v1, int cells, int shade, int light,
                               AlphaField alpha) {
        float grown = amount * FROST_STAGES;
        int full = Math.min(FROST_STAGES, (int) grown);
        float partial = grown - full, keep = full < FROST_STAGES ? fadeOut(partial) : 1;
        inset = FROST_INSET;
        if (full > 0) {
            int stage = full - 1;
            faceMelt(lists[look.tex * FROST_STAGES + stage], look.melting() ? lists[look.prevTex * FROST_STAGES + stage] : null,
                    look, p, dir, gs, 0, 1, cam, u0, v0, u1, v1, cells, 0xFFFFFF, shade, light,
                    (x, y) -> keep * alpha.at(x, y), null);
        }
        if (full < FROST_STAGES) {
            faceMelt(frostFaint[look.tex * FROST_STAGES + full],
                    look.melting() ? frostFaint[look.prevTex * FROST_STAGES + full] : null,
                    look, p, dir, gs, 0, 1, cam, u0, v0, u1, v1, cells, 0xFFFFFF, shade, light,
                    (x, y) -> partial * alpha.at(x, y), null);
        }
    }

    /** 1 when the camera is on the side the face looks out to, OTHER_SIDE_ALPHA when it sees the face through the glass. */
    private static float seenFrom(Vec3 cam, BlockPos p, Direction dir, GlassState gs) {
        double half = gs.pane ? GlassState.PANE_HALF : 0.5;
        double side = (cam.x - p.getX() - 0.5) * dir.getStepX() + (cam.y - p.getY() - 0.5) * dir.getStepY()
                + (cam.z - p.getZ() - 0.5) * dir.getStepZ() - half;
        return side >= 0 ? 1 : OTHER_SIDE_ALPHA;
    }

    /** Recomputes light and window merging for one glass block; staggered by seed so refreshes spread over frames. */
    private static void refreshCache(Level level, Long2ObjectOpenHashMap<GlassState> states, GlassState gs,
                                     BlockPos p, long now) {
        gs.renderTime = now + Math.floorMod(gs.seed, 10);
        if (gs.isWindow()) {
            gs.lightInner = packedLight(level, p.relative(gs.inner));
            gs.lightOuter = packedLight(level, p.relative(gs.inner.getOpposite()));
            // Neighbouring windows of the same room merge into one surface
            if (gs.inner.getAxis().isHorizontal()) {
                int rx = -gs.inner.getStepZ(), rz = gs.inner.getStepX();
                gs.below = GlassTracker.run(states, gs, p, 0, -1, 0);
                gs.above = GlassTracker.run(states, gs, p, 0, 1, 0);
                gs.left = GlassTracker.run(states, gs, p, -rx, 0, -rz);
                gs.right = GlassTracker.run(states, gs, p, rx, 0, rz);
                gs.corners = (GlassTracker.INSTANCE.turnsCorner(states, gs, p.offset(-rx, 0, -rz), -rx, -rz) ? 1 : 0)
                        | (GlassTracker.INSTANCE.turnsCorner(states, gs, p.offset(rx, 0, rz), rx, rz) ? 2 : 0);
            } else {
                // A roof window spreads east-west and north-south; seen from below, its up is south (see faceMelt)
                gs.below = GlassTracker.run(states, gs, p, 0, 0, -1);
                gs.above = GlassTracker.run(states, gs, p, 0, 0, 1);
                gs.left = GlassTracker.run(states, gs, p, -1, 0, 0);
                gs.right = GlassTracker.run(states, gs, p, 1, 0, 0);
            }
        }
        if (gs.rainTop) {
            gs.lightTop = packedLight(level, p.above());
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int i = dir.get2DDataValue();
            if ((gs.rainMask & (1 << i)) != 0) {
                gs.lightSide[i] = packedLight(level, p.relative(dir));
            }
        }
    }

    /** Seed of a column of glass faces: the same for every block straight above and below, different for each face. */
    private static int column(BlockPos p, Direction dir) {
        return hash(p.getX() * 0x1F1F1F1F ^ p.getZ() * 0x2545F491 ^ (dir.get2DDataValue() + 1) * 0x9E3779B9);
    }

    /**
     * Drops of mist running down a heavily fogged window face (see Drips): marks how clear they wipe each pixel in CLEAR
     * and draws the drops themselves. False when none of them is on this face.
     */
    private static boolean mistRuns(BlockPos p, Direction in, GlassState gs, Vec3 cam, int shade, int light, double seconds) {
        // A pane's face lines up with the pixel grid only where its arms reach both sides
        if (gs.pane && !(gs.paneConnects(in.getClockWise()) && gs.paneConnects(in.getCounterClockWise()))) {
            return false;
        }
        java.util.Arrays.fill(CLEAR, 0);
        cleared = false;
        float chance = (gs.fog - MIST_RUNS) / (1 - MIST_RUNS) * MIST_CHANCE;
        Drips.paint(Drips.MIST, column(p, in) ^ 0x6A09E667, p.getY(), gs.above, gs.below, 0, 15, Math.min(1, chance), seconds,
                (column, row, strength, kind) -> {
                    int i = row * 16 + column;
                    CLEAR[i] = Math.max(CLEAR[i], kind == Drips.TRAIL ? strength : 1);
                    cleared = true;
                    if (kind != Drips.TRAIL) {
                        // The drop: clear, with a darker rim where it sits and a glint on its top
                        float x = column * PIXEL, y = row * PIXEL;
                        int rim = shaded(0.55f * strength, MIST_RIM, shade);
                        rect(drops, p, in, gs, cam, x, x + PIXEL, y, y + PIXEL, rim, rim, light);
                        if (row < 15) {
                            int glint = shaded(0.85f * strength, WHITE, shade);
                            rect(drops, p, in, gs, cam, x, x + PIXEL, y + PIXEL, y + 2 * PIXEL, glint, glint, light);
                        }
                    }
                });
        return cleared;
    }

    /** How clear running drops have wiped the mist at a point of the face being drawn, 0..1 (see mistRuns). */
    private static float clear(float x, float y) {
        return CLEAR[Math.min(15, (int) (y * 16)) * 16 + Math.min(15, (int) (x * 16))];
    }

    /**
     * Raindrops on one wet face: a few drops, one pixel wide, sliding down with a fading trail and resting
     * between runs, plus beads that cling to the glass and slowly come and go.
     */
    private static void raindrops(BlockPos pos, Direction dir, GlassState gs, float min, float max, Vec3 cam,
                                  float amount, int shade, int light, double seconds) {
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
                    int c = shaded(amount * DROP_TRAIL[t], DROP_RGB, shade);
                    rect(rain, pos, dir, gs, cam, x, x + PIXEL, row * PIXEL, (row + 1) * PIXEL, c, c, light);
                }
            }
        }
        for (int k = 0; k < BEADS; k++) {
            int h = hash(seed ^ (k + 1) * 0x2545F491);
            float x = min + Math.floorMod(h, columns) * PIXEL;
            float y = ((h >>> 8) & 15) * PIXEL;
            // Beads fade in and out in a few steps, not smoothly, to match the pixel look
            float pulse = Math.round(2 + 2 * Math.sin(seconds * 0.6 + ((h >>> 16) & 0xFF))) / 4f;
            int c = shaded(amount * 0.6f * pulse, DROP_RGB, shade);
            rect(rain, pos, dir, gs, cam, x, x + PIXEL, y, y + PIXEL, c, c, light);
        }
    }

    /**
     * Snowflakes stuck to one face open to a snowfall, as many as amount of FLAKES at a time (twice as many in a storm):
     * each lands on a pixel of its own, stays a while and melts away over FLAKE_MELT seconds, then lands again somewhere
     * else. Some are a little crystal of two pixels. As their number drops (the snowfall over, a storm passing) the last
     * of them fades with it rather than vanishing.
     */
    private static void flakes(BlockPos pos, Direction dir, GlassState gs, float min, float max, Vec3 cam, float amount,
                               float storm, int shade, int light, double seconds) {
        if (gs.pane) {
            Direction right = dir.getClockWise();
            min = Math.max(min, gs.paneConnects(right.getOpposite()) ? 0 : 0.5f - GlassState.PANE_HALF);
            max = Math.min(max, gs.paneConnects(right) ? 1 : 0.5f + GlassState.PANE_HALF);
        }
        int columns = Math.round((max - min) * 16);
        if (columns <= 1) {
            return;
        }
        int seed = gs.seed * 31 + dir.get2DDataValue() + 0x5EED;
        float count = FLAKES * (1 + storm) * amount;
        for (int k = 0; k < count; k++) {
            int h = hash(seed + k * 0x632BE5AB);
            double life = FLAKE_MIN + (h & 0xFF) / 255.0 * (FLAKE_MAX - FLAKE_MIN);
            double t = seconds / life + (h >>> 8 & 0xFF) / 255.0;
            double phase = t - Math.floor(t);
            int f = hash(h ^ (int) Math.floor(t) * 0x9E3779B9);
            float x = min + Math.floorMod(f, columns - 1) * PIXEL, y = (1 + (f >>> 8 & 15) % 14) * PIXEL;
            // Landing in a moment, melting away slowly; flake k is there by how far the count is past it
            double age = phase * life;
            float alpha = 0.9f * (float) Math.min(Math.min(1, age / FLAKE_LAND), (life - age) / FLAKE_MELT)
                    * Math.min(1, count - k);
            int c = shaded(alpha, FLAKE_RGB, shade);
            rect(rain, pos, dir, gs, cam, x, x + PIXEL, y, y + PIXEL, c, c, light);
            if ((f >>> 12 & 3) == 0) {
                // A little crystal: a fainter pixel on the slant beside it
                int d = shaded(alpha * 0.6f, FLAKE_RGB, shade);
                rect(rain, pos, dir, gs, cam, x + PIXEL, x + 2 * PIXEL, y - PIXEL, y, d, d, light);
            }
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
    private static void topDrops(BlockPos pos, GlassState gs, Vec3 cam, float amount, int shade, int light, double seconds) {
        int seed = gs.seed * 31 + 7;
        for (int k = 0; k < TOP_BEADS; k++) {
            int h = hash(seed ^ (k + 1) * 0x2545F491);
            float pulse = Math.round(2 + 2 * Math.sin(seconds * 0.6 + ((h >>> 16) & 0xFF))) / 4f;
            topPixel(pos, cam, h & 15, (h >>> 4) & 15, shaded(amount * 0.6f * pulse, DROP_RGB, shade), light);
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
            topPixel(pos, cam, h & 15, (h >>> 4) & 15, shaded(amount * alpha, DROP_RGB, shade), light);
        }
    }

    /** One pixel on the top face of the block, column px and row pz of a 16x16 grid. */
    private static void topPixel(BlockPos pos, Vec3 cam, int px, int pz, int color, int light) {
        float x = (float) (pos.getX() + px * PIXEL - cam.x);
        float y = (float) (pos.getY() + 1 - INSET - cam.y);
        float z = (float) (pos.getZ() + pz * PIXEL - cam.z);
        vertex(rain, x, y, z, color);
        vertex(rain, x, y, z + PIXEL, color);
        vertex(rain, x + PIXEL, y, z + PIXEL, color);
        vertex(rain, x + PIXEL, y, z, color);
        rain.add(0f);
        rain.add(1f);
        rain.add(0f);
        rain.add(Float.intBitsToFloat(light));
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
                             float u0, float v0, float u1, float v1, int cells, int rgb, int shade, int light, AlphaField alpha) {
        faceMelt(out, null, null, pos, dir, gs, spanMin, spanMax, cam, u0, v0, u1, v1, cells, rgb, shade, light, alpha, null);
    }

    /**
     * Like face(), but while a melt is running (oldOut not null) each cell goes either to the new texture's list or
     * to the old one's, switching over in a fixed random order as melt goes from 0 to 1. A tone, when given, brightens or
     * darkens each cell's light.
     */
    private static void faceMelt(FloatArrayList out, FloatArrayList oldOut, Melt look, BlockPos pos, Direction dir,
                                 GlassState gs, float spanMin, float spanMax, Vec3 cam, float u0, float v0, float u1,
                                 float v1, int cells, int rgb, int shade, int light, AlphaField alpha, AlphaField tone) {
        float nx = dir.getStepX(), ny = dir.getStepY(), nz = dir.getStepZ();
        float offset = (gs.pane ? GlassState.PANE_HALF : 0.5f) - inset;
        // The face's right-hand and up axes: on a wall right as in GlassState.faceX and the world's up; on a roof
        // window east, and north seen from above or south seen from below
        float rx = ny == 0 ? -nz : 1, rz = ny == 0 ? nx : 0;
        float uy = ny == 0 ? 1 : 0, uz = -ny;
        // A pane only covers its centre post plus the arms that connect to something
        // Only the given span of the face, e.g. the part of a side face left uncovered by a pane next to it
        float xMin = spanMin, xMax = spanMax;
        if (gs.pane) {
            Direction right = dir.getClockWise();
            xMin = Math.max(xMin, gs.paneConnects(right.getOpposite()) ? 0 : 0.5f - GlassState.PANE_HALF);
            xMax = Math.min(xMax, gs.paneConnects(right) ? 1 : 0.5f + GlassState.PANE_HALF);
        }
        float ox = (float) (pos.getX() + 0.5 + nx * offset - rx * 0.5 - cam.x);
        float oy = (float) (pos.getY() + 0.5 + ny * offset - uy * 0.5 - cam.y);
        float oz = (float) (pos.getZ() + 0.5 + nz * offset - rz * 0.5 - uz * 0.5 - cam.z);
        float xStep = (xMax - xMin) / cells;
        float yStep = 1f / cells;
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < cells; j++) {
                float x0 = xMin + i * xStep, x1 = x0 + xStep, y0 = j * yStep, y1 = y0 + yStep;
                // How much of the cell shows the new texture: all of it, but for the cells a melt is passing, which fade
                // over from the old one within look.fade of the melt instead of switching at once
                float now = 1;
                if (oldOut != null) {
                    // Each cell starts in its own turn, early enough to be done when the melt is
                    float rank = (hash(gs.seed + i * 131 + j * 7919) & 0xFFFF) / 65536f, fade = Math.min(1, look.fade);
                    now = Math.clamp((look.blend - (look.reversed ? 1 - rank : rank) * (1 - fade)) / fade, 0f, 1f);
                }
                float a00, a10, a11, a01;
                int cellShade = shade;
                if (cells == 1) {
                    // Whole face: alpha varies smoothly across it (the bottom-up fog gradient)
                    a00 = alpha.at(x0, y0);
                    a10 = alpha.at(x1, y0);
                    a11 = alpha.at(x1, y1);
                    a01 = alpha.at(x0, y1);
                } else {
                    float a = alpha.at((x0 + x1) / 2, (y0 + y1) / 2);
                    // Without a shader pack faint cells are simply faint, so they fade in from nothing
                    if (a < (lit ? MIN_CELL_ALPHA : 0.004f)) {
                        continue;
                    }
                    a00 = a10 = a11 = a01 = a;
                    if (tone != null) {
                        cellShade = scale(shade, tone.at((x0 + x1) / 2, (y0 + y1) / 2));
                    }
                }
                for (int k = 0; k < 2; k++) {
                    FloatArrayList target = k == 0 ? out : oldOut;
                    float share = k == 0 ? now : fadeOut(now);
                    if (share <= 0) {
                        continue;
                    }
                    corner(target, ox, oy, oz, rx, rz, uy, uz, x0, y0, u0, v0, u1, v1, shaded(a00 * share, rgb, cellShade));
                    corner(target, ox, oy, oz, rx, rz, uy, uz, x1, y0, u0, v0, u1, v1, shaded(a10 * share, rgb, cellShade));
                    corner(target, ox, oy, oz, rx, rz, uy, uz, x1, y1, u0, v0, u1, v1, shaded(a11 * share, rgb, cellShade));
                    corner(target, ox, oy, oz, rx, rz, uy, uz, x0, y1, u0, v0, u1, v1, shaded(a01 * share, rgb, cellShade));
                    target.add(nx);
                    target.add(ny);
                    target.add(nz);
                    target.add(Float.intBitsToFloat(light));
                }
            }
        }
    }

    /**
     * How strongly a texture melting away is drawn while the one replacing it is drawn in by share: the pixels both
     * have, about MELT_KEEP opaque, keep their opacity throughout rather than thinning halfway, the rest fade out.
     */
    private static float fadeOut(float share) {
        return (1 - share) / (1 - MELT_KEEP * share);
    }

    private static void corner(FloatArrayList out, float ox, float oy, float oz, float rx, float rz, float uy, float uz,
                               float x, float y, float u0, float v0, float u1, float v1, int color) {
        out.add(ox + rx * x);
        out.add(oy + uy * y);
        out.add(oz + rz * x + uz * y);
        out.add(u0 + (u1 - u0) * x);
        out.add(v1 + (v0 - v1) * y); // v grows downward in the texture
        out.add(Float.intBitsToFloat(color));
    }

    /**
     * Submits quads on the glass, lit by the lightmap like the world. Without a shader pack they are shaded in their
     * colour like the faces of blocks (see shading), so lit as if facing the sky; a pack lights a film on the glass (fog,
     * frost, drops) so too, as light comes through it from both sides, and the rest by the direction of their face. Dense
     * ones hide what is drawn later behind them.
     */
    static void submit(SubmitNodeCollector collector, PoseStack poseStack, String texture, FloatArrayList quads, boolean film,
                       boolean dense) {
        if (quads.isEmpty()) {
            return;
        }
        int normals = lit && !film ? TURNED : UP;
        Compat.submitTranslucent(collector, poseStack, ResiduumClient.MOD_ID, TEX + texture + ".png", dense,
                (pose, consumer) -> emit(quads, pose, consumer, normals));
    }

    /** Like submit, dense and with their own normals as they are, for things that are not glass (the shield's splats). */
    static void submitLit(SubmitNodeCollector collector, PoseStack poseStack, String texture, FloatArrayList quads) {
        Compat.submitTranslucent(collector, poseStack, ResiduumClient.MOD_ID, TEX + texture + ".png", true,
                (pose, consumer) -> emit(quads, pose, consumer));
    }

    /** Like submit, for decals drawn on block surfaces (see Compat.submitDecal). */
    static void submitDecal(SubmitNodeCollector collector, PoseStack poseStack, String texture, FloatArrayList quads) {
        submitDecal(collector, poseStack, ResiduumClient.MOD_ID, TEX + texture + ".png", quads);
    }

    /** The same with any texture, the game's own included. */
    static void submitDecal(SubmitNodeCollector collector, PoseStack poseStack, String namespace, String path,
                            FloatArrayList quads) {
        if (quads.isEmpty()) {
            return;
        }
        Compat.submitDecal(collector, poseStack, namespace, path, (pose, consumer) -> emit(quads, pose, consumer));
    }

    /** Like submitDecal, for quads that darken the block's own texture under them (see Compat.submitMultiply). */
    static void submitMultiply(SubmitNodeCollector collector, PoseStack poseStack, String texture, FloatArrayList quads) {
        if (quads.isEmpty()) {
            return;
        }
        Compat.submitMultiply(collector, poseStack, ResiduumClient.MOD_ID, TEX + texture + ".png",
                (pose, consumer) -> emit(quads, pose, consumer));
    }

    /** Like submitDecal, for opaque cut-out quads that shader packs should light and reflect with the solid world. */
    static void submitSolid(SubmitNodeCollector collector, PoseStack poseStack, String texture, FloatArrayList quads) {
        if (quads.isEmpty()) {
            return;
        }
        Compat.submitSolid(collector, poseStack, ResiduumClient.MOD_ID, TEX + texture + ".png",
                (pose, consumer) -> emit(quads, pose, consumer));
    }

    static void emit(FloatArrayList q, PoseStack.Pose pose, VertexConsumer vc) {
        emit(q, pose, vc, FACE);
    }

    /**
     * Normals quads are emitted with: their face's, as they are (decals); or turned to show the camera their front, with
     * their face's normal or one straight up.
     */
    private static final int FACE = 0, TURNED = 1, UP = 2;

    /**
     * Adds the quads to the buffer. Turned ones show the camera their front: some types draw no backs, and the rest light
     * a back by its normal turned round, as mobs are lit, so a film seen from the side its normal points to went dark.
     */
    private static void emit(FloatArrayList q, PoseStack.Pose pose, VertexConsumer vc, int normals) {
        float[] a = q.elements();
        for (int i = 0; i + STRIDE <= q.size(); i += STRIDE) {
            float nx = a[i + 24], ny = a[i + 25], nz = a[i + 26];
            // Corners are relative to the camera: the quad shows its back when the cross of its first two edges points
            // the way the camera looks at its first corner
            float ex = a[i + 6] - a[i], ey = a[i + 7] - a[i + 1], ez = a[i + 8] - a[i + 2];
            float fx = a[i + 12] - a[i], fy = a[i + 13] - a[i + 1], fz = a[i + 14] - a[i + 2];
            boolean turn = normals != FACE
                    && (ey * fz - ez * fy) * a[i] + (ez * fx - ex * fz) * a[i + 1] + (ex * fy - ey * fx) * a[i + 2] > 0;
            if (normals == UP) {
                nx = nz = 0;
                ny = 1;
            }
            int light = Float.floatToRawIntBits(a[i + 27]);
            for (int k = 0; k < 4; k++) {
                int v = i + 6 * (turn ? (4 - k) % 4 : k);
                vc.addVertex(pose, a[v], a[v + 1], a[v + 2]).setColor(Float.floatToRawIntBits(a[v + 5]))
                        .setUv(a[v + 3], a[v + 4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(pose, nx, ny, nz);
            }
        }
    }

    /**
     * Colour an overlay on a face of the glass is shaded with: as the game shades the faces of blocks by their direction,
     * so it is lit like the glass it is on; a shader pack shades it itself.
     */
    private static int shading(Direction dir) {
        return lit ? WHITE : scale(WHITE, DecalRenderer.faceShade(dir));
    }

    /** A light colour dimmed (or brightened) by factor. */
    private static int scale(int rgb, float factor) {
        return Math.min(255, (int) ((rgb >> 16 & 0xFF) * factor)) << 16 | Math.min(255, (int) ((rgb >> 8 & 0xFF) * factor)) << 8
                | Math.min(255, (int) ((rgb & 0xFF) * factor));
    }

    /** Lightmap coordinates of a block, as vanilla packs them for rendering. */
    static int packedLight(Level level, BlockPos pos) {
        return level.getBrightness(LightLayer.SKY, pos) << 20 | level.getBrightness(LightLayer.BLOCK, pos) << 4;
    }

    /** Vertex colour of rgb lit by the light colour shade. */
    static int shaded(float alpha, int rgb, int shade) {
        int r = (rgb >> 16 & 0xFF) * (shade >> 16 & 0xFF) / 255;
        int g = (rgb >> 8 & 0xFF) * (shade >> 8 & 0xFF) / 255;
        int b = (rgb & 0xFF) * (shade & 0xFF) / 255;
        return (int) (Math.clamp(alpha, 0f, 1f) * 255) << 24 | r << 16 | g << 8 | b;
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
