package com.w1z4r_d.residuum.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.compat.Compat;
import com.w1z4r_d.residuum.glass.GlassTracker;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tumbleweeds blowing across deserts and badlands now and then, never nearer the player than MIN_DISTANCE. Each works
 * out its whole way when it turns up, LIFE seconds of it, as a ball the wind pushes over the ground: it hops as it rolls,
 * falls off edges and bounces where it lands, and knocks back off anything standing in its way, never rolling up onto
 * it. Once it is held still (against a wall, a cactus) or reaches water it stays there and is gone STUCK seconds later;
 * otherwise it fades out over the last FADE_OUT seconds of its way. It fades in over FADE_IN. A hit breaks it up (see
 * hit). Nothing in the world but this client knows of it.
 */
public final class Tumbleweeds {
    /** The steps its way is worked out in, a game tick each. */
    private static final double STEP_SECONDS = 0.05;
    private static final double LIFE = 15, FADE_IN = 2.5, FADE_OUT = 5, STUCK = 5;
    private static final double MIN_DISTANCE = 16, MAX_DISTANCE = 48;
    private static final int MAX_ALIVE = 2;
    /** Ticks between new tumbleweeds on average, at the default frequency. */
    private static final double MEAN_TICKS = 20 * 20;
    private static final double SIZE_MIN = 0.6, SIZE_MAX = 1.3;
    /** How fast it rolls at night, in blocks a second; by day the wind is weaker and it rolls DAY_SPEED as fast. */
    private static final double SPEED_MIN = 2.5, SPEED_MAX = 4, DAY_SPEED = 0.67;
    /** How far it rolls between hops, give or take a third, and how high a hop goes for its size. */
    private static final double HOP_LENGTH = 1.3, HOP_HEIGHT = 0.5;
    /** How high a hop goes at most, kicked hardest: under a slab, so it never hops up onto anything. */
    private static final double MAX_HOP = 0.4, HOP_KICK = 1.2;
    /**
     * Blocks a second squared it falls at; how quickly the wind brings it up to its speed, a share a second, rolling on
     * the ground and in the air, where nothing holds it back but the wind has less to push on.
     */
    private static final double GRAVITY = 20, WIND_GRIP = 2, AIR_GRIP = 0.4;
    /** How much of its speed it keeps bouncing back off a wall, and the little hop the knock gives it. */
    private static final double WALL_BOUNCE = 0.55, WALL_HOP = 3;
    /** Running into a wall slower than this, in blocks a second, it only stops: no knock, no hop. */
    private static final double KNOCK = 1;
    /** How much of its speed it keeps bouncing up off the ground, when it lands faster than LAND_BOUNCE. */
    private static final double GROUND_BOUNCE = 0.35, LAND_BOUNCE = 3;
    /** Held to within STILL blocks over a second, it has stopped for good. */
    private static final double STILL = 0.2;
    private static final int STILL_STEPS = 20;
    /** How far it may fall at most; a deeper drop and it is gone, as if into the void. */
    private static final int MAX_FALL = 40;
    /** A hit reaches it a little outside its twigs, as they stick out. */
    private static final double HIT_MARGIN = 1.15;
    private static final int BREAK_BITS = 14;
    /**
     * Puffs of the ground's dust it kicks up, of strength 1 to 3: kicking off for a hop, knocking into a wall, landing
     * from higher than a hop; landing faster than DUSTY_LANDING blocks a second. DUST_BITS bits of dust per strength.
     */
    private static final int KICK = 1, KNOCKED = 2, LANDED = 3, DUST_BITS = 3;
    private static final double DUSTY_LANDING = 2;
    /** Its shadow on the ground, as a mob's: SHADOW dark right under it, fading out by SHADOW_REACH blocks above it. */
    private static final float SHADOW = 0.45f;
    private static final double SHADOW_REACH = 2;

