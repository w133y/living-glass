package com.w1z4r_d.residuum.decal;

import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.glass.Climate;
import com.w1z4r_d.residuum.glass.GlassTracker;
import com.w1z4r_d.residuum.render.ModelFace;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Faces of blocks out in the weather taking on a coat: hoarfrost on the tops of grass and leaves on clear nights cold
 * enough to freeze; and while it snows, snow on what is built (not the ground and rock of the land, nor anything that
 * gives light and so is warm): driven thick onto the walls that face the wind and a little onto those it blows along,
 * and a cap of it on the tops of stairs, slabs, fences and walls, which the game's own snow does not settle on. Snow
 * on a wall falls away again once the snowfall is over; a cap stays, as the snow on the ground does.
 * <p>
 * Every face next to one with snow on it is kept as a host: it has no snow of its own, but the snow beside it runs on
 * onto it a few pixels (see DecalRenderer.drawCoat), so snow drapes over the edge of a ledge, drifts up a wall from a
 * step, and passes round a corner instead of stopping at it.
 * <p>
 * Found by sweeping the columns round the player, a slice a tick; a coat the sweep no longer finds in place loses it
 * again. Coats further off are kept as they are while their weather lasts, also in another dimension for a while, so
 * the player finds them as they left them.
 */
public final class Coats {
    public static final Coats INSTANCE = new Coats();

    public enum Kind { RIME, SNOW, CAP }

    public static final class Coat {
        public final long pos;
        public final Direction face;
        /** A host or snow on a wall becomes a cap when the sweep finds its top under the sky. */
        public Kind kind;
        public float amount;
        /**
         * As strong as this coat gets where the sweep finds it in place: all of it facing the wind or as a cap, a
         * little where the wind blows along the face; 0 for a host.
         */
        float full;
        /** The tick the sweep last found this face in place for its coat, and the tick snow beside it last hosted it. */
        long seen = Long.MIN_VALUE / 2, hosted = Long.MIN_VALUE / 2;
        /** Whether the sweep reached this face last tick. */
        boolean near;
        /**
         * For drawing (see DecalRenderer): the parts of the block's model that face this way, each {a0, a1, b0, b1 in
         * face coordinates, the plane it lies in} with the model face it came from where that has see-through pixels;
         * and, worked out about once a second, the light in front and the coat's quads in the block's own coordinates.
         */
        public float[][] rects;
        public ModelFace[] rectFaces;
        public int light;
        public long drawnTime = -1;
        public FloatArrayList drawn;

        Coat(long pos, Direction face, Kind kind) {
            this.pos = pos;
            this.face = face;
            this.kind = kind;
        }
    }

    /** How far round the player coats are found, and how far below the top of a column walls may still take snow. */
    private static final int REACH = 24, WALL_DEPTH = 24;
    /** Coats further off than this go whatever the weather, so the map does not grow without end. */
    private static final int KEEP_REACH = 256;
    private static final int COLUMNS_PER_TICK = 96;
    /** A coat the sweep has not found in place for this long goes again. */
    private static final int STALE_TICKS = 2 * (2 * REACH + 1) * (2 * REACH + 1) / COLUMNS_PER_TICK + 20;
    /** How long frost takes to go in the morning sun (its build-up and the snow's timings are settings). */
    private static final double RIME_MELT = 120;
    /** Snow at least this strong on a face has the faces round it kept as hosts, looked over every HOST_TICKS. */
    private static final float GIVES = 0.02f;
    private static final int HOST_TICKS = 20;
    /** The wind keeps a way for this long, in ticks, before it turns. */
    private static final long WIND_TICKS = 12000;
    /** The ground of the land, by name: the dirt tag holds less of it in some versions than in others. */
    private static final Set<Block> TERRAIN = Set.of(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MOSS_BLOCK, Blocks.PALE_MOSS_BLOCK, Blocks.MUD,
            Blocks.MUDDY_MANGROVE_ROOTS, Blocks.DIRT_PATH, Blocks.FARMLAND, Blocks.GRAVEL, Blocks.CLAY, Blocks.SNOW_BLOCK,
            Blocks.POWDER_SNOW, Blocks.SNOW, Blocks.CALCITE);

    private final Map<DecalTracker.Key, Coat> coats = new HashMap<>();

    /** Each dimension's coats as the player left it, and when (System.nanoTime). */
    private record Stash(long at, Map<DecalTracker.Key, Coat> coats) {
    }

    private final Map<ResourceKey<Level>, Stash> stashed = new HashMap<>();
    private Level level;
    private long tick;
    private int sweep;

    private Coats() {
    }

    public Map<DecalTracker.Key, Coat> coats() {
        return coats;
    }

    /** Where the wind comes from now: a way of its own every ten minutes, the same for everyone in the world. */
    public static Direction windFrom(Level level) {
        int h = (int) (level.getGameTime() / WIND_TICKS) * 0x9E3779B9;
        h ^= h >>> 15;
        return Direction.from2DDataValue(h & 3);
    }

