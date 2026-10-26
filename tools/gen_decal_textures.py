"""Ash, snow splat and lightning scorch textures for Living Glass, in the vanilla manner: a few fixed shades grouped
into clean clusters, and one texture pixel per block pixel (1/16 block).

ash_full_{variant}_{mask}: scorch under or behind a fire, covering nearly the whole face. Mask bits (1 left,
2 right, 4 bottom, 8 top) mark edges that fade out; an edge without its bit continues into the next block's ash.
ash_side_{variant}_{mask}: soot on a block next to a fire, from the bottom up to about half the face. Only the
left/right bits are used; the top always fades out.
snow_{variant}: a snowball splat in a 14x14 square (14/16 of a block), in SNOW_VARIANTS shapes: a round lump lit
from the top left with a few bits of snow thrown off round it.
lightning_scorch_{variant}: a 64x64 burn mark spanning 4x4 blocks where lightning struck, in LIGHTNING_VARIANTS
styles that differ in size, shape and cracks. It darkens the block's own texture like the cracks of a block being
broken: its colour multiplies what is under it twice over, so mid grey leaves it as it is and darker greys char it.
Its alpha is when each pixel goes as the mark fades (pixels go once the fade drops under 0.1 / alpha): the rim first,
the heart last.
lightning_embers_{variant}: the glowing cinders drawn over it while it is fresh.
lightning_glass_{variant}: the same mark where lightning struck sand, melted into glass (fulgurite): the heart a
pale glassy pool with a thin edge, the cracks thin glass veins, with a LabPBR specular map lightning_glass_{variant}_s
that shader packs read through Iris (smooth metal), and lightning_sinter_{variant}: a faint rim of sintered sand.
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
# Sand melted into a glass pool, shaded like a vanilla glass pane: lit side, clear middle and thick side of the
# pool, its bright and dark rim, glints, the veins, the sintered rim round it, and the glass's LabPBR specular values
# (red: smoothness, all mirror-smooth; green: 230 = iron, so it reflects strongly; alpha 255: no glow)
GLASS_LIT, GLASS_CLEAR, GLASS_THICK = (222, 232, 226, 255), (192, 205, 197, 255), (164, 181, 175, 255)
RIM_LIT, RIM_THICK = (240, 246, 242, 255), (126, 134, 118, 255)
GLINT = (250, 253, 251, 255)
VEIN = (196, 206, 198, 255)
SINTER = (110, 104, 92)  # multiplies like the scorch: sand a little darker and browner
# Scorch as a multiplier, from the rim to the heart, and how far into the fade each ring lasts
CHAR = [(100, 96, 92), (76, 72, 68), (52, 49, 46), (30, 28, 26)]
CHAR_LASTS = [0.75, 0.55, 0.35, 0.15]
NEUTRAL = (128, 128, 128, 0)


def charred(colour, lasts):
    """A multiplying pixel that goes once the fade drops under lasts."""
    return colour + (min(255, round(25.5 / max(0.1, min(0.95, lasts)))),)
GLASS_SPECULAR = (255, 230, 0, 255)
VEIN_SPECULAR = (235, 230, 0, 255)


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


# Snowball splats: a round lump, lit from the top left with a shadowed rim at the bottom right, and a few bits
SNOW_VARIANTS = 6
N, M = 14, 16
for variant in range(SNOW_VARIANTS):
    rnd = random.Random(911 + variant * 613)
    c = (N - 1) / 2
    radius = (3.4, 3.8, 4.2, 4.6, 3.6, 4.0)[variant]
    wobble = smooth(rnd, N, 3, 0.35)  # just enough that the edge is not a perfect circle
    img = Image.new("RGBA", (N + 2 * M, N + 2 * M), (0, 0, 0, 0))
    for y in range(N):
        for x in range(N):
            dx, dy = x - c, y - c
            r = math.hypot(dx, dy) + wobble[y][x]
            if r >= radius:
                continue
            lit = -(dx + dy) / (2 * radius)  # about 0.7 at the top left, -0.7 at the bottom right
            if r > radius - 1.1 and dx + dy > 1:
                shade = 3  # shadowed rim
            else:
                shade = 0 if lit > 0.2 else 1 if lit > -0.25 else 2
            img.putpixel((x + M, y + M), SNOW[shade])
    for _ in range(rnd.randint(2, 5)):  # bits thrown off, one or two pixels each
        a, d = rnd.uniform(0, 2 * math.pi), rnd.uniform(radius + 1.3, c)
        x, y = round(c + math.cos(a) * d), round(c + math.sin(a) * d)
        for px, py in [(x, y)] + ([(x + rnd.choice((-1, 1)), y)] if rnd.random() < 0.4 else []):
            if 0 <= px < N and 0 <= py < N:
                img.putpixel((px + M, py + M), SNOW[1 if px + py < N else 2])
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

    spine = set()  # the middle line of every crack, for the thin glass veins on sand

    def crack(x, y, angle, length, w, level):
        for _ in range(length):
            angle += rnd.uniform(-0.35, 0.35)
            x += math.cos(angle)
            y += math.sin(angle)
            if 0 <= int(round(x)) < L and 0 <= int(round(y)) < L:
                spine.add((int(round(x)), int(round(y))))
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
        for i in range(int(heart * 0.8) + 1):  # its line in from the heart, for the veins that reach the small glass pool
            spine.add((int(round(31.5 + math.cos(a) * i)), int(round(31.5 + math.sin(a) * i))))
    for _ in range(specks):  # burnt bits thrown clear of the mark
        a, d = rnd.uniform(0, 2 * math.pi), rnd.uniform(edges[3] + 2, 29)
        sx, sy = int(31.5 + math.cos(a) * d), int(31.5 + math.sin(a) * d)
        bits = [(sx, sy)] + [(sx + rnd.choice((-1, 0, 1)), sy + rnd.choice((-1, 0, 1))) for _ in range(rnd.randint(0, 2))]
        for px, py in bits:
            if 0 <= px < L and 0 <= py < L:
                mark[py][px] = max(mark[py][px], rnd.choice((1, 2)))
    img = Image.new("RGBA", (L + 2 * M, L + 2 * M), NEUTRAL)
    lasting = smooth(random.Random(71 + variant * 977), L, 6, 0.12)  # so pixels go in clumps, not one by one
    embers = Image.new("RGBA", (L + 2 * M, L + 2 * M), (0, 0, 0, 0))
    for y in range(L):
        for x in range(L):
            if mark[y][x] >= 0:
                img.putpixel((x + M, y + M), charred(CHAR[mark[y][x]], CHAR_LASTS[mark[y][x]] + lasting[y][x]))
            # Glowing cinders only in the heart
            r = radius(x, y)
            if r < heart - 1.5 and rnd.random() < 0.3:
                embers.putpixel((x + M, y + M), EMBERS[0 if r < heart * 0.45 else 1 if rnd.random() < 0.6 else 2])
    img.save(f"{out}/lightning_scorch_{variant}.png")
    embers.save(f"{out}/lightning_embers_{variant}.png")

    # The same mark melted into glass: the heart a glass pool shaded like a vanilla pane (lit from the top left, a
    # bright rim there and a dark thick one opposite, short diagonal glints), the cracks thin veins, and a one-pixel
    # rim of sintered sand round the pool
    # A small pool in the middle of the heart, about half its size
    pool = {(x, y) for y in range(L) for x in range(L) if radius(x, y) + burn[y][x] * 0.4 < max(3.5, heart * 0.5)}
    outside = {(x, y) for y in range(L) for x in range(L)} - pool

    def near(x, y, cells):
        return any((x + dx, y + dy) in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))

    cx = sum(x for x, _ in pool) / len(pool)
    cy = sum(y for _, y in pool) / len(pool)
    reach = max(abs(x - cx) + abs(y - cy) for x, y in pool)
    grnd = random.Random(53 + variant * 977)
    inner = sorted(c for c in pool if not near(c[0], c[1], outside) and (c[0] - cx) + (c[1] - cy) < 0)
    glints = set()
    for _ in range(2):
        x, y = grnd.choice(inner)
        for i in range(grnd.randint(2, 4)):
            if (x + i, y - i) in pool and not near(x + i, y - i, outside):
                glints.add((x + i, y - i))
    glass = Image.new("RGBA", (L + 2 * M, L + 2 * M), (0, 0, 0, 0))
    specular = Image.new("RGBA", (L + 2 * M, L + 2 * M), (0, 0, 0, 0))
    sinter = Image.new("RGBA", (L + 2 * M, L + 2 * M), NEUTRAL)
    for y in range(L):
        for x in range(L):
            at = (x + M, y + M)
            if (x, y) in pool:
                slope = ((x - cx) + (y - cy)) / reach  # -1 at the lit top left, 1 at the thick bottom right
                if near(x, y, outside):
                    colour = RIM_LIT if slope < 0 else RIM_THICK
                elif (x, y) in glints:
                    colour = GLINT
                else:
                    colour = GLASS_LIT if slope < -0.4 else GLASS_THICK if slope > 0.4 else GLASS_CLEAR
                glass.putpixel(at, colour)
                specular.putpixel(at, GLASS_SPECULAR)
            elif (x, y) in spine and mark[y][x] >= 1:
                glass.putpixel(at, VEIN)
                specular.putpixel(at, VEIN_SPECULAR)
            elif near(x, y, pool):
                sinter.putpixel(at, charred(SINTER, 0.6))
    glass.save(f"{out}/lightning_glass_{variant}.png")
    specular.save(f"{out}/lightning_glass_{variant}_s.png")
    sinter.save(f"{out}/lightning_sinter_{variant}.png")
print("ok")
