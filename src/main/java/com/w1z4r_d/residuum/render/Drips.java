package com.w1z4r_d.residuum.render;

/**
 * Drops of condensation running down the inside of fogged glass. Each starts as a bead somewhere on the glass and
 * gathers for a while, then runs down in jerks, a few pixels at a time with pauses in between, wavering a
 * pixel aside and back, never in a straight line. Once it has run its length or reaches the bottom of the glass it
 * rests there a little and is gone. The pixels it ran over stay wiped clear of mist for a while behind it.
 *
 * Everything follows from a seed and the time, so there is nothing to keep, and every block of a tall window works out
 * the same drops: a face draws the drops that started on it and those that started on the blocks above and ran into it.
 */
final class Drips {
    /** What a pixel handed to a Painter is: the trail, a drop running, or a drop sitting still. */
    static final int TRAIL = 0, RUNNING = 1, BEAD = 2;

    interface Painter {
        /** A pixel of the face, column and row counted from its bottom left; strength 0..1 of the trail or the drop. */
        void pixel(int column, int row, float strength, int kind);
    }

    /**
     * How drops behave, each a pixel wide: per block, slots drops at a time each starting every periodMin..periodMax
     * seconds; a bead first for gatherMin..gatherMax seconds; then lengthMin..lengthMax pixels of running in stretches of
     * 2 or 3 pixels at speedMin..speedMax pixels a second, pausing pauseMin..pauseMax seconds between them; at rest for
     * rest seconds at the end; each pixel run over marked for trail seconds.
     */
    record Style(int slots, double periodMin, double periodMax, double gatherMin, double gatherMax, int lengthMin,
                 int lengthMax, double speedMin, double speedMax, double pauseMin, double pauseMax, double rest,
                 double trail) {
    }

    static final Style MIST = new Style(1, 14, 30, 1.5, 5, 10, 48, 5, 16, 0.3, 1.6, 6, 45);

    private Drips() {
    }

    /**
     * Paints the drops on one face of a column of glass. key: the column and face, the same for every block of it; y: the
     * block's height in the world; above and below: how many blocks of the same glass face go on up and down from it;
     * columns first..last of the face may hold drops; chance 0..1 that a slot has a drop each time round.
     */
    static void paint(Style s, int key, int y, int above, int below, int first, int last, float chance, double now,
                      Painter painter) {
        if (last - first < 2 || chance <= 0) {
            return;
        }
        double life = life(s);
        int cycles = (int) Math.ceil(life / s.periodMin) + 1;
        int bottom = (y - below) * 16;
        for (int start = y + Math.min(above, (int) Math.ceil(s.lengthMax / 16.0)); start >= y; start--) {
            for (int slot = 0; slot < s.slots; slot++) {
                int h = OverlayRenderer.hash(key ^ start * 0x2545F491 ^ slot * 0x632BE5AB);
                double period = s.periodMin + (h & 0xFF) / 255.0 * (s.periodMax - s.periodMin);
                double phase = (h >>> 8 & 0xFF) / 255.0;
                long now0 = (long) Math.floor(now / period + phase);
                for (long cycle = now0; cycle > now0 - cycles; cycle--) {
                    int d = OverlayRenderer.hash(h ^ (int) cycle * 0x9E3779B9);
                    if ((d & 0xFFFF) / 65536f < chance) {
                        drop(s, d, (cycle - phase) * period, now, life, start * 16, bottom, y * 16, first, last, painter);
                    }
                }
            }
        }
    }

    /** The longest a drop can last: gathering, its run with a pause after every stretch, and its rest or its trail. */
    private static double life(Style s) {
        return s.gatherMax + s.lengthMax / s.speedMin + s.lengthMax / 2.0 * s.pauseMax + Math.max(s.rest, s.trail);
    }

    /** One drop, started at time born on the block whose bottom pixel row is startRow; rows from faceRow up are the face's. */
    private static void drop(Style s, int h, double born, double now, double life, int startRow, int bottom, int faceRow,
                             int first, int last, Painter painter) {
        double age = now - born;
        if (age < 0 || age > life) {
            return;
        }
        double gather = s.gatherMin + (h >>> 16 & 0xFF) / 255.0 * (s.gatherMax - s.gatherMin);
        int column = first + 1 + Math.floorMod(h, last - first - 1), home = column;
        int row = startRow + (h >>> 24 & 15);
        if (age < gather) {
            // Gathering: from nothing
            paint(painter, column, row, faceRow, (float) (age / gather), BEAD);
            return;
        }
        double t = age - gather;
        int r = OverlayRenderer.hash(h + 0x51ED27);
        int length = Math.min(row - bottom, s.lengthMin + Math.floorMod(r, s.lengthMax - s.lengthMin + 1));
        double speed = s.speedMin + (r >>> 8 & 0xFF) / 255.0 * (s.speedMax - s.speedMin);
        double time = 0;
        int run = 0;
        for (int stretch = 0; run < length; stretch++) {
            int k = OverlayRenderer.hash(r ^ stretch * 0x632BE5AB);
            if (stretch > 0 && (column == home ? (k & 3) < 2 : (k & 3) != 0)) {
                // Between stretches it wavers a pixel aside and mostly back again, so its trail waves down the glass
                // rather than drifting across it
                column = column != home ? home : Math.clamp(column + ((k & 4) == 0 ? -1 : 1), first, last);
            }
            int pixels = Math.min(length - run, 2 + (k >>> 3 & 1));
            for (int i = 0; i < pixels; i++) {
                double passed = time + (i + 1) / speed;
                if (passed > t) {
                    paint(painter, column, row - run - i, faceRow, 1, RUNNING);
                    return;
                }
                trail(s, painter, column, row - run - i, faceRow, t - passed);
            }
            run += pixels;
            time += pixels / speed;
            if (run < length) {
                time += s.pauseMin + (k >>> 8 & 0xFF) / 255.0 * (s.pauseMax - s.pauseMin);
                if (time > t) {
                    paint(painter, column, row - run, faceRow, 1, RUNNING);
                    return;
                }
            }
        }
        // Run out: it rests at the end of its trail for a while, then is gone
        if (t - time < s.rest) {
            paint(painter, column, row - run, faceRow, 1, BEAD);
        }
    }

    private static void trail(Style s, Painter painter, int column, int row, int faceRow, double since) {
        if (since < s.trail) {
            paint(painter, column, row, faceRow, (float) (1 - since / s.trail), TRAIL);
        }
    }

    private static void paint(Painter painter, int column, int row, int faceRow, float strength, int kind) {
        if (row >= faceRow && row < faceRow + 16) {
            painter.pixel(column, row - faceRow, strength, kind);
        }
    }
}
