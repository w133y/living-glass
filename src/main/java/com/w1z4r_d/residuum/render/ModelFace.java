package com.w1z4r_d.residuum.render;

import net.minecraft.core.Direction;

import java.util.BitSet;

/**
 * One face of a block's model as the player sees it: which way it looks, its four corners in block-local coordinates
 * (x, y, z each), where on its texture each corner falls (u, v each, 0 to 1 across the texture), and the texture's
 * see-through pixels. Marks that lie on what is seen of a block, not on its outline, go by these (see Compat.modelFaces).
 */
public record ModelFace(Direction face, float[] xyz, float[] uv, Mask mask) {
    /** A texture's size in pixels and which of them are opaque, row by row; opaque is null when all are. */
    public record Mask(int width, int height, BitSet opaque) {
        /** Whether the pixel at (u, v), 0 to 1 across the texture, is opaque. */
        public boolean opaqueAt(float u, float v) {
            if (opaque == null) {
                return true;
            }
            int x = Math.clamp((int) (u * width), 0, width - 1), y = Math.clamp((int) (v * height), 0, height - 1);
            return opaque.get(y * width + x);
        }
    }

    /** Whether all of the face's texture is opaque, so nothing of the face is see-through. */
    public boolean solid() {
        return mask == null || mask.opaque() == null;
    }
}
