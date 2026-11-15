package com.w1z4r_d.residuum.render;

import com.w1z4r_d.residuum.ResiduumConfig;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grass and ferns players walk through lie down the way they went, then stand back up bit by bit. The game builds a
 * plant into its chunk's mesh once, so a bend goes in STAGES fine steps, each one a rebuild of the plant's section: the
 * plants' models (wrapped by Compat.registerTrampling) bend their quads by the stage they read here while the chunk is
 * meshed, on the game's mesher threads, hence the concurrent map.
 */
public final class Trampling {
    /**
     * A plant stepped into goes down PRESS stages a tick, so it gives way over a few ticks rather than at once; left
     * alone it lies FLAT_TICKS, then springs back up over RISE_TICKS, quickly at first and slower as it straightens.
     */
    private static final int STAGES = 12, PRESS = 4;
    private static final long FLAT_TICKS = 40, RISE_TICKS = 500;
    /** How far the top of a plant a block high leans the way it was walked, and how far it sinks, lying flat. */
    private static final float LEAN = 0.35f, SINK = 0.3f;
    private static final int ANGLES = 16;
    private static final Set<Block> PLANTS = Set.of(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.SHORT_DRY_GRASS, Blocks.TALL_DRY_GRASS, Blocks.BUSH);

    /** Bent plants by the position of their bottom block: stage (1..STAGES) | angle (0..ANGLES-1) << 4. */
    private static final Map<Long, Integer> BENT = new ConcurrentHashMap<>();
    /** When a player last stood in each bent plant, in ticks of this tracker. */
    private static final Long2LongMap LEFT = new Long2LongOpenHashMap();
    private static ClientLevel level;
    private static long tick;

    private Trampling() {
    }

    /** The plants that lie down: grass and ferns, short and tall, dry grass and bushes. */
    public static boolean bends(BlockState state) {
        return PLANTS.contains(state.getBlock());
    }

    public static void tick(Minecraft mc) {
        if (mc.level != level) {
            BENT.clear();
            LEFT.clear();
            level = mc.level;
        }
        ResiduumConfig cfg = ResiduumConfig.get();
        if (level == null) {
            return;
        }
        if (!cfg.grassOn()) {
            for (long pos : LEFT.keySet()) {
                set(pos, 0);
            }
            LEFT.clear();
            return;
        }
        tick++;
        for (Player player : level.players()) {
            if (!player.isSpectator()) {
                walk(player);
            }
        }
        // Every quarter second, so plants in one section rise together, in one rebuild
        if (tick % 5 == 0) {
            rise();
        }
    }

    /** The plants round a player's feet lie down the way the player is going, or stay as they lie under one standing. */
    private static void walk(Player player) {
        AABB box = player.getBoundingBox();
        double dx = player.getX() - player.xo, dz = player.getZ() - player.zo;
        boolean moving = dx * dx + dz * dz > 1.0e-4;
        int angle = Math.floorMod(Math.round((float) (Math.atan2(dz, dx) / (2 * Math.PI) * ANGLES)), ANGLES);
        int y = (int) Math.floor(box.minY + 1.0e-3);
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1.0e-6); x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1.0e-6); z++) {
                BlockPos pos = bottom(new BlockPos(x, y, z));
                if (pos == null) {
                    continue;
                }
                long key = pos.asLong();
                Integer was = BENT.get(key);
                // A plant lying down keeps the way it lies, so turning on it does not swing it round. One stepped on
                // is pushed aside off the player's way, to whichever side of it it stands (either way right under the
                // middle), or any way at all by a player standing still
                int hash = (int) key * 0x9E3779B9;
                double off = (pos.getX() + 0.5 - player.getX()) * -dz + (pos.getZ() + 0.5 - player.getZ()) * dx;
                int side = Math.abs(off) > 0.1 * Math.sqrt(dx * dx + dz * dz) ? (off > 0 ? 1 : -1) : (hash >>> 31 == 0 ? 1 : -1);
                int a = was != null ? was >> 4 : moving ? Math.floorMod(angle + side * ANGLES / 4, ANGLES)
                        : Math.floorMod(hash, ANGLES);
                LEFT.put(key, tick);
                set(key, Math.min(STAGES, (was == null ? 0 : was & 15) + PRESS) | a << 4);
            }
        }
    }

    /** The bottom block of the bending plant at pos, or null when there is none. */
    private static BlockPos bottom(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!bends(state)) {
            return null;
        }
        return state.getBlock() instanceof DoublePlantBlock
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    /** Plants left alone stand back up a stage at a time; plants that are gone are forgotten. */
    private static void rise() {
        Iterator<Long2LongMap.Entry> it = LEFT.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            Long2LongMap.Entry e = it.next();
            long key = e.getLongKey(), since = tick - e.getLongValue();
            Integer was = BENT.get(key);
            float risen = Math.clamp((since - FLAT_TICKS) / (float) RISE_TICKS, 0f, 1f);
            int stage = Math.min(was == null ? 0 : was & 15, Math.round(STAGES * (1 - risen) * (1 - risen)));
            if (stage == 0 || !level.isLoaded(BlockPos.of(key)) || !bends(level.getBlockState(BlockPos.of(key)))) {
                it.remove();
                set(key, 0);
            } else {
                set(key, stage | (was >> 4) << 4);
            }
        }
    }

    /** Sets a plant's bend, and has its section rebuilt if that changed it. */
    private static void set(long key, int bend) {
        Integer was = bend == 0 ? BENT.remove(key) : BENT.put(key, bend);
        if (was == null ? bend != 0 : was != bend) {
            BlockPos pos = BlockPos.of(key);
            // A tall plant's top half may be in the section above
            level.setSectionRangeDirty(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4,
                    pos.getX() >> 4, (pos.getY() + 1) >> 4, pos.getZ() >> 4);
        }
    }

    /** The bend of the plant whose block is at pos, upper half true for the top block of a tall one; 0 if it stands. */
    public static int bend(BlockPos pos, boolean upperHalf) {
        Integer bend = BENT.get(upperHalf ? pos.below().asLong() : pos.asLong());
        return bend == null ? 0 : bend;
    }

    /**
     * Moves a vertex of a plant's model, xyz within its block, by its bend: the higher up the plant it is, the further
     * it leans the way the plant was walked and the lower it sinks, a little more or less from plant to plant.
     */
    public static void bendVertex(float[] xyz, int bend, boolean upperHalf, long seed) {
        float share = (bend & 15) / (float) STAGES;
        double angle = (bend >> 4) * 2 * Math.PI / ANGLES;
        int h = (int) (seed * 0x9E3779B97F4A7C15L >>> 40);
        float give = share * (0.8f + 0.4f * (h & 0xFF) / 255f);
        // Up the plant from its root, a block for the bottom block of a tall one, two for the top of it
        float up = Math.max(0, xyz[1] + (upperHalf ? 1 : 0));
        float lean = LEAN * give * up, sink = SINK * give * up;
        xyz[0] += (float) Math.cos(angle) * lean;
        xyz[1] -= sink;
        xyz[2] += (float) Math.sin(angle) * lean;
    }
}
