"""Ash, snow splat and lightning scorch textures for Living Glass, in the vanilla manner: a few fixed shades grouped
into clean clusters, and one texture pixel per block pixel (1/16 block).

ash_full_{variant}_{mask}: scorch under or behind a fire, covering nearly the whole face. Mask bits (1 left,
2 right, 4 bottom, 8 top) mark edges that fade out; an edge without its bit continues into the next block's ash.
ash_side_{variant}_{mask}: soot on a block next to a fire, from the bottom up to about half the face. Only the
left/right bits are used; the top always fades out.
snow_{variant}: a 10x10 snowball splat (10/16 of a block).
lightning_scorch_{variant}: a 64x64 burn mark spanning 4x4 blocks where lightning struck.
lightning_embers_{variant}: the glowing cinders drawn over it while it is fresh.
"""
import math
import os
import random
import sys
from PIL import Image

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
S = 16
VARIANTS = 3

# Soot shades from the edge of a patch to its heart, with the alpha each is drawn at
SOOT = [(70, 60, 52, 120), (48, 42, 37, 175), (33, 29, 26, 205), (22, 19, 17, 220)]
SNOW = [(255, 255, 255, 255), (240, 246, 248, 255), (219, 230, 236, 255), (194, 208, 216, 255)]
EMBERS = [(255, 214, 110, 255), (255, 150, 40, 255), (200, 70, 20, 255)]


def smooth(rnd, size, cells, amp):
    """Value noise on a coarse grid, blended smoothly: clustered, blotchy variation instead of salt and pepper."""
    g = [[rnd.uniform(-amp, amp) for _ in range(cells + 1)] for _ in range(cells + 1)]
    n = [[0.0] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            gx, gy = x / (size - 1) * cells, y / (size - 1) * cells
            ix, iy = min(int(gx), cells - 1), min(int(gy), cells - 1)
            fx, fy = gx - ix, gy - iy
            fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
            top = g[iy][ix] * (1 - fx) + g[iy][ix + 1] * fx
            bot = g[iy + 1][ix] * (1 - fx) + g[iy + 1][ix + 1] * fx
            n[y][x] = top * (1 - fy) + bot * fy
    return n


def edge_dist(x, y, mask):
    d = [99]
    if mask & 1: d.append(x)
    if mask & 2: d.append(S - 1 - x)
    if mask & 4: d.append(S - 1 - y)
    if mask & 8: d.append(y)
    return min(d)


def soot_px(level):
    return SOOT[max(0, min(len(SOOT) - 1, level))]


for variant in range(VARIANTS):
    rnd = random.Random(7 + variant * 1000)
    rag = smooth(rnd, S, 3, 1.3)
    tone = smooth(rnd, S, 4, 0.9)

    # Full scorch: shade steps by distance from a fading edge, nudged by clustered noise
    for mask in range(16):
        img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        for y in range(S):
            for x in range(S):
                d = edge_dist(x, y, mask) + rag[y][x]
                if d < 0.5:
                    continue
                level = 0 if d < 1.5 else 1 if d < 2.5 else 2 if d < 3.5 else 3
                if level == 3 and tone[y][x] < -0.45:
                    level = 2  # a few slightly lighter clusters in the heart
                img.putpixel((x, y), soot_px(level))
        img.save(f"{out}/ash_full_{variant}_{mask}.png")

    # Side soot: rises from the bottom in steps, with a wavy top
    for mask in range(4):
        img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        for y in range(S):
            for x in range(S):
                rise = (S - 1 - y) + rag[y][x] * 1.5
                side = edge_dist(x, y, mask) + rag[y][x]
                if rise > 8 or side < 0.5:
                    continue
                level = 3 if rise < 2 else 2 if rise < 4.5 else 1 if rise < 6.5 else 0
                if side < 2:
                    level = min(level, int(side))
                img.putpixel((x, y), soot_px(round(level + tone[y][x] * 0.4)))
        img.save(f"{out}/ash_side_{variant}_{mask}.png")

    # Snowball splat, 10x10: a chunky lump, lit from the top left, with a few bits thrown off
    N = 10
    lump = smooth(rnd, N, 2, 1.2)
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    for y in range(N):
        for x in range(N):
            r = math.hypot(x - 4.5, y - 4.5) + lump[y][x]
            if r < 3.6:
                shade = 0 if x + y < 7 else 1 if x + y < 11 else 2
                if r > 2.8 and x + y > 9:
                    shade = 3  # shadowed rim at the bottom right
                img.putpixel((x, y), SNOW[shade])
    for x, y in ((rnd.randrange(N), 0), (0, rnd.randrange(N)), (N - 1, rnd.randrange(2, N)), (rnd.randrange(2, N), N - 1)):
        if math.hypot(x - 4.5, y - 4.5) > 4:
            img.putpixel((x, y), SNOW[1])
    img.save(f"{out}/snow_{variant}.png")

    # Lightning scorch, 64x64 over 4x4 blocks: a dark heart with jagged cracks forking outward
    L = 64
    burn = smooth(rnd, L, 6, 3.0)
    mark = [[-1] * L for _ in range(L)]
    for y in range(L):
        for x in range(L):
            r = math.hypot(x - 31.5, y - 31.5) + burn[y][x]
            if r < 7:
                mark[y][x] = 3
            elif r < 10:
                mark[y][x] = 2
            elif r < 13:
                mark[y][x] = 1

    def crack(x, y, angle, length, width):
        for _ in range(length):
            angle += rnd.uniform(-0.5, 0.5)
            x += math.cos(angle) * 1.4
            y += math.sin(angle) * 1.4
            for wx in range(-width, width + 1):
                for wy in range(-width, width + 1):
                    px, py = int(x) + wx, int(y) + wy
                    if 0 <= px < L and 0 <= py < L:
                        mark[py][px] = max(mark[py][px], 2 if width else 1)
            if rnd.random() < 0.08 and length > 6:
                crack(x, y, angle + rnd.choice([-0.9, 0.9]), length // 2, 0)

    for i in range(rnd.randint(6, 8)):
        a = i / 7 * 2 * math.pi + rnd.uniform(-0.3, 0.3)
        crack(31.5 + math.cos(a) * 8, 31.5 + math.sin(a) * 8, a, rnd.randint(9, 15), 1 if i % 2 == 0 else 0)
    img = Image.new("RGBA", (L, L), (0, 0, 0, 0))
    embers = Image.new("RGBA", (L, L), (0, 0, 0, 0))
    for y in range(L):
        for x in range(L):
            if mark[y][x] >= 0:
                img.putpixel((x, y), soot_px(mark[y][x]))
                # Glowing cinders sit in the heart and along the cracks
                heart = math.hypot(x - 31.5, y - 31.5) < 9
                if mark[y][x] >= 2 and rnd.random() < (0.22 if heart else 0.08):
                    embers.putpixel((x, y), EMBERS[0 if heart and rnd.random() < 0.4 else rnd.choice([1, 2])])
    img.save(f"{out}/lightning_scorch_{variant}.png")
    embers.save(f"{out}/lightning_embers_{variant}.png")
print("ok")
