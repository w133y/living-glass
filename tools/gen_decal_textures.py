"""Ash, snow splat and lightning scorch textures for Living Glass, in the vanilla manner: a few fixed shades grouped
into clean clusters, and one texture pixel per block pixel (1/16 block).

ash_full_{variant}_{mask}: scorch under or behind a fire, covering nearly the whole face. Mask bits (1 left,
2 right, 4 bottom, 8 top) mark edges that fade out; an edge without its bit continues into the next block's ash.
ash_side_{variant}_{mask}: soot on a block next to a fire, from the bottom up to about half the face. Only the
left/right bits are used; the top always fades out.
snow_{variant}: a 10x10 snowball splat (10/16 of a block).
lightning_scorch_{variant}: a 64x64 burn mark spanning 4x4 blocks where lightning struck, in LIGHTNING_VARIANTS
styles that differ in size, shape and cracks.
lightning_embers_{variant}: the glowing cinders drawn over it while it is fresh.
lightning_glass_{variant}: the same mark where lightning struck sand and fused it into glass (fulgurite), with a
LabPBR specular map lightning_glass_{variant}_s that shader packs read through Iris: the glassy heart is smooth
metal, the sintered rim rougher glass.
Splats and burn marks sit in the middle of a sheet with a clear margin one block wide, so a block face the mark only
touches can take the sheet over the whole face: marks that overlap on a face then share their quads and blend
instead of flickering.
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
# Fused sand from the rim to the glassy heart, a glint on the glass, and their LabPBR specular values
# (red: smoothness, green: 230 = iron, so metallic, below 230 = how much a non-metal reflects, alpha 255: no glow)
FUSED = [(176, 160, 118, 110), (142, 128, 96, 165), (98, 96, 80, 215), (60, 62, 56, 235)]
GLINT = (214, 222, 214, 235)
FUSED_SPECULAR = [(90, 20, 0, 255), (150, 60, 0, 255), (215, 230, 0, 255), (245, 230, 0, 255)]
GLINT_SPECULAR = (255, 230, 0, 255)


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
    N, M = 10, 16
    lump = smooth(rnd, N, 2, 1.2)
    img = Image.new("RGBA", (N + 2 * M, N + 2 * M), (0, 0, 0, 0))
    for y in range(N):
        for x in range(N):
            r = math.hypot(x - 4.5, y - 4.5) + lump[y][x]
            if r < 3.6:
                shade = 0 if x + y < 7 else 1 if x + y < 11 else 2
                if r > 2.8 and x + y > 9:
                    shade = 3  # shadowed rim at the bottom right
                img.putpixel((x + M, y + M), SNOW[shade])
    for x, y in ((rnd.randrange(N), 0), (0, rnd.randrange(N)), (N - 1, rnd.randrange(2, N)), (rnd.randrange(2, N), N - 1)):
        if math.hypot(x - 4.5, y - 4.5) > 4:
            img.putpixel((x + M, y + M), SNOW[1])
    img.save(f"{out}/snow_{variant}.png")

# Lightning scorch, 64x64 over 4x4 blocks: a charred heart fading out in steps, forked cracks, burnt specks
LIGHTNING_VARIANTS = 6
STYLES = [
    # heart radius, ring widths outward, blob noise, stretch, cracks, crack length, crack width, fork chance, specks
    (9, (4, 4, 3), 2.5, 1.0, (4, 6), (14, 20), 1, 0.06, 0),
    (6, (3, 3, 3), 1.5, 1.0, (6, 7), (16, 24), 0, 0.10, 6),
    (11, (3, 4, 3), 4.0, 1.0, (3, 3), (8, 12), 1, 0.0, 3),
    (8, (4, 3, 3), 2.0, 1.6, (2, 3), (16, 22), 1, 0.08, 8),
    (7, (3, 2, 2), 1.5, 1.0, (8, 10), (8, 14), 0, 0.0, 4),
    (9, (3, 4, 4), 3.0, 1.25, (4, 5), (12, 18), 1, 0.12, 5),
]
assert len(STYLES) == LIGHTNING_VARIANTS
L, M = 64, 16
for variant, (heart, rings, noise, stretch, arms_range, length_range, width, fork, specks) in enumerate(STYLES):
    rnd = random.Random(31 + variant * 977)
    burn = smooth(rnd, L, 5, noise)
    tilt = rnd.uniform(0, math.pi)
    k = math.sqrt(stretch)
    edges = [heart, heart + rings[0], heart + rings[0] + rings[1], heart + sum(rings)]

    def radius(x, y):
        """Distance from the middle in the mark's own stretched shape."""
        dx, dy = x - 31.5, y - 31.5
        along = dx * math.cos(tilt) + dy * math.sin(tilt)
        side = -dx * math.sin(tilt) + dy * math.cos(tilt)
        return math.hypot(along / k, side * k)

    mark = [[-1] * L for _ in range(L)]
    for y in range(L):
        for x in range(L):
            r = radius(x, y) + burn[y][x]
            mark[y][x] = 3 if r < edges[0] else 2 if r < edges[1] else 1 if r < edges[2] else 0 if r < edges[3] else -1

    def crack(x, y, angle, length, w, level):
        for _ in range(length):
            angle += rnd.uniform(-0.35, 0.35)
            x += math.cos(angle)
            y += math.sin(angle)
            for wx in range(-w, w + 1):
                for wy in range(-w, w + 1):
                    px, py = int(round(x)) + wx, int(round(y)) + wy
                    if 0 <= px < L and 0 <= py < L:
                        mark[py][px] = max(mark[py][px], level)
            if rnd.random() < fork and length > 8:
                crack(x, y, angle + rnd.choice([-0.8, 0.8]), length // 2, 0, max(1, level - 1))

    arms = rnd.randint(*arms_range)
    for i in range(arms):
        a = (i + rnd.uniform(-0.25, 0.25)) / arms * 2 * math.pi
        if stretch > 1.2:  # a long mark cracks mostly along its length
            a = tilt + (0 if i % 2 == 0 else math.pi) + rnd.uniform(-0.6, 0.6)
        crack(31.5 + math.cos(a) * heart * 0.8, 31.5 + math.sin(a) * heart * 0.8, a, rnd.randint(*length_range), width, 2)
    for _ in range(specks):  # burnt bits thrown clear of the mark
        a, d = rnd.uniform(0, 2 * math.pi), rnd.uniform(edges[3] + 2, 29)
        sx, sy = int(31.5 + math.cos(a) * d), int(31.5 + math.sin(a) * d)
        bits = [(sx, sy)] + [(sx + rnd.choice((-1, 0, 1)), sy + rnd.choice((-1, 0, 1))) for _ in range(rnd.randint(0, 2))]
        for px, py in bits:
            if 0 <= px < L and 0 <= py < L:
                mark[py][px] = max(mark[py][px], rnd.choice((1, 2)))
    img = Image.new("RGBA", (L + 2 * M, L + 2 * M), (0, 0, 0, 0))
    embers = Image.new("RGBA", (L + 2 * M, L + 2 * M), (0, 0, 0, 0))
    for y in range(L):
        for x in range(L):
            if mark[y][x] >= 0:
                img.putpixel((x + M, y + M), soot_px(mark[y][x]))
            # Glowing cinders only in the heart
            r = radius(x, y)
            if r < heart - 1.5 and rnd.random() < 0.3:
                embers.putpixel((x + M, y + M), EMBERS[0 if r < heart * 0.45 else 1 if rnd.random() < 0.6 else 2])
    img.save(f"{out}/lightning_scorch_{variant}.png")
    embers.save(f"{out}/lightning_embers_{variant}.png")

    # The same mark fused into glass, with short diagonal glints on the glassy heart like vanilla glass
    glints = set()
    grnd = random.Random(53 + variant * 977)
    for _ in range(grnd.randint(3, 5)):
        while True:
            x, y = grnd.randrange(L), grnd.randrange(L)
            if mark[y][x] == 3:
                break
        for i in range(grnd.randint(2, 3)):
            if 0 <= x + i < L and 0 <= y - i < L and mark[y - i][x + i] >= 2:
                glints.add((x + i, y - i))
    glass = Image.new("RGBA", (L + 2 * M, L + 2 * M), (0, 0, 0, 0))
    specular = Image.new("RGBA", (L + 2 * M, L + 2 * M), (0, 0, 0, 0))
    for y in range(L):
        for x in range(L):
            level = mark[y][x]
            if level < 0:
                continue
            glint = (x, y) in glints
            glass.putpixel((x + M, y + M), GLINT if glint else FUSED[level])
            specular.putpixel((x + M, y + M), GLINT_SPECULAR if glint else FUSED_SPECULAR[level])
    glass.save(f"{out}/lightning_glass_{variant}.png")
    specular.save(f"{out}/lightning_glass_{variant}_s.png")
print("ok")
