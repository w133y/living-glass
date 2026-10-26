package com.w1z4r_d.livingglass.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.w1z4r_d.livingglass.LivingGlassConfig;
import com.w1z4r_d.livingglass.compat.Compat;
import com.w1z4r_d.livingglass.decal.DecalTracker;
import com.w1z4r_d.livingglass.decal.DecalTracker.Ash;
import com.w1z4r_d.livingglass.decal.DecalTracker.Kind;
import com.w1z4r_d.livingglass.decal.DecalTracker.Splat;
import com.w1z4r_d.livingglass.glass.Melt;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Draws ash and snowball splats on block faces, with the same pixel melt as the glass when ash patches merge. */
final class DecalRenderer {
    private static final int VARIANTS = 3;
    /** Full scorch textures: variant x 16 edge masks; side ash: variant x 4 left/right masks, after them. */
    private static final int SIDE_BASE = VARIANTS * 16;
    private static final FloatArrayList[] ashLists = lists(SIDE_BASE + VARIANTS * 4);
    private static final int SNOW_VARIANTS = 6;
    private static final FloatArrayList[] snowLists = lists(SNOW_VARIANTS);
    /**
     * Decals lie right on the block surface (the depth bias of their render type keeps them in front of it). Kinds
     * that can overlap on one face are pulled toward the eye by different hairs, so they never share a depth and
     * flicker; toward the eye rather than off the face, so they do not move on screen and decals on two faces that
     * meet at an outside edge close up with no gap.
     */
    private static final double ASH_LIFT = 0, SCORCH_LIFT = 0.001, SNOW_LIFT = 0.002, EMBER_LIFT = 0.003;
    /** About the angle one screen pixel covers, in radians, for the lift of solid decals seen at a slant (see slant). */
    private static final double PIXEL_ANGLE = 0.0025;
    /**
     * A snow splat is up to 14 pixels wide, at the same pixel size as the block textures, and a lightning scorch 64
     * pixels, four blocks across. Their textures carry a clear margin a block wide round the mark (see splatPart).
     */
    private static final float SPLAT_HALF = 7 / 16f;
    private static final float SCORCH_HALF = 2;
    private static final int LIGHTNING_VARIANTS = 6;
    /** Embers glow for this long after the strike, then only the burn mark is left. */
    private static final int EMBER_TICKS = 200;
    /** How many pixels a burn mark runs down (or up) the side of a step right at the strike; less further out. */
    private static final int STREAK_PX = 20;
    private static final Direction[] PAIRS = {Direction.EAST, Direction.SOUTH};
    private static final FloatArrayList[] scorchLists = lists(LIGHTNING_VARIANTS);
    private static final FloatArrayList[] emberLists = lists(LIGHTNING_VARIANTS);
    /**
     * The burn mark on sand: its heart melted into an opaque glass pool, which shader packs reflect like a mirror (its
     * LabPBR map, or an ender pearl's built-in material in packs without PBR maps), in a thin see-through rim of
     * sintered sand.
     */
    private static final FloatArrayList[] glassLists = lists(LIGHTNING_VARIANTS);
    private static final FloatArrayList[] sinterLists = lists(LIGHTNING_VARIANTS);
    /** Which lists a burn mark goes into per texture variant: soot on most ground, sinter and glass on sand. */
    private static final FloatArrayList[][] SOIL = new FloatArrayList[LIGHTNING_VARIANTS][];
    private static final FloatArrayList[][] SAND = new FloatArrayList[LIGHTNING_VARIANTS][];

    static {
        for (int i = 0; i < LIGHTNING_VARIANTS; i++) {
            SOIL[i] = new FloatArrayList[] {scorchLists[i]};
            SAND[i] = new FloatArrayList[] {sinterLists[i], glassLists[i]};
        }
    }

    /** Block models' quads by state, for marks on posts and fences (see model), and which states are posts (thin). */
    private static final Map<BlockState, List<float[]>> MODELS = new IdentityHashMap<>();
    private static final Map<BlockState, Boolean> FOOTPRINTS = new IdentityHashMap<>();
    /** Wall faces a burn mark has been drawn on this frame. */
    private static final Set<DecalTracker.Key> WALLS_TAKEN = new HashSet<>();
    /** A splat fades out over the last quarter of its life. */
    private static final float SPLAT_FADE = 0.25f;
    private static final int CACHE_TICKS = 20;

    private DecalRenderer() {
    }

    private static FloatArrayList[] lists(int n) {
        FloatArrayList[] out = new FloatArrayList[n];
        for (int i = 0; i < n; i++) {
            out[i] = new FloatArrayList();
        }
        return out;
    }

    static void render(PoseStack poseStack, SubmitNodeCollector collector, Level level, Vec3 cam, LivingGlassConfig cfg) {
        for (FloatArrayList list : ashLists) {
            list.clear();
        }
        for (FloatArrayList list : snowLists) {
            list.clear();
        }
        for (FloatArrayList list : scorchLists) {
            list.clear();
        }
        for (FloatArrayList list : emberLists) {
            list.clear();
        }
        for (FloatArrayList list : glassLists) {
            list.clear();
        }
        for (FloatArrayList list : sinterLists) {
            list.clear();
        }
        DecalTracker decals = DecalTracker.INSTANCE;
        double maxDist = cfg.render.radius * (double) cfg.render.radius;
        long now = decals.tick();
        Map<DecalTracker.Key, Ash> all = decals.ash();
        for (Ash a : all.values()) {
            BlockPos pos = BlockPos.of(a.pos);
            if (a.amount <= 0.004f || pos.distToCenterSqr(cam) > maxDist) {
                continue;
            }
            if (a.renderTime < 0 || now - a.renderTime >= CACHE_TICKS) {
                refresh(level, all, a, pos, now);
            }
            drawAsh(a, pos, cam);
        }
        long life = (long) (cfg.timing.snowSeconds * 20);
        for (Splat s : decals.splats()) {
            BlockPos pos = BlockPos.of(s.pos());
            if (pos.distToCenterSqr(cam) <= maxDist) {
                float age = (now - s.born()) / (float) life;
                float alpha = age < 1 - SPLAT_FADE ? 1 : (1 - age) / SPLAT_FADE;
                drawSplat(level, s, pos, cam, alpha);
            }
        }
        long scorchLife = (long) (cfg.timing.lightningSeconds * 20);
        // Newest first: where two marks reach the same wall, only the newest is drawn on it
        WALLS_TAKEN.clear();
        List<DecalTracker.Strike> strikes = decals.strikes();
        for (int k = strikes.size() - 1; k >= 0; k--) {
            DecalTracker.Strike st = strikes.get(k);
            if (BlockPos.of(st.ground()).distToCenterSqr(cam) <= maxDist) {
                drawStrike(level, st, cam, now - st.born(), scorchLife);
            }
        }
        for (int i = 0; i < ashLists.length; i++) {
            String name = i < SIDE_BASE ? "ash_full_" + i / 16 + "_" + i % 16
                    : "ash_side_" + (i - SIDE_BASE) / 4 + "_" + (i - SIDE_BASE) % 4;
            OverlayRenderer.submitDecal(collector, poseStack, name, ashLists[i]);
        }
        for (int i = 0; i < SNOW_VARIANTS; i++) {
            OverlayRenderer.submitDecal(collector, poseStack, "snow_" + i, snowLists[i]);
        }
        for (int i = 0; i < LIGHTNING_VARIANTS; i++) {
            // Soot darkens the blocks' own textures, like the cracks of a block being broken
            OverlayRenderer.submitMultiply(collector, poseStack, "lightning_scorch_" + i, scorchLists[i]);
            OverlayRenderer.submitMultiply(collector, poseStack, "lightning_sinter_" + i, sinterLists[i]);
            String glass = "lightning_glass_" + i;
            FloatArrayList quads = glassLists[i];
            if (OverlayRenderer.lit) {
                // Solid, so the pack draws it before it works out reflections, and tagged as an ender pearl for packs
                // that give built-in materials to what they know: theirs is mirror-smooth whatever its colour
                slant(quads);
                ShaderPacks.submitAs("ender_pearl", () -> OverlayRenderer.submitSolid(collector, poseStack, glass, quads));
            } else {
                OverlayRenderer.submitDecal(collector, poseStack, glass, quads);
            }
            OverlayRenderer.submitDecal(collector, poseStack, "lightning_embers_" + i, emberLists[i]);
        }
    }

