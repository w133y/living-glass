package com.w1z4r_d.residuum.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.compat.Compat;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fireflies over grass on dry nights that are not cold: specks of light that come out over grass, ferns and flowers
 * under the open sky round the player, glow up, pulse gently as they drift about where they are, and dim out again.
 * They come out little by little as night falls and thin out toward dawn, and keep away from torches and lamps. Each
 * is a bright pixel with a soft cross of light round it, added onto the world like lightning, so it glows in the dark;
 * cool green-blue in cool biomes, yellow-green in mild ones and amber in hot ones.
 */
public final class Fireflies {
    /** How far round the player they come out, and how far above or below the player the ground there may be. */
    private static final int REACH = 20, HEIGHT = 10;
    /**
     * Spots tried a tick at full night and 100%. About one in two is grass in a meadow, and each firefly lasts 8 to 16
     * seconds, so some 40 are out at once round the player there.
     */
    private static final float TRIES = 0.35f;
    private static final int MAX = 256;
    /** How long they take to all come out as night falls, and to go toward dawn, in ticks. */
    private static final float DUSK_TICKS = 1200;
    /** Below this base temperature a biome is too cold for them (taiga and colder). */
    private static final float COLD = 0.3f;
    /** How long one glows up and dims out again, in ticks. */
    private static final int FADE_TICKS = 30;
    /** Half the width of the bright speck (a pixel), and the reach of the light round it, in blocks. */
    private static final float CORE = 1 / 32f, GLOW = 3 / 32f;
    /**
     * Never smaller on screen than about this share of their distance, a pixel or two, so far ones do not flicker in
     * and out between pixels.
     */
    private static final float MIN_SIZE = 0.0025f;
    /** Their speck and light from cool biomes to hot ones, and the base temperatures at those ends. */
    private static final int[] CORE_RGB = {0xD2FFF0, 0xF4FF9A, 0xFFE48A}, GLOW_RGB = {0x5AF0C8, 0xB4FF5A, 0xFFA43C};
    private static final float COOL = 0.4f, MILD = 0.85f, HOT = 1.6f;
    private static final float GLOW_ALPHA = 0.3f;
    private static final Random RANDOM = new Random();
    private static final FloatArrayList corners = new FloatArrayList();

    /** Where one came out, when, for how long, the seed of how it drifts and pulses, and its colours. */
    private record Firefly(double x, double y, double z, long born, int life, int seed, int core, int glow) {
    }

    private static final List<Firefly> flies = new ArrayList<>();
    private static Level level;
    private static float dusk;

    private Fireflies() {
    }

    public static void tick(Minecraft mc) {
        ClientLevel now = mc.level;
        Player player = mc.player;
        if (now != level) {
            flies.clear();
            level = now;
            dusk = 0;
        }
        if (now == null || player == null || mc.isPaused()) {
            return;
        }
        long time = now.getGameTime();
        flies.removeIf(f -> time - f.born() > f.life());
        ResiduumConfig cfg = ResiduumConfig.get();
        boolean out = cfg.enabled && cfg.fireflies.enabled && now.isDarkOutside() && !now.isRaining();
        // They come out little by little as night falls; by day, in rain or switched off, none come out and those
        // still out dim away within a second or two, the whole of them, not a few at a time
        dusk = out ? Math.min(1, dusk + 1 / DUSK_TICKS) : 0;
        if (!out) {
            flies.replaceAll(f -> time - f.born() + FADE_TICKS < f.life()
                    ? new Firefly(f.x(), f.y(), f.z(), f.born(), (int) (time - f.born()) + FADE_TICKS, f.seed(), f.core(), f.glow()) : f);
        }
        for (float tries = out ? TRIES * (float) cfg.fireflies.frequency * dusk : 0; tries > 0 && flies.size() < MAX; tries--) {
            if (RANDOM.nextFloat() < tries) {
                spawn(now, player, time);
            }
        }
    }

    private static void spawn(ClientLevel level, Player player, long time) {
        int x = player.getBlockX() + RANDOM.nextInt(2 * REACH + 1) - REACH;
        int z = player.getBlockZ() + RANDOM.nextInt(2 * REACH + 1) - REACH;
        // The first block over the ground, where grass grows: under the open sky by the way it is found
        BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        if (Math.abs(pos.getY() - player.getY()) > HEIGHT) {
            return; // not loaded, or ground far over or under the player: a cave roof, a cliff
        }
        BlockState state = level.getBlockState(pos);
        float temp = level.getBiome(pos).value().getBaseTemperature();
        if (!(Trampling.bends(state) || state.is(BlockTags.FLOWERS)) || level.getBrightness(LightLayer.BLOCK, pos) > 7
                || temp < COLD) {
            return;
        }
        // Over the top of the plant, never in among its leaves, where its flat faces would cut the light into flickering
        // stripes as it drifts
        double top = pos.getY() + (state.getBlock() instanceof DoublePlantBlock ? 2 : 1) + 0.2;
        flies.add(new Firefly(x + RANDOM.nextDouble(), top + RANDOM.nextDouble() * 1.2, z + RANDOM.nextDouble(),
                time, 160 + RANDOM.nextInt(160), RANDOM.nextInt(), shade(CORE_RGB, temp), shade(GLOW_RGB, temp)));
    }

