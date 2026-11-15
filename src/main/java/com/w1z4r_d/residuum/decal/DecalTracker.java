package com.w1z4r_d.residuum.decal;

import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.compat.Compat;
import com.w1z4r_d.residuum.glass.GlassTracker;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.LightningRodBlock;
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
    private static final int MAX_SHIELD_MARKS = 3;
    /**
     * How long an axe's gash stays on someone else's shield: as long as vanilla axes keep a shield down. On your own it
     * goes with the shield's actual cooldown (see ShieldMarks).
     */
    public static final long SCRATCH_TICKS = 100;
    /** How far from a disabled shield's holder the sound of it may be, in blocks, to be put down to that holder. */
    private static final double SCRATCH_REACH = 0.75;
    /** How far off the middle of a shield's plate, in pixels, a mark's centre may be (the plate cuts marks off at its edge). */
    private static final int SHIELD_REACH_X = 2, SHIELD_REACH_UP = 6, SHIELD_REACH_DOWN = 4;
    private static final int MAX_STRIKES = 32;
    private static final int MAX_CRACKS = 128, MAX_PRINTS = 512;
    /** How far round the player mobs and other players leave footprints, in blocks. */
    private static final double PRINT_REACH = 24;
    /**
     * A footprint every STRIDE blocks walked, each foot in turn, FOOT_SIDE either side of the middle of the way it goes
     * (for something as wide as a player). Moving further than JUMP in a tick is a teleport, not a step.
     */
    private static final double STRIDE = 0.55, FOOT_SIDE = 0.12, JUMP = 3;
    private static final Direction[] FIRE_SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP};
    /** How many columns a burn mark reaches either way from the struck one: four blocks across round any point of it. */
    public static final int STRIKE_REACH = 3;
    /** How many posts or fences stacked on the ground a burn mark climbs at most; the struck stack, all of it. */
    private static final int POST_HEIGHT = 4;
    private static final int STRUCK_HEIGHT = 24;

    /**
     * Explosions blacken what they blew open: faces looking toward the blast with nothing in between, out to
     * BLAST_REACH times its radius and a block, black over the inner BLAST_CORE of that and thinning out to nothing at
     * its edge. The server sends the blast before the blocks it broke, so the soot goes on BLAST_DELAY ticks later, onto the
     * crater as it is by then.
     */
    private static final double BLAST_REACH = 1.5, BLAST_CORE = 0.6;
    private static final int BLAST_DELAY = 5;
    /** shortcut: blasts add no ash past this many faces, each costing a few hundred pixels a frame; raise if drawAsh gets cheaper. */
    private static final int MAX_ASH = 4096;

    /** How long soot takes to build up over a torch just put in, and to go once the torch is gone. */
    public static final double TORCH_SOOT_SECONDS = 120, TORCH_SOOT_FADE_SECONDS = 600;
    /** How far toward its wall the flame of a torch on a wall sits from the middle of its block, as vanilla's smoke. */
    private static final double WALL_FLAME = 0.27;

    /**
     * Full scorch under or behind a fire, the lower half of a block standing next to one, or a smudge of soot on the
     * underside of the block over a torch.
     */
    public enum Kind { FULL, SIDE, SPOT }

    public record Key(long pos, Direction face) {
    }

    public static final class Ash {
        public final long pos;
        public final Direction face;
        public final int seed;
        public Kind kind = Kind.SIDE;
        public float amount, target;
        // Render data refreshed about once a second
        public long renderTime = -1;
        public float shade;
        public int light;
        /** Block-local position of the face's surface along its normal. */
        public double plane;
        /** Part of the face the block's shape actually covers, in face coordinates {a0, a1, b0, b1}. */
        public float[] rect;
        /** The ash past each edge (left, right, bottom, top in face coordinates), on this surface or round a corner. */
        public final Ash[] edges = new Ash[4];
        /**
         * Corners where the ash runs on past both edges but not round the corner itself (a wall behind this block
         * that ends at the next one): the patch fades out there instead of stopping square.
         */
        public final boolean[] notch = new boolean[4];
        /** Spots: where the flame is under the face, in block-local x and z, and how much soot it gives (1 or half). */
        public double spotX = 0.5, spotZ = 0.5;
        public float scale = 1;
        /** Soot an explosion left: how much, held until tick blastUntil, then fading as any ash does. */
        public float blast;
        public long blastUntil;
        /**
         * What DecalRenderer drew of this face last, as rectangles of equal pixels (x0, x1, y0, y1 in pixels, then
         * strength), and the strengths of the face and of the ash past its edges it drew them from.
         */
        public it.unimi.dsi.fastutil.floats.FloatArrayList drawn;
        public final float[] drawnFrom = new float[5];

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
     * A snowball splat on a shield its holder had raised, or the gash an axe left knocking it aside (scratch): where on
     * the front of the plate, in texture pixels right and up of its middle, and when.
     */
    public record ShieldMark(int entity, int x, int y, int seed, long born, boolean scratch) {
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
    /** Torches seen, wherever they are, each smudging the block over its flame. */
    private final LongOpenHashSet torches = new LongOpenHashSet();
    /** Torches just put in, under which soot builds up from nothing rather than having been there all along. */
    private final LongOpenHashSet newTorches = new LongOpenHashSet();
    private final Map<Key, Ash> ash = new HashMap<>();
    private final List<Splat> splats = new ArrayList<>();
    private final List<ShieldMark> shieldMarks = new ArrayList<>();
    private final List<Strike> strikes = new ArrayList<>();
    /**
     * Cracks an arrow or trident left in glass, as splats: the face it hit, where, and seed picks the look. The crack
     * goes through, so it shows on the far side of the glass too.
     */
    private final List<Splat> cracks = new ArrayList<>();
    /** Holes arrows and tridents left in blocks other than glass, as splats; they share the cracks' life. */
    private final List<Splat> holes = new ArrayList<>();
    /**
     * Footprints in snow and sand, as splats on the top of the block they are pressed into, at the height of its top
     * (y); seed is the texture (print_N) and, from bit 8, the quarter turns it is drawn with.
     */
    private final List<Splat> prints = new ArrayList<>();
    /** Where each walker round the player left its last footprint: x, z, and the foot (1 right, -1 left). */
    private final Int2ObjectOpenHashMap<double[]> walkers = new Int2ObjectOpenHashMap<>();

    /** An explosion whose soot is still to go on: where, how big, and the tick it goes on. */
    private record Blast(Vec3 at, float radius, long due) {
    }

    private final List<Blast> blasts = new ArrayList<>();
    /** Lightning bolts already turned into a strike, so each leaves one mark. */
    private final IntOpenHashSet seenBolts = new IntOpenHashSet();
    /**
     * A lightning rod struck lately, crackling for a while after (see RodArcs): the rod, the axis it stands along, the
     * game time it was struck and for how many ticks it crackles, RODS_MIN to RODS_MAX. It throws off up to RODS_SPARKS
     * sparks a tick at first, fewer and fewer until it is quiet.
     */
    public record Rod(BlockPos pos, Direction.Axis axis, long struck, int ticks) {
    }

    private static final int RODS_MIN = 200, RODS_MAX = 300;
    private static final float RODS_SPARKS = 0.8f;
    private final List<Rod> rods = new ArrayList<>();
    private final java.util.Random random = new java.util.Random();
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

    public List<ShieldMark> shieldMarks() {
        return shieldMarks;
    }

    public List<Strike> strikes() {
        return strikes;
    }

    public List<Splat> cracks() {
        return cracks;
    }

    public List<Splat> holes() {
        return holes;
    }

    public List<Splat> prints() {
        return prints;
    }

    public long tick() {
        return tick;
    }

    /** Blocks the glass scanner tells this tracker about wherever it finds them: fires and torches. */
    public static boolean tracks(BlockState state) {
        return state.getBlock() instanceof BaseFireBlock || sooty(state);
    }

    /** Torches that burn with a flame: all but redstone ones. */
    private static boolean sooty(BlockState state) {
        return state.getBlock() instanceof BaseTorchBlock && !(state.getBlock() instanceof RedstoneTorchBlock);
    }

    /** Called for every block the glass scanner looks at, so fires and torches around the player are found too. */
    public void onBlock(BlockPos pos, BlockState state) {
        long key = pos.asLong();
        if (state.getBlock() instanceof BaseFireBlock) {
            fires.add(key);
        } else {
            fires.remove(key);
        }
        if (sooty(state)) {
            torches.add(key);
        }
    }

    /**
     * A block changed: ash and splats on it vanish at once, so do the parts of splats and burn marks around it that
     * lay on it, and ash next to it re-checks how it joins up.
     */
    public void onBlockChanged(BlockPos pos, boolean replaced) {
        long key = pos.asLong();
        if (replaced) {
            // Another block in its place, broken and built again or swapped: it is clean, whatever was on the old one
            ash.values().removeIf(a -> a.pos == key);
        }
        if (level != null && sooty(level.getBlockState(pos))) {
            torches.add(key);
            newTorches.add(key);
        }
        // Soot over a torch goes with the block it was on, whatever comes in its place, and a block put in over a torch
        // is clean: the torch smudges it afresh
        Ash spot = ash.get(new Key(key, Direction.DOWN));
        if (spot != null && spot.kind == Kind.SPOT) {
            ash.remove(new Key(key, Direction.DOWN));
        }
        for (int down = 1; down <= 2; down++) {
            long below = pos.below(down).asLong();
            if (torches.contains(below)) {
                newTorches.add(below);
            }
        }
        ash.values().removeIf(a -> a.pos == key && (level == null || !hasFace(pos, a.face)));
        splats.removeIf(s -> s.pos() == key);
        cracks.removeIf(s -> s.pos() == key);
        holes.removeIf(s -> s.pos() == key);
        prints.removeIf(s -> s.pos() == key);
        for (List<Splat> marks : List.of(splats, cracks, holes, prints)) {
            for (Splat s : marks) {
                if (BlockPos.of(s.pos()).distManhattan(pos) <= 2) {
                    s.gone().add(key);
                }
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
            torches.clear();
            newTorches.clear();
            ash.clear();
            splats.clear();
            cracks.clear();
            holes.clear();
            prints.clear();
            walkers.clear();
            shieldMarks.clear();
            strikes.clear();
            blasts.clear();
            seenBolts.clear();
            rods.clear();
            level = mc.level;
            Stash back = level == null ? null : stashed.remove(level.dimension());
            if (back != null && System.nanoTime() - back.at() <= GlassTracker.KEEP_NANOS) {
                ash.putAll(back.ash());
                splats.addAll(back.splats());
                strikes.addAll(back.strikes());
            }
        }
        ResiduumConfig cfg = ResiduumConfig.get();
        if (level == null || mc.player == null || !cfg.enabled) {
            return;
        }
        tick++;
        long life = (long) (cfg.timing.snowSeconds * 20);
        splats.removeIf(s -> tick - s.born > life);
        long crackLife = (long) (cfg.timing.crackSeconds * 20), printLife = (long) (cfg.timing.footprintSeconds * 20);
        cracks.removeIf(s -> tick - s.born > crackLife);
        holes.removeIf(s -> tick - s.born > crackLife);
        prints.removeIf(s -> tick - s.born > printLife);
        if (cfg.printsOn()) {
            footprints(mc.player, cfg);
        } else {
            walkers.clear();
        }
        shieldMarks.removeIf(s -> tick - s.born > (s.scratch ? SCRATCH_TICKS : life));
        if (cfg.lightning.enabled) {
            trackLightning();
            sparkRods();
        }
        long scorchLife = (long) (cfg.timing.lightningSeconds * 20);
        strikes.removeIf(s -> tick - s.born > scorchLife);
        blasts.removeIf(b -> {
            if (tick < b.due()) {
                return false;
            }
            blacken(b, (long) (cfg.timing.blastSeconds * 20));
            return true;
        });

        if (tick % TARGET_INTERVAL_TICKS == 0) {
            updateAshTargets(mc.player.blockPosition(), cfg);
        }
        ResiduumConfig.Timing t = cfg.timing;
        Iterator<Ash> it = ash.values().iterator();
        while (it.hasNext()) {
            Ash a = it.next();
            if (a.kind == Kind.SPOT) {
                a.amount = GlassTracker.approach(a.amount, a.target, TORCH_SOOT_SECONDS, TORCH_SOOT_FADE_SECONDS);
            } else {
                a.amount = GlassTracker.approach(a.amount, a.target, a.kind == Kind.FULL ? t.ashBuildSeconds
                        : t.ashSideBuildSeconds, t.ashFadeSeconds);
            }
            if (a.amount <= 0 && a.target <= 0) {
                it.remove();
            }
        }
    }

    private void updateAshTargets(BlockPos player, ResiduumConfig cfg) {
        // Soot whose chunk has gone comes back in full with it, so it need not be kept
        ash.values().removeIf(a -> a.kind == Kind.SPOT && !level.isLoaded(BlockPos.of(a.pos)));
        for (Ash a : ash.values()) {
            a.target = tick < a.blastUntil ? a.blast : 0;
        }
        if (cfg.torchSoot.enabled) {
            BlockPos.MutableBlockPos t = new BlockPos.MutableBlockPos();
            LongIterator torch = torches.iterator();
            while (torch.hasNext()) {
                long key = torch.nextLong();
                t.set(key);
                if (!level.isLoaded(t)) {
                    // Out of the world for now: found again with its chunk, its soot there in full
                    torch.remove();
                    newTorches.remove(key);
                    continue;
                }
                BlockState state = level.getBlockState(t);
                if (!sooty(state)) {
                    torch.remove();
                    newTorches.remove(key);
                    continue;
                }
                sootAbove(t, state, newTorches.contains(key));
            }
        }
        int reach = cfg.scan.radius + 16;
        BlockPos.MutableBlockPos f = new BlockPos.MutableBlockPos();
        LongIterator it = fires.iterator();
        while (cfg.ash.enabled && it.hasNext()) {
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
        ash.values().removeIf(a -> level.isLoaded(BlockPos.of(a.pos)) && !hasFace(BlockPos.of(a.pos), a.face));
    }

    /**
     * Soot on the underside of the block over a torch's flame, or half as much on the next block up with a block of air
     * between them. Over a torch that was already there it is there already; over one just put in it builds up.
     */
    private void sootAbove(BlockPos torch, BlockState state, boolean fresh) {
        // The flame is in the middle of a standing torch, and toward the wall on a torch on a wall
        Direction facing = state.getOptionalValue(BlockStateProperties.HORIZONTAL_FACING).orElse(null);
        double x = 0.5 - (facing == null ? 0 : WALL_FLAME * facing.getStepX());
        double z = 0.5 - (facing == null ? 0 : WALL_FLAME * facing.getStepZ());
        BlockPos above = torch.above();
        Ash a = null;
        if (sootCatcher(above)) {
            a = spot(above, x, z, 1, fresh);
        } else if (level.getBlockState(above).getCollisionShape(level, above).isEmpty() && sootCatcher(above.above())) {
            a = spot(above.above(), x, z, 0.5f, fresh);
        }
        if (a == null || a.amount >= a.scale) {
            newTorches.remove(torch.asLong()); // built up: an old torch from now on
        }
    }

    /** A block whose underside soot can settle on: one you would bump into, with a bottom face. */
    private boolean sootCatcher(BlockPos pos) {
        return hasFace(pos, Direction.DOWN) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private Ash spot(BlockPos pos, double x, double z, float scale, boolean fresh) {
        Key key = new Key(pos.asLong(), Direction.DOWN);
        Ash a = ash.get(key);
        if (a == null) {
            a = new Ash(key.pos(), Direction.DOWN);
            a.kind = Kind.SPOT;
            ash.put(key, a);
        } else if (a.kind != Kind.SPOT) {
            return null; // a fire's ash on the same face is darker than any soot
        }
        if (a.target < scale) {
            a.target = scale;
            a.scale = scale;
            a.spotX = x;
            a.spotZ = z;
            a.renderTime = -1;
        }
        if (!fresh) {
            a.amount = Math.max(a.amount, a.target);
        }
        return a;
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

    /**
     * The server says something exploded. A fiery blast (not the gust of a wind charge) leaves soot on what it blew
     * open, a few ticks from now.
     */
    public void explosion(Vec3 at, float radius, ParticleOptions particle) {
        ResiduumConfig cfg = ResiduumConfig.get();
        if (level != null && cfg.enabled && cfg.explosions.enabled
                && (particle.getType() == ParticleTypes.EXPLOSION || particle.getType() == ParticleTypes.EXPLOSION_EMITTER)) {
            blasts.add(new Blast(at, radius, tick + BLAST_DELAY));
        }
    }

    /**
     * Soot on every face round blast b that looks toward it with open air in front and nothing in between, darker the
     * nearer; each face counts as a little nearer or further than it is, so the edge of the black is ragged, not round.
     */
    private void blacken(Blast b, long hold) {
        double reach = b.radius() * BLAST_REACH + 1;
        BlockPos centre = BlockPos.containing(b.at());
        int r = (int) Math.ceil(reach);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    pos.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    for (Direction face : Direction.values()) {
                        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
                        Vec3 on = Vec3.atCenterOf(pos).add(normal.scale(0.5));
                        Vec3 toBlast = b.at().subtract(on);
                        int bits = (int) (pos.asLong() * 0x9E3779B9L) + face.ordinal() * 0x632BE5AB;
                        double d = toBlast.length() + ((bits >>> 8 & 0xFF) / 255.0 - 0.5) * 0.8;
                        if (d >= reach || toBlast.dot(normal) <= 0) {
                            continue;
                        }
                        BlockPos open = pos.relative(face);
                        BlockState in = level.getBlockState(open);
                        if (!in.getCollisionShape(level, open).isEmpty() || !in.getFluidState().isEmpty() || !hasFace(pos, face)) {
                            continue;
                        }
                        if (level.clip(new ClipContext(b.at(), on.add(normal.scale(0.1)), ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.ANY, CollisionContext.empty())).getType() != HitResult.Type.MISS) {
                            continue; // round a corner or behind a wall from the blast
                        }
                        Key key = new Key(pos.asLong(), face);
                        Ash a = ash.get(key);
                        if (a == null) {
                            if (ash.size() >= MAX_ASH) {
                                continue;
                            }
                            a = new Ash(key.pos(), face);
                            ash.put(key, a);
                        }
                        float strength = (float) Math.clamp((reach - d) / (reach * (1 - BLAST_CORE)), 0, 1);
                        a.kind = Kind.FULL;
                        a.blast = Math.max(a.blastUntil > tick ? a.blast : 0, strength);
                        a.blastUntil = tick + hold;
                        a.amount = Math.max(a.amount, strength);
                        a.target = Math.max(a.target, strength);
                    }
                }
            }
        }
        // The new faces and those round them join up afresh
        for (Ash a : ash.values()) {
            if (BlockPos.of(a.pos).distManhattan(centre) <= 3 * r + 2) {
                a.renderTime = -1;
            }
        }
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

    /** Sparks crackling round the lightning rods struck lately, fewer and fewer as each one dies down. */
    private void sparkRods() {
        long now = level.getGameTime();
        rods.removeIf(r -> now - r.struck() > r.ticks() || !(level.getBlockState(r.pos()).getBlock() instanceof LightningRodBlock));
        for (Rod r : rods) {
            float left = 1 - (float) (now - r.struck()) / r.ticks();
            if (random.nextFloat() >= RODS_SPARKS * left * left) {
                continue;
            }
            // Somewhere along the rod, just off its surface, flying off a little
            double along = random.nextDouble() - 0.5, round = random.nextDouble() * Math.PI * 2;
            double ox = Math.cos(round) * 0.12, oy = Math.sin(round) * 0.12;
            double x = r.pos().getX() + 0.5 + (r.axis() == Direction.Axis.X ? along : ox);
            double y = r.pos().getY() + 0.5 + (r.axis() == Direction.Axis.Y ? along : r.axis() == Direction.Axis.X ? ox : oy);
            double z = r.pos().getZ() + 0.5 + (r.axis() == Direction.Axis.Z ? along : oy);
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, (random.nextDouble() - 0.5) * 0.1,
                    (random.nextDouble() - 0.5) * 0.1, (random.nextDouble() - 0.5) * 0.1);
        }
    }

    /** The lightning rods struck lately that still crackle. */
    public List<Rod> rods() {
        return rods;
    }

    private void strike(Vec3 at) {
        BlockPos pos = BlockPos.containing(at.x, at.y - 0.05, at.z);
        // A lightning rod takes the bolt and goes on sparking for a while after
        BlockState rod = level.getBlockState(pos);
        if (rod.getBlock() instanceof LightningRodBlock) {
            rods.add(new Rod(pos, rod.getValue(LightningRodBlock.FACING).getAxis(), level.getGameTime(),
                    RODS_MIN + random.nextInt(RODS_MAX - RODS_MIN + 1)));
        }
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
        ResiduumConfig cfg = ResiduumConfig.get();
        Vec3 velocity = snowball.getDeltaMovement();
        if (level == null || !cfg.enabled || !cfg.snowballs.enabled || velocity.lengthSqr() < 1.0e-4) {
            return;
        }
        Vec3 from = snowball.position().subtract(velocity);
        Vec3 to = snowball.position().add(velocity.scale(1.5)).add(velocity.normalize().scale(0.6));
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        Vec3 at = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : to;
        Entity owner = snowball instanceof Projectile projectile ? projectile.getOwner() : null;
        Entity struck = null;
        Vec3 entry = null;
        double nearest = Double.MAX_VALUE;
        for (Entity e : level.getEntities(snowball, new AABB(from, at).inflate(0.5),
                e -> e.isPickable() && e != owner && !Compat.isSnowball(e))) {
            Vec3 in = e.getBoundingBox().inflate(0.3).clip(from, at).orElse(null);
            if (in != null && from.distanceToSqr(in) < nearest) {
                nearest = from.distanceToSqr(in);
                struck = e;
                entry = in;
            }
        }
        if (struck != null) {
            shieldHit(struck, velocity, entry);
            return; // it hit someone, not the wall behind them
        }
        if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) {
            return;
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

    /**
     * An arrow or trident stuck in a block (the server says so, with the sound of it there): glass cracks round where
     * it went in, anything else is left a hole there. The way it flew picks the face, from the nearest arrow's flight;
     * with none to go by, the nearest face round the spot.
     */
    public void projectileHit(double x, double y, double z) {
        ResiduumConfig cfg = ResiduumConfig.get();
        if (level == null || !cfg.enabled || !cfg.cracks.enabled && !cfg.arrowHoles.enabled) {
            return;
        }
        Vec3 at = new Vec3(x, y, z);
        Entity arrow = null;
        for (Entity e : level.getEntities((Entity) null, new AABB(at, at).inflate(1.5), Compat::isArrow)) {
            if (arrow == null || e.distanceToSqr(at) < arrow.distanceToSqr(at)) {
                arrow = e;
            }
        }
        Vec3 way = arrow == null ? Vec3.ZERO : arrow.getDeltaMovement();
        if (arrow != null && way.lengthSqr() < 1.0e-4) {
            way = arrow.position().subtract(arrow.xo, arrow.yo, arrow.zo);
        }
        BlockHitResult hit = way.lengthSqr() < 1.0e-4 ? null : blockHit(at, way.normalize());
        for (Direction d : hit == null ? Direction.values() : new Direction[0]) {
            BlockHitResult h = blockHit(at, new Vec3(d.getStepX(), d.getStepY(), d.getStepZ()).scale(0.3));
            if (h != null && (hit == null || h.getLocation().distanceToSqr(at) < hit.getLocation().distanceToSqr(at))) {
                hit = h;
            }
        }
        if (hit == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        Vec3 point = hit.getLocation();
        boolean glass = GlassTracker.isGlass(level.getBlockState(pos));
        if (glass ? !cfg.cracks.enabled : !cfg.arrowHoles.enabled) {
            return;
        }
        List<Splat> marks = glass ? cracks : holes;
        if (marks.size() >= MAX_CRACKS) {
            marks.remove(0);
        }
        marks.add(new Splat(pos.asLong(), hit.getDirection(), point.x - pos.getX(), point.y - pos.getY(),
                point.z - pos.getZ(), (int) (tick * 0x9E3779B9L ^ pos.asLong()), tick, new LongOpenHashSet()));
    }

    /** The block met along way from just before at to just past it, or null. */
    private BlockHitResult blockHit(Vec3 at, Vec3 way) {
        Vec3 from = at.subtract(way.normalize().scale(1.2)), to = at.add(way.normalize().scale(0.8));
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK && !hit.isInside() ? hit : null;
    }

    /** Players, and mobs if they leave prints too, walking round the player on snow or sand. */
    private void footprints(Player player, ResiduumConfig cfg) {
        IntOpenHashSet here = new IntOpenHashSet();
        AABB round = player.getBoundingBox().inflate(PRINT_REACH, 8, PRINT_REACH);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, round,
                e -> !e.isSpectator() && (cfg.footprints.mobs || e instanceof Player))) {
            here.add(e.getId());
            step(e);
        }
        walkers.int2ObjectEntrySet().removeIf(w -> !here.contains(w.getIntKey()));
    }

    /** A walker went on: once it has gone a stride, the next foot comes down, and on snow or sand leaves a print. */
    private void step(LivingEntity e) {
        double[] last = walkers.get(e.getId());
        double dx = last == null ? 0 : e.getX() - last[0], dz = last == null ? 0 : e.getZ() - last[1];
        double moved = Math.sqrt(dx * dx + dz * dz);
        if (last == null || moved > JUMP) {
            walkers.put(e.getId(), new double[] {e.getX(), e.getZ(), 1});
            return;
        }
        if (moved < STRIDE) {
            return;
        }
        last[0] = e.getX();
        last[1] = e.getZ();
        last[2] = -last[2];
        if (!e.onGround()) {
            return;
        }
        BlockPos feet = BlockPos.containing(e.getX(), e.getY() + 0.01, e.getZ());
        BlockPos ground = feet;
        BlockState state = level.getBlockState(feet);
        double surface;
        int kind;
        if (state.getBlock() instanceof SnowLayerBlock) {
            surface = state.getShape(level, feet).max(Direction.Axis.Y);
            kind = 0;
        } else {
            ground = feet.below();
            state = level.getBlockState(ground);
            if (!state.isCollisionShapeFullBlock(level, ground)) {
                return;
            }
            surface = 1;
            kind = state.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK) ? 0 : state.is(BlockTags.SAND) ? 1 : -1;
        }
        if (kind < 0 || Math.abs(ground.getY() + surface - e.getY()) > 0.2) {
            return;
        }
        // The foot comes down to its side of the way it goes: right of it is east when it heads north. Blocky as the
        // foot, the print lies on the block's own pixels, turned the nearest quarter turn to the way it went
        double side = last[2] * FOOT_SIDE * Math.clamp(e.getBbWidth() / 0.6, 0.5, 1.5);
        double fx = Math.round((e.getX() - dz / moved * side) * 16) / 16.0;
        double fz = Math.round((e.getZ() + dx / moved * side) * 16) / 16.0;
        int heading = Math.floorMod((int) Math.round(Math.atan2(dx, -dz) / (Math.PI / 2)), 4);
        boolean boots = e.getBbHeight() > e.getBbWidth() * 2.2;
        int texture = kind * 3 + (boots ? (last[2] > 0 ? 1 : 0) : 2);
        int quarters = (4 - heading) % 4;
        if (prints.size() >= MAX_PRINTS) {
            prints.remove(0);
        }
        prints.add(new Splat(ground.asLong(), Direction.UP, fx - ground.getX(), surface, fz - ground.getZ(),
                texture | quarters << 8, tick, new LongOpenHashSet()));
    }

    /**
     * A snowball hit something living: if it was holding up a shield and the snowball came at its front, the shield
     * gets a splat, a pixel-aligned spot near where on the plate the snowball landed.
     */
    private void shieldHit(Entity entity, Vec3 velocity, Vec3 at) {
        if (!(entity instanceof LivingEntity living) || !living.isBlocking()) {
            return;
        }
        Vec3 facing = Vec3.directionFromRotation(0, living.getYHeadRot());
        if (facing.x * velocity.x + facing.z * velocity.z >= 0) {
            return; // from behind: the shield was not in the way
        }
        // Seen from the front, the entity's right hand is on the left
        double right = (at.x - entity.getX()) * -facing.z + (at.z - entity.getZ()) * facing.x;
        double up = at.y - entity.getY() - 0.45 * entity.getBbHeight();
        int seed = (int) (tick * 0x9E3779B9L ^ entity.getId());
                int x = Math.clamp(Math.round(-right * 12) + Math.floorMod(seed, 3) - 1, -SHIELD_REACH_X, SHIELD_REACH_X);
        int y = Math.clamp(Math.round(up * 14) + Math.floorMod(seed >>> 8, 3) - 1, -SHIELD_REACH_DOWN, SHIELD_REACH_UP);
        addShieldMark(new ShieldMark(entity.getId(), x, y, seed, tick, false));
    }

    /** Keeps MAX_SHIELD_MARKS of each kind on a shield at most, the oldest going first. */
    private void addShieldMark(ShieldMark mark) {
        long same = shieldMarks.stream().filter(m -> m.entity() == mark.entity() && m.scratch() == mark.scratch()).count();
        if (same >= MAX_SHIELD_MARKS) {
            shieldMarks.stream().filter(m -> m.entity() == mark.entity() && m.scratch() == mark.scratch()).findFirst()
                    .ifPresent(shieldMarks::remove);
        }
        shieldMarks.add(mark);
    }

    /**
     * A shield was knocked aside by an axe (the server tells clients only with its sound, at its holder): the holder
     * standing right there gets a gash across the front of their shield, somewhere on the plate, at a slant of its own.
     */
    public void shieldDisabled(double x, double y, double z) {
        ResiduumConfig cfg = ResiduumConfig.get();
        if (level == null || !cfg.enabled || !cfg.shieldScratches.enabled) {
            return;
        }
        AABB around = new AABB(x, y, z, x, y, z).inflate(SCRATCH_REACH);
        LivingEntity holder = null;
        for (Entity e : level.getEntities((Entity) null, around, e -> e instanceof LivingEntity)) {
            if (holder == null || e.distanceToSqr(x, y, z) < holder.distanceToSqr(x, y, z)) {
                holder = (LivingEntity) e;
            }
        }
        if (holder == null) {
            return;
        }
        int seed = (int) (tick * 0x9E3779B9L ^ holder.getId() * 0x632BE5AB);
        int sx = Math.floorMod(seed, 2 * SHIELD_REACH_X + 1) - SHIELD_REACH_X;
        int sy = Math.floorMod(seed >>> 8, SHIELD_REACH_UP + SHIELD_REACH_DOWN + 1) - SHIELD_REACH_DOWN;
        addShieldMark(new ShieldMark(holder.getId(), sx, sy, seed, tick, true));
    }

}