    /**
     * One tumbleweed: its way as x, y, z of the bottom of its ball and how far it has rolled forward, every STEP_SECONDS,
     * how many of those steps it moves, when it is gone (seconds of its life), when it turned up (game ticks), its size,
     * the direction the wind blows it in, whether it is a ball of pale dry grass rather than of dead bush twigs, and the
     * puffs of dust it kicks up on its way, each the step it is at times four plus its strength.
     */
    private record Weed(double[] path, int rolling, double end, long born, float size, float dx, float dz, int seed,
                        boolean dry, int[] puffs) {
    }

    private static final List<Weed> weeds = new ArrayList<>();
    private static final Random random = new Random();
    /** Quads of the two kinds: dead bush twigs, and pale dry grass. */
    private static final FloatArrayList twigQuads = new FloatArrayList(), grassQuads = new FloatArrayList();
    private static final FloatArrayList shadowQuads = new FloatArrayList();
    private static FloatArrayList quads = twigQuads;
    private static Level level;

    private Tumbleweeds() {
    }

    public static void tick(Minecraft mc) {
        if (mc.level != level) {
            weeds.clear();
            level = mc.level;
        }
        ResiduumConfig cfg = ResiduumConfig.get();
        if (level == null || mc.player == null || !cfg.enabled || !cfg.tumbleweeds.enabled) {
            weeds.clear();
            return;
        }
        long now = level.getGameTime();
        weeds.removeIf(w -> (now - w.born) / 20.0 > w.end);
        // Its way is worked out a tick a step, so the puffs of this tick are those of the step it is at
        for (Weed w : weeds) {
            for (int puff : w.puffs) {
                if (puff >>> 2 == now - w.born) {
                    dust(w, puff >>> 2, puff & 3);
                }
            }
        }
        if (weeds.size() < MAX_ALIVE && random.nextDouble() < cfg.tumbleweeds.frequency / MEAN_TICKS) {
            Weed w = spawn(mc.player.position(), now, cfg);
            if (w != null) {
                weeds.add(w);
            }
        }
    }

    /** A tumbleweed somewhere round the player, out on open desert ground; null when the spot picked is not that. */
    private static Weed spawn(Vec3 player, long now, ResiduumConfig cfg) {
        double reach = Math.min(MAX_DISTANCE, GlassTracker.viewRadius(cfg) - 4);
        if (reach < MIN_DISTANCE) {
            return null;
        }
        double angle = random.nextDouble() * Math.PI * 2, distance = MIN_DISTANCE + random.nextDouble() * (reach - MIN_DISTANCE);
        int x = Mth.floor(player.x + Math.cos(angle) * distance), z = Mth.floor(player.z + Math.sin(angle) * distance);
        BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        if (!level.isLoaded(at) || !level.canSeeSky(at) || !GlassTracker.desert(level, at) || !level.getFluidState(at).isEmpty()
                || !level.getFluidState(at.below()).isEmpty() || top(at.below()) <= 0) {
            return null;
        }
        // Downwind: the wind turns slowly over the day, each tumbleweed a little to either side of it
        double wind = Math.sin(now / 9000.0) * Math.PI * 2 + (random.nextDouble() - 0.5) * 0.8;
        float dx = (float) Math.cos(wind), dz = (float) Math.sin(wind);
        double speed = (SPEED_MIN + random.nextDouble() * (SPEED_MAX - SPEED_MIN)) * (level.isDarkOutside() ? 1 : DAY_SPEED);
        float size = (float) (SIZE_MIN + random.nextDouble() * (SIZE_MAX - SIZE_MIN));
        int seed = random.nextInt();
        int steps = (int) Math.round(LIFE / STEP_SECONDS);
        double[] path = new double[(steps + 1) * 4];
        IntArrayList puffs = new IntArrayList();
        int rolling = roll(path, puffs, x + 0.5, at.getY() - 1 + top(at.below()), z + 0.5, dx, dz, speed, size, seed);
        double end = rolling < steps ? rolling * STEP_SECONDS + STUCK : LIFE;
        return new Weed(path, rolling, end, now, size, dx, dz, seed, random.nextBoolean(), puffs.toIntArray());
    }