    static void render(PoseStack poseStack, SubmitNodeCollector collector, Level level, Vec3 cam) {
        corners.clear();
        if (flies.isEmpty()) {
            return;
        }
        double now = level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vector3f view = new Vector3f(), right = new Vector3f(), up = new Vector3f();
        for (Firefly f : flies) {
            double age = now - f.born();
            if (age < 0 || age > f.life()) {
                continue;
            }
            int h = OverlayRenderer.hash(f.seed());
            double phase = (h & 0xFF) / 40.0, speed = 0.05 + (h >>> 8 & 0xFF) / 255.0 * 0.06, s = age * speed;
            // Lazy loops about where it came out, a little up and down
            float x = (float) (f.x() + 0.5 * Math.sin(s + phase) + 0.25 * Math.sin(s * 2.3 + phase * 2) - cam.x);
            float y = (float) (f.y() + 0.15 * Math.sin(s * 1.7 + phase * 3) - cam.y);
            float z = (float) (f.z() + 0.5 * Math.cos(s * 0.9 + phase) + 0.25 * Math.cos(s * 2.9 + phase) - cam.z);
            double in = Math.min(age, f.life() - age) / FADE_TICKS;
            float fade = (float) Math.clamp(in, 0, 1);
            // A slow, smooth throb, each at a pace of its own
            float pulse = (float) (0.65 + 0.35 * Math.sin(age * (0.1 + (h >>> 16 & 0xFF) / 255.0 * 0.08) + phase));
            float bright = fade * pulse;
            if (bright <= 0.01f) {
                continue;
            }
            view.set(x, y, z);
            float distance = view.length();
            if (distance < 0.2f) {
                continue;
            }
            view.normalize();
            float core = Math.max(CORE, distance * MIN_SIZE), reach = core * GLOW / CORE;
            view.cross(0, 1, 0, right);
            if (right.lengthSquared() < 1e-6f) {
                right.set(1, 0, 0);
            }
            right.normalize();
            right.cross(view, up).normalize();
            int glow = argb(GLOW_ALPHA * bright, f.glow());
            // The light round it as a cross, a pixel wide each way, rather than a square. Each layer a hair nearer the
            // eye than the one under it, so they never share a depth and flicker over each other
            float lift = distance * 0.002f;
            quad(x, y, z, right, up, reach, core, glow);
            quad(x - view.x * lift, y - view.y * lift, z - view.z * lift, right, up, core, reach, glow);
            quad(x - view.x * 2 * lift, y - view.y * 2 * lift, z - view.z * 2 * lift, right, up, core, core,
                    argb(bright, f.core()));
        }
        if (!corners.isEmpty()) {
            Compat.submitLightning(collector, poseStack, (pose, consumer) -> {
                float[] a = corners.elements();
                for (int i = 0; i + 4 <= corners.size(); i += 4) {
                    consumer.addVertex(pose, a[i], a[i + 1], a[i + 2]).setColor(Float.floatToRawIntBits(a[i + 3]));
                }
            });
        }
    }

    /** A colour for a biome this warm: between the cool, mild and hot ones of colours. */
    private static int shade(int[] colours, float temp) {
        return temp < MILD ? mix(colours[0], colours[1], (temp - COOL) / (MILD - COOL))
                : mix(colours[1], colours[2], (temp - MILD) / (HOT - MILD));
    }

    private static int mix(int a, int b, float t) {
        float s = Math.clamp(t, 0f, 1f);
        int out = 0;
        for (int shift = 0; shift < 24; shift += 8) {
            int ca = a >>> shift & 0xFF, cb = b >>> shift & 0xFF;
            out |= Math.round(ca + (cb - ca) * s) << shift;
        }
        return out;
    }

    private static int argb(float a, int rgb) {
        return (int) (Math.clamp(a, 0f, 1f) * 255) << 24 | rgb;
    }

    /** A rectangle facing the camera round (x, y, z), w across and h up either way from it, wound to face it. */
    private static void quad(float x, float y, float z, Vector3f right, Vector3f up, float w, float h, int color) {
        corner(x - right.x * w - up.x * h, y - right.y * w - up.y * h, z - right.z * w - up.z * h, color);
        corner(x + right.x * w - up.x * h, y + right.y * w - up.y * h, z + right.z * w - up.z * h, color);
        corner(x + right.x * w + up.x * h, y + right.y * w + up.y * h, z + right.z * w + up.z * h, color);
        corner(x - right.x * w + up.x * h, y - right.y * w + up.y * h, z - right.z * w + up.z * h, color);
    }

    private static void corner(float x, float y, float z, int color) {
        corners.add(x);
        corners.add(y);
        corners.add(z);
        corners.add(Float.intBitsToFloat(color));
    }
}
