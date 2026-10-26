package com.w1z4r_d.livingglass.glass;

import com.w1z4r_d.livingglass.LivingGlassClient;
import com.w1z4r_d.livingglass.LivingGlassConfig;
import com.w1z4r_d.livingglass.compat.Compat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Outside temperature and inside warmth (plan section 2.2 - 2.4). */
final class Climate {
    static final float FREEZING = 0.15f;
    private static final TagKey<Block> HEAT_SOURCES = Compat.blockTag(LivingGlassClient.MOD_ID, "heat_sources");
    private static final int WARMTH_TTL_TICKS = 60;
    private static final int WARMTH_SCANS_PER_TICK = 2;

    private record Warmth(float value, long expires) {
    }

    /** Keyed by the inner air position in 4x4x4 cells, so neighbouring windows share one scan. */
    private final Long2ObjectOpenHashMap<Warmth> warmthCache = new Long2ObjectOpenHashMap<>();
    private int scansLeft;

    void startTick() {
        scansLeft = WARMTH_SCANS_PER_TICK;
    }

    void clear() {
        warmthCache.clear();
    }

    /** Drops cached warmth for every cell whose scan could include pos. */
    void invalidate(BlockPos pos, int radius) {
        int reach = (radius >> 2) + 1;
        int cx = pos.getX() >> 2, cy = pos.getY() >> 2, cz = pos.getZ() >> 2;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    warmthCache.remove(BlockPos.asLong(cx + dx, cy + dy, cz + dz));
                }
            }
        }
    }

    static float outsideTemp(Level level, BlockPos pos, LivingGlassConfig.Climate cfg) {
        Biome biome = level.getBiome(pos).value();
        float temp = biome.getBaseTemperature();
        // Vanilla altitude drop above the snow line, without the noise term
        int snowLine = level.getSeaLevel() + 17;
        if (pos.getY() > snowLine) {
            temp -= (pos.getY() - snowLine) * 0.05f / 40.0f;
        }
        if (level.isDarkOutside()) {
            temp -= (float) cfg.nightPenalty;
        }
        if (level.isRaining() && biome.hasPrecipitation()) {
            temp -= (float) cfg.rainPenalty;
        }
        return temp;
    }

    static boolean snowingAt(Level level, BlockPos pos) {
        return level.isRaining() && level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel()) == Biome.Precipitation.SNOW;
    }

    /** Heat around an inside position, 0..1; NaN when this tick's scan budget is spent and nothing is cached. */
    float warmth(Level level, BlockPos inside, long tick, int radius) {
        long key = BlockPos.asLong(inside.getX() >> 2, inside.getY() >> 2, inside.getZ() >> 2);
        Warmth cached = warmthCache.get(key);
        if (cached != null && cached.expires > tick) {
            return cached.value;
        }
        if (scansLeft <= 0) {
            return cached != null ? cached.value : Float.NaN;
        }
        scansLeft--;
        float value = scanHeat(level, inside, radius);
        warmthCache.put(key, new Warmth(value, tick + WARMTH_TTL_TICKS));
        return value;
    }

    private static float scanHeat(Level level, BlockPos center, int r) {
        float sum = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    p.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    float w = heatWeight(level.getBlockState(p));
                    if (w > 0) {
                        sum += w * falloff(Mth.sqrt(dx * dx + dy * dy + dz * dz), r);
                    }
                }
            }
        }
        for (Player player : level.players()) {
            double dist = Math.sqrt(player.blockPosition().distSqr(center));
            if (dist <= r && !level.canSeeSky(player.blockPosition())) {
                sum += 0.10f * falloff((float) dist, r);
            }
        }
        // Saturating: one lit furnace nearby already warms a room noticeably, more sources add less and less
        return 1 - (float) Math.exp(-3 * sum);
    }

    /** Gentle near the source, dropping to zero at the scan edge. */
    private static float falloff(float dist, int r) {
        float t = dist / (r + 1);
        return Math.max(0, 1 - t * t);
    }

    static float heatWeight(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AbstractFurnaceBlock) return lit(state) ? 0.35f : 0;
        if (block instanceof CampfireBlock) return lit(state) ? 0.45f : 0;
        if (block instanceof BaseFireBlock) return 0.40f;
        if (state.getFluidState().is(FluidTags.LAVA)) return 0.60f;
        if (state.is(Blocks.MAGMA_BLOCK)) return 0.20f;
        if (block instanceof LanternBlock || block instanceof BaseTorchBlock) return 0.05f;
        if (state.is(HEAT_SOURCES)) return 0.30f;
        return 0;
    }

    private static boolean lit(BlockState state) {
        return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
