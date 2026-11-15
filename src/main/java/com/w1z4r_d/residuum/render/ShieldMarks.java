package com.w1z4r_d.residuum.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.decal.DecalTracker;
import com.w1z4r_d.residuum.decal.DecalTracker.ShieldMark;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * Snowball splats on the front of a shield, drawn in the shield model's own space where one unit is a texture pixel
 * over 16, so a splat's pixels line up with the shield's.
 */
public final class ShieldMarks {
    /** Lets the item model resolver tell a render state whose entity is holding the item (see ItemModelResolverMixin). */
    public interface Owned {
        void residuum$setOwner(int entityId);
    }

    /** The entity whose item is being submitted, -1 when none is (inventory, item frames, dropped items). */
    public static int owner = -1;

    /** A splat texture is 14 pixels of mark in a 46 pixel sheet, with a 16 pixel clear margin all round. */
    private static final float PIXEL = 1 / 16f, MARK = 14, MARGIN = 16 / 46f, SHEET = 46;
    /** The plate's front is its face at z = -2 pixels in the model; this keeps the splat just off it. */
    private static final float FRONT = -2 * PIXEL - 0.004f;
    /** Each newer splat sits this much further out, so splats that overlap never fight over the same depth. */
    private static final float LAYER = 0.006f;
    /**
     * The plate is 12 by 22 pixels but the shield's face is not all of it: its rim and chamfered bottom corners are
     * clear or dark, so splats are cut off a little inside the plate. Top and bottom in model y, which points down.
     */
    private static final float PLATE_X = 5 * PIXEL, PLATE_TOP = -10 * PIXEL, PLATE_BOTTOM = 8 * PIXEL;
    /** Splats are much smaller than on blocks, as the shield is. */
    private static final float SIZE = 0.56f;
    /** A splat melts from this share of its life on: shrinking to a third of its size, then gone as it fades. */
    private static final float MELT_FROM = 0.3f, MELTED_SIZE = 0.35f, FADE_FROM = 0.6f;
    /** An axe's gash is bigger than a splat, and fades out as the shield comes back, over the last share of its time. */
    private static final float SCRATCH_SIZE = 0.85f, SCRATCH_FADE = 0.3f;
    private static final int SCRATCH_VARIANTS = 3;
    /** Quads of this frame's marks, per texture; submitted now, drawn later in the frame, cleared at the next. */
    private static final FloatArrayList[] snow = lists(DecalRenderer.SNOW_VARIANTS), scratches = lists(SCRATCH_VARIANTS);

    private static FloatArrayList[] lists(int n) {
        FloatArrayList[] out = new FloatArrayList[n];
        for (int i = 0; i < n; i++) {
            out[i] = new FloatArrayList();
        }
        return out;
    }

    private ShieldMarks() {
    }

