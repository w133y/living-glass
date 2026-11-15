package com.w1z4r_d.residuum.render;

import com.w1z4r_d.residuum.ResiduumConfig;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Steam off water that lava or magma heats from close by: under the bed of a pond, behind the bank of a stream, a
 * block or two away (water that touches lava turns it to stone at once). Wisps rise off the surface of such water
 * round the player, more of them the nearer the heat. Found by sweeping the blocks round the player, a slice a tick.
 */
public final class Steam {
    /** How far round the player, and how many blocks of that a tick are looked at. */
    private static final int REACH = 12, PER_TICK = 1024;
    /** How far heat reaches the water from, in blocks either way. */
    private static final int HEAT = 2;
    /** A spot the sweep has not found again for this long has cooled, in ticks. */
    private static final int STALE_TICKS = 2 * (2 * REACH + 1) * (2 * REACH + 1) * (2 * REACH + 1) / PER_TICK + 20;
    /** Wisps a tick from the hottest spot; the fewer the further off the heat. */
    private static final float WISPS = 0.08f;
    private static final int MAX_SPOTS = 128;
    private static final Random RANDOM = new Random();

    /** Steaming water surfaces: position, then (the tick it was last found) << 3 | how near the heat (1 to 3). */
    private static final Long2LongMap SPOTS = new Long2LongOpenHashMap();
    private static ClientLevel level;
    private static long tick;
    private static int sweep;

    private Steam() {
    }

    public static void tick(Minecraft mc) {
        if (mc.level != level) {
            SPOTS.clear();
            level = mc.level;
        }
        Player player = mc.player;
        ResiduumConfig cfg = ResiduumConfig.get();
        if (level == null || player == null || mc.isPaused()) {
            return;
        }
        if (!cfg.enabled || !cfg.steam.enabled) {
            SPOTS.clear();
            return;
        }
        tick++;
        int span = 2 * REACH + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int k = 0; k < PER_TICK; k++) {
            sweep = (sweep + 1) % (span * span * span);
            pos.set(player.getBlockX() + sweep % span - REACH, player.getBlockY() + sweep / (span * span) - REACH,
                    player.getBlockZ() + sweep / span % span - REACH);
            int near = heat(pos);
            if (near > 0 && (SPOTS.size() < MAX_SPOTS || SPOTS.containsKey(pos.asLong()))) {
                SPOTS.put(pos.asLong(), tick << 3 | near);
            }
        }
        SPOTS.long2LongEntrySet().removeIf(e -> tick - (e.getLongValue() >>> 3) > STALE_TICKS);
        for (Long2LongMap.Entry e : SPOTS.long2LongEntrySet()) {
            if (RANDOM.nextFloat() < WISPS * (e.getLongValue() & 7) / 3) {
                BlockPos at = BlockPos.of(e.getLongKey());
                level.addParticle(ParticleTypes.CLOUD, at.getX() + RANDOM.nextDouble(), at.getY() + 0.95,
                        at.getZ() + RANDOM.nextDouble(), (RANDOM.nextDouble() - 0.5) * 0.01, 0.03 + RANDOM.nextDouble() * 0.03,
                        (RANDOM.nextDouble() - 0.5) * 0.01);
            }
        }
    }

    /**
     * For the top of a body of water at pos (open air over it), how near heat is: 3 for lava or magma a block away, 2
     * for two blocks, 1 for diagonally that far; 0 for none or for anything but a water surface.
     */
    private static int heat(BlockPos pos) {
        if (!level.getFluidState(pos).is(FluidTags.WATER) || !level.getBlockState(pos.above()).isAir()) {
            return 0;
        }
        int best = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -HEAT; dx <= HEAT; dx++) {
            for (int dy = -HEAT - 1; dy <= 0; dy++) {
                for (int dz = -HEAT; dz <= HEAT; dz++) {
                    p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    BlockState state = level.getBlockState(p);
                    if (state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.MAGMA_BLOCK)) {
                        int d = Math.max(Math.abs(dx), Math.max(Math.abs(dz), Math.abs(dy)));
                        best = Math.max(best, d <= 1 ? 3 : Math.abs(dx) + Math.abs(dz) + Math.abs(dy) <= 3 ? 2 : 1);
                    }
                }
            }
        }
        return best;
    }
}