    /**
     * Pulls solid decal quads further toward the eye the more slanted their face is seen, by about a pixel's worth of
     * its depth. Solid render types have no depth bias of their own, and packs jitter the terrain by part of a pixel
     * but not entities, so a solid decal seen at a slant would flicker with the ground under it.
     */
    private static void slant(FloatArrayList quads) {
        float[] e = quads.elements();
        for (int q = 0; q + OverlayRenderer.STRIDE <= quads.size(); q += OverlayRenderer.STRIDE) {
            float nx = e[q + 24], ny = e[q + 25], nz = e[q + 26];
            for (int v = q; v < q + 24; v += 6) {
                double distance = Math.sqrt(e[v] * e[v] + e[v + 1] * e[v + 1] + e[v + 2] * e[v + 2]);
                double facing = Math.max(0.02, Math.abs(e[v] * nx + e[v + 1] * ny + e[v + 2] * nz) / distance);
                double keep = Math.max(0, 1 - PIXEL_ANGLE * Math.sqrt(1 - facing * facing) / facing);
                e[v] *= (float) keep;
                e[v + 1] *= (float) keep;
                e[v + 2] *= (float) keep;
            }
        }
    }

    /** Light and which neighbouring faces carry ash too, so neighbouring patches join without an edge. */
    private static void refresh(Level level, Map<DecalTracker.Key, Ash> all, Ash a, BlockPos pos, long now) {
        a.renderTime = now + Math.floorMod(a.seed, 10);
        a.shade = faceShade(a.face);
        a.light = OverlayRenderer.packedLight(level, pos.relative(a.face));
        Direction t1 = across(a.face), t2 = up(a.face);
        // Left, right, bottom and top neighbours, and which two of them meet at each corner
        Direction[] sides = {t1.getOpposite(), t1, t2.getOpposite(), t2};
        int[][] meet = {{0, 2}, {1, 2}, {1, 3}, {0, 3}};
        for (int c = 0; c < 4; c++) {
            Direction s0 = sides[meet[c][0]], s1 = sides[meet[c][1]];
            Ash n0 = beyond(all, pos, a.face, s0), n1 = beyond(all, pos, a.face, s1);
            List<Ash> near = new ArrayList<>(3);
            if (n0 != null) {
                near.add(n0);
            }
            if (n1 != null) {
                near.add(n1);
            }
            Ash diagonal = null;
            if (n0 != null && n1 != null) {
                // Round the corner: the next block of this surface, or past either neighbour toward the other side
                diagonal = all.get(new DecalTracker.Key(pos.relative(s0).relative(s1).asLong(), a.face));
                if (diagonal == null) {
                    diagonal = beyond(all, BlockPos.of(n0.pos), n0.face, s1);
                }
                if (diagonal == null) {
                    diagonal = beyond(all, BlockPos.of(n1.pos), n1.face, s0);
                }
            }
            if (diagonal != null) {
                near.add(diagonal);
            }
            a.notch[c] = n0 != null && n1 != null && diagonal == null;
            a.corners[c] = near.toArray(new Ash[0]);
        }
        a.mask = (joined(all, pos, a.face, t1.getOpposite()) ? 0 : 1) | (joined(all, pos, a.face, t1) ? 0 : 2);
        if (a.kind == Kind.FULL) {
            a.mask |= (joined(all, pos, a.face, t2.getOpposite()) ? 0 : 4) | (joined(all, pos, a.face, t2) ? 0 : 8);
        }
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        Direction.Axis axis = a.face.getAxis();
        boolean positive = a.face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double surface = shape.isEmpty() ? (positive ? 1 : 0) : positive ? shape.max(axis) : shape.min(axis);
        a.plane = surface;
        // Cut to the real shape, so soot on a fence or pane does not hang in the air beside it
        a.rect = faceRect(level, pos, a.face, surface);
    }

    /**
     * Whether the ash on face continues past its edge toward t: onto the next block of the same floor or wall, or
     * round the inside corner onto the wall (or floor) that meets it there. It may join ash that has only just
     * started, as where they meet both show only as far as the fainter one has built up (see drawAsh).
     */
    private static boolean joined(Map<DecalTracker.Key, Ash> all, BlockPos pos, Direction face, Direction t) {
        Ash n = beyond(all, pos, face, t);
        return n != null && (n.target > 0 || n.amount > 0.02f);
    }

    /** The ash past the edge of face toward t: on the next block of the same surface, or round an inside corner. */
    private static Ash beyond(Map<DecalTracker.Key, Ash> all, BlockPos pos, Direction face, Direction t) {
        Ash n = all.get(new DecalTracker.Key(pos.relative(t).asLong(), face));
        return n != null ? n : all.get(new DecalTracker.Key(pos.relative(t).relative(face).asLong(), t.getOpposite()));
    }