    /**
     * Works out a tumbleweed's way into path from the bottom of its ball at x, y, z, the wind blowing along dx, dz at
     * speed, and the puffs of dust it kicks up on it into puffs (see Weed); returns how many steps of it it moves before
     * it stops for good.
     */
    private static int roll(double[] path, IntArrayList puffs, double x, double y, double z, double dx, double dz,
                            double speed, double size, int seed) {
        Random rnd = new Random(seed);
        int steps = path.length / 4 - 1;
        double hop = Math.min(size * HOP_HEIGHT, MAX_HOP / (HOP_KICK * HOP_KICK));
        double r = size / 2, hopSpeed = Math.sqrt(2 * GRAVITY * hop);
        double vx = dx * speed * 0.5, vy = 0, vz = dz * speed * 0.5, rolled = 0, travelled = 0;
        double nextHop = HOP_LENGTH * (0.67 + rnd.nextDouble() * 0.67);
        double stillX = x, stillZ = z;
        boolean grounded = true;
        put(path, 0, x, y, z, 0);
        for (int i = 1; i <= steps; i++) {
            // The wind pushes it toward its speed, swaying a little either way so it does not roll dead straight
            double sway = Math.sin(i * STEP_SECONDS * 3.1 + seed) * 0.2;
            double wx = (dx - dz * sway) * speed, wz = (dz + dx * sway) * speed;
            double grip = Math.min(1, (grounded ? WIND_GRIP : AIR_GRIP) * STEP_SECONDS);
            vx += (wx - vx) * grip;
            vz += (wz - vz) * grip;
            vy -= GRAVITY * STEP_SECONDS;
            // Along each way on its own, knocking back off whatever stands up in it
            double nx = x + vx * STEP_SECONDS;
            if (wall(nx + Math.signum(vx) * r, y, z, size)) {
                if (grounded && Math.abs(vx) > KNOCK) {
                    puffs.add(i << 2 | KNOCKED);
                }
                vy = grounded && Math.abs(vx) > KNOCK ? Math.max(vy, WALL_HOP) : vy;
                vx = -vx * WALL_BOUNCE;
                nx = x;
            }
            x = nx;
            double nz = z + vz * STEP_SECONDS;
            if (wall(x, y, nz + Math.signum(vz) * r, size)) {
                if (grounded && Math.abs(vz) > KNOCK) {
                    puffs.add(i << 2 | KNOCKED);
                }
                vy = grounded && Math.abs(vz) > KNOCK ? Math.max(vy, WALL_HOP) : vy;
                vz = -vz * WALL_BOUNCE;
                nz = z;
            }
            z = nz;
            double ground = ground(x, y, z);
            if (Double.isNaN(ground)) {
                return i - 1; // over a drop too deep, or at the edge of the world this client has: it stops there
            }
            y += vy * STEP_SECONDS;
            boolean flying = !grounded;
            grounded = y <= ground;
            if (grounded && flying && -vy > DUSTY_LANDING) {
                puffs.add(i << 2 | LANDED);
            }
            if (grounded) {
                y = ground;
                vy = vy < -LAND_BOUNCE ? -vy * GROUND_BOUNCE : 0;
                grounded = vy == 0;
            }
            double moved = Math.hypot(vx, vz) * STEP_SECONDS;
            rolled += (vx * dx + vz * dz) * STEP_SECONDS; // backward too, after knocking back
            travelled += moved;
            if (grounded && travelled >= nextHop) {
                puffs.add(i << 2 | KICK);
                vy = hopSpeed * (0.7 + rnd.nextDouble() * (HOP_KICK - 0.7));
                travelled = 0;
                nextHop = HOP_LENGTH * (0.67 + rnd.nextDouble() * 0.67);
            }
            put(path, i, x, y, z, rolled);
            if (!level.getFluidState(BlockPos.containing(x, y + r, z)).isEmpty()) {
                return i; // in water: it soaks up and stays
            }
            if (i % STILL_STEPS == 0) {
                if (grounded && Math.hypot(x - stillX, z - stillZ) < STILL) {
                    return i;
                }
                stillX = x;
                stillZ = z;
            }
        }
        return steps;
    }

