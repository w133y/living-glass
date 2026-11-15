package com.w1z4r_d.residuum.glass;

import com.w1z4r_d.residuum.ResiduumClient;
import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.compat.Compat;
import com.w1z4r_d.residuum.decal.DecalTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

/** Finds windows around the player and eases their effect values toward climate targets. */
public final class GlassTracker {
    public static final GlassTracker INSTANCE = new GlassTracker();

    private static final TagKey<Block> AFFECTED_GLASS = Compat.blockTag(ResiduumClient.MOD_ID, "affected_glass");
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** Inside air must be under a roof and at most this sky light (the air right behind a window sits at 13). */
    private static final int INSIDE_MAX_SKY_LIGHT = 13;
    private static final int OUTSIDE_MIN_SKY_LIGHT = 14;
    private static final float DROPS_FADE_IN_SECONDS = 3;
    private static final int TARGET_INTERVAL_TICKS = 20;
    /** How often windows let go are looked over for ones the player is back near. */
    private static final int RESTORE_INTERVAL_TICKS = 5;
    /**
     * A chunk just loaded is looked through for glass this long after, once its light is worked out and the chunks
     * round it are in, and again a while later for glass whose surroundings were not yet known the first time.
     */
    private static final int CHUNK_FIRST_LOOK_TICKS = 40, CHUNK_SECOND_LOOK_TICKS = 200;
    private static final int CHUNKS_PER_TICK = 4;
    /** Carpets and thin snow still leave a window face open. */
    private static final double LOW_BLOCK_HEIGHT = 0.2;
    /** Narrower uncovered strips of a face are not worth drawing raindrops on. */
    private static final float MIN_WET_SPAN = 0.2f;
    /** Inside fog target at which the outside ice forms at its fastest pace. */
    private static final float ICE_FULLY_FOGGED = 0.5f;
    /** How much dust settles on a desert window: a little by day, and a fifth more of that by night. */
    private static final float SAND_DAY = 0.15f, SAND_NIGHT = SAND_DAY * 1.2f;
    /** How far past the top of the window the ice front has to climb before the frost covers it all, in blocks. */
    public static final float ICE_OVERSHOOT = 0.4f;
    /** How far neighbouring windows are followed when merging a large window into one surface. */
    private static final int MAX_RUN = 8;
    /** How far below a glass roof the floor of the room under it may be, and how far up a glass roof may be. */
    private static final int ROOM_HEIGHT = 8;
    /** Snowflakes settle on the glass over this long once snow falls on it, and melt away over this long after. */
    private static final float FLAKES_SECONDS = 20, FLAKES_MELT_SECONDS = 40;
    private static final float BREATH_BUILD_SECONDS = 3;
    private static final float BREATH_FADE_SECONDS = 10;
    private static final double BREATH_DISTANCE = 0.8;
    /** How far below comfort temperature the outside must be before breath fogs the glass. */
    private static final double BREATH_MIN_COLD = 0.2;