    /**
     * Submits the splats of the current owner's shield. modelSpace is whether the pose is already the model's frame
     * (26.x puts the shield's flip into the item transform); older versions flip inside the shield renderer.
     */
    public static void draw(PoseStack poseStack, SubmitNodeCollector collector, int light, boolean modelSpace) {
        ResiduumConfig cfg = ResiduumConfig.get();
        if (owner < 0 || !cfg.enabled) {
            return;
        }
        DecalTracker decals = DecalTracker.INSTANCE;
        for (FloatArrayList list : snow) {
            list.clear();
        }
        for (FloatArrayList list : scratches) {
            list.clear();
        }
        long life = (long) (cfg.timing.snowSeconds * 20);
        int layer = 0;
        boolean any = false;
        // Gashes first, nearest the plate, so snow lands on them
        for (int pass = 0; pass < 2; pass++) {
            boolean scratch = pass == 0;
            if (scratch ? !cfg.shieldScratches.enabled : !cfg.snowballs.enabled) {
                continue;
            }
            for (ShieldMark m : decals.shieldMarks()) {
                if (m.entity() != owner || m.scratch() != scratch) {
                    continue;
                }
                // Between ticks too, so the melt is smooth and not in steps of a tick
                float age = Math.min(1, (decals.tick() + partialTick() - m.born())
                        / (float) (scratch ? DecalTracker.SCRATCH_TICKS : life));
                float alpha, size;
                if (scratch) {
                    // On your own shield it follows the cooldown you see on the hotbar; on others' the usual one
                    float left = owner == ownId() ? ownCooldown() : 1 - age;
                    alpha = Math.clamp(left / SCRATCH_FADE, 0f, 1f);
                    size = SCRATCH_SIZE;
                    if (alpha <= 0) {
                        continue;
                    }
                } else {
                    alpha = age < FADE_FROM ? 1 : (1 - age) / (1 - FADE_FROM);
                    float melt = Math.clamp((age - MELT_FROM) / (1 - MELT_FROM), 0f, 1f);
                    size = SIZE * (1 - (1 - MELTED_SIZE) * melt * melt * (3 - 2 * melt));
                }
                FloatArrayList[] lists = scratch ? scratches : snow;
                int variant = Math.floorMod(OverlayRenderer.hash(m.seed()), lists.length);
                // A gash can lie either way across, mirrored
                boolean mirror = scratch && (OverlayRenderer.hash(m.seed() * 31) & 1) != 0;
                quad(lists[variant], m.x(), m.y(), size, FRONT - layer++ * LAYER, mirror,
                        OverlayRenderer.argb(alpha, 0xFFFFFF, 1), light);
                any = true;
            }
        }
        if (!any) {
            return;
        }
        if (!modelSpace) {
            poseStack.pushPose();
            poseStack.scale(1, -1, -1);
        }
        // Not the decal type: its depth offset depends on the slope, so marks that overlap flickered
        for (int i = 0; i < scratches.length; i++) {
            if (!scratches[i].isEmpty()) {
                OverlayRenderer.submitLit(collector, poseStack, "shield_scratch_" + i, scratches[i]);
            }
        }
        for (int i = 0; i < snow.length; i++) {
            if (!snow[i].isEmpty()) {
                OverlayRenderer.submitLit(collector, poseStack, "snow_" + i, snow[i]);
            }
        }
        if (!modelSpace) {
            poseStack.popPose();
        }
    }

    private static int ownId() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? -1 : mc.player.getId();
    }

    /** How much of its cooldown the shield in either of your hands has left, 1 just knocked aside, 0 ready again. */
    private static float ownCooldown() {
        Minecraft mc = Minecraft.getInstance();
        float partial = partialTick(), left = 0;
        for (var hand : net.minecraft.world.InteractionHand.values()) {
            left = Math.max(left, mc.player.getCooldowns().getCooldownPercent(mc.player.getItemInHand(hand), partial));
        }
        return left;
    }

    /** Time since the last tick, 0 to 1. */
    private static float partialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    /**
     * The mark's square seen from the front of the plate (model x to the right, model y down), x and y pixels off its
     * middle, size times its full size, and cut off where it would run past the plate.
     */
    private static void quad(FloatArrayList out, int x, int y, float size, float z, boolean mirror, int color, int light) {
        float half = MARK / 2 * size * PIXEL;
        float cx = x * PIXEL, cy = -y * PIXEL;
        float left = cx - half, right = cx + half, top = cy - half, bottom = cy + half;
        float x0 = Math.max(left, -PLATE_X), x1 = Math.min(right, PLATE_X);
        float y0 = Math.max(top, PLATE_TOP), y1 = Math.min(bottom, PLATE_BOTTOM);
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        float span = MARK / SHEET;
        float u0 = MARGIN + span * (x0 - left) / (2 * half), u1 = MARGIN + span * (x1 - left) / (2 * half);
        if (mirror) {
            u0 = 2 * MARGIN + span - u0;
            u1 = 2 * MARGIN + span - u1;
        }
        float v0 = MARGIN + span * (y0 - top) / (2 * half), v1 = MARGIN + span * (y1 - top) / (2 * half);
        vertex(out, x0, y1, z, u0, v1, color);
        vertex(out, x1, y1, z, u1, v1, color);
        vertex(out, x1, y0, z, u1, v0, color);
        vertex(out, x0, y0, z, u0, v0, color);
        out.add(0f);
        out.add(0f);
        out.add(-1f);
        out.add(Float.intBitsToFloat(light));
    }

    private static void vertex(FloatArrayList out, float x, float y, float z, float u, float v, int color) {
        out.add(x);
        out.add(y);
        out.add(z);
        out.add(u);
        out.add(v);
        out.add(Float.intBitsToFloat(color));
    }
}