    private static void put(double[] path, int i, double x, double y, double z, double rolled) {
        path[i * 4] = x;
        path[i * 4 + 1] = y;
        path[i * 4 + 2] = z;
        path[i * 4 + 3] = rolled;
    }

    /**
     * Whether something stands up at x, z where a ball of size resting at height y would run into it. A chunk not loaded
     * stops it too: its blocks are not known, and would read as air.
     */
    private static boolean wall(double x, double y, double z, double size) {
        if (!level.isLoaded(BlockPos.containing(x, y, z))) {
            return true;
        }
        for (double h : new double[] {y + 0.05, y + size / 2, y + size - 0.05}) {
            BlockPos p = BlockPos.containing(x, h, z);
            VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
            if (!shape.isEmpty() && p.getY() + shape.max(Direction.Axis.Y) > y + 0.05
                    && p.getY() + shape.min(Direction.Axis.Y) < y + size) {
                return true;
            }
        }
        return false;
    }

    /** The height of the ground under x, z at height y or below; NaN when there is none within MAX_FALL. */
    private static double ground(double x, double y, double z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(y + 0.05), Mth.floor(z));
        if (!level.isLoaded(p)) {
            return Double.NaN;
        }
        for (int i = 0; i < MAX_FALL; i++, p.move(Direction.DOWN)) {
            double top = top(p);
            if (top > 0 && p.getY() + top <= y + 0.05) {
                return p.getY() + top;
            }
        }
        return Double.NaN;
    }

    /** How high the block at p is solid, 0 when you walk through it. */
    private static double top(BlockPos p) {
        VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
        return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
    }

    /** A puff of the dust of the ground under w where it is at step, as it lands or kicks off; bigger the stronger. */
    private static void dust(Weed w, int step, int strength) {
        double t = step * STEP_SECONDS;
        // None while it is too faint to see yet, or nearly gone
        if (Math.min(t / FADE_IN, (w.end - t) / FADE_OUT) < 0.3) {
            return;
        }
        double x = w.path[step * 4], y = w.path[step * 4 + 1], z = w.path[step * 4 + 2], r = w.size / 2;
        BlockState ground = level.getBlockState(BlockPos.containing(x, y - 0.05, z));
        if (ground.isAir()) {
            return;
        }
        BlockParticleOption bit = new BlockParticleOption(ParticleTypes.BLOCK, ground);
        for (int k = 0; k < strength * DUST_BITS; k++) {
            double a = random.nextDouble() * Math.PI * 2;
            level.addParticle(bit, x + Math.cos(a) * r * 0.7, y + 0.05, z + Math.sin(a) * r * 0.7, Math.cos(a) * 0.06,
                    0.08 + random.nextDouble() * 0.08, Math.sin(a) * 0.06);
        }
    }

    /**
     * Its shadow on the ground under a ball of radius r with its bottom at x, bottom, z, as a mob's: laid on the top of
     * every block under it, fainter the further below it is, none more than SHADOW_REACH blocks down.
     */
    private static void shadow(double x, double bottom, double z, double r, double alpha, Vec3 cam) {
        for (int bx = Mth.floor(x - r); bx <= Mth.floor(x + r); bx++) {
            for (int bz = Mth.floor(z - r); bz <= Mth.floor(z + r); bz++) {
                double ground = ground(bx + 0.5, bottom, bz + 0.5);
                if (Double.isNaN(ground) || bottom - ground > SHADOW_REACH) {
                    continue;
                }
                int color = OverlayRenderer.argb((float) (SHADOW * alpha * (1 - (bottom - ground) / SHADOW_REACH)), 0xFFFFFF, 1);
                // The part of the shadow over this block, wound to face up
                double x0 = Math.max(bx, x - r), x1 = Math.min(bx + 1, x + r), z0 = Math.max(bz, z - r), z1 = Math.min(bz + 1, z + r);
                float y = (float) (ground - cam.y);
                quads = shadowQuads;
                for (int c = 0; c < 4; c++) {
                    double cx = c == 1 || c == 2 ? x1 : x0, cz = c < 2 ? z1 : z0;
                    corner((float) (cx - cam.x), y, (float) (cz - cam.z), (float) ((cx - x + r) / (2 * r)),
                            (float) ((cz - z + r) / (2 * r)), color);
                }
                shadowQuads.add(0f);
                shadowQuads.add(1f);
                shadowQuads.add(0f);
                shadowQuads.add(Float.intBitsToFloat(OverlayRenderer.packedLight(level, BlockPos.containing(bx + 0.5, ground + 0.05, bz + 0.5))));
            }
        }
    }

    /** Game time now, between ticks too. */
    private static double now(Level world) {
        return world.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    /**
     * Where w is at game time now, into out: the x, y, z of its middle, how far it has rolled and how much of it shows.
     */
    private static void place(Weed w, double now, double[] out) {
        double t = (now - w.born) / 20.0;
        // Where along its way it is, between two of its points
        double along = Math.min(t / STEP_SECONDS, w.rolling);
        int i = Math.min((int) along, Math.max(0, w.rolling - 1));
        double f = w.rolling == 0 ? 0 : along - i;
        double[] p = w.path;
        int j = w.rolling == 0 ? 0 : i + 1;
        out[0] = Mth.lerp(f, p[i * 4], p[j * 4]);
        out[1] = Mth.lerp(f, p[i * 4 + 1], p[j * 4 + 1]) + w.size / 2;
        out[2] = Mth.lerp(f, p[i * 4 + 2], p[j * 4 + 2]);
        out[3] = Mth.lerp(f, p[i * 4 + 3], p[j * 4 + 3]);
        out[4] = Math.min(Math.min(1, t / FADE_IN), Math.min(1, (w.end - t) / FADE_OUT));
    }

    /**
     * The player swung at whatever is under the crosshair: a tumbleweed in reach and nearer than that breaks up in a puff
     * of twigs. True when one did, so the swing goes at the air rather than on to the block or mob behind it.
     */
    public static boolean hit(Minecraft mc) {
        if (weeds.isEmpty() || mc.player == null || mc.level != level) {
            return false;
        }
        Vec3 eye = mc.player.getEyePosition(), look = mc.player.getViewVector(1.0f);
        double reach = mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS
                ? mc.hitResult.getLocation().distanceTo(eye) : mc.player.blockInteractionRange();
        double now = now(level);
        double[] at = new double[5];
        Weed nearest = null;
        double best = reach;
        for (Weed w : weeds) {
            place(w, now, at);
            if (at[4] <= 0.2) {
                continue; // too faint yet, or nearly gone, to be there to hit
            }
            // Where the line of the swing passes nearest its middle, and whether that is within its twigs
            double ox = at[0] - eye.x, oy = at[1] - eye.y, oz = at[2] - eye.z;
            double along = ox * look.x + oy * look.y + oz * look.z;
            double miss = ox * ox + oy * oy + oz * oz - along * along;
            double r = w.size / 2 * HIT_MARGIN;
            if (along > 0 && along < best && miss <= r * r) {
                best = along;
                nearest = w;
            }
        }
        if (nearest == null) {
            return false;
        }
        place(nearest, now, at);
        weeds.remove(nearest);
        BlockParticleOption twigs = new BlockParticleOption(ParticleTypes.BLOCK,
                (nearest.dry ? Blocks.SHORT_DRY_GRASS : Blocks.DEAD_BUSH).defaultBlockState());
        for (int k = 0; k < BREAK_BITS; k++) {
            double r = nearest.size / 2;
            level.addParticle(twigs, at[0] + (random.nextDouble() - 0.5) * r * 2, at[1] + (random.nextDouble() - 0.5) * r * 2,
                    at[2] + (random.nextDouble() - 0.5) * r * 2, 0, 0, 0);
        }
        level.playLocalSound(at[0], at[1], at[2], SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.8f,
                0.9f + random.nextFloat() * 0.3f, false);
        return true;
    }

    static void render(PoseStack poseStack, SubmitNodeCollector collector, Level world, Vec3 cam, ResiduumConfig cfg) {
        twigQuads.clear();
        grassQuads.clear();
        shadowQuads.clear();
        if (weeds.isEmpty() || world != level) {
            return;
        }
        double now = now(world);
        double[] at = new double[5];
        Matrix3f turn = new Matrix3f();
        Vector3f a = new Vector3f(), b = new Vector3f(), n = new Vector3f();
        for (Weed w : weeds) {
            place(w, now, at);
            double x = at[0], y = at[1], z = at[2], rolled = at[3], alpha = at[4];
            double radius = w.size / 2;
            if (alpha <= 0 || !View.sees(x, y, z, w.size)) {
                continue;
            }
            shadow(x, y - radius, z, radius * 0.9, alpha, cam);
            quads = w.dry ? grassQuads : twigQuads;
            // Rolling forward turns it about the level line across its way; it started at a turn of its own
            turn.rotation((float) (rolled / radius), w.dz, 0, -w.dx);
            turn.mul(new Matrix3f().rotationXYZ(w.seed * 0.37f, w.seed * 0.11f, w.seed * 0.53f));
            int light = OverlayRenderer.packedLight(world, BlockPos.containing(x, y, z));
            int color = OverlayRenderer.argb((float) alpha, 0xFFFFFF, 1);
            // Three squares crossed at right angles: a ball of twigs from any side
            for (int k = 0; k < 3; k++) {
                turn.getColumn((k + 1) % 3, a).mul((float) radius);
                turn.getColumn((k + 2) % 3, b).mul((float) radius);
                turn.getColumn(k, n);
                float cx = (float) (x - cam.x), cy = (float) (y - cam.y), cz = (float) (z - cam.z);
                corner(cx - a.x - b.x, cy - a.y - b.y, cz - a.z - b.z, 0, 1, color);
                corner(cx + a.x - b.x, cy + a.y - b.y, cz + a.z - b.z, 1, 1, color);
                corner(cx + a.x + b.x, cy + a.y + b.y, cz + a.z + b.z, 1, 0, color);
                corner(cx - a.x + b.x, cy - a.y + b.y, cz - a.z + b.z, 0, 0, color);
                quads.add(n.x);
                quads.add(n.y);
                quads.add(n.z);
                quads.add(Float.intBitsToFloat(light));
            }
        }
        // Lit by the world's lightmap like a mob, and solid enough to hide what is behind it
        OverlayRenderer.submitLit(collector, poseStack, "tumbleweed", twigQuads);
        OverlayRenderer.submitLit(collector, poseStack, "tumbleweed_dry", grassQuads);
        if (!shadowQuads.isEmpty()) {
            Compat.submitDecal(collector, poseStack, "minecraft", "textures/misc/shadow.png",
                    (pose, consumer) -> OverlayRenderer.emit(shadowQuads, pose, consumer));
        }
    }

    private static void corner(float x, float y, float z, float u, float v, int color) {
        quads.add(x);
        quads.add(y);
        quads.add(z);
        quads.add(u);
        quads.add(v);
        quads.add(Float.intBitsToFloat(color));
    }
}
