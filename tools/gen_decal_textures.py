"""Ash and snow splat textures for Living Glass. 16x16 pixel art, VARIANTS random variants.

ash_full_{variant}_{mask}: scorch under or behind a fire, covering nearly the whole face. Mask bits (1 left,
2 right, 4 bottom, 8 top) mark edges that fade out; an edge without its bit continues into the next block's ash.
ash_side_{variant}_{mask}: soot on a block next to a fire, from the bottom up to about half the face. Only the
left/right bits are used; the top always fades out.
snow_{variant}: a snowball splat.
"""
import os
import random
import sys
from PIL import Image

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
S = 16
VARIANTS = 3


def smooth_noise(rnd, cells, amp):
    """Blotchy value noise over the 16x16 face: random values on a coarse grid, blended between them."""
    grid = [[rnd.uniform(-amp, amp) for _ in range(cells + 1)] for _ in range(cells + 1)]
    out = [[0.0] * S for _ in range(S)]
    for y in range(S):
        for x in range(S):
            gx, gy = x / (S - 1) * cells, y / (S - 1) * cells
            ix, iy = min(int(gx), cells - 1), min(int(gy), cells - 1)
            fx, fy = gx - ix, gy - iy
            fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
            top = grid[iy][ix] * (1 - fx) + grid[iy][ix + 1] * fx
            bottom = grid[iy + 1][ix] * (1 - fx) + grid[iy + 1][ix + 1] * fx
            out[y][x] = top * (1 - fy) + bottom * fy
    return out


def edge_dist(x, y, mask):
    d = [99.0]
    if mask & 1: d.append(x + 0.5)
    if mask & 2: d.append(S - 0.5 - x)
    if mask & 4: d.append(S - 0.5 - y)
    if mask & 8: d.append(y + 0.5)
    return min(d)


def soot(depth):
    """Colour from charred black in the middle to brown-grey at the thin edge (depth 0..1)."""
    edge, core = (58, 50, 44), (16, 14, 13)
    return tuple(int(edge[i] + (core[i] - edge[i]) * depth) for i in range(3))


for variant in range(VARIANTS):
    rnd = random.Random(7 + variant * 1000)
    blot = smooth_noise(rnd, 3, 2.2)      # wobbles the edge of the scorch
    shade = smooth_noise(rnd, 4, 0.25)    # patches of darker and lighter soot
    flakes = {(rnd.randrange(S), rnd.randrange(S)) for _ in range(6)}

    for mask in range(16):
        img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        for y in range(S):
            for x in range(S):
                d = edge_dist(x, y, mask) + blot[y][x]
                if d < 1.0:
                    continue
                depth = max(0.0, min(1.0, (d - 1.0) / 4.0 + shade[y][x]))
                a = int(100 + 105 * min(1.0, (d - 1.0) / 2.5))
                c = (92, 86, 80) if (x, y) in flakes and depth > 0.5 else soot(depth)
                img.putpixel((x, y), (*c, a))
        img.save(f"{out}/ash_full_{variant}_{mask}.png")

    for mask in range(4):
        img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        for y in range(S):
            for x in range(S):
                rise = (S - 0.5 - y) + blot[y][x] * 1.2  # pixels up from the bottom, with a wavy top
                side = edge_dist(x, y, mask) + blot[y][x] * 0.6
                if rise > 8.5 or side < 1.0:
                    continue
                depth = max(0.0, min(1.0, 1 - rise / 8.5 + shade[y][x]))
                a = int(90 + 140 * depth)
                if side < 2.5:
                    a = int(a * (side - 1.0) / 1.5)
                img.putpixel((x, y), (*soot(depth), max(0, a)))
        img.save(f"{out}/ash_side_{variant}_{mask}.png")

    # Snow splat: an opaque packed blob with a few flecks thrown out around it
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    for y in range(S):
        for x in range(S):
            r = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5 + blot[y][x] * 0.7
            if r < 4:
                img.putpixel((x, y), (248, 250, 255, 255))
            elif r < 5.3:
                img.putpixel((x, y), (228, 236, 248, 255))
    for _ in range(10):
        x, y = rnd.randrange(S), rnd.randrange(S)
        if 5.5 < ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5 < 7.5:
            img.putpixel((x, y), (238, 244, 252, 255))
    img.save(f"{out}/snow_{variant}.png")
print("ok")