    private final Long2ObjectOpenHashMap<GlassState> states = new Long2ObjectOpenHashMap<>();
    /** How long a window keeps its fog, frost and ice after the player leaves it behind. */
    public static final long KEEP_NANOS = 5 * 60 * 1_000_000_000L;
    /**
     * Windows dropped because the player went away (out of range, chunk unloaded, another dimension), per dimension,
     * so they look the same on coming back within KEEP_NANOS instead of starting again from clear glass.
     */
    private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<GlassState>> parkedByDimension = new HashMap<>();
    private Long2ObjectOpenHashMap<GlassState> parked = new Long2ObjectOpenHashMap<>();
    private final Climate climate = new Climate();
    private final LongArrayFIFOQueue pending = new LongArrayFIFOQueue();
    private final LongOpenHashSet pendingSet = new LongOpenHashSet();
    /** Chunks to look through for glass, as pairs of the tick they are due and their position (see chunkKey). */
    private final LongArrayFIFOQueue firstLook = new LongArrayFIFOQueue(), secondLook = new LongArrayFIFOQueue();
    /** Glass just put in, which starts clear outside instead of taking the window's ice. */
    private final LongOpenHashSet placed = new LongOpenHashSet();

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
        placed.clear();
        sweepIndex = 0;
    }

    /** A block changed: recheck glass around it, including the column below (roof blocks). */
    public void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        Player player = Minecraft.getInstance().player;
        ResiduumConfig cfg = ResiduumConfig.get();
        if (level == null || player == null || !cfg.enabled) {
            return;
        }
        int reach = cfg.scan.radius + 16;
        boolean near = player.blockPosition().distSqr(pos) <= (double) reach * reach;
        boolean glass = isGlass(oldState) || isGlass(newState);
        // Farms, redstone and growing crops change blocks all over the loaded world: far off, only glass itself coming
        // or going matters, as the full re-check round each change would be too much there
        if (!near && !(glass && player.blockPosition().distSqr(pos) <= keepRadius(cfg) * keepRadius(cfg))) {
            return;
        }
        if (isGlass(oldState) != isGlass(newState)) {
            // Glass broken or put in: what it had is gone for good, even if the player puts it back straight away
            long key = pos.asLong();
            states.remove(key);
            parked.remove(key);
            if (isGlass(newState)) {
                placed.add(key);
            }
        }
        if (!near) {
            for (Direction dir : Direction.values()) {
                lookAt(pos.relative(dir).asLong()); // the window it joins or leaves changes shape
            }
            lookAt(pos.asLong());
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
            if (mc.level == null) {
                parkedByDimension.clear(); // left the world: another one may have glass at the same places
            } else if (level != null) {
                states.values().forEach(this::park);
            }
            clear();
            level = mc.level;
            parked = level == null ? new Long2ObjectOpenHashMap<>()
                    : parkedByDimension.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
        }
        ResiduumConfig cfg = ResiduumConfig.get();
        if (level == null || mc.player == null || !cfg.enabled) {
            return;
        }
        long start = System.nanoTime();
        tick++;
        climate.startTick();

        lookThroughChunks();
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
        // The scan goes on when windows are left to another mod: it also finds the fires and torches for the marks
        if (cfg.glassOn()) {
            update(mc.player.blockPosition(), cfg);
        }
        if (tick % RESTORE_INTERVAL_TICKS == 0) {
            restoreNearby(mc.player.blockPosition(), cfg);
        }
        if (tick % 200 == 0) {
            long now = System.nanoTime();
            parked.values().removeIf(gs -> now - gs.parkedAt > KEEP_NANOS);
        }

        tickNanos = tickNanos * 0.95 + (System.nanoTime() - start) * 0.05;
    }

    /** How far effects reach: as far as the game draws, or the mod's own limit when that is set and shorter. */
    public static double viewRadius(ResiduumConfig cfg) {
        double game = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        return cfg.render.distance > 0 ? Math.min(cfg.render.distance, game) : game;
    }

    /** How far away tracked windows are let go: past where they are drawn and where the sweep finds them, so they stay. */
    private static double keepRadius(ResiduumConfig cfg) {
        return Math.max(cfg.scan.radius, viewRadius(cfg)) + 8.0;
    }

    /** Whether pos is in a desert or badlands, where dust blows about. */
    public static boolean desert(Level level, BlockPos pos) {
        return Climate.sandAt(level, pos) != 0;
    }

    /**
     * A chunk came in: it is looked through for glass, wherever it is, so windows are known before they are in sight and
     * come back as they were as soon as the world round them is there, not when the sweep round the player reaches them.
     */
    public void onChunkLoad(LevelChunk chunk) {
        firstLook.enqueue(tick + CHUNK_FIRST_LOOK_TICKS);
        firstLook.enqueue(chunkKey(chunk.getPos().getMiddleBlockX() >> 4, chunk.getPos().getMiddleBlockZ() >> 4));
    }

    private static long chunkKey(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    private void lookThroughChunks() {
        for (int n = 0; n < CHUNKS_PER_TICK; n++) {
            LongArrayFIFOQueue queue = !firstLook.isEmpty() && firstLook.firstLong() <= tick ? firstLook
                    : !secondLook.isEmpty() && secondLook.firstLong() <= tick ? secondLook : null;
            if (queue == null) {
                return;
            }
            queue.dequeueLong();
            long key = queue.dequeueLong();
            if (queue == firstLook) {
                secondLook.enqueue(tick + CHUNK_SECOND_LOOK_TICKS);
                secondLook.enqueue(key);
            }
            lookThrough((int) (key >> 32), (int) key);
        }
    }

    /** Queues every glass block of the chunk for a look, and tells the decal tracker of its fires and torches. */
    private void lookThrough(int cx, int cz) {
        if (!level.hasChunk(cx, cz)) {
            return;
        }
        LevelChunk chunk = level.getChunk(cx, cz);
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            // Most sections hold none, which their palette tells without looking at each block
            if (section.hasOnlyAir() || !section.maybeHas(s -> isGlass(s) || DecalTracker.tracks(s))) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (isGlass(state)) {
                            lookAt(BlockPos.asLong(cx * 16 + x, baseY + y, cz * 16 + z));
                        } else if (DecalTracker.tracks(state)) {
                            DecalTracker.INSTANCE.onBlock(p.set(cx * 16 + x, baseY + y, cz * 16 + z), state);
                        }
                    }
                }
            }
        }
    }

    private void lookAt(long key) {
        if (pendingSet.add(key)) {
            pending.enqueue(key);
        }
    }

    /**
     * Windows let go that the player is back near again go straight back in, rather than waiting for the sweep to come
     * round to them, which takes seconds: they come back as they were, before they are in sight.
     */
    private void restoreNearby(BlockPos player, ResiduumConfig cfg) {
        double reach = (keepRadius(cfg) - 4) * (keepRadius(cfg) - 4);
        LongIterator it = parked.keySet().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (distSq(key, player) <= reach && pendingSet.add(key)) {
                pending.enqueue(key);
            }
        }
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
        // Glass put in stays new until it is tracked, though it may take a few looks before it counts as a window
        boolean fresh = placed.contains(key);
        if (!level.isLoaded(pos)) {
            GlassState left = states.remove(key);
            if (left != null) {
                park(left);
            }
            return;
        }
        BlockState state = level.getBlockState(pos);
        DecalTracker.INSTANCE.onBlock(pos, state);
        if (!isGlass(state)) {
            states.remove(key);
            parked.remove(key);
            placed.remove(key);
            return;
        }
        GlassState gs = states.get(key);
        boolean back = false;
        if (gs == null) {
            // A window the player comes back to looks as it did when they left
            gs = parked.remove(key);
            back = gs != null && System.nanoTime() - gs.parkedAt <= KEEP_NANOS;
            if (back) {
                gs.renderTime = -1;
            } else {
                gs = new GlassState(key);
            }
        }
        if (!classify(pos, state, gs)) {
            states.remove(key);
            return;
        }
        if (!states.containsKey(key)) {
            if (makeRoom(key)) {
                if (!back) {
                    inheritFromWindow(pos, gs, fresh);
                }
                states.put(key, gs);
                placed.remove(key);
            } else if (back) {
                park(gs);
            }
        }
    }

    /**
     * Glass newly tracked in an existing window takes over its neighbours' fog, frost and ice, so the window keeps
     * freezing as one surface instead of the block starting from clear glass. Glass the player just put in (fresh)
     * only takes the fog and frost of the room: the ice or sand outside has to settle on it again.
     */
    private void inheritFromWindow(BlockPos pos, GlassState gs, boolean fresh) {
        if (!gs.isWindow()) {
            return;
        }
        // The neighbours in the window's plane: up, down and along a wall, or the four round a roof window
        BlockPos[] around = gs.inner.getAxis().isHorizontal()
                ? new BlockPos[] {pos.above(), pos.below(), pos.relative(gs.inner.getClockWise()),
                        pos.relative(gs.inner.getCounterClockWise())}
                : new BlockPos[] {pos.north(), pos.south(), pos.east(), pos.west()};
        for (BlockPos n : around) {
            GlassState other = states.get(n.asLong());
            if (other != null && other.inner == gs.inner) {
                gs.fog = Math.max(gs.fog, other.fog);
                gs.frost = Math.max(gs.frost, other.frost);
                if (!fresh && other.ice > gs.ice) {
                    gs.ice = other.ice;
                    gs.iceFront = other.iceFront + n.getY() - pos.getY();
                }
                if (!fresh && other.sand > gs.sand) {
                    gs.sand = other.sand;
                    gs.sandColor = other.sandColor;
                }
            }
        }
    }

    /**
     * At the cap, drops the window farthest from the player to make room for key, unless key itself is
     * the farthest (then it is not tracked, so far windows do not keep pushing each other out).
     */
    // ponytail: O(n) scan for the farthest window, only runs when the cap is hit
    private boolean makeRoom(long key) {
        if (states.size() < ResiduumConfig.get().tracked.windows) {
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
        park(states.remove(farthest));
        return true;
    }

    /** Keeps a window the player left behind for a while (see KEEP_NANOS). */
    private void park(GlassState gs) {
        gs.parkedAt = System.nanoTime();
        parked.put(gs.pos, gs);
    }

    private static long distSq(long packed, BlockPos to) {
        long dx = BlockPos.getX(packed) - to.getX();
        long dy = BlockPos.getY(packed) - to.getY();
        long dz = BlockPos.getZ(packed) - to.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean isGlass(BlockState state) {
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
        // Glass with a block standing against it inside (a furnace, a shelf) is still part of the window it is in
        for (Direction dir : HORIZONTAL) {
            if (gs.inner == null && (paneAxis == null || dir.getAxis() == paneAxis) && looksOut(pos, dir)
                    && !isOpen(pos.relative(dir.getOpposite()), dir) && inWindow(pos, dir)) {
                gs.inner = dir.getOpposite();
            }
        }
        // Glass in a roof or ceiling, with the sky above it and a room under it, is a window looking up
        if (gs.inner == null && !gs.pane && ResiduumConfig.get().skylights.enabled && skylight(pos)) {
            gs.inner = Direction.DOWN;
        }
        gs.roomMask = 0;
        gs.outsideMask = 0;
        if (gs.inner != null && !gs.pane && gs.inner.getAxis().isHorizontal()) {
            roomSides(pos, gs);
            for (Direction dir : HORIZONTAL) {
                if (dir.getAxis() != gs.inner.getAxis() && isOpen(pos.relative(dir), dir.getOpposite())
                        && isOutside(pos.relative(dir))) {
                    gs.outsideMask |= 1 << dir.get2DDataValue();
                }
            }
        }
        BlockPos above = pos.above();
        gs.rainTop = !gs.pane && isOpen(above, Direction.DOWN) && level.canSeeSky(above);
        gs.renderTime = -1; // faces may have changed, refresh cached light on the next frame
        gs.tint = state.getBlock() instanceof BeaconBeamBlock beam ? beam.getColor().getTextureDiffuseColor() & 0xFFFFFF : 0xFFFFFF;
        return gs.inner != null || gs.rainMask != 0 || gs.rainTop;
    }

    /** Whether the face of the glass at pos toward out is uncovered and out of doors. */
    private boolean looksOut(BlockPos pos, Direction out) {
        BlockPos side = pos.relative(out);
        return isOpen(side, out.getOpposite()) && isOutside(side);
    }

    /**
     * Whether glass right next to pos in the wall (above, below or beside it) is a window looking out toward out.
     * shortcut: only the glass next to it, so of two blocked ones side by side with one neighbour only the nearer
     * counts; walk on along the wall if rows of furnaces behind glass turn up.
     */
    private boolean inWindow(BlockPos pos, Direction out) {
        for (Direction d : new Direction[] {Direction.UP, Direction.DOWN, out.getClockWise(), out.getCounterClockWise()}) {
            BlockPos n = pos.relative(d);
            if (isGlass(level.getBlockState(n)) && looksOut(n, out) && isInside(n.relative(out.getOpposite()), out)) {
                return true;
            }
        }
        return false;
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
                && (!level.canSeeSky(pos) && level.getBrightness(LightLayer.SKY, pos) <= INSIDE_MAX_SKY_LIGHT || underGlass(pos));
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
        return isOpen(pos, toward) && (!level.canSeeSky(pos) && level.getBrightness(LightLayer.SKY, pos) <= INSIDE_MAX_SKY_LIGHT
                || underGlass(pos));
    }

    /**
     * Under a glass roof: sky light comes through glass unweakened, so a room under one is as bright as the outside,
     * and only the roof tells them apart.
     */
    private boolean underGlass(BlockPos pos) {
        BlockPos.MutableBlockPos p = pos.mutable();
        for (int i = 0; i < ROOM_HEIGHT; i++) {
            BlockState state = level.getBlockState(p.move(Direction.UP));
            if (!state.isAir()) {
                return isGlass(state);
            }
        }
        return false;
    }

    /** Glass with open sky right above it and the open air of a room below, with a floor a few blocks down. */
    private boolean skylight(BlockPos pos) {
        BlockPos above = pos.above(), below = pos.below();
        if (!isOpen(above, Direction.DOWN) || !level.canSeeSky(above) || !isOpen(below, Direction.UP)) {
            return false;
        }
        BlockPos.MutableBlockPos p = below.mutable();
        for (int i = 0; i < ROOM_HEIGHT; i++) {
            if (!isOpen(p.move(Direction.DOWN), Direction.UP)) {
                return true;
            }
        }
        return false; // a glass floor over a drop, not a ceiling
    }

    /** Refreshes targets about once a second per window and eases current values every tick. */
    private void update(BlockPos player, ResiduumConfig cfg) {
        double keep = keepRadius(cfg) * keepRadius(cfg);
        ResiduumConfig.Timing t = cfg.timing;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        ObjectIterator<GlassState> it = states.values().iterator();
        while (it.hasNext()) {
            GlassState gs = it.next();
            if (Math.floorMod(tick + gs.seed, TARGET_INTERVAL_TICKS) == 0) {
                p.set(gs.pos);
                if (p.distSqr(player) > keep || !level.isLoaded(p)) {
                    park(gs);
                    it.remove();
                    continue;
                }
                if (!isGlass(level.getBlockState(p))) {
                    it.remove(); // gone without a change reaching us, as far ones may
                    continue;
                }
                updateTargets(p, gs, cfg, p.distSqr(player) > (cfg.scan.radius + 16.0) * (cfg.scan.radius + 16.0));
            }
            gs.fog = approach(gs.fog, gs.fogTarget, t.fogBuildSeconds, t.fogClearSeconds);
            gs.frost = approach(gs.frost, gs.frostTarget, t.frostBuildSeconds, t.frostMeltSeconds);
            gs.drops = approach(gs.drops, gs.dropTarget, DROPS_FADE_IN_SECONDS, t.dryingSeconds);
            gs.flakes = approach(gs.flakes, gs.flakeTarget, FLAKES_SECONDS, FLAKES_MELT_SECONDS);
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
            // Outside ice forms faster on glass that fogs from inside, sliding smoothly between the two paces by how
            // much the room fogs the glass (the target, so all blocks of a window freeze together)
            double fogged = Math.min(1, gs.fogTarget / ICE_FULLY_FOGGED);
            float was = gs.ice;
            double build = (t.iceBuildSeconds + (t.iceFoggedSeconds - t.iceBuildSeconds) * fogged) * (gs.frostyNight ? 2 : 1);
            gs.ice = approach(gs.ice, gs.iceTarget, build, t.frostMeltSeconds);
            gs.iceFront = front(gs, gs.iceFront, gs.ice, was);
            // The wind takes dust off at the pace ice builds up
            gs.sand = approach(gs.sand, gs.sandTarget, dustSeconds(t), t.iceBuildSeconds);
        }
        if (cfg.breathSpot.enabled) {
            breathe(Minecraft.getInstance().player, cfg);
        }
    }

    /**
     * Where the front of the outside ice goes when its amount moved from was: up toward the top of the
     * window while it grows and down toward its bottom while it goes, by the same share of the way left as the amount
     * moved, so it gets there just as the amount does. Only the way left depends on the window's size, so breaking or
     * adding glass never makes the ice jump.
     */
    private static float front(GlassState gs, float front, float amount, float was) {
        float top = gs.glassAbove + 1 + ICE_OVERSHOOT, bottom = -gs.glassBelow;
        if (amount <= 0) {
            return bottom;
        }
        if (was <= 0) {
            front = bottom; // starting on bare glass: from the bottom of the window, wherever the front was left
        }
        if (amount >= 1) {
            return top;
        }
        if (amount > was) {
            return front + (top - front) * (amount - was) / (1 - was);
        }
        if (amount < was) {
            return front - (front - bottom) * (was - amount) / was;
        }
        return front;
    }

    /**
     * Marks the window the player breathes on, if it is cold enough to fog from breath: where their gaze meets the glass,
     * no more than BREATH_DISTANCE from their eyes.
     */
    private void breathe(Player player, ResiduumConfig cfg) {
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
                    // How far along the gaze the glass is, and where on it the gaze lands
                    double reach = dist / facing;
                    if (dist <= 0 || facing <= 0 || reach > BREATH_DISTANCE) {
                        continue;
                    }
                    float x = GlassState.faceX(pos, in, eye.x + look.x * reach, eye.z + look.z * reach);
                    float y = (float) (eye.y + look.y * reach - pos.getY());
                    if (x >= 0 && x <= 1 && y >= 0 && y <= 1) {
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
        // Wiping is for walls: a roof window is out of reach
        if (gs == null || !gs.isWindow() || !gs.inner.getAxis().isHorizontal() || (gs.fog < 0.05f && gs.frost < 0.05f)) {
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

    private void updateTargets(BlockPos pos, GlassState gs, ResiduumConfig cfg, boolean far) {
        gs.dropTarget = gs.rainTop && level.isRainingAt(pos.above()) ? 1 : 0;
        gs.flakeTarget = 0;
        for (Direction dir : HORIZONTAL) {
            int i = dir.get2DDataValue();
            if ((gs.rainMask & (1 << i)) != 0) {
                BlockPos cell = BlockPos.of(gs.rainCell[i]);
                if (level.isRainingAt(cell)) {
                    gs.dropTarget = 1;
                } else if (Climate.snowingAt(level, cell)) {
                    gs.flakeTarget = 1;
                }
            }
        }
        if (!gs.isWindow()) {
            gs.fogTarget = gs.frostTarget = gs.iceTarget = gs.sandTarget = 0;
            return;
        }
        // The glass as it stands, not only the part of it tracked so far: a window that comes into range a block at a
        // time then climbs from one bottom, and its coats climb it unseen too
        gs.glassBelow = column(pos, -1);
        gs.glassAbove = column(pos, 1);
        BlockPos outside = pos.relative(gs.inner.getOpposite());
        BlockPos inside = pos.relative(gs.inner);
        float temp = Climate.outsideTemp(level, outside, cfg.climate);
        // Outside ice grows while snow is falling on the window, and at half the pace on a freezing night without;
        // afterwards it stays while it is freezing and melts once it is not
        boolean snowing = Climate.snowingAt(level, outside);
        gs.frostyNight = !snowing && level.isDarkOutside() && temp < Climate.FREEZING;
        gs.iceTarget = snowing || gs.frostyNight ? 1 : temp < Climate.FREEZING ? gs.ice : 0;
        // Sand settles on desert windows the same way, more of it at night
        int sand = Climate.sandAt(level, outside);
        if (sand != 0) {
            gs.sandColor = sand;
        }
        gs.sandTarget = sand == 0 ? 0 : (level.isDarkOutside() ? SAND_NIGHT : SAND_DAY) * (float) cfg.sand.strength;
        float warmth = climate.warmth(level, inside, tick, cfg.climate.heatScanRadius, far);
        if (Float.isNaN(warmth)) {
            return; // scan budget spent this tick; try again next interval
        }
        float coldness = Math.clamp(((float) cfg.climate.comfortTemp - temp) / (float) cfg.climate.comfortTemp, 0f, 1f);
        gs.outsideTemp = temp;
        gs.warmth = warmth;
        gs.fogTarget = Math.min(1, coldness * warmth * (float) cfg.condensation.strength);
        gs.frostTarget = temp < Climate.FREEZING ? Climate.smoothstep(0.4f, 1.0f, coldness * warmth) : 0;
    }

    /** Glass blocks in a row straight below (step -1) or above (step 1) pos, up to MAX_RUN. */
    private int column(BlockPos pos, int step) {
        BlockPos.MutableBlockPos p = pos.mutable();
        int n = 0;
        while (n < MAX_RUN && isGlass(level.getBlockState(p.move(0, step, 0)))) {
            n++;
        }
        return n;
    }

    /** How long desert dust takes to settle on a window, from clear glass to fully covered. */
    public static double dustSeconds(ResiduumConfig.Timing t) {
        return t.dustBuildSeconds;
    }

    /**
     * Counts contiguous windows facing the same room, starting next to pos and stepping by (sx, sy, sz). A window that
     * turns a corner of glass runs on round it, so its corner has no frame either.
     */
    public static int run(Long2ObjectOpenHashMap<GlassState> states, GlassState gs, BlockPos pos, int sx, int sy, int sz) {
        int n = 0;
        while (n < MAX_RUN) {
            int k = n + 1;
            GlassState next = states.get(BlockPos.asLong(pos.getX() + sx * k, pos.getY() + sy * k, pos.getZ() + sz * k));
            // Panes and full glass blocks in one row of the wall form a single window
            if (next == null || next.inner != gs.inner) {
                return sy == 0 && INSTANCE.turnsCorner(states, gs, pos.offset(sx * k, 0, sz * k), sx, sz) ? n + 1 : n;
            }
            n++;
        }
        return n;
    }

    /**
     * Whether a wall window running along (sx, sz) turns a corner at corner: glass there, and next to it on the room's
     * side the window of the other wall, facing back the way this one came.
     */
    public boolean turnsCorner(Long2ObjectOpenHashMap<GlassState> states, GlassState gs, BlockPos corner, int sx, int sz) {
        if (level == null || !gs.inner.getAxis().isHorizontal() || !isGlass(level.getBlockState(corner))) {
            return false;
        }
        GlassState other = states.get(corner.relative(gs.inner).asLong());
        return other != null && other.inner != null && other.inner.getStepX() == -sx && other.inner.getStepZ() == -sz;
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
