package com.w1z4r_d.livingglass.glass;

import com.w1z4r_d.livingglass.LivingGlassClient;
import com.w1z4r_d.livingglass.LivingGlassConfig;
import com.w1z4r_d.livingglass.compat.Compat;
import com.w1z4r_d.livingglass.decal.DecalTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Finds windows around the player and eases their effect values toward climate targets. */
public final class GlassTracker {
    public static final GlassTracker INSTANCE = new GlassTracker();

    private static final TagKey<Block> AFFECTED_GLASS = Compat.blockTag(LivingGlassClient.MOD_ID, "affected_glass");
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** Inside air must be under a roof and at most this sky light (the air right behind a window sits at 13). */
    private static final int INSIDE_MAX_SKY_LIGHT = 13;
    private static final int OUTSIDE_MIN_SKY_LIGHT = 14;
    private static final float DROPS_FADE_IN_SECONDS = 3;
    private static final int TARGET_INTERVAL_TICKS = 20;
    /** Carpets and thin snow still leave a window face open. */
    private static final double LOW_BLOCK_HEIGHT = 0.2;
    /** Narrower uncovered strips of a face are not worth drawing raindrops on. */
    private static final float MIN_WET_SPAN = 0.2f;
    /** Inside fog above this makes the outside ice form faster. */
    private static final float ICE_FOGGED_THRESHOLD = 0.3f;
    private static final float BREATH_BUILD_SECONDS = 3;
    private static final float BREATH_FADE_SECONDS = 10;
    private static final double BREATH_DISTANCE = 1.5;
    /** How far below comfort temperature the outside must be before breath fogs the glass. */
    private static final double BREATH_MIN_COLD = 0.2;

    private final Long2ObjectOpenHashMap<GlassState> states = new Long2ObjectOpenHashMap<>();
    private final Climate climate = new Climate();
    private final LongArrayFIFOQueue pending = new LongArrayFIFOQueue();
    private final LongOpenHashSet pendingSet = new LongOpenHashSet();

    private Level level;
    private long tick;
    private BlockPos sweepCenter = BlockPos.ZERO;
    private int sweepIndex;

    /** Exponential average of tick time in nanoseconds, for the debug HUD. */
    public double tickNanos;

    private GlassTracker() {
    }

    public Long2ObjectOpenHashMap<GlassState> states() {
        return states;
    }

    public void clear() {
        states.clear();
        climate.clear();
        pending.clear();
        pendingSet.clear();
        sweepIndex = 0;
    }

