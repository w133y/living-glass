package com.w1z4r_d.residuum.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.w1z4r_d.residuum.compat.Compat;
import com.w1z4r_d.residuum.decal.DecalTracker;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Little bolts of lightning crackling off a lightning rod for a while after it is struck (see DecalTracker): now and then
 * an arc jumps from near the rod's tip a short way into the air, jagged, flickering for a moment like the bolt itself,
 * fewer and shorter as the rod discharges until it is quiet again.
 */
final class RodArcs {
    /** Arcs that can crackle at once per rod; each lasts ARC_TICKS, and its slot tries again every 1.5 to 3.5 times that. */
    private static final int SLOTS = 3;
    private static final double ARC_TICKS = 4;
    private static final int SEGMENTS = 5;
    /** How far an arc reaches from the rod at most, in blocks, and how far its kinks stray from a straight line. */
    private static final float REACH = 0.8f, KINK = 0.07f;
    /** Half the width of an arc's bright core and of the glow round it, in blocks; their colours, added onto the world. */
    private static final float CORE = 1 / 48f, GLOW = 1 / 18f;
    private static final float[] CORE_RGBA = {0.85f, 0.9f, 1f, 0.9f}, GLOW_RGBA = {0.35f, 0.45f, 1f, 0.35f};
    /** Corners of the arcs this frame: x, y, z and the packed colour of each. */
    private static final FloatArrayList corners = new FloatArrayList();

    private RodArcs() {
    }

    static void render(PoseStack poseStack, SubmitNodeCollector collector, Level level, Vec3 cam) {
        corners.clear();
        double now = level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (DecalTracker.Rod rod : DecalTracker.INSTANCE.rods()) {
            double age = now - rod.struck();
            float left = (float) (1 - age / rod.ticks());
            if (left <= 0 || age < 0) {
                continue;
            }
            int seed = (int) rod.pos().asLong() * 0x9E3779B9;
            for (int slot = 0; slot < SLOTS; slot++) {
                int h = OverlayRenderer.hash(seed + slot * 0x632BE5AB);
                double period = ARC_TICKS * (1.5 + (h & 0xFF) / 255.0 * 2);
                double t = age / period + (h >>> 8 & 0xFF) / 255.0;
                long cycle = (long) Math.floor(t);
                double into = (t - cycle) * period;
                int c = OverlayRenderer.hash(h ^ (int) cycle * 0x9E3779B9);
                // Dark between arcs, and fewer of them as the rod discharges
                if (into >= ARC_TICKS || (c & 0xFFFF) / 65536f >= left) {
                    continue;
                }
                // Flickering like the bolt: bright, dim, bright, dim
                arc(rod, c, (int) into % 2 == 0 ? 1 : 0.5f, 0.4f + 0.6f * left, cam);
            }
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

    /** One arc of a rod, its shape from the random number c, at brightness bright and reach for how charged the rod is. */
    private static void arc(DecalTracker.Rod rod, int c, float bright, float reach, Vec3 cam) {
        Direction.Axis axis = rod.axis();
        // From somewhere on the upper half of the rod (along its axis), a little off its middle
        float along = 0.5f - ((c >>> 16 & 0xFF) / 255f) * 0.5f;
        Vector3f from = new Vector3f((float) (rod.pos().getX() + 0.5 - cam.x), (float) (rod.pos().getY() + 0.5 - cam.y),
                (float) (rod.pos().getZ() + 0.5 - cam.z));
        from.setComponent(axis.ordinal(), from.get(axis.ordinal()) + along);
        // Out in a direction of its own, mostly away from the rod's axis
        double yaw = (c & 0xFF) / 255.0 * Math.PI * 2, tilt = ((c >>> 8 & 0xFF) / 255.0 - 0.3) * 1.2;
        Vector3f dir = new Vector3f((float) (Math.cos(yaw) * Math.cos(tilt)), (float) Math.sin(tilt),
                (float) (Math.sin(yaw) * Math.cos(tilt)));
        float length = REACH * reach * (0.4f + 0.6f * ((c >>> 24 & 0xFF) / 255f));
        Vector3f prev = new Vector3f(from), next = new Vector3f();
        int k = c;
        for (int i = 1; i <= SEGMENTS; i++) {
            k = OverlayRenderer.hash(k + i);
            next.set(dir).mul(length * i / SEGMENTS).add(from);
            if (i < SEGMENTS) {
                next.add(((k & 0xFF) / 255f - 0.5f) * 2 * KINK, ((k >>> 8 & 0xFF) / 255f - 0.5f) * 2 * KINK,
                        ((k >>> 16 & 0xFF) / 255f - 0.5f) * 2 * KINK);
            }
            segment(prev, next, GLOW, GLOW_RGBA, bright);
            segment(prev, next, CORE, CORE_RGBA, bright);
            prev.set(next);
        }
    }

    /** A ribbon from a to b (relative to the camera), half as wide as half, turned to face the camera. */
    private static void segment(Vector3f a, Vector3f b, float half, float[] rgba, float bright) {
        Vector3f along = new Vector3f(b).sub(a);
        Vector3f view = new Vector3f(a).add(b).mul(0.5f);
        Vector3f side = along.cross(view, new Vector3f());
        if (side.lengthSquared() < 1e-8f) {
            return;
        }
        side.normalize(half);
        int color = (int) (rgba[3] * bright * 255) << 24 | (int) (rgba[0] * 255) << 16 | (int) (rgba[1] * 255) << 8
                | (int) (rgba[2] * 255);
        // Wound to face the camera
        corner(a.x - side.x, a.y - side.y, a.z - side.z, color);
        corner(b.x - side.x, b.y - side.y, b.z - side.z, color);
        corner(b.x + side.x, b.y + side.y, b.z + side.z, color);
        corner(a.x + side.x, a.y + side.y, a.z + side.z, color);
    }

    private static void corner(float x, float y, float z, int color) {
        corners.add(x);
        corners.add(y);
        corners.add(z);
        corners.add(Float.intBitsToFloat(color));
    }
}
