package com.w1z4r_d.residuum.render;

import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.glass.GlassTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * What the camera can see this frame, worked out once: as far as the game draws (or the mod's own limit, see
 * GlassTracker.viewRadius) and only what lies in the cone of the screen, so effects behind the player cost nothing.
 */
final class View {
    /** Closer than this the cone is not asked: a block right by the camera can fill the screen from any angle. */
    private static final double NEAR_SQ = 4 * 4;
    /** Widens the cone for what makes the view wider than the field of view setting: sprinting, speed and the like. */
    private static final double MARGIN = Math.toRadians(12);
    /** Beyond this distance faces are drawn in fewer, bigger cells: a texture pixel there is smaller than the screen's. */
    static final double DETAIL_DIST_SQ = 48 * 48;

    static Vec3 cam = Vec3.ZERO;
    private static double lookX, lookY, lookZ, cosHalf, maxDistSq;

    private View() {
    }

    static void update(Minecraft mc, Entity viewer, Vec3 camera) {
        cam = camera;
        Vec3 look = viewer.getViewVector(1.0f);
        double sign = mc.options.getCameraType().isMirrored() ? -1 : 1; // third person from the front looks back
        lookX = look.x * sign;
        lookY = look.y * sign;
        lookZ = look.z * sign;
        // Half the field of view across the screen's diagonal, its widest
        double fov = Math.toRadians(mc.options.fov().get());
        double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
        double half = Math.atan(Math.tan(fov / 2) * Math.sqrt(1 + aspect * aspect)) + MARGIN;
        cosHalf = Math.cos(Math.min(half, Math.PI));
        double radius = GlassTracker.viewRadius(ResiduumConfig.get());
        maxDistSq = radius * radius;
    }

    /** Whether anything within reach blocks of x, y, z can be on screen. */
    static boolean sees(double x, double y, double z, double reach) {
        double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > maxDistSq) {
            return false;
        }
        return distSq <= NEAR_SQ || dx * lookX + dy * lookY + dz * lookZ >= Math.sqrt(distSq) * cosHalf - reach;
    }

    static double distanceSq(double x, double y, double z) {
        double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