    /** A block changed: recheck glass around it, including the column below (roof blocks). */
    public void onBlockChanged(BlockPos pos) {
        // Farms, redstone and growing crops change blocks all over the loaded world; only nearby changes matter
        Player player = Minecraft.getInstance().player;
        LivingGlassConfig cfg = LivingGlassConfig.get();
        int reach = cfg.scan.radius + 16;
        if (level == null || player == null || !cfg.enabled || player.blockPosition().distSqr(pos) > (double) reach * reach) {
            return;
        }
        climate.invalidate(pos, cfg.climate.heatScanRadius);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -16; dy <= 2; dy++) {
                    long p = BlockPos.asLong(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (pendingSet.add(p)) {
                        pending.enqueue(p);
                    }
                }
            }
        }
    }

    public void tick(Minecraft mc) {
        if (mc.level != level) {
            clear();
            level = mc.level;
        }
        LivingGlassConfig cfg = LivingGlassConfig.get();
        if (level == null || mc.player == null || !cfg.enabled) {
            return;
        }
        long start = System.nanoTime();
        tick++;
        climate.startTick();

        int budget = cfg.scan.budgetPerTick;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        while (budget > 0 && !pending.isEmpty()) {
            long packed = pending.dequeueLong();
            pendingSet.remove(packed);
            p.set(packed);
            refresh(p);
            budget--;
        }
        sweep(mc.player.blockPosition(), cfg.scan.radius, budget, p);
        update(mc.player.blockPosition(), cfg);

        tickNanos = tickNanos * 0.95 + (System.nanoTime() - start) * 0.05;
    }

    /** Visits the sphere around the player a slice at a time; recenters after each full pass. */
    private void sweep(BlockPos player, int r, int budget, BlockPos.MutableBlockPos p) {
        int side = 2 * r + 1;
        int total = side * side * side;
        int limit = budget * 2; // cube corners outside the sphere are skipped cheaply
        // Teleported or flew far: start a fresh pass around the player instead of finishing the old area
        if (sweepCenter.distSqr(player) > (double) r * r) {
            sweepIndex = total;
        }
        while (budget > 0 && limit-- > 0) {
            if (sweepIndex >= total) {
                sweepIndex = 0;
                sweepCenter = player;
            }
            int i = sweepIndex++;
            int dx = i % side - r;
            int dz = (i / side) % side - r;
            int dy = i / (side * side) - r;
            if (dx * dx + dy * dy + dz * dz > r * r) {
                continue;
            }
            p.set(sweepCenter.getX() + dx, sweepCenter.getY() + dy, sweepCenter.getZ() + dz);
            refresh(p);
            budget--;
        }
    }

    private void refresh(BlockPos pos) {
        long key = pos.asLong();
        if (!level.isLoaded(pos)) {
            states.remove(key);
            return;
        }
        BlockState state = level.getBlockState(pos);
        DecalTracker.INSTANCE.onBlock(pos, state);
        if (!isGlass(state)) {
            states.remove(key);
            return;
        }
        GlassState gs = states.get(key);
        if (gs == null) {
            gs = new GlassState(key);
        }
        if (!classify(pos, state, gs)) {
            states.remove(key);
            return;
        }
        if (!states.containsKey(key) && makeRoom(key)) {
            states.put(key, gs);
        }
    }

    /**
     * At the cap, drops the window farthest from the player to make room for key, unless key itself is
     * the farthest (then it is not tracked, so far windows do not keep pushing each other out).
     */
    // ponytail: O(n) scan for the farthest window, only runs when the cap is hit
    private boolean makeRoom(long key) {
        if (states.size() < LivingGlassConfig.get().tracked.max) {
            return true;
        }
        BlockPos player = Minecraft.getInstance().player.blockPosition();
        long farthest = key;
        long best = distSq(key, player);
        for (long other : states.keySet()) {
            long d = distSq(other, player);
            if (d > best) {
                best = d;
                farthest = other;
            }
        }
        if (farthest == key) {
            return false;
        }
        states.remove(farthest);
        return true;
    }

    private static long distSq(long packed, BlockPos to) {
        long dx = BlockPos.getX(packed) - to.getX();
        long dy = BlockPos.getY(packed) - to.getY();
        long dz = BlockPos.getZ(packed) - to.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    static boolean isGlass(BlockState state) {
        Block block = state.getBlock();
        return state.is(Blocks.GLASS) || state.is(Blocks.TINTED_GLASS) || state.is(Blocks.GLASS_PANE)
                || block instanceof StainedGlassBlock || block instanceof StainedGlassPaneBlock
                || state.is(AFFECTED_GLASS);
    }

    /** Fills inner face, rain faces and look; false when the glass needs no tracking. */
    private boolean classify(BlockPos pos, BlockState state, GlassState gs) {
        gs.pane = state.getBlock() instanceof IronBarsBlock;
        Direction.Axis paneAxis = null;
        gs.paneConn = 0;
        if (gs.pane) {
            for (Direction dir : HORIZONTAL) {
                if (state.getValue(CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(dir))) {
                    gs.paneConn |= 1 << dir.get2DDataValue();
                }
            }
            boolean ns = state.getValue(BlockStateProperties.NORTH) || state.getValue(BlockStateProperties.SOUTH);
            boolean ew = state.getValue(BlockStateProperties.EAST) || state.getValue(BlockStateProperties.WEST);
            if (ns == ew) {
                return false; // lone post or corner piece
            }
            // A pane running north-south faces east and west
            paneAxis = ns ? Direction.Axis.X : Direction.Axis.Z;
        }

        gs.inner = null;
        gs.rainMask = 0;
        for (Direction dir : HORIZONTAL) {
            if (paneAxis != null && dir.getAxis() != paneAxis) {
                continue;
            }
            BlockPos side = pos.relative(dir);
            if (!isOpen(side, dir.getOpposite())) {
                partialRain(gs, dir, side);
                continue;
            }
            if (level.canSeeSky(side)) {
                setRain(gs, dir, side, 0, 1);
            }
            if (gs.inner == null && isOutside(side) && isInside(pos.relative(dir.getOpposite()), dir)) {
                gs.inner = dir.getOpposite();
            }
        }
        gs.roomMask = 0;
        if (gs.inner != null && !gs.pane) {
            roomSides(pos, gs);
        }
        BlockPos above = pos.above();
        gs.rainTop = !gs.pane && isOpen(above, Direction.DOWN) && level.canSeeSky(above);
        gs.renderTime = -1; // faces may have changed, refresh cached light on the next frame
        gs.tint = state.getBlock() instanceof BeaconBeamBlock beam ? beam.getColor().getTextureDiffuseColor() & 0xFFFFFF : 0xFFFFFF;
        return gs.inner != null || gs.rainMask != 0 || gs.rainTop;
    }

    /**
     * Whether the neighbour at pos leaves the glass face it touches (its side facing toward) uncovered.
     * Uses the block's visual shape: torches, plants, carpets and thin snow leave the face open, while
     * slabs, stairs, fences, beds and other blocks pressed against the glass hide it, so it gets no overlay.
     */
    private boolean isOpen(BlockPos pos, Direction toward) {
        BlockState state = level.getBlockState(pos);
        if (isGlass(state)) {
            return false;
        }
        VoxelShape touching = state.getShape(level, pos).getFaceShape(toward);
        return touching.isEmpty() || touching.max(Direction.Axis.Y) <= LOW_BLOCK_HEIGHT;
    }

    /**
     * A neighbour covering only a vertical strip of the face, like a pane ending at the glass, still leaves
     * the rest of the face out in the rain. The wet part is the uncovered side whose diagonal cell is open sky.
     */
    private void partialRain(GlassState gs, Direction dir, BlockPos side) {
        BlockState state = level.getBlockState(side);
        float[] covered = coveredSpan(state, side, dir);
        if (covered == null) {
            return;
        }
        Direction right = dir.getClockWise();
        float c0 = covered[0], c1 = covered[1];
        if (c0 >= MIN_WET_SPAN && isOpenSky(side.relative(right.getOpposite()))) {
            setRain(gs, dir, side.relative(right.getOpposite()), 0, c0);
        } else if (1 - c1 >= MIN_WET_SPAN && isOpenSky(side.relative(right))) {
            setRain(gs, dir, side.relative(right), c1, 1);
        }
    }

    /**
     * The span of the glass face toward dir that the neighbour at side covers, in face coordinates
     * (x grows toward dir.getClockWise()); null when it does not touch the face at all.
     */
    private float[] coveredSpan(BlockState state, BlockPos side, Direction dir) {
        VoxelShape touching = state.getShape(level, side).getFaceShape(dir.getOpposite());
        if (touching.isEmpty()) {
            return null;
        }
        Direction right = dir.getClockWise();
        Direction.Axis axis = right.getAxis();
        float lo = (float) touching.min(axis), hi = (float) touching.max(axis);
        boolean positive = right.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        return positive ? new float[] {lo, hi} : new float[] {1 - hi, 1 - lo};
    }

    /**
     * Side faces of a window (across its inner/outer axis) that face into the warm room fog up too: the whole face
     * when the room air touches it, or the room-side part when a pane or similar strip covers its middle.
     */
    private void roomSides(BlockPos pos, GlassState gs) {
        for (Direction dir : HORIZONTAL) {
            if (dir.getAxis() == gs.inner.getAxis()) {
                continue;
            }
            int i = dir.get2DDataValue();
            BlockPos side = pos.relative(dir);
            if (isOpen(side, dir.getOpposite())) {
                if (isIndoorAir(side)) {
                    setRoom(gs, i, 0, 1);
                }
                continue;
            }
            BlockState state = level.getBlockState(side);
            float[] covered = coveredSpan(state, side, dir);
            if (covered == null) {
                continue;
            }
            // The room side of this face is toward gs.inner, which is either face-right or face-left
            boolean innerIsRight = dir.getClockWise() == gs.inner;
            BlockPos diagonal = side.relative(gs.inner);
            if (!isIndoorAir(diagonal)) {
                continue;
            }
            if (innerIsRight && 1 - covered[1] >= MIN_WET_SPAN) {
                setRoom(gs, i, covered[1], 1);
            } else if (!innerIsRight && covered[0] >= MIN_WET_SPAN) {
                setRoom(gs, i, 0, covered[0]);
            }
        }
    }

    private static void setRoom(GlassState gs, int i, float min, float max) {
        gs.roomMask |= 1 << i;
        gs.roomMin[i] = min;
        gs.roomMax[i] = max;
    }

    /** Open, roofed space with little sky light, like the room behind a window. */
    private boolean isIndoorAir(BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        return (shape.isEmpty() || shape.max(Direction.Axis.Y) <= LOW_BLOCK_HEIGHT)
                && !isGlass(level.getBlockState(pos))
                && !level.canSeeSky(pos) && level.getBrightness(LightLayer.SKY, pos) <= INSIDE_MAX_SKY_LIGHT;
    }

    private boolean isOpenSky(BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        return (shape.isEmpty() || shape.max(Direction.Axis.Y) <= LOW_BLOCK_HEIGHT) && level.canSeeSky(pos);
    }

    private static void setRain(GlassState gs, Direction dir, BlockPos cell, float min, float max) {
        int i = dir.get2DDataValue();
        gs.rainMask |= 1 << i;
        gs.rainMin[i] = min;
        gs.rainMax[i] = max;
        gs.rainCell[i] = cell.asLong();
    }

    private boolean isOutside(BlockPos pos) {
        return level.canSeeSky(pos) || level.getBrightness(LightLayer.SKY, pos) >= OUTSIDE_MIN_SKY_LIGHT;
    }

    private boolean isInside(BlockPos pos, Direction toward) {
        return isOpen(pos, toward) && !level.canSeeSky(pos) && level.getBrightness(LightLayer.SKY, pos) <= INSIDE_MAX_SKY_LIGHT;
    }

    /** Refreshes targets about once a second per window and eases current values every tick. */
    private void update(BlockPos player, LivingGlassConfig cfg) {
        double keep = (cfg.scan.radius + 8.0) * (cfg.scan.radius + 8.0);
        LivingGlassConfig.Timing t = cfg.timing;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        ObjectIterator<GlassState> it = states.values().iterator();
        while (it.hasNext()) {
            GlassState gs = it.next();
            if (Math.floorMod(tick + gs.seed, TARGET_INTERVAL_TICKS) == 0) {
                p.set(gs.pos);
                if (p.distSqr(player) > keep || !level.isLoaded(p)) {
                    it.remove();
                    continue;
                }
                updateTargets(p, gs, cfg);
            }
            gs.fog = approach(gs.fog, gs.fogTarget, t.fogBuildSeconds, t.fogClearSeconds);
            gs.frost = approach(gs.frost, gs.frostTarget, t.frostBuildSeconds, t.frostMeltSeconds);
            gs.drops = approach(gs.drops, gs.dropTarget, DROPS_FADE_IN_SECONDS, t.dryingSeconds);
            // Breath patches fade on their own; breathe() keeps feeding the one in front of the player
            for (int i = 0; i < GlassState.MAX_BREATHS; i++) {
                gs.breath[i] = approach(gs.breath[i], 0, 1, BREATH_FADE_SECONDS);
            }
            // Wiped patches fog up again at the normal build-up speed
            for (int i = 0; i < GlassState.MAX_WIPES; i++) {
                gs.wipe[i] = approach(gs.wipe[i], 0, 1, t.fogBuildSeconds);
            }
            gs.patches = gs.hasPatches();
            gs.look.tick(t.meltSeconds);
            gs.iceLook.tick(t.meltSeconds);
            // Outside ice forms faster when the glass is already fogged from inside
            gs.ice = approach(gs.ice, gs.iceTarget, gs.fog > ICE_FOGGED_THRESHOLD ? t.iceFoggedSeconds : t.iceBuildSeconds,
                    t.frostMeltSeconds);
        }
        if (cfg.breathSpot.enabled) {
            breathe(Minecraft.getInstance().player, cfg);
        }
    }

    /** Marks the window right in front of the player's face, if it is cold enough to fog from breath. */
    private void breathe(Player player, LivingGlassConfig cfg) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        BlockPos eyeBlock = BlockPos.containing(eye);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = eyeBlock.offset(dx, dy, dz);
                    GlassState gs = states.get(pos.asLong());
                    // Written so that a window whose temperature is not known yet (NaN) is skipped too
                    if (gs == null || !gs.isWindow() || !(gs.outsideTemp <= cfg.climate.comfortTemp - BREATH_MIN_COLD)) {
                        continue;
                    }
                    Direction in = gs.inner;
                    double half = gs.pane ? GlassState.PANE_HALF : 0.5;
                    double dist = (eye.x - pos.getX() - 0.5) * in.getStepX() + (eye.z - pos.getZ() - 0.5) * in.getStepZ() - half;
                    double facing = -(look.x * in.getStepX() + look.z * in.getStepZ());
                    float x = GlassState.faceX(pos, in, eye.x, eye.z);
                    float y = (float) (eye.y - pos.getY());
                    if (dist > 0 && dist < BREATH_DISTANCE && facing > 0.6 && x >= 0 && x <= 1 && y >= 0 && y <= 1) {
                        // Net build-up: the feed outpaces the per-tick fade applied in update()
                        gs.addBreath(x, y, (float) (1.0 / (BREATH_BUILD_SECONDS * 20) + 1.0 / (BREATH_FADE_SECONDS * 20)));
                    }
                }
            }
        }
    }

    /** Right-click with an empty hand on the inner face clears a patch; returns true when something was wiped. */
    public boolean wipe(BlockHitResult hit) {
        GlassState gs = states.get(hit.getBlockPos().asLong());
        if (gs == null || !gs.isWindow() || (gs.fog < 0.05f && gs.frost < 0.05f)) {
            return false;
        }
        Direction face = hit.getDirection();
        Vec3 at = hit.getLocation();
        float x = GlassState.faceX(hit.getBlockPos(), face, at.x, at.z);
        // The inner face, or the fogged room-side part of a side face (next to a pane, or sticking into the room)
        boolean roomSide = face.getAxis().isHorizontal() && (gs.roomMask & (1 << face.get2DDataValue())) != 0
                && x >= gs.roomMin[face.get2DDataValue()] && x <= gs.roomMax[face.get2DDataValue()];
        if (face != gs.inner && !roomSide) {
            return false;
        }
        gs.addWipe(face, x, (float) (at.y - hit.getBlockPos().getY()));
        return true;
    }

    private void updateTargets(BlockPos pos, GlassState gs, LivingGlassConfig cfg) {
        gs.dropTarget = gs.rainTop && level.isRainingAt(pos.above()) ? 1 : 0;
        for (Direction dir : HORIZONTAL) {
            int i = dir.get2DDataValue();
            if ((gs.rainMask & (1 << i)) != 0 && level.isRainingAt(BlockPos.of(gs.rainCell[i]))) {
                gs.dropTarget = 1;
                break;
            }
        }
        if (!gs.isWindow()) {
            gs.fogTarget = gs.frostTarget = gs.iceTarget = 0;
            return;
        }
        BlockPos outside = pos.relative(gs.inner.getOpposite());
        BlockPos inside = pos.relative(gs.inner);
        float temp = Climate.outsideTemp(level, outside, cfg.climate);
        // Outside ice only needs frost outdoors, not a warm room
        gs.iceTarget = temp < Climate.FREEZING ? 1 : 0;
        float warmth = climate.warmth(level, inside, tick, cfg.climate.heatScanRadius);
        if (Float.isNaN(warmth)) {
            return; // scan budget spent this tick; try again next interval
        }
        float coldness = Math.clamp(((float) cfg.climate.comfortTemp - temp) / (float) cfg.climate.comfortTemp, 0f, 1f);
        gs.outsideTemp = temp;
        gs.warmth = warmth;
        gs.fogTarget = Math.min(1, coldness * warmth * (float) cfg.condensation.strength);
        gs.frostTarget = temp < Climate.FREEZING ? Climate.smoothstep(0.4f, 1.0f, coldness * warmth) : 0;
    }

    /** Moves linearly toward target; a full 0..1 swing takes riseSeconds up or fallSeconds down. */
    public static float approach(float value, float target, double riseSeconds, double fallSeconds) {
        if (value < target) {
            return Math.min(target, value + (float) (1.0 / (riseSeconds * 20)));
        }
        if (value > target) {
            return fallSeconds <= 0 ? target : Math.max(target, value - (float) (1.0 / (fallSeconds * 20)));
        }
        return value;
    }
}
