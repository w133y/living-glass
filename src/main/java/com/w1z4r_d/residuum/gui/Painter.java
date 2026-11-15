package com.w1z4r_d.residuum.gui;

import net.minecraft.network.chat.Component;

/** Draws on the settings screen, the same on every Minecraft version (see compat.PaintedWidget). */
public interface Painter {
    /**
     * The uw x vh texel region at (u, v) of a texW x texH texture, stretched over w x h screen pixels at (x, y) and
     * tinted argb.
     */
    void blit(String namespace, String path, int x, int y, int w, int h, float u, float v, int uw, int vh, int texW,
              int texH, int argb);

    void fill(int x0, int y0, int x1, int y1, int argb);

    /** Text with its top left at x, y, in argb (alpha included), with or without the game's drop shadow. */
    void text(Component text, int x, int y, int argb, boolean shadow);

    /** Draws only inside x0, y0 to x1, y1 until endScissor. */
    void scissor(int x0, int y0, int x1, int y1);

    void endScissor();

    /** Turns what is drawn next by radians about x, y, until pop. */
    void rotate(float x, float y, float radians);

    void pop();
}
