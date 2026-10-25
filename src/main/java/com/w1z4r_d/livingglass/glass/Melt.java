package com.w1z4r_d.livingglass.glass;

/**
 * Which texture a face shows, and the pixel dissolve from the previous one after it changes (a neighbouring
 * window or ash patch was built or broken), so the face melts into its new shape instead of switching at once.
 */
public final class Melt {
    public int tex = -1, prevTex = -1;
    public float blend = 1;
    /** The melt runs backwards (the neighbour was put back), so cells switch in reverse order. */
    public boolean reversed;

    /** Shows tex from now on, melting from what is on screen. */
    public void show(int tex) {
        if (tex == this.tex) {
            return;
        }
        if (tex == prevTex && blend < 1) {
            // Put back before the melt finished: run the same melt backwards from where it is
            prevTex = this.tex;
            blend = 1 - blend;
            reversed = !reversed;
        } else if (this.tex >= 0) {
            prevTex = blend >= 0.5f ? this.tex : prevTex;
            blend = 0;
            reversed = false;
        }
        this.tex = tex;
    }

    public boolean melting() {
        return blend < 1 && prevTex >= 0;
    }

    /** Advances the melt by one tick; a full melt takes the given seconds. */
    public void tick(double seconds) {
        blend = GlassTracker.approach(blend, 1, seconds, 1);
    }
}
