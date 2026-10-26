package com.w1z4r_d.livingglass.decal;

import com.w1z4r_d.livingglass.LivingGlassConfig;
import com.w1z4r_d.livingglass.compat.Compat;
import com.w1z4r_d.livingglass.glass.GlassTracker;
import com.w1z4r_d.livingglass.glass.Melt;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Ash left by fire and splats left by snowballs, on the faces of any blocks around the player. */
public final class DecalTracker {
    public static final DecalTracker INSTANCE = new DecalTracker();

    private static final int TARGET_INTERVAL_TICKS = 10;
    private static final int MAX_SPLATS = 256;
    private static final int MAX_STRIKES = 32;
    private static final double SNOWBALL_RANGE = 64;
    private static final Direction[] FIRE_SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};
    /** How many columns a burn mark reaches either way from the struck one: four blocks across round any point of it. */
    public static final int STRIKE_REACH = 3;

    /** Full scorch under or behind a fire, or the lower half of a block standing next to one. */
    public enum Kind { FULL, SIDE }

    public record Key(long pos, Direction face) {
    }

    public static final class Ash {
        public final long pos;
        public final Direction face;
        public final int seed;
        public Kind kind = Kind.SIDE;
        public float amount, target;
        public final Melt look = new Melt();
        // Render data refreshed about once a second
        public long renderTime = -1;
        public float shade;
        public int light;
        public int mask;
        /** Block-local position of the face's surface along its normal. */
        public double plane;
        /** Part of the face the block's shape actually covers, in face coordinates {a0, a1, b0, b1}. */
        public float[] rect;

        Ash(long pos, Direction face) {
            this.pos = pos;
            this.face = face;
            this.seed = (int) (pos ^ (pos >>> 29)) * 0x9E3779B9 + face.ordinal() * 0x632BE5AB;
        }
    }

    /**
     * A snowball hit, at a point of a face in block-local coordinates. Blocks around it that changed since (broken or
     * placed) are in gone: the parts of the splat that spill onto them go, and new blocks there stay clean.
     */
    public record Splat(long pos, Direction face, double x, double y, double z, int seed, long born, LongOpenHashSet gone) {
    }

    /**
     * A lightning strike: the ground block it hit, the strike point in world coordinates (x, z on the pixel grid) and
     * the ground around it as it was then, so the mark keeps to those blocks: for each column the y of the block whose
     * top carries the mark and the height of that top, one block up to two down from the struck one; +infinity where
     * the ground rises higher and -infinity where it drops away lower, so the mark only climbs or runs down the side
     * there; NaN under water.
     * Blocks changed since are in gone and lose their part of the mark; the ground a broken block uncovers stays clean.
     */
    public record Strike(long ground, double x, double z, int seed, long born, int[] top, double[] surface,
                         LongOpenHashSet gone) {
    }

    private final LongOpenHashSet fires = new LongOpenHashSet();
    private final Map<Key, Ash> ash = new HashMap<>();
    private final List<Splat> splats = new ArrayList<>();
    private final List<Strike> strikes = new ArrayList<>();
    /** Lightning bolts already turned into a strike, so each leaves one mark. */
    private final IntOpenHashSet seenBolts = new IntOpenHashSet();
    private final IntOpenHashSet boltsNow = new IntOpenHashSet();
    /** Last two positions of every snowball in range, to find where it hit once it is gone. */
    private final Int2ObjectOpenHashMap<Vec3[]> snowballs = new Int2ObjectOpenHashMap<>();
    private final IntOpenHashSet alive = new IntOpenHashSet();
    private Level level;
    private long tick;

    private DecalTracker() {
    }

    public Map<Key, Ash> ash() {
        return ash;
    }

    public List<Splat> splats() {
        return splats;
    }

    public List<Strike> strikes() {
        return strikes;
    }

    public long tick() {
        return tick;
    }

    /** Called for every block the glass scanner looks at, so fires around the player are found too. */
    public void onBlock(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof BaseFireBlock) {
            fires.add(pos.asLong());
        } else {
            fires.remove(pos.asLong());
        }
    }

    /**
     * A block changed: ash and splats on it vanish at once, so do the parts of splats and burn marks around it that
     * lay on it, and ash next to it re-checks how it joins up.
     */
    public void onBlockChanged(BlockPos pos) {
        long key = pos.asLong();
        ash.values().removeIf(a -> a.pos == key && (level == null || !hasFace(pos, a.face)));
        splats.removeIf(s -> s.pos() == key);
        for (Splat s : splats) {
            if (BlockPos.of(s.pos()).distManhattan(pos) <= 2) {
                s.gone().add(key);
            }
        }
        for (Strike st : strikes) {
            BlockPos ground = BlockPos.of(st.ground());
            if (Math.abs(pos.getX() - ground.getX()) <= STRIKE_REACH && Math.abs(pos.getZ() - ground.getZ()) <= STRIKE_REACH) {
                st.gone().add(key);
            }
        }
        for (Ash a : ash.values()) {
            if (BlockPos.of(a.pos).distManhattan(pos) <= 2) {
                a.renderTime = -1;
            }
        }
    }

    public void tick(Minecraft mc) {
        if (mc.level != level) {
            fires.clear();
            ash.clear();
            splats.clear();
            snowballs.clear();
            strikes.clear();
            seenBolts.clear();
            level = mc.level;
        }
        LivingGlassConfig cfg = LivingGlassConfig.get();
        if (level == null || mc.player == null || !cfg.enabled) {
            return;
        }
        tick++;
        if (cfg.snowballs.enabled) {
            trackSnowballs(mc.player.position());
        }
        long life = (long) (cfg.timing.snowSeconds * 20);
        splats.removeIf(s -> tick - s.born > life);
        if (cfg.lightning.enabled) {
            trackLightning();
        }
        long scorchLife = (long) (cfg.timing.lightningSeconds * 20);
        strikes.removeIf(s -> tick - s.born > scorchLife);

        if (tick % TARGET_INTERVAL_TICKS == 0) {
            updateAshTargets(mc.player.blockPosition(), cfg);
        }
        LivingGlassConfig.Timing t = cfg.timing;
        Iterator<Ash> it = ash.values().iterator();
        while (it.hasNext()) {
            Ash a = it.next();
            a.amount = GlassTracker.approach(a.amount, a.target, a.kind == Kind.FULL ? t.ashBuildSeconds : t.ashSideBuildSeconds,
                    t.ashFadeSeconds);
            a.look.tick(t.meltSeconds);
            if (a.amount <= 0 && a.target <= 0) {
                it.remove();
            }
        }
    }

    private void updateAshTargets(BlockPos player, LivingGlassConfig cfg) {
        for (Ash a : ash.values()) {
            a.target = 0;
        }
        if (!cfg.ash.enabled) {
            return;
        }
        int reach = cfg.scan.radius + 16;
        BlockPos.MutableBlockPos f = new BlockPos.MutableBlockPos();
        LongIterator it = fires.iterator();
        while (it.hasNext()) {
            f.set(it.nextLong());
            BlockState state = level.getBlockState(f);
            if (!(state.getBlock() instanceof BaseFireBlock) || f.distSqr(player) > (double) reach * reach) {
                it.remove();
                continue;
            }
            // Burning on top of a block, and on the faces of blocks a fire clings to
            mark(f.below(), Direction.UP, Kind.FULL);
            if (state.getBlock() instanceof FireBlock) {
                for (Direction dir : FIRE_SIDES) {
                    BooleanProperty clings = FireBlock.PROPERTY_BY_DIRECTION.get(dir);
                    if (state.hasProperty(clings) && state.getValue(clings)) {
                        mark(f.relative(dir), dir.getOpposite(), Kind.FULL);
                    }
                }
            }
            // Blocks standing right next to the fire get ash on their lower half
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                mark(f.relative(dir), dir.getOpposite(), Kind.SIDE);
            }
        }
        // Faces whose block is gone lose their ash at once
        ash.values().removeIf(a -> !hasFace(BlockPos.of(a.pos), a.face));
    }

    private void mark(BlockPos pos, Direction face, Kind kind) {
        if (!hasFace(pos, face)) {
            return;
        }
        Ash a = ash.computeIfAbsent(new Key(pos.asLong(), face), k -> new Ash(k.pos(), k.face()));
        a.target = 1;
        if (kind == Kind.FULL) {
            a.kind = Kind.FULL; // a full scorch wins over side ash on the same face
        }
    }

    /** The block at pos has something on its face toward face, so ash has a surface to sit on. */
    public boolean hasFace(BlockPos pos, Direction face) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !(state.getBlock() instanceof BaseFireBlock)
                && !state.getShape(level, pos).getFaceShape(face).isEmpty();
    }

    /** Every new lightning bolt leaves a burn mark on the ground it struck. */
    private void trackLightning() {
        boltsNow.clear();
        for (Entity e : ((net.minecraft.client.multiplayer.ClientLevel) level).entitiesForRendering()) {
            if (!Compat.isLightning(e)) {
                continue;
            }
            boltsNow.add(e.getId());
            if (seenBolts.add(e.getId())) {
                strike(e.position());
            }
        }
        seenBolts.retainAll(boltsNow);
    }

    private void strike(Vec3 at) {
        BlockPos pos = BlockPos.containing(at.x, at.y - 0.05, at.z);
        for (int i = 0; i < 4 && !hasFace(pos, Direction.UP); i++) {
            pos = pos.below();
        }
        if (!hasFace(pos, Direction.UP)) {
            return;
        }
        if (strikes.size() >= MAX_STRIKES) {
            strikes.remove(0);
        }
        // The highest top a mark can lie on in every column around the strike
        int size = 2 * STRIKE_REACH + 1;
        int[] top = new int[size * size];
        double[] surface = new double[size * size];
        for (int dx = -STRIKE_REACH; dx <= STRIKE_REACH; dx++) {
            for (int dz = -STRIKE_REACH; dz <= STRIKE_REACH; dz++) {
                int i = (dx + STRIKE_REACH) * size + dz + STRIKE_REACH;
                surface[i] = Double.NaN;
                for (int dy = 1; dy >= -2; dy--) {
                    BlockPos block = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(block);
                    if (state.getBlock() instanceof LiquidBlock) {
                        break;
                    }
                    double height = groundHeight(block);
                    if (height > 0) {
                        top[i] = block.getY();
                        surface[i] = block.getY() + height;
                        break;
                    }
                    if (dy == 1 && !state.getCollisionShape(level, block).isEmpty()) {
                        top[i] = block.getY(); // solid and buried: a hill or wall going on up
                        surface[i] = Double.POSITIVE_INFINITY;
                        break;
                    }
                    if (dy == -2) {
                        surface[i] = Double.NEGATIVE_INFINITY;
                    }
                }
            }
        }
        strikes.add(new Strike(pos.asLong(), Math.round(at.x * 16) / 16.0, Math.round(at.z * 16) / 16.0,
                (int) (tick * 0x632BE5ABL ^ pos.asLong()), tick, top, surface, new LongOpenHashSet()));
    }

    /**
     * Height (0..1) of the surface on top of block that a burn mark can lie on, or 0 if there is none: the top of its
     * real shape, so snow layers, slabs and carpets count, but grass and flowers you walk through do not, and neither
     * does a block buried under a full block.
     */
    private double groundHeight(BlockPos block) {
        BlockState state = level.getBlockState(block);
        if (state.isAir() || state.getBlock() instanceof BaseFireBlock
                || (state.getCollisionShape(level, block).isEmpty() && !(state.getBlock() instanceof SnowLayerBlock))) {
            return 0;
        }
        BlockPos above = block.above();
        if (level.getBlockState(above).isCollisionShapeFullBlock(level, above)) {
            return 0;
        }
        VoxelShape shape = state.getShape(level, block);
        return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
    }

    private void trackSnowballs(Vec3 player) {
        alive.clear();
        double range = SNOWBALL_RANGE * SNOWBALL_RANGE;
        for (Entity e : ((net.minecraft.client.multiplayer.ClientLevel) level).entitiesForRendering()) {
            if (!Compat.isSnowball(e) || e.position().distanceToSqr(player) > range) {
                continue;
            }
            alive.add(e.getId());
            Vec3[] last = snowballs.get(e.getId());
            Vec3 earlier = last == null ? e.position().subtract(e.getDeltaMovement()) : last[1];
            snowballs.put(e.getId(), new Vec3[] {earlier, e.position(), e.getDeltaMovement()});
        }
        var it = snowballs.int2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!alive.contains(entry.getIntKey())) {
                impact(entry.getValue());
                it.remove();
            }
        }
    }

    /** A snowball vanished: find the block face it was flying into, unless it hit a mob or player first. */
    private void impact(Vec3[] track) {
        Vec3 from = track[0];
        Vec3 velocity = track[2];
        if (velocity.lengthSqr() < 1.0e-4) {
            return;
        }
        Vec3 to = track[1].add(velocity.scale(1.5)).add(velocity.normalize().scale(0.6));
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        Vec3 at = hit.getLocation();
        for (Entity e : level.getEntities((Entity) null, new AABB(from, at).inflate(0.5),
                e -> e.isPickable() && !Compat.isSnowball(e))) {
            if (e.getBoundingBox().inflate(0.3).clip(from, at).isPresent()) {
                return; // it hit someone, not the wall behind them
            }
        }
        BlockPos pos = hit.getBlockPos();
        if (splats.size() >= MAX_SPLATS) {
            splats.remove(0);
        }
        splats.add(new Splat(pos.asLong(), hit.getDirection(), at.x - pos.getX(), at.y - pos.getY(), at.z - pos.getZ(),
                (int) (tick * 0x9E3779B9L ^ pos.asLong()), tick, new LongOpenHashSet()));
    }

}
