package com.w1z4r_d.residuum.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.compat.Compat;
import com.w1z4r_d.residuum.decal.Coats;
import com.w1z4r_d.residuum.decal.DecalTracker;
import com.w1z4r_d.residuum.decal.DecalTracker.Ash;
import com.w1z4r_d.residuum.decal.DecalTracker.Kind;
import com.w1z4r_d.residuum.decal.DecalTracker.Splat;
import com.w1z4r_d.residuum.glass.GlassTracker;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
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
    /** Ash: one soot sheet over ASH_SHEET x ASH_SHEET blocks, thinning out over ASH_EDGE toward a patch's edges. */
    private static final FloatArrayList ashList = new FloatArrayList();
    private static final int ASH_SHEET = 4;
    private static final float ASH_EDGE = 0.3f;
    /** How far a torch's soot spreads from over its flame, in blocks, and how far away so small a smudge is drawn. */
    private static final float SPOT_RADIUS = 5 / 16f;
    private static final double SPOT_DRAW_DIST_SQ = 64 * 64;
    /** How dark a torch's soot gets over its flame. */
    private static final float SPOT_STRENGTH = 0.8f;
    /** How much of its size a smudge starts from as it builds up, and how wide its soft edge is, as shares of its size. */
    private static final float SPOT_START = 0.35f, SPOT_EDGE = 0.3f;
    /** Which two edges (left, right, bottom, top) meet at each corner: left-bottom, right-bottom, right-top, left-top. */
    private static final int[][] CORNER_EDGES = {{0, 2}, {1, 2}, {1, 3}, {0, 3}};
    static final int SNOW_VARIANTS = 6;
    private static final FloatArrayList[] snowLists = lists(SNOW_VARIANTS);
    /**
     * Decals lie right on the block surface (the depth bias of their render type keeps them in front of it). Kinds
     * that can overlap on one face are pulled toward the eye by different hairs, so they never share a depth and
     * flicker; toward the eye rather than off the face, so they do not move on screen and decals on two faces that
     * meet at an outside edge close up with no gap.
     */
    private static final double ASH_LIFT = 0, SCORCH_LIFT = 0.001, SNOW_LIFT = 0.002, EMBER_LIFT = 0.003,
            PRINT_LIFT = 0.0015, CRACK_LIFT = 0.004, COAT_LIFT = 0.0005, HOLE_LIFT = 0.0025;
    /** About the angle one screen pixel covers, in radians, for the lift of solid decals seen at a slant (see slant). */
    private static final double PIXEL_ANGLE = 0.0025;
    /**
     * A snow splat is up to 14 pixels wide, at the same pixel size as the block textures, and a lightning scorch 64
     * pixels, four blocks across. Their textures carry a clear margin a block wide round the mark (see splatPart).
     */
    private static final float SPLAT_HALF = 7 / 16f;
    /** A crack is a web 20 pixels across, a footprint fits in 8 (see their textures). */
    private static final float CRACK_HALF = 10 / 16f, PRINT_HALF = 4 / 16f;
    static final int CRACK_VARIANTS = 4, PRINT_TEXTURES = 6;
    private static final FloatArrayList[] crackLists = lists(CRACK_VARIANTS);
    private static final FloatArrayList[] printLists = lists(PRINT_TEXTURES);
    /** An arrow's hole is a mark 4 pixels across (see its texture), in a few shapes. */
    private static final float HOLE_HALF = 2 / 16f;
    static final int HOLE_VARIANTS = 3;
    private static final FloatArrayList[] holeLists = lists(HOLE_VARIANTS);
    /** Hoarfrost, snow on walls and caps of snow: whole pixels in their own shades, so drawn untextured. */
    private static final FloatArrayList coatList = new FloatArrayList();
    /** Snow on walls and caps of snow, in the pixels of the game's own snow texture. */
    private static final FloatArrayList snowCoatList = new FloatArrayList();
    /** A footprint fades out over the last half of its life, a crack over its last quarter as splats do. */
    private static final float PRINT_FADE = 0.5f;
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

    static void render(PoseStack poseStack, SubmitNodeCollector collector, Level level, Vec3 cam, ResiduumConfig cfg) {
        ashList.clear();
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
        for (FloatArrayList list : crackLists) {
            list.clear();
        }
        for (FloatArrayList list : printLists) {
            list.clear();
        }
        for (FloatArrayList list : holeLists) {
            list.clear();
        }
        coatList.clear();
        snowCoatList.clear();
        for (Coats.Coat c : Coats.INSTANCE.coats().values()) {
            BlockPos pos = BlockPos.of(c.pos);
            if (View.sees(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1)) {
                drawCoat(level, c, pos, cam, DecalTracker.INSTANCE.tick());
            }
        }
        DecalTracker decals = DecalTracker.INSTANCE;
        long now = decals.tick();
        Map<DecalTracker.Key, Ash> all = decals.ash();
        for (Ash a : all.values()) {
            BlockPos pos = BlockPos.of(a.pos);
            if (a.amount <= 0.004f || !View.sees(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1)
                    || a.kind == Kind.SPOT && (!cfg.torchSoot.enabled || pos.distToCenterSqr(cam) > SPOT_DRAW_DIST_SQ)) {
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
            if (View.sees(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 2)) {
                float age = (now - s.born()) / (float) life;
                float alpha = age < 1 - SPLAT_FADE ? 1 : (1 - age) / SPLAT_FADE;
                drawSplat(level, s, pos, cam, alpha);
            }
        }
        long crackLife = (long) (cfg.timing.crackSeconds * 20);
        for (Splat s : decals.cracks()) {
            BlockPos pos = BlockPos.of(s.pos());
            if (cfg.cracks.enabled && View.sees(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 2)) {
                float age = (now - s.born()) / (float) crackLife;
                drawCrack(level, s, pos, cam, age < 1 - SPLAT_FADE ? 1 : (1 - age) / SPLAT_FADE);
            }
        }
        for (Splat s : decals.holes()) {
            BlockPos pos = BlockPos.of(s.pos());
            if (cfg.arrowHoles.enabled && View.sees(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1)) {
                float age = (now - s.born()) / (float) crackLife;
                drawHole(level, s, pos, cam, age < 1 - SPLAT_FADE ? 1 : (1 - age) / SPLAT_FADE);
            }
        }
        long printLife = (long) (cfg.timing.footprintSeconds * 20);
        for (Splat s : decals.prints()) {
            BlockPos pos = BlockPos.of(s.pos());
            if (cfg.footprints.enabled && View.sees(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1)) {
                float age = (now - s.born()) / (float) printLife;
                drawPrint(level, s, pos, cam, age < 1 - PRINT_FADE ? 1 : (1 - age) / PRINT_FADE);
            }
        }
        long scorchLife = (long) (cfg.timing.lightningSeconds * 20);
        // Newest first: where two marks reach the same wall, only the newest is drawn on it
        WALLS_TAKEN.clear();
        List<DecalTracker.Strike> strikes = decals.strikes();
        for (int k = strikes.size() - 1; k >= 0; k--) {
            DecalTracker.Strike st = strikes.get(k);
            BlockPos ground = BlockPos.of(st.ground());
            if (View.sees(ground.getX() + 0.5, ground.getY() + 0.5, ground.getZ() + 0.5, DecalTracker.STRIKE_REACH + 1)) {
                drawStrike(level, st, cam, now - st.born(), scorchLife);
            }
        }
        OverlayRenderer.submitDecal(collector, poseStack, "ash_sheet", ashList);
        for (int i = 0; i < PRINT_TEXTURES; i++) {
            OverlayRenderer.submitDecal(collector, poseStack, "print_" + i, printLists[i]);
        }
        for (int i = 0; i < CRACK_VARIANTS; i++) {
            OverlayRenderer.submitDecal(collector, poseStack, "crack_" + i, crackLists[i]);
        }
        OverlayRenderer.submitDecal(collector, poseStack, "white", coatList);
        // The game's own snow, so the coat looks as snow does in the world, resource packs' too
        OverlayRenderer.submitDecal(collector, poseStack, "minecraft", "textures/block/snow.png", snowCoatList);
        for (int i = 0; i < HOLE_VARIANTS; i++) {
            // A hole darkens the block's own texture, like the burn marks
            OverlayRenderer.submitMultiply(collector, poseStack, "arrow_hole_" + i, holeLists[i]);
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

    /** Light, shape and which neighbouring faces carry ash too, so neighbouring patches run into each other. */
    private static void refresh(Level level, Map<DecalTracker.Key, Ash> all, Ash a, BlockPos pos, long now) {
        a.renderTime = now + Math.floorMod(a.seed, 10);
        a.shade = faceShade(a.face);
        a.light = OverlayRenderer.packedLight(level, pos.relative(a.face));
        Direction t1 = across(a.face), t2 = up(a.face);
        Direction[] sides = {t1.getOpposite(), t1, t2.getOpposite(), t2}; // left, right, bottom, top
        for (int e = 0; e < 4; e++) {
            a.edges[e] = beyond(all, pos, a.face, sides[e]);
        }
        for (int c = 0; c < 4; c++) {
            int e0 = CORNER_EDGES[c][0], e1 = CORNER_EDGES[c][1];
            Ash n0 = a.edges[e0], n1 = a.edges[e1];
            boolean round = false;
            if (n0 != null && n1 != null) {
                // Round the corner: the next block of this surface, or past either neighbour toward the other side
                round = all.containsKey(new DecalTracker.Key(pos.relative(sides[e0]).relative(sides[e1]).asLong(), a.face))
                        || beyond(all, BlockPos.of(n0.pos), n0.face, sides[e1]) != null
                        || beyond(all, BlockPos.of(n1.pos), n1.face, sides[e0]) != null;
            }
            a.notch[c] = n0 != null && n1 != null && !round;
        }
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        Direction.Axis axis = a.face.getAxis();
        boolean positive = a.face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double surface = shape.isEmpty() ? (positive ? 1 : 0) : positive ? shape.max(axis) : shape.min(axis);
        a.plane = surface;
        // Cut to the real shape, so soot on a fence or pane does not hang in the air beside it
        a.rect = faceRect(level, pos, a.face, surface);
        a.drawn = null;
    }

    /**
     * The ash past the edge of face toward t: on the next block of the same surface, round an inside corner, or round
     * an outside corner onto the side of the same block.
     */
    private static Ash beyond(Map<DecalTracker.Key, Ash> all, BlockPos pos, Direction face, Direction t) {
        Ash n = all.get(new DecalTracker.Key(pos.relative(t).asLong(), face));
        if (n == null) {
            n = all.get(new DecalTracker.Key(pos.relative(t).relative(face).asLong(), t.getOpposite()));
        }
        return n != null ? n : all.get(new DecalTracker.Key(pos.asLong(), t));
    }

    /**
     * Ash on one face: the soot sheet laid on by its place in the world, as strong at each pixel as the ash has built
     * up there. Toward an edge it runs on past, it meets the next patch halfway between their strengths, so the two
     * blend; toward an edge with no ash beyond, and into a corner it does not run round, it thins out over a few
     * pixels with a ragged edge. Soot beside a fire climbs from the ground to about half way up. Each pixel is drawn
     * at one strength, rectangles of equal pixels as one quad. The quads are kept, in the block's own coordinates, and
     * worked out again only when the strengths, light or shape change; each frame only moves them to the camera.
     */
    private static void drawAsh(Ash a, BlockPos pos, Vec3 cam) {
        float[] r = a.rect;
        if (r == null) {
            return;
        }
        boolean same = a.drawn != null && a.drawnFrom[0] == a.amount;
        for (int e = 0; e < 4; e++) {
            same &= a.drawnFrom[e + 1] == (a.edges[e] == null ? -1 : a.edges[e].amount);
        }
        if (!same) {
            a.drawnFrom[0] = a.amount;
            for (int e = 0; e < 4; e++) {
                a.drawnFrom[e + 1] = a.edges[e] == null ? -1 : a.edges[e].amount;
            }
            FloatArrayList rects = rectangles(a);
            float[] d = rects.elements();
            a.drawn = new FloatArrayList();
            Vec3 origin = Vec3.atLowerCornerOf(pos);
            for (int k = 0; k + 5 <= rects.size(); k += 5) {
                float x0 = Math.max(r[0], d[k] / 16), x1 = Math.min(r[1], d[k + 1] / 16);
                float y0 = Math.max(r[2], d[k + 2] / 16), y1 = Math.min(r[3], d[k + 3] / 16);
                if (x1 > x0 && y1 > y0) {
                    ashQuad(a.drawn, pos, a, x0, x1, y0, y1, d[k + 4], origin);
                }
            }
        }
        int start = ashList.size();
        ashList.addAll(a.drawn);
        float[] e = ashList.elements();
        float ox = (float) (pos.getX() - cam.x), oy = (float) (pos.getY() - cam.y), oz = (float) (pos.getZ() - cam.z);
        for (int q = start; q < ashList.size(); q += OverlayRenderer.STRIDE) {
            for (int v = q; v < q + 24; v += 6) {
                e[v] += ox;
                e[v + 1] += oy;
                e[v + 2] += oz;
            }
        }
    }

    /**
     * The pixels of an ash face as rectangles of equal strength (x0, x1, y0, y1 in pixels, then strength): runs of
     * equal pixels along each row, each carried on down while the row under it has the same run.
     */
    private static FloatArrayList rectangles(Ash a) {
        FloatArrayList out = new FloatArrayList();
        int[] above = new int[16], here = new int[16];
        float[] row = new float[16];
        java.util.Arrays.fill(above, -1);
        for (int j = 0; j < 16; j++) {
            java.util.Arrays.fill(here, -1);
            for (int i = 0; i < 16; i++) {
                // In steps too fine to see as it builds up, so equal pixels still merge
                row[i] = Math.round(ashAt(a, i, j) * 128) / 128f;
            }
            for (int i = 0; i < 16; ) {
                float v = row[i];
                int run = i + 1;
                while (run < 16 && row[run] == v) {
                    run++;
                }
                if (v > 0.004f) {
                    int k = above[i];
                    if (k >= 0 && out.getFloat(k + 1) == run && out.getFloat(k + 4) == v) {
                        out.set(k + 3, j + 1);
                    } else {
                        k = out.size();
                        out.addAll(it.unimi.dsi.fastutil.floats.FloatArrayList.of(i, run, j, j + 1, v));
                    }
                    here[i] = k;
                }
                i = run;
            }
            int[] swap = above;
            above = here;
            here = swap;
        }
        return out;
    }

    /** How strong the ash on face a shows at pixel (i, j) of the face (see drawAsh). */
    private static float ashAt(Ash a, int i, int j) {
        float x = (i + 0.5f) / 16, y = (j + 0.5f) / 16;
        float[] toEdge = {x, 1 - x, y, 1 - y};
        boolean side = a.kind == Kind.SIDE;
        // A pixel or two either way, so a thinning edge is ragged rather than straight; by squares of two pixels, so it
        // takes a quarter of the quads
        int bits = OverlayRenderer.hash(a.seed + (i >> 1) * 131 + (j >> 1) * 7919);
        float ragged = ((bits & 0xFF) / 255f - 0.5f) * 3 / 16;
        if (a.kind == Kind.SPOT) {
            return spotAt(a, x, y, ragged);
        }
        float blend = 0, shape = 1;
        for (int e = 0; e < 4; e++) {
            if (side && e == 3) {
                continue; // soot beside a fire always thins out upward, below
            }
            Ash n = a.edges[e];
            if (n != null) {
                blend += (n.amount - a.amount) * Math.max(0, 0.5f - toEdge[e]); // halfway between the two at the edge
            } else if (!(side && e == 2)) {
                shape *= thinning(toEdge[e] + ragged, ASH_EDGE);
            }
        }
        for (int c = 0; c < 4; c++) {
            if (a.notch[c]) {
                float dx = toEdge[CORNER_EDGES[c][0]], dy = toEdge[CORNER_EDGES[c][1]];
                shape *= thinning((float) Math.sqrt(dx * dx + dy * dy) + ragged, ASH_EDGE * 1.5f);
            }
        }
        if (side) {
            shape *= 1 - thinning(y + ragged - 0.2f, 0.35f);
        }
        // The blend into the patches round it in steps of a 32nd, so a face of them is a few rectangles, not a pixel each
        float v = a.amount + Math.round(blend * 32) / 32f;
        return Math.clamp(v, 0f, 1f) * shape;
    }

    /**
     * Soot over a torch at a point of its face: a round smudge, darkest over the flame. As it builds up it darkens all over
     * at once, from nothing, while it spreads from a third of its size to all of it with a soft edge, so no pixel is
     * ever dark before the rest; smaller and half as dark from a torch a block further down.
     */
    private static float spotAt(Ash a, float x, float y, float ragged) {
        double[] local = {a.spotX, 0, a.spotZ};
        float dx = x - coord(across(a.face), local), dy = y - coord(up(a.face), local);
        float size = SPOT_RADIUS * (0.5f + 0.5f * a.scale);
        float d = (float) Math.sqrt(dx * dx + dy * dy) + ragged / 2;
        float grown = Math.min(1, a.amount / a.scale);
        float reach = size * (SPOT_START + (1 - SPOT_START) * grown);
        float edge = Math.clamp((reach - d) / (size * SPOT_EDGE), 0f, 1f);
        float t = Math.clamp(d / size, 0f, 1f);
        return SPOT_STRENGTH * a.scale * grown * (1 - t * t) * edge;
    }

    /** 0 at distance 0, rising smoothly to 1 at width. */
    private static float thinning(float distance, float width) {
        float t = Math.clamp(distance / width, 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    /**
     * A rectangle {x0..x1, y0..y1} of an ash face with the soot sheet over it by world position (ASH_SHEET blocks to a
     * sheet, its v running down walls), as opaque as strength.
     */
    private static void ashQuad(FloatArrayList out, BlockPos pos, Ash a, float x0, float x1, float y0, float y1, float strength,
                                Vec3 cam) {
        Direction face = a.face;
        Direction.Axis axis = face.getAxis();
        // The two world axes the sheet lies along on this face
        int uAxis = axis == Direction.Axis.X ? 2 : 0, vAxis = axis == Direction.Axis.Y ? 2 : 1;
        int[] block = {pos.getX(), pos.getY(), pos.getZ()};
        int color = soot(strength, a.shade);
        for (int c = 0; c < 4; c++) {
            float fx = c == 1 || c == 2 ? x1 : x0, fy = c >= 2 ? y1 : y0;
            double[] local = new double[3];
            local[axis.ordinal()] = a.plane;
            place(local, across(face), fx);
            place(local, up(face), fy);
            float u = (float) ((Math.floorMod(block[uAxis], ASH_SHEET) + local[uAxis]) / ASH_SHEET);
            float w = (float) ((Math.floorMod(block[vAxis], ASH_SHEET) + local[vAxis]) / ASH_SHEET);
            vertex(out, pos, face, a.plane, fx, fy, cam, ASH_LIFT, u, axis == Direction.Axis.Y ? w : 1 - w, color);
        }
        end(out, face, a.light);
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
     * A crack in glass round the point an arrow went in, on the face it hit and, as it goes through, on the far face of
     * the glass, mirrored so it is the same crack seen from behind. It runs on into the glass next to it in the same
     * wall, never onto anything else.
     */
    private static void drawCrack(Level level, Splat s, BlockPos pos, Vec3 cam, float alpha) {
        int style = OverlayRenderer.hash(s.seed());
        FloatArrayList out = crackLists[Math.floorMod(style, CRACK_VARIANTS)];
        int turn = (style >>> 8) & 3;
        double[] local = {s.x(), s.y(), s.z()};
        for (int back = 0; back < 2; back++) {
            Direction face = back == 0 ? s.face() : s.face().getOpposite();
            Direction t1 = across(face), t2 = up(face);
            local[t1.getAxis().ordinal()] = Math.round(local[t1.getAxis().ordinal()] * 16) / 16.0;
            local[t2.getAxis().ordinal()] = Math.round(local[t2.getAxis().ordinal()] * 16) / 16.0;
            float a = coord(t1, local), b = coord(t2, local);
            // From behind, walls flip across and floors flip up the face: mirror, and for floors half a turn more
            int seen = back == 0 ? turn : (face.getAxis() == Direction.Axis.Y ? (turn + 2) & 3 : turn) | 4;
            boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            for (int da = -1; da <= 1; da++) {
                for (int db = -1; db <= 1; db++) {
                    BlockPos block = pos.relative(t1, da).relative(t2, db);
                    BlockState state = level.getBlockState(block);
                    if (!GlassTracker.isGlass(state)) {
                        continue;
                    }
                    VoxelShape shape = state.getShape(level, block);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    double surface = positive ? shape.max(face.getAxis()) : shape.min(face.getAxis());
                    splatPart(level, out, s.gone(), block, face, a - da, b - db, surface, cam, alpha, CRACK_HALF, seen,
                            false, CRACK_LIFT);
                }
            }
        }
    }

    /** How far in from an edge with nothing to run on into a coat thins out to nothing, as a share of the face. */
    private static final float COAT_EDGE = 5 / 16f;
    /** How far snow reaches from a face onto the faces round it, and a drift up from the game's own snow, in blocks. */
    private static final float COAT_REACH = 5 / 16f;
    /** Pixels of a coat across a block: the block's own. */
    private static final int COAT_RES = 16;

    /**
     * How each coat grows, by the noise of the world at each of its pixels (see buildCoat): the sizes in pixels of its
     * clumps and of the grain over them, how much each pixel strays from its clump, and the smooth noise a pixel needs
     * to be part of a clump at strength s, from (at nothing) less (full) times s. Snow on walls covers about a third of
     * a wall full on, three quarters of that in solid clumps; frost a little over half of the grass; a cap three
     * quarters of a top, the wood showing between its clumps.
     */
    private record Growth(double clump, double grain, float stray, float from, float less) {
    }

    // From just past the most any pixel has, so a coat starts from nothing, a flake at a time where the stray of a
    // pixel lifts it, and gathers into clumps only as it grows
    private static final Growth RIME_GROWTH = new Growth(3, 1.1, 0.3f, 0.95f, 0.95f);
    private static final Growth SNOW_GROWTH = new Growth(5, 1.5, 0.25f, 1.0f, 0.8f);
    private static final Growth CAP_GROWTH = new Growth(5, 1.5, 0.25f, 0.95f, 1.1f);
    /** What a pixel right at the game's own snow needs, most of them there: a coat thickens toward it as a drift. */
    private static final float GAME_EDGE = -0.35f;

    /**
     * What a pixel of a coat is: a lone flake (its stray lifts it, see-through), the rim of a clump it is just growing
     * into (see-through), the clump itself (solid), or on a wall the bottom edge of a clump, in its own shade. How far
     * into a clump its rim reaches, in noise.
     */
    private static final int FLAKE = 1, RIM = 2, CLUMP = 3, UNDER = 4;
    private static final float COAT_RIM = 0.035f;

    /** Block models' faces by state, for coats (see coatRects). */
    private static final Map<BlockState, List<ModelFace>> MODEL_FACES = new IdentityHashMap<>();

    private static List<ModelFace> modelFaces(BlockState state) {
        if (MODEL_FACES.size() > 512) {
            MODEL_FACES.clear();
        }
        return MODEL_FACES.computeIfAbsent(state, Compat::modelFaces);
    }

    /** Snow on walls and caps of snow are one snow and run on into each other; hoarfrost only into hoarfrost. */
    private static boolean alike(Coats.Kind a, Coats.Kind b) {
        return a == b || a != Coats.Kind.RIME && b != Coats.Kind.RIME;
    }

    /**
     * Whether the game's own snow lies past the edge toward t of a face: beside the block, in the corner in front of
     * that edge (on the top of a block, or on the ground before a wall's foot), or, past a wall's bottom edge, against
     * its foot.
     */
    private static boolean snowyPast(Level level, BlockPos pos, Direction face, Direction t) {
        return snowy(level, pos.relative(t)) || snowy(level, pos.relative(t).relative(face))
                || t == Direction.DOWN && snowy(level, pos.relative(face));
    }

    private static boolean snowy(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof SnowLayerBlock || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW);
    }

    private static Growth growth(Coats.Kind kind) {
        return kind == Coats.Kind.RIME ? RIME_GROWTH : kind == Coats.Kind.CAP ? CAP_GROWTH : SNOW_GROWTH;
    }

    /** -1..1, the same for the same lattice point. */
    private static float lattice(int x, int y, int z, int seed) {
        return (OverlayRenderer.hash(x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ seed) & 0xFFFF) / 32767.5f - 1;
    }

    /** Smooth value noise in space, -1..1, over a lattice size apart: no seams across blocks or round corners. */
    private static float noise(double x, double y, double z, double size, int seed) {
        x /= size;
        y /= size;
        z /= size;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        float fx = (float) (x - ix), fy = (float) (y - iy), fz = (float) (z - iz);
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        float out = 0;
        for (int k = 0; k < 8; k++) {
            int dx = k & 1, dy = k >> 1 & 1, dz = k >> 2;
            out += lattice(ix + dx, iy + dy, iz + dz, seed) * (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy)
                    * (dz == 1 ? fz : 1 - fz);
        }
        return out;
    }

    /**
     * A coat over a face (see buildCoat): lone see-through flakes first, then clumps that grow out from their middles
     * with a see-through rim, solid inside, with a pixel of shadow along the bottom of each on a wall; it goes again
     * the same way. Snow is drawn in the pixels of the game's own snow texture, laid on the face as on a block, so it
     * looks like the snow on the ground; frost in its own pale blues. Its quads are kept, in the block's own
     * coordinates, and worked out again about once a second; each frame only moves them to the camera, a hair toward
     * the eye so they never share a depth with the face under them.
     */
    private static void drawCoat(Level level, Coats.Coat c, BlockPos pos, Vec3 cam, long now) {
        if (c.drawnTime < 0 || now - c.drawnTime >= CACHE_TICKS) {
            c.drawnTime = now + Math.floorMod((int) c.pos, 10);
            buildCoat(level, c, pos);
        }
        if (c.drawn == null || c.drawn.isEmpty()) {
            return;
        }
        FloatArrayList out = c.kind == Coats.Kind.RIME ? coatList : snowCoatList;
        int start = out.size();
        out.addAll(c.drawn);
        float[] e = out.elements();
        float ox = (float) (pos.getX() - cam.x), oy = (float) (pos.getY() - cam.y), oz = (float) (pos.getZ() - cam.z);
        double distance = Math.sqrt((ox + 0.5) * (ox + 0.5) + (oy + 0.5) * (oy + 0.5) + (oz + 0.5) * (oz + 0.5));
        float keep = (float) Math.max(0, 1 - COAT_LIFT * Math.max(1, distance / 8) / Math.max(0.5, distance));
        for (int q = start; q < out.size(); q += OverlayRenderer.STRIDE) {
            for (int v = q; v < q + 24; v += 6) {
                e[v] = (e[v] + ox) * keep;
                e[v + 1] = (e[v + 1] + oy) * keep;
                e[v + 2] = (e[v + 2] + oz) * keep;
            }
        }
    }

    /**
     * The parts of a coat's block that face its way, as the player sees them: the faces of the block's model that look
     * that way and lie square in a plane, each {a0, a1, b0, b1 in face coordinates, plane}; the step and the riser of
     * stairs, the post and rails of a fence, not the box of its outline. What is drawn by the game without a model
     * (a chest, a bed) goes by its outline. Kept with the coat, whose block does not change while it lasts.
     */
    private static float[][] coatRects(Level level, Coats.Coat c, BlockPos pos) {
        if (c.rects != null) {
            return c.rects;
        }
        Direction face = c.face, t1 = across(face), t2 = up(face);
        int axis = face.getAxis().ordinal();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        List<float[]> rects = new ArrayList<>();
        List<ModelFace> faces = new ArrayList<>();
        BlockState state = level.getBlockState(pos);
        List<ModelFace> model = modelFaces(state);
        for (ModelFace m : model) {
            if (m.face() != face) {
                continue;
            }
            float[] v = m.xyz();
            float a0 = 2, a1 = -1, b0 = 2, b1 = -1, n0 = 2, n1 = -1;
            float[] as = new float[4], bs = new float[4];
            for (int i = 0; i < 4; i++) {
                double[] corner = {v[i * 3], v[i * 3 + 1], v[i * 3 + 2]};
                as[i] = coord(t1, corner);
                bs[i] = coord(t2, corner);
                a0 = Math.min(a0, as[i]);
                a1 = Math.max(a1, as[i]);
                b0 = Math.min(b0, bs[i]);
                b1 = Math.max(b1, bs[i]);
                n0 = Math.min(n0, v[i * 3 + axis]);
                n1 = Math.max(n1, v[i * 3 + axis]);
            }
            boolean square = n1 - n0 < 0.001f && a1 - a0 >= 1 / 32f && b1 - b0 >= 1 / 32f;
            for (int i = 0; i < 4 && square; i++) {
                square = (Math.abs(as[i] - a0) < 0.001f || Math.abs(as[i] - a1) < 0.001f)
                        && (Math.abs(bs[i] - b0) < 0.001f || Math.abs(bs[i] - b1) < 0.001f);
            }
            if (!square) {
                continue; // tilted or turned, as the cross of a plant or a chain: no flat face to lie on
            }
            float[] rect = {Math.max(0, a0), Math.min(1, a1), Math.max(0, b0), Math.min(1, b1), n0};
            boolean twice = false;
            for (float[] r : rects) {
                twice |= java.util.Arrays.equals(r, rect);
            }
            if (!twice) { // some models lay two faces on each other, a base and an overlay
                rects.add(rect);
                faces.add(m.solid() ? null : m);
            }
        }
        if (model.isEmpty()) {
            VoxelShape shape = state.getShape(level, pos);
            for (AABB box : shape.isEmpty() ? List.<AABB>of() : shape.toAabbs()) {
                float[] a = span(box, t1), b = span(box, t2);
                if (a[1] - a[0] >= 1 / 32f && b[1] - b[0] >= 1 / 32f) {
                    rects.add(new float[] {a[0], a[1], b[0], b[1], (float) (positive ? box.max(face.getAxis()) : box.min(face.getAxis()))});
                    faces.add(null);
                }
            }
        }
        c.rects = rects.toArray(new float[0][]);
        c.rectFaces = faces.toArray(new ModelFace[0]);
        return c.rects;
    }

    /** Where box spans along t, as face coordinates from its start to its end. */
    private static float[] span(AABB box, Direction t) {
        double lo = box.min(t.getAxis()), hi = box.max(t.getAxis());
        return t.getAxisDirection() == Direction.AxisDirection.POSITIVE ? new float[] {(float) lo, (float) hi}
                : new float[] {(float) (1 - hi), (float) (1 - lo)};
    }

    /** How far the point (x, y, z) is from the box b {x0, y0, z0, x1, y1, z1}. */
    private static float away(float x, float y, float z, float[] b) {
        float dx = Math.max(0, Math.max(b[0] - x, x - b[3])), dy = Math.max(0, Math.max(b[1] - y, y - b[4]));
        float dz = Math.max(0, Math.max(b[2] - z, z - b[5]));
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Works out a coat's quads (c.drawn), in its block's own coordinates. A pixel of a part of the face is there where
     * the noise of the world at it is past what the pixel needs, as a flake, rim or clump (see COAT_RIM); the noise
     * never repeats and runs on unbroken from block to block and round corners. What it needs is the least of:
     * <ul>
     * <li>what the coat's own strength asks, thinning out raggedly toward an edge of the block with nothing past it
     * for snow to run on onto (the ground of the land, glass, air);
     * <li>for each face with snow within COAT_REACH of the pixel, on this block or the ones round it, what that face
     * asks, less the further off it is: so snow on a ledge drapes over its edge onto the face under it, snow on a
     * step drifts up the riser behind it, a wall passes into the one round the corner, and a face with no snow of its
     * own (a host) gets a fringe along the edge it shares with a snowy one;
     * <li>toward an edge past which the game's own snow lies (on the block above, at a wall's foot), a drift up to
     * it, the stronger the coat.
     * </ul>
     * Pixels over the see-through pixels of the block's texture are left out, as are faces a block stands against or
     * the game's snow lies on.
     */
    private static void buildCoat(Level level, Coats.Coat c, BlockPos pos) {
        c.drawn = null;
        float[][] rects = coatRects(level, c, pos);
        if (rects.length == 0) {
            return;
        }
        Direction face = c.face, t1 = across(face), t2 = up(face);
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        boolean snow = c.kind != Coats.Kind.RIME;
        BlockState front = level.getBlockState(pos.relative(face));
        boolean covered = front.isCollisionShapeFullBlock(level, pos.relative(face))
                || face == Direction.UP && front.getBlock() instanceof SnowLayerBlock;
        Growth g = growth(c.kind);
        Map<DecalTracker.Key, Coats.Coat> all = Coats.INSTANCE.coats();
        float wallScale = (float) com.w1z4r_d.residuum.ResiduumConfig.get().wallSnow.amount;
        // The faces with snow round this one: each part of them as a box about this block, what it needs at nothing
        // and how much less at its strength
        List<float[]> givers = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (Direction f : Direction.values()) {
                        if (f == Direction.DOWN || f == face.getOpposite()) {
                            continue;
                        }
                        p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                        Coats.Coat n = all.get(new DecalTracker.Key(p.asLong(), f));
                        if (n == null || n == c || n.amount <= 0 || !alike(n.kind, c.kind)) {
                            continue;
                        }
                        Growth gn = growth(n.kind);
                        Direction n1 = across(f), n2 = up(f);
                        for (float[] r : coatRects(level, n, p)) {
                            double[] lo = new double[3], hi = new double[3];
                            lo[f.getAxis().ordinal()] = hi[f.getAxis().ordinal()] = r[4];
                            place(lo, n1, r[0]);
                            place(hi, n1, r[1]);
                            place(lo, n2, r[2]);
                            place(hi, n2, r[3]);
                            givers.add(new float[] {(float) Math.min(lo[0], hi[0]) + dx, (float) Math.min(lo[1], hi[1]) + dy,
                                    (float) Math.min(lo[2], hi[2]) + dz, (float) Math.max(lo[0], hi[0]) + dx,
                                    (float) Math.max(lo[1], hi[1]) + dy, (float) Math.max(lo[2], hi[2]) + dz, gn.from(),
                                    gn.less() * Math.min(1.6f, n.amount * (n.kind == Coats.Kind.SNOW ? wallScale : 1))});
                        }
                    }
                }
            }
        }
        if (c.amount <= 0 && givers.isEmpty()) {
            return; // a host with nothing left beside it
        }
        // How much snow on walls the settings ask for, past the default by more of the wall covered
        float scale = c.kind == Coats.Kind.SNOW ? wallScale : 1;
        c.light = OverlayRenderer.packedLight(level, pos.relative(face));
        float shade = OverlayRenderer.lit ? 1 : faceShade(face);
        // Snow on walls and caps share their noise, so they run on into each other unbroken
        int seed = snow ? 0x632BE5AB : 0;
        float drift = Math.min(1, c.amount * 1.5f);
        Direction[] sides = {t1.getOpposite(), t1, t2.getOpposite(), t2}; // left, right, bottom, top
        FloatArrayList out = new FloatArrayList();
        Vec3 origin = Vec3.atLowerCornerOf(pos);
        int[][] what = new int[COAT_RES][COAT_RES];
        for (int k = 0; k < rects.length; k++) {
            float[] rect = rects[k];
            float plane = rect[4];
            if (covered && (positive ? plane > 0.999f : plane < 0.001f)) {
                continue;
            }
            // Past each edge of the block: something for the snow to run on onto, the game's own snow, or nothing
            float[] bound = {rect[0], 1 - rect[1], rect[2], 1 - rect[3]};
            boolean[] thins = new boolean[4], drifts = new boolean[4];
            for (int e = 0; e < 4; e++) {
                if (bound[e] > 0.01f) {
                    continue; // an edge within the block, where its own faces meet
                }
                Direction t = sides[e];
                Coats.Coat n = all.get(new DecalTracker.Key(pos.relative(t).asLong(), face));
                if (n == null) {
                    n = all.get(new DecalTracker.Key(pos.relative(t).relative(face).asLong(), t.getOpposite()));
                }
                if (n == null) {
                    n = all.get(new DecalTracker.Key(pos.asLong(), t));
                }
                if (n == null || !alike(n.kind, c.kind)) {
                    drifts[e] = snow && snowyPast(level, pos, face, t);
                    thins[e] = !drifts[e];
                }
            }
            // On a narrow part (a slab's side, a fence post) the thinning takes less of it, so most of it still shows
            float[] width = {Math.min(COAT_EDGE, (rect[1] - rect[0]) / 4), Math.min(COAT_EDGE, (rect[3] - rect[2]) / 4)};
            // Where on the block's texture a point of the part falls, for its see-through pixels
            ModelFace model = c.rectFaces[k];
            float[] uv = model == null ? null : texture(model, t1, t2);
            double[] local = new double[3];
            local[face.getAxis().ordinal()] = plane;
            for (int j = 0; j < COAT_RES; j++) {
                for (int i = 0; i < COAT_RES; i++) {
                    what[j][i] = 0;
                    float x = (i + 0.5f) / COAT_RES, y = (j + 0.5f) / COAT_RES;
                    if (x < rect[0] || x > rect[1] || y < rect[2] || y > rect[3]) {
                        continue;
                    }
                    if (uv != null && !model.mask().opaqueAt(uv[0] + uv[1] * x + uv[2] * y, uv[3] + uv[4] * x + uv[5] * y)) {
                        continue;
                    }
                    float[] toEdge = {x - rect[0], rect[1] - x, y - rect[2], rect[3] - y};
                    int bits = OverlayRenderer.hash((int) c.pos * 31 + face.ordinal() + (i >> 1) * 131 + (j >> 1) * 7919);
                    float ragged = ((bits & 0xFF) / 255f - 0.5f) * 4 / 16;
                    float s = 1;
                    for (int e = 0; e < 4; e++) {
                        if (thins[e]) {
                            s *= thinning(toEdge[e] + ragged * width[e / 2] / COAT_EDGE, width[e / 2]);
                        }
                    }
                    if (s <= 0) {
                        continue;
                    }
                    float needs = g.from() - g.less() * Math.min(1.6f, c.amount * scale) * s;
                    place(local, t1, x);
                    place(local, t2, y);
                    float lx = (float) local[0], ly = (float) local[1], lz = (float) local[2];
                    for (float[] giver : givers) {
                        float d = away(lx, ly, lz, giver);
                        if (d < COAT_REACH) {
                            needs = Math.min(needs, giver[6] - giver[7] * (1 - d / COAT_REACH) * s);
                        }
                    }
                    for (int e = 0; e < 4; e++) {
                        if (drifts[e] && toEdge[e] < COAT_REACH) {
                            needs = Math.min(needs, g.from() - (g.from() - GAME_EDGE) * drift * (1 - toEdge[e] / COAT_REACH));
                        }
                    }
                    if (needs > g.from() - 0.001f && c.amount <= 0) {
                        continue; // a host away from the snow beside it: bare
                    }
                    // The pixel's place in the world, in pixels, just off the face so faces back to back differ
                    double wx = (pos.getX() + lx + face.getStepX() * 0.02) * COAT_RES;
                    double wy = (pos.getY() + ly + face.getStepY() * 0.02) * COAT_RES;
                    double wz = (pos.getZ() + lz + face.getStepZ() * 0.02) * COAT_RES;
                    float smooth = 0.7f * noise(wx, wy, wz, g.clump(), seed) + 0.3f * noise(wx, wy, wz, g.grain(), seed + 1);
                    float n = smooth + g.stray() * lattice((int) Math.floor(wx), (int) Math.floor(wy), (int) Math.floor(wz), seed + 2);
                    what[j][i] = smooth >= needs + COAT_RIM ? CLUMP : smooth >= needs ? RIM : n >= needs ? FLAKE : 0;
                }
            }
            // On a wall a clump casts a pixel of shadow along its bottom edge, so it stands out from the wall
            if (c.kind == Coats.Kind.SNOW && face.getAxis().isHorizontal()) {
                for (int j = COAT_RES - 1; j > 0; j--) {
                    for (int i = 0; i < COAT_RES; i++) {
                        if (what[j][i] == CLUMP && what[j - 1][i] < RIM && (j - 0.5f) / COAT_RES >= rect[2]) {
                            what[j][i] = UNDER;
                        }
                    }
                }
            }
            // Runs of equal pixels along each row, carried on down while the row under has the same run
            int[] above = new int[COAT_RES], here = new int[COAT_RES];
            java.util.Arrays.fill(above, -1);
            List<float[]> runs = new ArrayList<>();
            for (int j = 0; j < COAT_RES; j++) {
                java.util.Arrays.fill(here, -1);
                for (int i = 0; i < COAT_RES; ) {
                    int v = what[j][i];
                    int run = i + 1;
                    while (run < COAT_RES && what[j][run] == v) {
                        run++;
                    }
                    if (v > 0) {
                        int at = above[i];
                        if (at >= 0 && runs.get(at)[0] == i && runs.get(at)[1] == run && runs.get(at)[4] == v) {
                            runs.get(at)[3] = j + 1;
                        } else {
                            at = runs.size();
                            runs.add(new float[] {i, run, j, j + 1, v});
                        }
                        here[i] = at;
                    }
                    i = run;
                }
                int[] swap = above;
                above = here;
                here = swap;
            }
            for (float[] r : runs) {
                int code = (int) r[4];
                float alpha = code == FLAKE ? 0.55f : code == RIM ? 0.75f : 1;
                // Snow takes its colours from its texture, only shaded; frost has its own
                int rgb = snow ? (code == UNDER ? 0xCED8E6 : 0xFFFFFF)
                        : code == FLAKE ? 0xDCE8F6 : code == RIM ? 0xE8F0FA : 0xF2F8FF;
                int color = OverlayRenderer.argb(alpha, rgb, shade);
                float x0 = Math.max(rect[0], r[0] / COAT_RES), x1 = Math.min(rect[1], r[1] / COAT_RES);
                float y0 = Math.max(rect[2], r[2] / COAT_RES), y1 = Math.min(rect[3], r[3] / COAT_RES);
                for (int corner = 0; corner < 4; corner++) {
                    float fx = corner == 1 || corner == 2 ? x1 : x0, fy = corner >= 2 ? y1 : y0;
                    // On the face as on a block: the texture's top along the face's top, a floor's north up it
                    vertex(out, pos, face, plane, fx, fy, origin, 0, fx, 1 - fy, color);
                }
                end(out, face, c.light);
            }
        }
        c.drawn = out;
    }

    /**
     * For a model face, where on its texture a point (x, y) of the block's face falls: {u0, ux, uy, v0, vx, vy} with
     * u = u0 + ux * x + uy * y and v likewise, from three of its corners; null when they do not span it.
     */
    private static float[] texture(ModelFace m, Direction t1, Direction t2) {
        float[] v = m.xyz(), uv = m.uv();
        float[] a = new float[4], b = new float[4];
        for (int i = 0; i < 4; i++) {
            double[] corner = {v[i * 3], v[i * 3 + 1], v[i * 3 + 2]};
            a[i] = coord(t1, corner);
            b[i] = coord(t2, corner);
        }
        float ax = a[1] - a[0], ay = b[1] - b[0], bx = a[3] - a[0], by = b[3] - b[0];
        float det = ax * by - ay * bx;
        if (Math.abs(det) < 1.0e-6f) {
            return null;
        }
        // (x, y) - corner 0 = s * (corner 1 - corner 0) + t * (corner 3 - corner 0)
        float sx = by / det, sy = -bx / det, tx = -ay / det, ty = ax / det;
        float du1 = uv[2] - uv[0], dv1 = uv[3] - uv[1], du3 = uv[6] - uv[0], dv3 = uv[7] - uv[1];
        float ux = du1 * sx + du3 * tx, uy = du1 * sy + du3 * ty, vx = dv1 * sx + dv3 * tx, vy = dv1 * sy + dv3 * ty;
        return new float[] {uv[0] - ux * a[0] - uy * b[0], ux, uy, uv[1] - vx * a[0] - vy * b[0], vx, vy};
    }

    /** The hole an arrow left, on the face it went into, on the block's pixels. */
    private static void drawHole(Level level, Splat s, BlockPos pos, Vec3 cam, float alpha) {
        int style = OverlayRenderer.hash(s.seed());
        Direction face = s.face();
        Direction t1 = across(face), t2 = up(face);
        double[] local = {s.x(), s.y(), s.z()};
        local[t1.getAxis().ordinal()] = Math.round(local[t1.getAxis().ordinal()] * 16) / 16.0;
        local[t2.getAxis().ordinal()] = Math.round(local[t2.getAxis().ordinal()] * 16) / 16.0;
        splatPart(level, holeLists[Math.floorMod(style, HOLE_VARIANTS)], s.gone(), pos, face, coord(t1, local),
                coord(t2, local), local[face.getAxis().ordinal()], cam, alpha, HOLE_HALF, (style >>> 8) & 7, false, HOLE_LIFT);
    }

    /** A footprint on the top of the snow or sand it is pressed into, cut off where that ground ends. */
    private static void drawPrint(Level level, Splat s, BlockPos pos, Vec3 cam, float alpha) {
        FloatArrayList out = printLists[Math.floorMod(s.seed() & 0xFF, PRINT_TEXTURES)];
        int turn = (s.seed() >>> 8) & 3;
        double[] local = {s.x(), s.y(), s.z()};
        Direction t1 = across(Direction.UP), t2 = up(Direction.UP);
        float a = coord(t1, local), b = coord(t2, local);
        Block ground = level.getBlockState(pos).getBlock();
        for (int da = -1; da <= 1; da++) {
            for (int db = -1; db <= 1; db++) {
                BlockPos block = pos.relative(t1, da).relative(t2, db);
                if (level.getBlockState(block).getBlock() == ground) {
                    splatPart(level, out, s.gone(), block, Direction.UP, a - da, b - db, s.y(), cam, alpha, PRINT_HALF, turn,
                            false, PRINT_LIFT);
                }
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
        for (FloatArrayList holes : holeLists) {
            multiplies |= out == holes;
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

    /** Vertex colour of ash as opaque as alpha, shaded like its face (shader packs shade it themselves). */
    private static int soot(float alpha, float shade) {
        return OverlayRenderer.argb(alpha, 0xFFFFFF, OverlayRenderer.lit ? 1 : shade);
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
        double keep = lift == 0 ? 1 : Math.max(0, 1 - lift * Math.max(1, distance / 8) / distance);
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
    static float faceShade(Direction face) {
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