    public void tick(Minecraft mc) {
        if (mc.level != level) {
            if (mc.level == null) {
                stashed.clear(); // left the world: another one may have blocks at the same places
            } else if (level != null) {
                stashed.put(level.dimension(), new Stash(System.nanoTime(), new HashMap<>(coats)));
            }
            coats.clear();
            level = mc.level;
            Stash back = level == null ? null : stashed.remove(level.dimension());
            if (back != null && System.nanoTime() - back.at() <= GlassTracker.KEEP_NANOS) {
                coats.putAll(back.coats());
                coats.values().forEach(c -> c.drawnTime = -1);
            }
        }
        Player player = mc.player;
        ResiduumConfig cfg = ResiduumConfig.get();
        if (level == null || player == null || !cfg.enabled) {
            return;
        }
        tick++;
        boolean frosty = cfg.hoarfrost.enabled && level.isDarkOutside() && !level.isRaining();
        // Snow on walls takes a wind strong enough to drive it there: by default a thunderstorm's, a blizzard
        boolean blizzard = cfg.wallSnow.enabled && level.isRaining() && (!cfg.wallSnow.stormsOnly || level.isThundering());
        boolean capping = cfg.snowCaps.enabled && level.isRaining();
        boolean snowy = blizzard || capping;
        if (frosty || snowy) {
            Direction wind = windFrom(level);
            int span = 2 * REACH + 1;
            for (int k = 0; k < COLUMNS_PER_TICK; k++) {
                sweep = (sweep + 1) % (span * span);
                int x = player.getBlockX() + sweep % span - REACH, z = player.getBlockZ() + sweep / span - REACH;
                column(x, z, player, frosty, blizzard, capping, wind, cfg);
            }
        }
        if (tick % HOST_TICKS == 0) {
            hosts();
        }
        BlockPos at = player.blockPosition();
        double speed = cfg.timing.coatSpeed;
        coats.values().removeIf(c -> {
            BlockPos pos = BlockPos.of(c.pos);
            int dx = Math.abs(pos.getX() - at.getX()), dz = Math.abs(pos.getZ() - at.getZ());
            boolean near = dx <= REACH && dz <= REACH;
            if (near && !c.near) {
                c.seen = tick; // back in reach: the sweep gets round to it soon
            }
            c.near = near;
            // Out of the sweep's reach a coat is taken to be still in place for as long as its weather lasts
            boolean weather = c.kind == Kind.RIME ? frosty : c.kind == Kind.CAP ? capping : blizzard;
            boolean found = !near || tick - c.seen <= STALE_TICKS;
            float target = weather && found ? c.full : 0;
            if (c.kind == Kind.CAP && cfg.snowCaps.enabled && cfg.snowCaps.stay && !level.isRaining()) {
                target = c.amount; // the snowfall is over: what lies on a ledge stays, as the snow on the ground does
            }
            double build = c.kind == Kind.RIME ? cfg.timing.hoarfrostBuildSeconds
                    : c.kind == Kind.CAP ? cfg.timing.capBuildSeconds : cfg.timing.wallSnowBuildSeconds;
            double melt = c.kind == Kind.RIME ? RIME_MELT : cfg.timing.wallSnowMeltSeconds;
            c.amount = GlassTracker.approach(c.amount, target, build / speed, melt / speed);
            boolean host = tick - c.hosted <= 2 * HOST_TICKS;
            return c.amount <= 0 && target <= 0 && !host || dx > KEEP_REACH || dz > KEEP_REACH;
        });
    }

