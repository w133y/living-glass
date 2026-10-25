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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

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
    private static final double SNOWBALL_RANGE = 64;
    private static final Direction[] FIRE_SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};

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
        /** Block-local position of the face plane along its normal, lifted just off the surface. */
        public double plane;

        Ash(long pos, Direction face) {
            this.pos = pos;
            this.face = face;
            this.seed = (int) (pos ^ (pos >>> 29)) * 0x9E3779B9 + face.ordinal() * 0x632BE5AB;
        }
    }

    /** A snowball hit, at a point of a face in block-local coordinates. */
    public record Splat(long pos, Direction face, double x, double y, double z, int seed, long born) {
    }

    private final LongOpenHashSet fires = new LongOpenHashSet();
    private final Map<Key, Ash> ash = new HashMap<>();
    private final List<Splat> splats = new ArrayList<>();
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

    public void tick(Minecraft mc) {
        if (mc.level != level) {
            fires.clear();
            ash.clear();
            splats.clear();
            snowballs.clear();
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
    boolean hasFace(BlockPos pos, Direction face) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !(state.getBlock() instanceof BaseFireBlock)
                && !state.getShape(level, pos).getFaceShape(face).isEmpty();
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
                (int) (tick * 0x9E3779B9L ^ pos.asLong()), tick));
    }

}
