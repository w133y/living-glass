package com.w1z4r_d.livingglass.decal;

import com.w1z4r_d.livingglass.LivingGlassConfig;
import com.w1z4r_d.livingglass.compat.Compat;
import com.w1z4r_d.livingglass.glass.GlassTracker;
import com.w1z4r_d.livingglass.glass.Melt;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
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
    private static final Direction[] FIRE_SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};
    /** How many columns a burn mark reaches either way from the struck one: four blocks across round any point of it. */
    public static final int STRIKE_REACH = 3;
    /** How many posts or fences stacked on the ground a burn mark climbs at most; the struck stack, all of it. */
    private static final int POST_HEIGHT = 4;
    private static final int STRUCK_HEIGHT = 24;

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
        /**
         * The ash on the neighbouring faces that meet this one at each corner (left-bottom, right-bottom, right-top,
         * left-top in face coordinates), so the patches build up and fade together where they meet.
         */
        public Ash[][] corners = {{}, {}, {}, {}};
        /**
         * Corners where the ash runs on past both edges but not round the corner itself (a wall behind this block
         * that ends at the next one): the patch fades out there instead of stopping square.
         */
        public final boolean[] notch = new boolean[4];

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
     * the ground around it as it was then, so the mark keeps to those blocks. For each column: the height of its
     * ground, one block up to two down from the struck one (+infinity where the ground rises higher and -infinity
     * where it drops away lower, so the mark only climbs or runs down the side there; NaN under water), and as bits up
     * from the ground block, the blocks whose tops carry the mark: the ground and posts, fences or lanterns on it.
     * Blocks changed since are in gone and lose their part of the mark; the ground a broken block uncovers stays clean.
     */
    public record Strike(long ground, double x, double z, int seed, long born, int[] layers, double[] surface,
                         LongOpenHashSet gone) {
    }

    /** What each dimension's marks were when the player went to another, kept a while for coming back. */
    private record Stash(long at, Map<Key, Ash> ash, List<Splat> splats, List<Strike> strikes) {
    }

    private final Map<ResourceKey<Level>, Stash> stashed = new HashMap<>();
    private final LongOpenHashSet fires = new LongOpenHashSet();
    private final Map<Key, Ash> ash = new HashMap<>();
    private final List<Splat> splats = new ArrayList<>();
    private final List<Strike> strikes = new ArrayList<>();
    /** Lightning bolts already turned into a strike, so each leaves one mark. */
    private final IntOpenHashSet seenBolts = new IntOpenHashSet();
    private final IntOpenHashSet boltsNow = new IntOpenHashSet();
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
            if (mc.level == null) {
                stashed.clear(); // left the world: another one may have blocks at the same places
            } else if (level != null) {
                stashed.put(level.dimension(), new Stash(System.nanoTime(), new HashMap<>(ash), new ArrayList<>(splats),
                        new ArrayList<>(strikes)));
            }
            fires.clear();
            ash.clear();
            splats.clear();
            strikes.clear();
            seenBolts.clear();
            level = mc.level;
            Stash back = level == null ? null : stashed.remove(level.dimension());
            if (back != null && System.nanoTime() - back.at() <= GlassTracker.KEEP_NANOS) {
                ash.putAll(back.ash());
                splats.addAll(back.splats());
                strikes.addAll(back.strikes());
            }
        }
        LivingGlassConfig cfg = LivingGlassConfig.get();
        if (level == null || mc.player == null || !cfg.enabled) {
            return;
        }
        tick++;
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
        for (int i = 0; i < 4 && groundHeight(pos) <= 0; i++) {
            pos = pos.below();
        }
        if (groundHeight(pos) <= 0) {
            return;
        }
        // A fence, post or lantern struck stands on the ground the mark spreads over, however tall its stack
        for (int i = 0; i < STRUCK_HEIGHT && !fullFootprint(pos) && groundHeight(pos.below()) > 0; i++) {
            pos = pos.below();
        }
        if (strikes.size() >= MAX_STRIKES) {
            strikes.remove(0);
        }
        // The ground in every column around the strike, and what stands on it that the mark lies on too
        int size = 2 * STRIKE_REACH + 1;
        int[] layers = new int[size * size];
        double[] surface = new double[size * size];
        for (int dx = -STRIKE_REACH; dx <= STRIKE_REACH; dx++) {
            for (int dz = -STRIKE_REACH; dz <= STRIKE_REACH; dz++) {
                int i = (dx + STRIKE_REACH) * size + dz + STRIKE_REACH;
                surface[i] = Double.NaN;
                int lowest = Integer.MIN_VALUE;
                boolean water = false;
                for (int dy = 1; dy >= -2; dy--) {
                    BlockPos block = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(block);
                    if (state.getBlock() instanceof LiquidBlock) {
                        water = true;
                        break;
                    }
                    double height = groundHeight(block);
                    if (height > 0) {
                        lowest = block.getY();
                        if (fullFootprint(block)) {
                            surface[i] = block.getY() + height;
                            break;
                        }
                        continue; // a post or fence: the ground it stands on is further down
                    }
                    if (dy == 1 && !state.getCollisionShape(level, block).isEmpty()) {
                        surface[i] = Double.POSITIVE_INFINITY; // solid and buried: a hill or wall going on up
                        break;
                    }
                }
                if (Double.isNaN(surface[i]) && !water) {
                    // Nothing to stand on within reach: posts over a drop are ground themselves, else it drops away
                    surface[i] = lowest != Integer.MIN_VALUE
                            ? lowest + groundHeight(new BlockPos(pos.getX() + dx, lowest, pos.getZ() + dz))
                            : Double.NEGATIVE_INFINITY;
                }
                if (Double.isFinite(surface[i])) {
                    // The ground and the posts, fences or lanterns stacked on it, however high the strike hit them
                    int groundY = groundY(surface[i]);
                    layers[i] = 1;
                    for (int k = 1; k <= (dx == 0 && dz == 0 ? STRUCK_HEIGHT : POST_HEIGHT); k++) {
                        BlockPos block = new BlockPos(pos.getX() + dx, groundY + k, pos.getZ() + dz);
                        if (groundHeight(block) <= 0 || fullFootprint(block)) {
                            break;
                        }
                        layers[i] |= 1 << k;
                    }
                }
            }
        }
        strikes.add(new Strike(pos.asLong(), Math.round(at.x * 16) / 16.0, Math.round(at.z * 16) / 16.0,
                (int) (tick * 0x632BE5ABL ^ pos.asLong()), tick, layers, surface, new LongOpenHashSet()));
    }

    /** The y of the block whose top is the ground at surface. */
    public static int groundY(double surface) {
        return (int) Math.ceil(surface - 1.0e-6) - 1;
    }

    private boolean fullFootprint(BlockPos block) {
        return fullFootprint(level, block);
    }

    /** Whether block's shape covers its whole cell seen from above, so it is ground, not a post standing on it. */
    public static boolean fullFootprint(Level level, BlockPos block) {
        boolean[] covered = new boolean[256];
        int count = 0;
        for (AABB box : level.getBlockState(block).getShape(level, block).toAabbs()) {
            for (int x = (int) Math.round(box.minX * 16); x < Math.round(box.maxX * 16); x++) {
                for (int z = (int) Math.round(box.minZ * 16); z < Math.round(box.maxZ * 16); z++) {
                    if (!covered[x * 16 + z]) {
                        covered[x * 16 + z] = true;
                        count++;
                    }
                }
            }
        }
        return count == 256;
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

    /**
     * A snowball broke on something (the server says so): leave a splat on the block face it flew into, unless it hit
     * a mob or player on the way. The ray runs back over its last tick of flight and on past where it is now, as the
     * server saw the hit a little before this client did; it meets blocks the way the snowball does, so grass and
     * flowers it flies through do not catch it, and a splat on the ground under a snow layer goes on the snow.
     */
    public void snowballHit(Entity snowball) {
        LivingGlassConfig cfg = LivingGlassConfig.get();
        Vec3 velocity = snowball.getDeltaMovement();
        if (level == null || !cfg.enabled || !cfg.snowballs.enabled || velocity.lengthSqr() < 1.0e-4) {
            return;
        }
        Vec3 from = snowball.position().subtract(velocity);
        Vec3 to = snowball.position().add(velocity.scale(1.5)).add(velocity.normalize().scale(0.6));
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
            return;
        }
        Vec3 at = hit.getLocation();
        Entity owner = snowball instanceof Projectile projectile ? projectile.getOwner() : null;
        for (Entity e : level.getEntities(snowball, new AABB(from, at).inflate(0.5),
                e -> e.isPickable() && e != owner && !Compat.isSnowball(e))) {
            if (e.getBoundingBox().inflate(0.3).clip(from, at).isPresent()) {
                return; // it hit someone, not the wall behind them
            }
        }
        BlockPos pos = hit.getBlockPos();
        Direction face = hit.getDirection();
        BlockPos above = pos.above();
        BlockState layer = level.getBlockState(above);
        if (face == Direction.UP && layer.getBlock() instanceof SnowLayerBlock) {
            // A thin snow layer has no collision of its own, so the snowball went through it to the ground
            pos = above;
            at = new Vec3(at.x, above.getY() + layer.getShape(level, above).max(Direction.Axis.Y), at.z);
        }
        if (splats.size() >= MAX_SPLATS) {
            splats.remove(0);
        }
        splats.add(new Splat(pos.asLong(), face, at.x - pos.getX(), at.y - pos.getY(), at.z - pos.getZ(),
                (int) (tick * 0x9E3779B9L ^ pos.asLong()), tick, new LongOpenHashSet()));
    }

}