    private void column(int x, int z, Player player, boolean frosty, boolean blizzard, boolean capping, Direction wind,
                        ResiduumConfig cfg) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        if (Math.abs(top - player.getBlockY()) > 32) {
            return; // not loaded, or far over or under the player
        }
        BlockPos ground = new BlockPos(x, top, z);
        if (frosty) {
            BlockState state = level.getBlockState(ground);
            if ((state.is(Blocks.GRASS_BLOCK) || state.is(BlockTags.LEAVES) || state.is(Blocks.PODZOL)
                    || state.is(Blocks.MOSS_BLOCK)) && Climate.outsideTemp(level, ground.above(), cfg.climate) < Climate.FREEZING
                    && level.getBlockState(ground.above()).isAir()) {
                keep(ground, Direction.UP, Kind.RIME, 1);
            }
        }
        if (!(blizzard || capping) || !Climate.snowingAt(level, ground.above())) {
            return;
        }
        // Stairs, slabs, fences, walls, doors and trapdoors under the sky, which the game's own snow does not cover.
        // The height map passes over what does not stop things moving, a door or a closed trapdoor, so up to two blocks
        // over the top it finds are looked at too
        BlockPos cap = ground;
        for (int k = 0; k < 2; k++) {
            BlockPos up = cap.above();
            BlockState over = level.getBlockState(up);
            if (over.isAir() || !built(over, up)) {
                break;
            }
            cap = up;
        }
        BlockState top0 = level.getBlockState(cap);
        if (capping && built(top0, cap) && !top0.isCollisionShapeFullBlock(level, cap)
                && level.getBlockState(cap.above()).isAir()) {
            keep(cap, Direction.UP, Kind.CAP, 1);
        }
        if (!blizzard) {
            return;
        }
        // The walls of this column the wind blows onto or along, with the open sky in front of them
        Direction[] faces = {wind, wind.getClockWise(), wind.getCounterClockWise()};
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = top; y >= top - WALL_DEPTH; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!built(state, pos)) {
                continue;
            }
            for (Direction face : faces) {
                if (windward(pos, face)) {
                    keep(pos.immutable(), face, Kind.SNOW, face == wind ? 1 : (float) cfg.wallSnow.sides);
                }
            }
        }
    }

    /**
     * Something built that snow sticks to: solid, and not the ground or rock of the land (earth, grass, stone, sand,
     * gravel, clay, snow, ice, ores), nor leaves or glass, nor anything that gives light, on which it would melt. Logs
     * count, so tree trunks take snow on their windward side.
     */
    private boolean built(BlockState state, BlockPos pos) {
        return !state.isAir() && state.getLightEmission() == 0 && !state.getCollisionShape(level, pos).isEmpty()
                && !state.is(BlockTags.LEAVES) && !GlassTracker.isGlass(state) && !TERRAIN.contains(state.getBlock())
                && !state.is(BlockTags.DIRT) && !state.is(BlockTags.BASE_STONE_OVERWORLD) && !state.is(BlockTags.SAND)
                && !state.is(BlockTags.ICE) && !state.is(ConventionalBlockTags.ORES) && !state.is(BlockTags.TERRACOTTA);
    }

    /**
     * Whether the wind drives snow onto a face of the block at pos: across open ground in front of it, the open sky
     * over it, or over it a block or two further out, so snow is driven in under the eaves of a roof too.
     */
    private boolean windward(BlockPos pos, Direction face) {
        for (int k = 1; k <= 3; k++) {
            BlockPos front = pos.relative(face, k);
            if (!passable(front)) {
                return false;
            }
            if (open(front)) {
                return true;
            }
        }
        return false;
    }

    /** Air, or something the wind blows through: a snow layer that has drifted there, a plant. */
    private boolean passable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || (state.getBlock() instanceof SnowLayerBlock || state.canBeReplaced()) && state.getFluidState().isEmpty();
    }

    /**
     * Whether the wind blows onto a face across this block: air or something it blows through, a snow layer that has
     * drifted there or a plant, under the open sky.
     */
    private boolean open(BlockPos front) {
        BlockState state = level.getBlockState(front);
        if (state.isAir()) {
            return level.canSeeSky(front);
        }
        return (state.getBlock() instanceof SnowLayerBlock || state.canBeReplaced()) && state.getFluidState().isEmpty()
                && level.canSeeSky(front.above());
    }

    /**
     * Keeps as hosts the faces that share an edge with a face that has snow on it: the next block of the same surface,
     * the other faces of the same block round its corners, and the face of a block that stands out in an inside
     * corner; wherever there is something built, out in the wind or not.
     */
    private void hosts() {
        List<Coat> givers = new ArrayList<>();
        for (Coat c : coats.values()) {
            if (c.kind != Kind.RIME && c.amount > GIVES) {
                givers.add(c);
            }
        }
        for (Coat c : givers) {
            BlockPos pos = BlockPos.of(c.pos);
            for (Direction t : Direction.values()) {
                if (t.getAxis() != c.face.getAxis()) {
                    host(pos.relative(t), c.face);
                    host(pos, t);
                    host(pos.relative(t).relative(c.face), t.getOpposite());
                }
            }
        }
    }

    private void host(BlockPos pos, Direction face) {
        if (face == Direction.DOWN) {
            return;
        }
        DecalTracker.Key key = new DecalTracker.Key(pos.asLong(), face);
        Coat c = coats.get(key);
        if (c == null) {
            if (!built(level.getBlockState(pos), pos)) {
                return;
            }
            c = new Coat(key.pos(), face, Kind.SNOW);
            coats.put(key, c);
        }
        if (c.kind != Kind.RIME) {
            c.hosted = tick;
        }
    }

    private void keep(BlockPos pos, Direction face, Kind kind, float full) {
        Coat c = coats.computeIfAbsent(new DecalTracker.Key(pos.asLong(), face), k -> new Coat(k.pos(), k.face(), kind));
        if (c.kind == Kind.SNOW && kind == Kind.CAP) {
            c.kind = Kind.CAP; // a host on a top the sweep finds under the sky
            c.drawnTime = -1;
        }
        if (c.kind == kind) {
            c.seen = tick;
            c.full = full;
        }
    }

    /**
     * A block changed: a coat on it goes at once, and comes back only as the weather lays it on again. A coat on a
     * face the block now stands in front of stays: snow settling at the foot of a wall leaves the snow on the wall,
     * and a face covered up is not drawn (see DecalRenderer.drawCoat).
     */
    public void onBlockChanged(BlockPos pos) {
        long key = pos.asLong();
        coats.values().removeIf(c -> c.pos == key);
    }
}
