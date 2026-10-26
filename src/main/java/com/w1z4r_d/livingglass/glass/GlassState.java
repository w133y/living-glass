package com.w1z4r_d.livingglass.glass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** One tracked glass block: its classification plus current and target effect intensities. */
public final class GlassState {
    /** Enough that old wipes always fade out on their own instead of being replaced. */
    public static final int MAX_WIPES = 32;
    public static final int MAX_BREATHS = 8;
    /** A new breath merges into an existing patch closer than this (face units). */
    private static final float BREATH_MERGE = 0.15f;
    public static final float PANE_HALF = 1 / 16f;

    public final long pos;
    public final int seed;

    /** Face pointing into the warm room; null when the glass is not a window. */
    public Direction inner;
    /** Horizontal faces whose neighbour is open to the sky, bit = Direction.get2DDataValue(). */
    public int rainMask;
    /** Per rain face: the wet part of the face (face x coordinates) and the cell checked for rain. */
    public final float[] rainMin = new float[4];
    public final float[] rainMax = new float[4];
    public final long[] rainCell = new long[4];
    /**
     * Windows only: side faces (across the window) whose room side fogs and frosts too, e.g. the part of a side
     * face next to a pane that faces into the room. Bit and span arrays are indexed like the rain faces.
     */
    public int roomMask;
    public final float[] roomMin = new float[4];
    public final float[] roomMax = new float[4];
    /** Open sky right above the block, so rain lands on its top face (full blocks only). */
    public boolean rainTop;
    public boolean pane;
    /** For panes: horizontal sides the pane connects to, bit = Direction.get2DDataValue(). */
    public int paneConn;
    /** RGB tint for condensation, 0xFFFFFF for clear glass. */
    public int tint;

    public float fog, frost, drops, ice;
    /**
     * How high the outside ice reaches, in blocks above this block's bottom (negative: not up to it yet). It moves
     * with the ice but not with the window's size, so glass broken or built around it leaves the frost where it is.
     */
    public float iceFront;
    /** When the player left this window behind (System.nanoTime), so it can be restored if they come back soon. */
    public long parkedAt;
    public float fogTarget, frostTarget, dropTarget, iceTarget;

    // Last climate inputs, shown in the debug HUD; NaN until the first climate update
    public float outsideTemp = Float.NaN, warmth;

    /** Texture of the inside face, melting into a new frame shape when a neighbouring window changes. */
    public final Melt look = new Melt();

    /** Any breath or wipe patch still visible; refreshed every tick. */
    public boolean patches;

    // Render data that changes slowly, refreshed about once a second instead of every frame
    public long renderTime = -1;
    public float shadeInner, shadeTop;
    public final float[] shadeSide = new float[4];
    /** Packed lightmap coordinates of the same air blocks, used instead of shade when a shader pack lights the overlays. */
    public int lightInner, lightTop;
    public float shadeOuter;
    public int lightOuter;
    public final int[] lightSide = new int[4];
    /** Windows of the same room directly below, above, left and right, for merging large windows. */
    public int below, above, left, right;

    // Patches on the inner face, in face coordinates (x across, y up, both 0..1)
    public final float[] breath = new float[MAX_BREATHS];
    public final float[] breathX = new float[MAX_BREATHS];
    public final float[] breathY = new float[MAX_BREATHS];
    public final float[] wipe = new float[MAX_WIPES];
    public final float[] wipeX = new float[MAX_WIPES];
    /** Face each wipe is on, as Direction.get2DDataValue(): the inner face or a room-side face. */
    public final byte[] wipeFace = new byte[MAX_WIPES];
    public final float[] wipeY = new float[MAX_WIPES];

    GlassState(long pos) {
        this.pos = pos;
        this.seed = (int) (pos ^ (pos >>> 29)) * 0x9E3779B9;
    }

    public boolean paneConnects(Direction dir) {
        return (paneConn & (1 << dir.get2DDataValue())) != 0;
    }

    public boolean isWindow() {
        return inner != null;
    }

    public boolean hasPatches() {
        for (float b : breath) {
            if (b > 0.004f) {
                return true;
            }
        }
        for (float w : wipe) {
            if (w > 0.004f) {
                return true;
            }
        }
        return false;
    }

    /** Feeds the breath patch at (x, y), starting a new one in the faintest slot if none is close. */
    void addBreath(float x, float y, float amount) {
        int faintest = 0;
        for (int i = 0; i < MAX_BREATHS; i++) {
            float dx = breathX[i] - x, dy = breathY[i] - y;
            if (breath[i] > 0 && dx * dx + dy * dy < BREATH_MERGE * BREATH_MERGE) {
                breath[i] = Math.min(1, breath[i] + amount);
                return;
            }
            if (breath[i] < breath[faintest]) {
                faintest = i;
            }
        }
        breath[faintest] = amount;
        breathX[faintest] = x;
        breathY[faintest] = y;
    }

    /** Replaces the faintest wipe patch. */
    void addWipe(Direction face, float x, float y) {
        int slot = 0;
        for (int i = 1; i < MAX_WIPES; i++) {
            if (wipe[i] < wipe[slot]) {
                slot = i;
            }
        }
        wipe[slot] = 1;
        wipeFace[slot] = (byte) face.get2DDataValue();
        wipeX[slot] = x;
        wipeY[slot] = y;
    }

    /**
     * Horizontal face coordinate of a world point on the face pointing to dir: 0 at the left edge, 1 at the right,
     * matching how OverlayRenderer lays out its quads.
     */
    public static float faceX(BlockPos pos, Direction dir, double x, double z) {
        return (float) ((x - pos.getX() - 0.5) * -dir.getStepZ() + (z - pos.getZ() - 0.5) * dir.getStepX() + 0.5);
    }
}