    private static void drawAsh(Ash a, BlockPos pos, Vec3 cam) {
        int style = OverlayRenderer.hash(a.seed);
        int variant = Math.floorMod(style >>> 4, VARIANTS);
        boolean flipU = (style & 4) != 0;
        // Full scorch may also turn upside down; side ash always rises from the bottom
        boolean flipV = a.kind == Kind.FULL && (style & 8) != 0;
        int mask = a.mask;
        if (flipU) {
            mask = (mask & ~3) | ((mask & 1) << 1) | ((mask & 2) >> 1);
        }
        if (flipV) {
            mask = (mask & ~12) | ((mask & 4) << 1) | ((mask & 8) >> 1);
        }
        a.look.show(a.kind == Kind.FULL ? variant * 16 + mask : SIDE_BASE + variant * 4 + (mask & 3));
        // As opaque as it has built up, at each corner only as far as the faintest ash meeting there, so neighbouring
        // patches grow and fade into each other rather than one ending at the other's bare edge
        float[] corner = new float[4];
        for (int c = 0; c < 4; c++) {
            corner[c] = a.notch[c] ? 0 : a.amount;
            for (Ash n : a.corners[c]) {
                corner[c] = Math.min(corner[c], n.amount);
            }
        }
        int light = a.light;
        Melt look = a.look;
        float[] r = a.rect;
        if (r == null) {
            return;
        }
        // Where the corners differ, one cell per pixel, each a pixel of the texture shown whole or not at all
        boolean even = corner[0] == corner[1] && corner[1] == corner[2] && corner[2] == corner[3];
        int cells = look.melting() || !even ? OverlayRenderer.GRID : 1;
        float step = 1f / cells;
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < cells; j++) {
                FloatArrayList out = ashLists[look.tex];
                if (look.melting()) {
                    float rank = (OverlayRenderer.hash(a.seed + i * 131 + j * 7919) & 0xFFFF) / 65536f;
                    if ((look.reversed ? 1 - rank : rank) >= look.blend) {
                        out = ashLists[look.prevTex];
                    }
                }
                float x0 = Math.max(r[0], i * step), x1 = Math.min(r[1], (i + 1) * step);
                float y0 = Math.max(r[2], j * step), y1 = Math.min(r[3], (j + 1) * step);
                if (x1 > x0 && y1 > y0) {
                    rect(out, pos, a.face, a.plane, cam, x0, x1, y0, y1, flipU, flipV,
                            even ? soot(corner[0], a.shade) : pixel(a, corner, i, j, step), light);
                }
            }
        }
    }

    /**
     * A splat centred on the hit point. Past the edge of the surface it carries on where snow would land: onto the
     * next blocks of the same wall or floor, round an outside edge onto the other face of the same block, or up the
     * side of a block standing out from the wall in an inside corner. Every part is cut to the real shape of the
     * block it is on, so it never hangs in the air next to panes, slabs or fences.
     */
    private static void drawSplat(Level level, Splat s, BlockPos pos, Vec3 cam, float alpha) {
        Direction face = s.face();
        Direction t1 = across(face), t2 = up(face);
        double[] local = {s.x(), s.y(), s.z()};
        // On the block's pixel grid, so splat pixels line up with the block's own pixels
        local[t1.getAxis().ordinal()] = Math.round(local[t1.getAxis().ordinal()] * 16) / 16.0;
        local[t2.getAxis().ordinal()] = Math.round(local[t2.getAxis().ordinal()] * 16) / 16.0;
        int normal = face.getAxis().ordinal();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double surface = local[normal];
        float a = coord(t1, local), b = coord(t2, local);
        FloatArrayList out = snowLists[Math.floorMod(OverlayRenderer.hash(s.seed()), SNOW_VARIANTS)];
        for (int da = -1; da <= 1; da++) {
            for (int db = -1; db <= 1; db++) {
                splatPart(level, out, s.gone(), pos.relative(t1, da).relative(t2, db), face, a - da, b - db, surface, cam,
                        alpha, SPLAT_HALF, 0, false, SNOW_LIFT);
            }
        }
        if (!atBoundary(face, surface)) {
            return; // a hit inside the block cell (on a slab top, a pane) has no block edge to fold round
        }
        for (Direction t : new Direction[] {t1, t1.getOpposite(), t2, t2.getOpposite()}) {
            int axis = t.getAxis().ordinal();
            boolean tPositive = t.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            double toEdge = tPositive ? 1 - local[axis] : local[axis];
            if (toEdge >= SPLAT_HALF) {
                continue;
            }
            BlockPos side = pos.relative(t);
            double[] folded = local.clone();
            if (!level.getBlockState(side).isCollisionShapeFullBlock(level, side)) {
                // Outside edge: fold onto the face of this block that looks toward t; the splat centre lands outside
                // that face, as far from the edge as it was on this one
                folded[axis] = tPositive ? 1 : 0;
                folded[normal] = surface + (positive ? toEdge : -toEdge);
                splatPart(level, out, s.gone(), pos, t, coord(across(t), folded), coord(up(t), folded), tPositive ? 1 : 0, cam,
                        alpha, SPLAT_HALF, 0, false, SNOW_LIFT);
            } else {
                // Inside corner: the wall goes on, and a block in front of it stands out; climb its side face
                BlockPos corner = side.relative(face);
                Direction back = t.getOpposite();
                folded[axis] = tPositive ? 0 : 1;
                folded[normal] = (positive ? 0 : 1) - (positive ? toEdge : -toEdge);
                splatPart(level, out, s.gone(), corner, back, coord(across(back), folded), coord(up(back), folded),
                        tPositive ? 0 : 1, cam, alpha, SPLAT_HALF, 0, false, SNOW_LIFT);
            }
        }
    }

    /**
     * A lightning burn mark projected from above onto the ground as it was at the strike: each column's part on its
     * top face then, and where neighbouring columns differ in height, on the side face of the step between them.
     * Embers glow on the top faces while the mark is fresh. Each strike turns and mirrors its texture its own way.
     * On sand the mark is fused glass instead of soot.
     */
    private static void drawStrike(Level level, DecalTracker.Strike st, Vec3 cam, long age, long life) {
        if (age >= life) {
            return;
        }
        // Soot fades by losing pixels, rim first (see its texture's alpha); glass by turning see-through
        float fade = age < life * 2 / 3 ? 1 : (life - age) / (life / 3f);
        float glow = age < EMBER_TICKS ? 1 - age / (float) EMBER_TICKS : 0;
        BlockPos ground = BlockPos.of(st.ground());
        int style = OverlayRenderer.hash(st.seed());
        int variant = Math.floorMod(style, LIGHTNING_VARIANTS), turn = (style >>> 8) & 7;
        int reach = DecalTracker.STRIKE_REACH, size = 2 * reach + 1;
        int[] layers = st.layers();
        double[] surface = st.surface();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int i = (dx + reach) * size + dz + reach;
                if (Double.isNaN(surface[i])) {
                    continue;
                }
                if (!Double.isInfinite(surface[i])) {
                    // Floor face coordinates: x across, north up
                    float ca = (float) (st.x() - (ground.getX() + dx));
                    float cb = (float) (1 - (st.z() - (ground.getZ() + dz)));
                    int groundY = DecalTracker.groundY(surface[i]);
                    for (int k = 0; layers[i] >>> k != 0; k++) {
                        if ((layers[i] >>> k & 1) == 0) {
                            continue;
                        }
                        BlockPos block = new BlockPos(ground.getX() + dx, groundY + k, ground.getZ() + dz);
                        // A post, fence or lantern goes by its model where it has one
                        BlockState state = level.getBlockState(block);
                        List<float[]> model = thin(level, block, state) ? model(state) : List.of();
                        if (!model.isEmpty()) {
                            modelMarks(level, variant, st, turn, block, surface[i], model, ca, cb, glow, cam, fade);
                            continue;
                        }
                        // A stair's riser smokes from the ground up
                        sides(level, variant, st, turn, block, surface[i], cam, fade);
                        // Every top of its shape: both treads of a stair
                        for (double local : tops(level, block)) {
                            for (FloatArrayList out : (fused(level, block) ? SAND : SOIL)[variant]) {
                                splatPart(level, out, st.gone(), block, Direction.UP, ca, cb, local, cam, fade,
                                        SCORCH_HALF, turn, false, SCORCH_LIFT);
                            }
                            if (glow > 0) {
                                splatPart(level, emberLists[variant], st.gone(), block, Direction.UP, ca, cb, local, cam,
                                        glow, SCORCH_HALF, turn, true, EMBER_LIFT);
                            }
                        }
                    }
                }
                // Each pair of neighbouring columns once: toward east and toward south
                for (Direction dir : PAIRS) {
                    int nx = dx + dir.getStepX(), nz = dz + dir.getStepZ();
                    if (nx > reach || nz > reach) {
                        continue;
                    }
                    int j = (nx + reach) * size + nz + reach;
                    if (Double.isNaN(surface[j]) || (Double.isInfinite(surface[i]) && Double.isInfinite(surface[j]))
                            || Math.abs(surface[j] - surface[i]) < 1.0e-3) {
                        continue;
                    }
                    boolean here = surface[i] > surface[j];
                    int wx = ground.getX() + (here ? dx : nx), wz = ground.getZ() + (here ? dz : nz);
                    Direction face = here ? dir : dir.getOpposite();
                    double low = surface[here ? j : i], high = surface[here ? i : j];
                    if (Double.isInfinite(low) || Double.isInfinite(high) || high - low > 1 + 1.0e-3) {
                        fold(level, variant, st, turn, wx, wz, face, low, high, cam, fade);
                    } else {
                        drape(level, variant, st, turn, wx, DecalTracker.groundY(high), wz, face, low, high, cam, fade);
                    }
                }
            }
        }
    }

    /**
     * Scorch on the side face of a step at most a block high, on the blocks of the higher column (x, z) up to topY:
     * the mark's pixel row along the top edge runs down the face and its row along the foot runs up it, so the burn
     * carries straight on over both edges. Nearer the strike the soot reaches further, so its ragged end makes an arch
     * rather than a line, and a step near the heart is covered whole. Where two marks reach the same wall only the
     * newest is drawn on it, as their streaks would differ and flicker.
     */
    private static void drape(Level level, int variant, DecalTracker.Strike st, int turn, int x, int topY, int z,
                              Direction face, double lowSurface, double highSurface, Vec3 cam, float fade) {
        Direction across = across(face);
        boolean alongX = across.getAxis() == Direction.Axis.X;
        boolean acrossPositive = across.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        boolean facePositive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double centreAlong = alongX ? st.x() : st.z();
        double centreFixed = alongX ? st.z() : st.x();
        double fixed = (alongX ? z : x) + (facePositive ? 1 : 0);
        if (Math.abs(fixed - centreFixed) >= SCORCH_HALF) {
            return;
        }
        // Rows of texels just inside the higher ground's edge and just outside it, at texel centres
        double inward = (facePositive ? -0.5 : 0.5) / 16;
        double rowHigh = fixed + inward - centreFixed, rowLow = fixed - inward - centreFixed;
        int height = (int) Math.round((highSurface - lowSurface) * 16);
        double plane = facePositive ? 1 : 0;
        int blockAlong = alongX ? x : z;
        for (int y = (int) Math.floor(lowSurface + 1.0e-4); y <= topY; y++) {
            BlockPos block = new BlockPos(x, y, z);
            BlockPos air = block.relative(face);
            DecalTracker.Key wall = new DecalTracker.Key(block.asLong(), face);
            List<float[]> rects = faceRects(level, block, face, plane);
            if (rects.isEmpty() || WALLS_TAKEN.contains(wall) || st.gone().contains(block.asLong())
                    || level.getBlockState(air).isCollisionShapeFullBlock(level, air)) {
                continue;
            }
            int light = OverlayRenderer.packedLight(level, air);
            FloatArrayList[] outs = (fused(level, block) ? SAND : SOIL)[variant];
            boolean drawn = false;
            for (int k = 0; k < 16; k++) {
                double offset = blockAlong + (k + 0.5) / 16 - centreAlong; // this pixel column's centre from the strike
                float a0 = acrossPositive ? k / 16f : 1 - (k + 1) / 16f, a1 = a0 + 1 / 16f;
                if (Math.abs(offset) >= SCORCH_HALF) {
                    continue;
                }
                int bits = OverlayRenderer.hash(st.seed() ^ (int) Math.floor((offset + centreAlong) * 16) * 0x9E3779B9
                        ^ (int) Math.round(fixed * 16) * 0x632BE5AB);
                int reach = (int) Math.round(STREAK_PX * (1 - Math.hypot(offset, fixed - centreFixed) / SCORCH_HALF));
                int split = height < 2 ? height : Math.clamp(height / 2 + ragged(bits), 1, height - 1);
                int down = Math.min(split, reach + ragged(bits >>> 8)), up = Math.min(height - split, reach + ragged(bits >>> 16));
                // Each part of the wall's shape this pixel column crosses
                for (float[] rect : rects) {
                    if (a0 < rect[0] - 1.0e-4f || a1 > rect[1] + 1.0e-4f) {
                        continue;
                    }
                    float bottom = (float) Math.max(rect[2], lowSurface - y), roof = (float) Math.min(rect[3], highSurface - y);
                    for (FloatArrayList out : outs) {
                        int color = tint(out, fade, face);
                        drawn |= streak(out, block, face, plane, a0, a1,
                                Math.max(bottom, (float) (highSurface - y - down / 16.0)), roof,
                                texel(turn, alongX, offset, rowHigh, false), texel(turn, alongX, offset, rowHigh, true),
                                cam, color, light);
                        drawn |= streak(out, block, face, plane, a0, a1, bottom,
                                Math.min(roof, (float) (lowSurface - y + up / 16.0)),
                                texel(turn, alongX, offset, rowLow, false), texel(turn, alongX, offset, rowLow, true),
                                cam, color, light);
                    }
                }
            }
            if (drawn) {
                WALLS_TAKEN.add(wall);
            }
        }
    }

    /**
     * Soot on the face of a tall step, or of one whose far side lies beyond the strike's reach (an infinite surface: a
     * cliff, a drop). The mark on the side the strike is on carries on round the edge onto the face for up to a block,
     * as if that ground went on past the edge: up a cliff from its foot, down a drop from its rim. So it fades out
     * with distance from the strike just as the mark does; a face turned away from the strike stays clean.
     */
    private static void fold(Level level, int variant, DecalTracker.Strike st, int turn, int x, int z, Direction face,
                             double lowSurface, double highSurface, Vec3 cam, float fade) {
        Direction across = across(face);
        boolean alongX = across.getAxis() == Direction.Axis.X;
        boolean acrossPositive = across.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        int sign = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        double centreAlong = alongX ? st.x() : st.z();
        double centreFixed = alongX ? st.z() : st.x();
        double fixed = (alongX ? z : x) + (sign > 0 ? 1 : 0);
        int blockAlong = alongX ? x : z;
        // Up from the foot when the strike is out in front of the face, down from the rim when it is behind it
        boolean up = sign * (fixed - centreFixed) <= 0;
        double edge = up ? lowSurface : highSurface;
        if (Double.isInfinite(edge) || Math.abs(fixed - centreFixed) >= SCORCH_HALF
                || blockAlong + 1 <= centreAlong - SCORCH_HALF || blockAlong >= centreAlong + SCORCH_HALF) {
            return;
        }
        double from = up ? edge : Math.max(edge - 1, lowSurface), to = up ? Math.min(edge + 1, highSurface) : edge;
        int away = up ? -sign : sign; // which way the ground runs on past the edge, folded onto the face
        double plane = sign > 0 ? 1 : 0;
        for (int y = (int) Math.floor(from + 1.0e-4); y < to - 1.0e-4; y++) {
            BlockPos block = new BlockPos(x, y, z);
            BlockPos air = block.relative(face);
            DecalTracker.Key wall = new DecalTracker.Key(block.asLong(), face);
            List<float[]> rects = faceRects(level, block, face, plane);
            if (rects.isEmpty() || WALLS_TAKEN.contains(wall) || st.gone().contains(block.asLong())
                    || level.getBlockState(air).isCollisionShapeFullBlock(level, air)) {
                continue;
            }
            int light = OverlayRenderer.packedLight(level, air);
            for (float[] rect : rects) {
                float b0 = (float) Math.max(rect[2], from - y), b1 = (float) Math.min(rect[3], to - y);
                if (b1 <= b0) {
                    continue;
                }
                for (FloatArrayList out : (fused(level, block) ? SAND : SOIL)[variant]) {
                    int color = tint(out, fade, face);
                    for (int c = 0; c < 4; c++) {
                        float a = c == 1 || c == 2 ? rect[1] : rect[0], b = c >= 2 ? b1 : b0;
                        double offset = blockAlong + (acrossPositive ? a : 1 - a) - centreAlong;
                        double row = fixed + away * Math.abs(y + b - edge) - centreFixed;
                        vertex(out, block, face, plane, a, b, cam, SCORCH_LIFT, texel(turn, alongX, offset, row, false),
                                texel(turn, alongX, offset, row, true), color);
                    }
                    end(out, face, light);
                }
            }
            WALLS_TAKEN.add(wall);
        }
    }

    /**
     * Soot on the side faces inside a block's cell of a column the mark lies on: the riser of a stair, from its shape.
     * Posts, fences and lanterns go by their model instead (see modelMarks), as their outline shape is not what is seen.
     */
    private static void sides(Level level, int variant, DecalTracker.Strike st, int turn, BlockPos block, double ground,
                              Vec3 cam, float fade) {
        if (st.gone().contains(block.asLong())) {
            return;
        }
        int light = OverlayRenderer.packedLight(level, block);
        for (AABB box : level.getBlockState(block).getShape(level, block).toAabbs()) {
            for (Direction face : Direction.Plane.HORIZONTAL) {
                boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                double plane = positive ? box.max(face.getAxis()) : box.min(face.getAxis());
                if (plane > 1.0e-3 && plane < 1 - 1.0e-3) { // on the cell's edge it is a wall between columns
                    side(level, variant, st, turn, block, ground, face, plane, low(box, across(face)),
                            high(box, across(face)), box.minY, box.maxY, cam, fade, light);
                }
            }
        }
    }

    /**
     * Soot on one upright face, a0..a1 across and y0..y1 up within the block, on the block's own pixel grid: along the
     * face each pixel takes the mark's texel on the ground there, and each pixel row higher up takes its texels from
     * further out, away from the strike. How much further grows with the face's distance from the strike, so a post at
     * the strike is charred all the way up and soot on posts further out thins sooner with height.
     */
    private static void side(Level level, int variant, DecalTracker.Strike st, int turn, BlockPos block, double ground,
                             Direction face, double plane, float a0, float a1, double y0, double y1, Vec3 cam, float fade,
                             int light) {
        double edge = SCORCH_HALF + 0.95; // past the mark, short of where its texture would wrap round
        Direction across = across(face);
        // The face's two ends on the ground, from the strike point
        double[] ends = new double[4];
        for (int e = 0; e < 2; e++) {
            double[] local = new double[3];
            local[face.getAxis().ordinal()] = plane;
            place(local, across, e == 0 ? a0 : a1);
            ends[e * 2] = block.getX() + local[0] - st.x();
            ends[e * 2 + 1] = block.getZ() + local[2] - st.z();
        }
        double far = Math.max(Math.hypot(ends[0], ends[1]), Math.hypot(ends[2], ends[3]));
        if (far >= edge) {
            return;
        }
        // Out of the face when it looks away from the strike, into it when it looks toward it
        double mx = (ends[0] + ends[2]) / 2, mz = (ends[1] + ends[3]) / 2;
        int away = mx * face.getStepX() + mz * face.getStepZ() < 0 ? -1 : 1;
        double dx = face.getStepX() * away, dz = face.getStepZ() * away;
        double climb = Math.min(1, Math.hypot(mx, mz) / SCORCH_HALF); // texels further out per pixel up
        FloatArrayList[] outs = (fused(level, block) ? SAND : SOIL)[variant];
        int rows = (int) Math.round((y1 - y0) * 16);
        for (int r = 0; r < rows; r++) {
            float b0 = (float) (y0 + r / 16.0), b1 = r == rows - 1 ? (float) y1 : b0 + 1 / 16f;
            int out = (int) (climb * Math.abs(block.getY() + (b0 + b1) / 2 - ground) * 16);
            double step = (out + 0.5) / 16; // the middle of a texel: the face itself lies on a texel edge
            if (far + step >= edge) {
                break;
            }
            for (FloatArrayList list : outs) {
                int color = tint(list, fade, face);
                for (int c = 0; c < 4; c++) {
                    int e = c == 1 || c == 2 ? 1 : 0;
                    double qx = ends[e * 2] + dx * step, qz = ends[e * 2 + 1] + dz * step;
                    vertex(list, block, face, plane, e == 0 ? a0 : a1, c >= 2 ? b1 : b0, cam, SCORCH_LIFT,
                            markTex(turn, qx, qz, SCORCH_HALF + 1, false),
                            markTex(turn, qx, qz, SCORCH_HALF + 1, true), color);
                }
                end(list, face, light);
            }
        }
    }

    /**
     * The mark on a post, fence or lantern standing on the ground, laid on the faces of its model as the player sees
     * them: on what faces up as on the ground, and up its sides like soot (see side). Faces pressed against the next
     * block, like the ends of a fence post standing on another, stay clean.
     */
    private static void modelMarks(Level level, int variant, DecalTracker.Strike st, int turn, BlockPos block,
                                   double ground, List<float[]> model, float ca, float cb, float glow, Vec3 cam,
                                   float fade) {
        if (st.gone().contains(block.asLong())) {
            return;
        }
        int light = OverlayRenderer.packedLight(level, block);
        for (float[] q : model) {
            Direction face = Direction.values()[(int) q[0]];
            AABB box = quadBox(q);
            Direction.Axis axis = face.getAxis();
            double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(axis) : box.min(axis);
            if (face == Direction.DOWN || box.max(axis) - box.min(axis) > 1.0e-3
                    || (atBoundary(face, plane) && pressed(level, block, face, box))) {
                continue; // underneath, slanted, or pressed against the next block
            }
            if (face == Direction.UP) {
                float[] rect = {low(box, across(face)), high(box, across(face)), low(box, up(face)), high(box, up(face))};
                if (rect[1] <= ca - SCORCH_HALF || rect[0] >= ca + SCORCH_HALF || rect[3] <= cb - SCORCH_HALF
                        || rect[2] >= cb + SCORCH_HALF) {
                    continue; // past the mark, where its texture would wrap round
                }
                for (FloatArrayList out : (fused(level, block) ? SAND : SOIL)[variant]) {
                    rectQuad(out, block, face, plane, rect, ca, cb, SCORCH_HALF, turn, SCORCH_LIFT, tint(out, fade, face),
                            light, cam);
                }
                if (glow > 0) {
                    rectQuad(emberLists[variant], block, face, plane, rect, ca, cb, SCORCH_HALF, turn, EMBER_LIFT,
                            OverlayRenderer.argb(glow, 0xFFFFFF, 1), OverlayRenderer.FULL_BRIGHT, cam);
                }
            } else {
                side(level, variant, st, turn, block, ground, face, plane, low(box, across(face)), high(box, across(face)),
                        box.minY, box.maxY, cam, fade, light);
            }
        }
    }

    /** Whether block is a post, fence, wall or lantern rather than ground: it does not cover its whole cell. */
    private static boolean thin(Level level, BlockPos block, BlockState state) {
        if (FOOTPRINTS.size() > 512) {
            FOOTPRINTS.clear();
        }
        return !FOOTPRINTS.computeIfAbsent(state, s -> DecalTracker.fullFootprint(level, block));
    }

    /** The flat box round a model quad {direction, then x, y, z of four corners}. */
    private static AABB quadBox(float[] q) {
        return new AABB(Math.min(Math.min(q[1], q[4]), Math.min(q[7], q[10])),
                Math.min(Math.min(q[2], q[5]), Math.min(q[8], q[11])),
                Math.min(Math.min(q[3], q[6]), Math.min(q[9], q[12])),
                Math.max(Math.max(q[1], q[4]), Math.max(q[7], q[10])),
                Math.max(Math.max(q[2], q[5]), Math.max(q[8], q[11])),
                Math.max(Math.max(q[3], q[6]), Math.max(q[9], q[12])));
    }

    /** The quads of state's block model (see Compat.modelQuads), looked up once per state. */
    private static List<float[]> model(BlockState state) {
        if (MODELS.size() > 512) {
            MODELS.clear();
        }
        return MODELS.computeIfAbsent(state, Compat::modelQuads);
    }

    /** One pixel column of a wall from height b0 to b1 in the colour of one texel (u, v) of the scorch texture. */
    private static boolean streak(FloatArrayList out, BlockPos block, Direction face, double plane, float a0, float a1,
                                  float b0, float b1, float u, float v, Vec3 cam, int color, int light) {
        if (b1 <= b0) {
            return false;
        }
        vertex(out, block, face, plane, a0, b0, cam, SCORCH_LIFT, u, v, color);
        vertex(out, block, face, plane, a1, b0, cam, SCORCH_LIFT, u, v, color);
        vertex(out, block, face, plane, a1, b1, cam, SCORCH_LIFT, u, v, color);
        vertex(out, block, face, plane, a0, b1, cam, SCORCH_LIFT, u, v, color);
        end(out, face, light);
        return true;
    }

    /** Scorch texture coordinate of the texel at offset along a wall and row across it from the strike point. */
    private static float texel(int turn, boolean alongX, double offset, double row, boolean wantV) {
        return markTex(turn, alongX ? offset : row, alongX ? row : offset, SCORCH_HALF + 1, wantV);
    }

    /**
     * Vertex colour of a decal quad: white for soot that multiplies the block under it, which is shaded already;
     * otherwise shaded like the face it lies on (shader packs shade it themselves).
     */
    private static int tint(FloatArrayList out, float alpha, Direction face) {
        boolean multiplies = false;
        for (int i = 0; i < LIGHTNING_VARIANTS; i++) {
            multiplies |= out == scorchLists[i] || out == sinterLists[i];
        }
        return OverlayRenderer.argb(alpha, 0xFFFFFF, OverlayRenderer.lit || multiplies ? 1 : faceShade(face));
    }

    /** Lightning fuses sand into glass rather than charring it. */
    private static boolean fused(Level level, BlockPos block) {
        return level.getBlockState(block).is(BlockTags.SAND);
    }

    /** -2 to 2 pixels, from the low bits. */
    private static int ragged(int bits) {
        return Math.floorMod(bits & 0xFF, 5) - 2;
    }

    /**
     * The part of a mark centred at (ca, cb) in block's face coordinates that lies on the given surface plane of that
     * block: each part of the block's shape in that plane whole, with the mark's texture laid over it and the
     * texture's clear margin over the rest. So marks that overlap on a face have the same quads and blend instead of fighting
     * over depth. Skipped when the mark does not reach the face, the block changed since the mark was made (gone), or
     * the face is covered by a full block in front of it. turn turns and mirrors the texture (see markTex).
     */
    private static void splatPart(Level level, FloatArrayList out, LongOpenHashSet gone, BlockPos block, Direction face,
                                  float ca, float cb, double surface, Vec3 cam, float alpha, float half, int turn,
                                  boolean glow, double lift) {
        if (ca + half <= 0 || ca - half >= 1 || cb + half <= 0 || cb - half >= 1 || gone.contains(block.asLong())) {
            return;
        }
        List<float[]> shapes = faceRects(level, block, face, surface);
        if (shapes.isEmpty()) {
            return;
        }
        BlockPos air = block.relative(face);
        if (atBoundary(face, surface) && level.getBlockState(air).isCollisionShapeFullBlock(level, air)) {
            return;
        }
        // Lit by the lightmap like the block under it; glowing parts (embers) stay bright in the dark
        int color = glow ? OverlayRenderer.argb(alpha, 0xFFFFFF, 1) : tint(out, alpha, face);
        int light = glow ? OverlayRenderer.FULL_BRIGHT : OverlayRenderer.packedLight(level, air);
        for (float[] shape : shapes) {
            rectQuad(out, block, face, surface, shape, ca, cb, half, turn, lift, color, light, cam);
        }
    }

    /** One rectangle {a0, a1, b0, b1} of a face with the texture of a mark centred at (ca, cb) laid over it. */
    private static void rectQuad(FloatArrayList out, BlockPos block, Direction face, double plane, float[] rect, float ca,
                                 float cb, float half, int turn, double lift, int color, int light, Vec3 cam) {
        for (int c = 0; c < 4; c++) {
            float a = c == 1 || c == 2 ? rect[1] : rect[0], b = c >= 2 ? rect[3] : rect[2];
            vertex(out, block, face, plane, a, b, cam, lift, markTex(turn, a - ca, cb - b, half + 1, false),
                    markTex(turn, a - ca, cb - b, half + 1, true), color);
        }
        end(out, face, light);
    }

    /**
     * Texture coordinate (v when wantV, else u) of a point (x, y) in blocks from the middle of a mark, y growing down
     * the texture, on a sheet texHalf blocks either way of it, turned by turn: 0 to 3 quarter turns, plus 4 mirrored.
     */
    private static float markTex(int turn, double x, double y, double texHalf, boolean wantV) {
        if ((turn & 4) != 0) {
            x = -x;
        }
        int quarter = turn & 3;
        int cos = quarter == 0 ? 1 : quarter == 2 ? -1 : 0, sin = quarter == 1 ? 1 : quarter == 3 ? -1 : 0;
        double t = wantV ? sin * x + cos * y : cos * x - sin * y;
        return (float) ((t + texHalf) / (2 * texHalf));
    }

    /**
     * Face-coordinate rectangles {a0, a1, b0, b1} of the parts of block's shape whose side toward face lies in the
     * surface plane (block-local coordinate along the face normal), one per box of the shape, so an L-shaped side or
     * a fence's rails are covered without the gaps between them.
     */
    static List<float[]> faceRects(Level level, BlockPos block, Direction face, double surface) {
        List<float[]> rects = new ArrayList<>(2);
        BlockState state = level.getBlockState(block);
        if (state.isAir() || state.getBlock() instanceof BaseFireBlock) {
            return rects;
        }
        Direction.Axis axis = face.getAxis();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        Direction t1 = across(face), t2 = up(face);
        // A fence or wall's outline is not what is seen (its arms are solid between the rails): go by its model
        List<float[]> model = thin(level, block, state) ? model(state) : List.of();
        for (float[] q : model) {
            AABB box = quadBox(q);
            double side = positive ? box.max(axis) : box.min(axis);
            if (q[0] == face.ordinal() && box.max(axis) - box.min(axis) <= 1.0e-3 && Math.abs(side - surface) <= 1.0e-3
                    && !(atBoundary(face, surface) && pressed(level, block, face, box))) {
                rects.add(new float[] {low(box, t1), high(box, t1), low(box, t2), high(box, t2)});
            }
        }
        if (!model.isEmpty()) {
            return rects;
        }
        for (AABB box : state.getShape(level, block).toAabbs()) {
            double side = positive ? box.max(axis) : box.min(axis);
            if (Math.abs(side - surface) <= 1.0e-3 && !(atBoundary(face, surface) && pressed(level, block, face, box))) {
                rects.add(new float[] {low(box, t1), high(box, t1), low(box, t2), high(box, t2)});
            }
        }
        return rects;
    }

    /** Whether the side of box toward face is pressed against a part of the next block, like a post on a post. */
    private static boolean pressed(Level level, BlockPos block, Direction face, AABB box) {
        BlockPos next = block.relative(face);
        Direction.Axis axis = face.getAxis();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        for (AABB other : level.getBlockState(next).getShape(level, next).toAabbs()) {
            if ((positive ? other.min(axis) : 1 - other.max(axis)) > 1.0e-3) {
                continue;
            }
            boolean covers = true;
            for (Direction.Axis in : Direction.Axis.values()) {
                covers &= in == axis || other.min(in) <= box.min(in) + 1.0e-3 && other.max(in) >= box.max(in) - 1.0e-3;
            }
            if (covers) {
                return true;
            }
        }
        return false;
    }

    /** The rectangle round all of faceRects; null when there are none. */
    static float[] faceRect(Level level, BlockPos block, Direction face, double surface) {
        float[] all = null;
        for (float[] r : faceRects(level, block, face, surface)) {
            all = all == null ? r.clone()
                    : new float[] {Math.min(all[0], r[0]), Math.max(all[1], r[1]), Math.min(all[2], r[2]), Math.max(all[3], r[3])};
        }
        return all;
    }

    /** The heights (0..1) of the top faces of block's shape, each once. */
    private static DoubleArrayList tops(Level level, BlockPos block) {
        DoubleArrayList heights = new DoubleArrayList(2);
        for (AABB box : level.getBlockState(block).getShape(level, block).toAabbs()) {
            boolean seen = false;
            for (int i = 0; i < heights.size(); i++) {
                seen |= Math.abs(heights.getDouble(i) - box.maxY) <= 1.0e-3;
            }
            if (!seen) {
                heights.add(box.maxY);
            }
        }
        return heights;
    }

    private static float low(AABB box, Direction t) {
        return (float) (t.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.min(t.getAxis()) : 1 - box.max(t.getAxis()));
    }

    private static float high(AABB box, Direction t) {
        return (float) (t.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(t.getAxis()) : 1 - box.min(t.getAxis()));
    }

    /** The surface plane is the block cell's own face (not a slab top or the side of a pane inside the cell). */
    private static boolean atBoundary(Direction face, double surface) {
        double edge = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        return Math.abs(surface - edge) <= 1.0e-3;
    }

    /** One cell of a face in face coordinates (x across, y up, 0..1), with the whole texture mapped over the face. */
    private static void rect(FloatArrayList out, BlockPos pos, Direction face, double plane, Vec3 cam,
                             float x0, float x1, float y0, float y1, boolean flipU, boolean flipV, int color, int light) {
        vertex(out, pos, face, plane, x0, y0, cam, ASH_LIFT, u(x0, flipU), v(y0, flipV), color);
        vertex(out, pos, face, plane, x1, y0, cam, ASH_LIFT, u(x1, flipU), v(y0, flipV), color);
        vertex(out, pos, face, plane, x1, y1, cam, ASH_LIFT, u(x1, flipU), v(y1, flipV), color);
        vertex(out, pos, face, plane, x0, y1, cam, ASH_LIFT, u(x0, flipU), v(y1, flipV), color);
        end(out, face, light);
    }

    /**
     * The colour of pixel cell (i, j) of an ash face, with the corners' alpha (left-bottom, right-bottom,
     * right-top, left-top) blended to its middle, give or take a little per pixel where it is neither built up nor
     * gone, so a patch fading toward a corner ends in a ragged pixel edge rather than a straight one.
     */
    private static int pixel(Ash a, float[] corner, int i, int j, float step) {
        float x = (i + 0.5f) * step, y = (j + 0.5f) * step;
        float bottom = corner[0] + (corner[1] - corner[0]) * x, top = corner[3] + (corner[2] - corner[3]) * x;
        float alpha = bottom + (top - bottom) * y;
        float jitter = ((OverlayRenderer.hash(a.seed + i * 131 + j * 7919 + 17) & 0xFF) / 255f - 0.5f) * 0.4f;
        alpha += jitter * (1 - Math.abs(2 * alpha - 1));
        return soot(Math.clamp(alpha, 0f, 1f), a.shade);
    }

    /** Vertex colour of ash as opaque as alpha, shaded like its face (shader packs shade it themselves). */
    private static int soot(float alpha, float shade) {
        return OverlayRenderer.argb(alpha, 0xFFFFFF, OverlayRenderer.lit ? 1 : shade);
    }

    private static float u(float x, boolean flip) {
        return flip ? 1 - x : x;
    }

    private static float v(float y, boolean flip) {
        return flip ? y : 1 - y; // v grows downward in the texture
    }

    /**
     * One corner of a decal quad on the plane of a face, pulled toward the eye by lift; further off by more, as depth
     * gets coarser with distance.
     */
    private static void vertex(FloatArrayList out, BlockPos pos, Direction face, double plane, float a, float b, Vec3 cam,
                               double lift, float u, float v, int color) {
        double[] local = new double[3];
        local[face.getAxis().ordinal()] = plane;
        place(local, across(face), a);
        place(local, up(face), b);
        double x = pos.getX() + local[0] - cam.x, y = pos.getY() + local[1] - cam.y, z = pos.getZ() + local[2] - cam.z;
        double distance = Math.sqrt(x * x + y * y + z * z);
        double keep = Math.max(0, 1 - lift * Math.max(1, distance / 8) / distance);
        out.add((float) (x * keep));
        out.add((float) (y * keep));
        out.add((float) (z * keep));
        out.add(u);
        out.add(v);
        out.add(Float.intBitsToFloat(color));
    }

    private static void end(FloatArrayList out, Direction face, int light) {
        if (face.getAxis().isHorizontal()) {
            // Wall faces run "across" clockwise, which winds their quads backwards; the decal render type culls back
            // faces, so swap the second and fourth vertex to face outward like floors and ceilings do
            int second = out.size() - 18, fourth = out.size() - 6;
            float[] e = out.elements();
            for (int k = 0; k < 6; k++) {
                float t = e[second + k];
                e[second + k] = e[fourth + k];
                e[fourth + k] = t;
            }
        }
        out.add(face.getStepX());
        out.add(face.getStepY());
        out.add(face.getStepZ());
        out.add(Float.intBitsToFloat(light));
    }

    /** Sets the block-local coordinate along axis t to face coordinate c (0 at the start of t, 1 at its end). */
    private static void place(double[] local, Direction t, float c) {
        local[t.getAxis().ordinal()] = t.getAxisDirection() == Direction.AxisDirection.POSITIVE ? c : 1 - c;
    }

    private static float coord(Direction t, double[] local) {
        double c = local[t.getAxis().ordinal()];
        return (float) (t.getAxisDirection() == Direction.AxisDirection.POSITIVE ? c : 1 - c);
    }

    /** Vanilla's fixed shading per block face, so a decal is as bright as the face it lies on (shader packs do their own). */
    private static float faceShade(Direction face) {
        return switch (face) {
            case UP -> 1f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            case EAST, WEST -> 0.6f;
        };
    }

    /** Face "right" axis: as on glass for walls; east on floors and ceilings. */
    private static Direction across(Direction face) {
        return face.getAxis().isHorizontal() ? face.getClockWise() : Direction.EAST;
    }

    /** Face "up" axis: up on walls; north on floors, south on ceilings. */
    private static Direction up(Direction face) {
        return face.getAxis().isHorizontal() ? Direction.UP : face == Direction.UP ? Direction.NORTH : Direction.SOUTH;
    }
}
