package com.w1z4r_d.livingglass.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.w1z4r_d.livingglass.LivingGlassConfig;
import com.w1z4r_d.livingglass.decal.DecalTracker;
import com.w1z4r_d.livingglass.decal.DecalTracker.Ash;
import com.w1z4r_d.livingglass.decal.DecalTracker.Kind;
import com.w1z4r_d.livingglass.decal.DecalTracker.Splat;
import com.w1z4r_d.livingglass.glass.Melt;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/** Draws ash and snowball splats on block faces, with the same pixel melt as the glass when ash patches merge. */
final class DecalRenderer {
    private static final int VARIANTS = 3;
    /** Full scorch textures: variant x 16 edge masks; side ash: variant x 4 left/right masks, after them. */
    private static final int SIDE_BASE = VARIANTS * 16;
    private static final FloatArrayList[] ashLists = lists(SIDE_BASE + VARIANTS * 4);
    private static final FloatArrayList[] snowLists = lists(VARIANTS);
    /**
     * Decals lie right on the block surface (the depth bias of their render type keeps them in front of it). Kinds
     * that can overlap on one face are pulled toward the eye by different hairs, so they never share a depth and
     * flicker; toward the eye rather than off the face, so they do not move on screen and decals on two faces that
     * meet at an outside edge close up with no gap.
     */
    private static final double ASH_LIFT = 0, SCORCH_LIFT = 0.001, SNOW_LIFT = 0.002, EMBER_LIFT = 0.003;
    /** Snow splat texture is 10 pixels wide, at the same pixel size as the block textures. */
    private static final float SPLAT_HALF = 5 / 16f;
    /** The lightning scorch texture is 64 pixels, four blocks across. */
    private static final float SCORCH_HALF = 2;
    /** Embers glow for this long after the strike, then only the burn mark is left. */
    private static final int EMBER_TICKS = 200;
    /** How many pixels a burn mark runs down (or up) the side of a tall step in the ground. */
    private static final int DRAPE_PX = 12;
    private static final Direction[] PAIRS = {Direction.EAST, Direction.SOUTH};
    private static final FloatArrayList[] scorchLists = lists(VARIANTS);
    private static final FloatArrayList[] emberLists = lists(VARIANTS);
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
        for (DecalTracker.Strike st : decals.strikes()) {
            if (BlockPos.of(st.ground()).distToCenterSqr(cam) <= maxDist) {
                drawStrike(level, st, cam, now - st.born(), scorchLife);
            }
        }
        for (int i = 0; i < ashLists.length; i++) {
            String name = i < SIDE_BASE ? "ash_full_" + i / 16 + "_" + i % 16
                    : "ash_side_" + (i - SIDE_BASE) / 4 + "_" + (i - SIDE_BASE) % 4;
            OverlayRenderer.submitDecal(collector, poseStack, name, ashLists[i]);
        }
        for (int i = 0; i < VARIANTS; i++) {
            OverlayRenderer.submitDecal(collector, poseStack, "snow_" + i, snowLists[i]);
            OverlayRenderer.submitDecal(collector, poseStack, "lightning_scorch_" + i, scorchLists[i]);
            OverlayRenderer.submitDecal(collector, poseStack, "lightning_embers_" + i, emberLists[i]);
        }
    }

    /** Light and which neighbouring faces carry ash too, so neighbouring patches join without an edge. */
    private static void refresh(Level level, Map<DecalTracker.Key, Ash> all, Ash a, BlockPos pos, long now) {
        a.renderTime = now + Math.floorMod(a.seed, 10);
        a.shade = faceShade(a.face);
        a.light = OverlayRenderer.packedLight(level, pos.relative(a.face));
        Direction t1 = across(a.face), t2 = up(a.face);
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
     * round the inside corner onto the wall (or floor) that meets it there.
     */
    private static boolean joined(Map<DecalTracker.Key, Ash> all, BlockPos pos, Direction face, Direction t) {
        return ashy(all, pos.relative(t), face) || ashy(all, pos.relative(t).relative(face), t.getOpposite());
    }

    private static boolean ashy(Map<DecalTracker.Key, Ash> all, BlockPos pos, Direction face) {
        Ash n = all.get(new DecalTracker.Key(pos.asLong(), face));
        return n != null && (n.target > 0 || n.amount > 0.02f);
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
        int color = OverlayRenderer.argb(a.amount, 0xFFFFFF, OverlayRenderer.lit ? 1 : a.shade);
        int light = a.light;
        Melt look = a.look;
        float[] r = a.rect;
        if (r == null) {
            return;
        }
        int cells = look.melting() ? OverlayRenderer.GRID : 1;
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
                    rect(out, pos, a.face, a.plane, cam, x0, x1, y0, y1, flipU, flipV, color, light);
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
        FloatArrayList out = snowLists[Math.floorMod(s.seed(), VARIANTS)];
        for (int da = -1; da <= 1; da++) {
            for (int db = -1; db <= 1; db++) {
                splatPart(level, out, s.gone(), pos.relative(t1, da).relative(t2, db), face, a - da, b - db, surface, cam,
                        alpha, SPLAT_HALF, false, SNOW_LIFT);
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
                        alpha, SPLAT_HALF, false, SNOW_LIFT);
            } else {
                // Inside corner: the wall goes on, and a block in front of it stands out; climb its side face
                BlockPos corner = side.relative(face);
                Direction back = t.getOpposite();
                folded[axis] = tPositive ? 0 : 1;
                folded[normal] = (positive ? 0 : 1) - (positive ? toEdge : -toEdge);
                splatPart(level, out, s.gone(), corner, back, coord(across(back), folded), coord(up(back), folded),
                        tPositive ? 0 : 1, cam, alpha, SPLAT_HALF, false, SNOW_LIFT);
            }
        }
    }

    /**
     * A lightning burn mark projected from above onto the ground as it was at the strike: each column's part on its
     * top face then, and where neighbouring columns differ in height, on the side face of the step between them.
     * Embers glow on the top faces while the mark is fresh.
     */
    private static void drawStrike(Level level, DecalTracker.Strike st, Vec3 cam, long age, long life) {
        if (age >= life) {
            return;
        }
        float fade = 0.9f * (age < life * 2 / 3 ? 1 : (life - age) / (life / 3f));
        float glow = age < EMBER_TICKS ? 1 - age / (float) EMBER_TICKS : 0;
        BlockPos ground = BlockPos.of(st.ground());
        int variant = Math.floorMod(st.seed(), VARIANTS);
        int reach = DecalTracker.STRIKE_REACH, size = 2 * reach + 1;
        int[] top = st.top();
        double[] surface = st.surface();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int i = (dx + reach) * size + dz + reach;
                if (Double.isNaN(surface[i])) {
                    continue;
                }
                BlockPos block = new BlockPos(ground.getX() + dx, top[i], ground.getZ() + dz);
                // Floor face coordinates: x across, north up
                float ca = (float) (st.x() - block.getX());
                float cb = (float) (1 - (st.z() - block.getZ()));
                double local = surface[i] - top[i];
                splatPart(level, scorchLists[variant], st.gone(), block, Direction.UP, ca, cb, local, cam, fade, SCORCH_HALF,
                        false, SCORCH_LIFT);
                if (glow > 0) {
                    splatPart(level, emberLists[variant], st.gone(), block, Direction.UP, ca, cb, local, cam, glow, SCORCH_HALF,
                            true, EMBER_LIFT);
                }
                // Each pair of neighbouring columns once: toward east and toward south
                for (Direction dir : PAIRS) {
                    int nx = dx + dir.getStepX(), nz = dz + dir.getStepZ();
                    if (nx > reach || nz > reach) {
                        continue;
                    }
                    int j = (nx + reach) * size + nz + reach;
                    if (Double.isNaN(surface[j]) || Math.abs(surface[j] - surface[i]) < 1.0e-3) {
                        continue;
                    }
                    boolean here = surface[i] > surface[j];
                    int high = here ? i : j;
                    drape(level, scorchLists[variant], st, ground.getX() + (here ? dx : nx), top[high],
                            ground.getZ() + (here ? dz : nz), here ? dir : dir.getOpposite(), surface[here ? j : i],
                            surface[high], cam, fade);
                }
            }
        }
    }

    /**
     * Scorch on the side face of a step, on the blocks of the higher column (x, z) up to topY: the mark's pixel row
     * along the top edge runs down the face and its row along the foot runs up it, so the burn carries straight on
     * over both edges. Each pixel column ends raggedly about halfway, or after DRAPE_PX pixels on a tall step.
     */
    private static void drape(Level level, FloatArrayList out, DecalTracker.Strike st, int x, int topY, int z,
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
        // Texture rows just inside the higher ground's edge and just outside it, taken at texel centres
        double inward = (facePositive ? -0.5 : 0.5) / 16;
        float rowHigh = tex(fixed + inward - centreFixed), rowLow = tex(fixed - inward - centreFixed);
        int height = (int) Math.round((highSurface - lowSurface) * 16);
        int color = OverlayRenderer.argb(fade, 0xFFFFFF, OverlayRenderer.lit ? 1 : faceShade(face));
        double plane = facePositive ? 1 : 0;
        int blockAlong = alongX ? x : z;
        for (int y = (int) Math.floor(lowSurface + 1.0e-4); y <= topY; y++) {
            BlockPos block = new BlockPos(x, y, z);
            BlockPos air = block.relative(face);
            float[] rect = faceRect(level, block, face, plane);
            if (rect == null || st.gone().contains(block.asLong()) || level.getBlockState(air).isCollisionShapeFullBlock(level, air)) {
                continue;
            }
            int light = OverlayRenderer.packedLight(level, air);
            float bottom = (float) Math.max(rect[2], lowSurface - y), roof = (float) Math.min(rect[3], highSurface - y);
            for (int k = 0; k < 16; k++) {
                double along = blockAlong + (k + 0.5) / 16; // centre of this pixel column in the world
                float a0 = acrossPositive ? k / 16f : 1 - (k + 1) / 16f, a1 = a0 + 1 / 16f;
                if (Math.abs(along - centreAlong) >= SCORCH_HALF || a0 < rect[0] - 1.0e-4f || a1 > rect[1] + 1.0e-4f) {
                    continue;
                }
                int bits = OverlayRenderer.hash(st.seed() ^ (int) Math.floor(along * 16) * 0x9E3779B9
                        ^ (int) Math.round(fixed * 16) * 0x632BE5AB);
                int split = height < 2 ? height : Math.clamp(height / 2 + ragged(bits), 1, height - 1);
                int down = Math.min(split, DRAPE_PX + ragged(bits >>> 8));
                int up = Math.min(height - split, DRAPE_PX + ragged(bits >>> 16));
                float column = tex(along - centreAlong);
                streak(out, block, face, plane, a0, a1, Math.max(bottom, (float) (highSurface - y - down / 16.0)), roof,
                        alongX ? column : rowHigh, alongX ? rowHigh : column, cam, color, light);
                streak(out, block, face, plane, a0, a1, bottom, Math.min(roof, (float) (lowSurface - y + up / 16.0)),
                        alongX ? column : rowLow, alongX ? rowLow : column, cam, color, light);
            }
        }
    }

    /** One pixel column of a wall from height b0 to b1 in the colour of one texel (u, v) of the scorch texture. */
    private static void streak(FloatArrayList out, BlockPos block, Direction face, double plane, float a0, float a1,
                               float b0, float b1, float u, float v, Vec3 cam, int color, int light) {
        if (b1 <= b0) {
            return;
        }
        vertex(out, block, face, plane, a0, b0, cam, SCORCH_LIFT, u, v, color);
        vertex(out, block, face, plane, a1, b0, cam, SCORCH_LIFT, u, v, color);
        vertex(out, block, face, plane, a1, b1, cam, SCORCH_LIFT, u, v, color);
        vertex(out, block, face, plane, a0, b1, cam, SCORCH_LIFT, u, v, color);
        end(out, face, light);
    }

    /** Scorch texture coordinate of a world offset from the strike point. */
    private static float tex(double offset) {
        return (float) ((offset + SCORCH_HALF) / (2 * SCORCH_HALF));
    }

    /** -1, 0 or 1 pixel, from the low bits. */
    private static int ragged(int bits) {
        return Math.floorMod(bits & 0xFF, 3) - 1;
    }

    /**
     * The part of a splat centred at (ca, cb) in block's face coordinates that lies on the given surface plane of
     * that block, cut to the parts of the block's shape in that plane. Skipped when nothing is there, the block
     * changed since the mark was made (gone), or the face is covered by a full block in front of it.
     */
    private static void splatPart(Level level, FloatArrayList out, LongOpenHashSet gone, BlockPos block, Direction face,
                                  float ca, float cb, double surface, Vec3 cam, float alpha, float half, boolean glow,
                                  double lift) {
        if (ca + half <= 0 || ca - half >= 1 || cb + half <= 0 || cb - half >= 1 || gone.contains(block.asLong())) {
            return;
        }
        float[] shape = faceRect(level, block, face, surface);
        if (shape == null) {
            return;
        }
        float a0 = Math.max(shape[0], ca - half), a1 = Math.min(shape[1], ca + half);
        float b0 = Math.max(shape[2], cb - half), b1 = Math.min(shape[3], cb + half);
        if (a1 <= a0 || b1 <= b0) {
            return;
        }
        BlockPos air = block.relative(face);
        if (atBoundary(face, surface) && level.getBlockState(air).isCollisionShapeFullBlock(level, air)) {
            return;
        }
        // Lit by the lightmap like the block under it; glowing parts (embers) stay bright in the dark
        int color = OverlayRenderer.argb(alpha, 0xFFFFFF, OverlayRenderer.lit || glow ? 1 : faceShade(face));
        int light = glow ? OverlayRenderer.FULL_BRIGHT : OverlayRenderer.packedLight(level, air);
        // Texture coordinates follow the cut rectangle, so the splat is cut off at the edge, not squashed
        float size = 2 * half;
        float u0 = (a0 - (ca - half)) / size, u1 = (a1 - (ca - half)) / size;
        float v0 = 1 - (b0 - (cb - half)) / size, v1 = 1 - (b1 - (cb - half)) / size;
        vertex(out, block, face, surface, a0, b0, cam, lift, u0, v0, color);
        vertex(out, block, face, surface, a1, b0, cam, lift, u1, v0, color);
        vertex(out, block, face, surface, a1, b1, cam, lift, u1, v1, color);
        vertex(out, block, face, surface, a0, b1, cam, lift, u0, v1, color);
        end(out, face, light);
    }

    /**
     * Face-coordinate rectangle {a0, a1, b0, b1} covered by the parts of block's shape whose side toward face lies
     * in the surface plane (block-local coordinate along the face normal); null when no part does.
     */
    static float[] faceRect(Level level, BlockPos block, Direction face, double surface) {
        BlockState state = level.getBlockState(block);
        if (state.isAir() || state.getBlock() instanceof BaseFireBlock) {
            return null;
        }
        Direction.Axis axis = face.getAxis();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        Direction t1 = across(face), t2 = up(face);
        float a0 = 1, a1 = 0, b0 = 1, b1 = 0;
        for (AABB box : state.getShape(level, block).toAabbs()) {
            double side = positive ? box.max(axis) : box.min(axis);
            if (Math.abs(side - surface) > 1.0e-3) {
                continue;
            }
            a0 = Math.min(a0, low(box, t1));
            a1 = Math.max(a1, high(box, t1));
            b0 = Math.min(b0, low(box, t2));
            b1 = Math.max(b1, high(box, t2));
        }
        return a1 > a0 && b1 > b0 ? new float[] {a0, a1, b0, b1} : null;
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
