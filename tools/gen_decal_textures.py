"""Ash, snow splat and lightning scorch textures for Residuum, in the vanilla manner: a few fixed shades grouped
into clean clusters, and one texture pixel per block pixel (1/16 block).

ash_sheet: soot left by fire, one seamless 64x64 sheet (4x4 blocks) that burnt faces take by their place in the
world, dark soot in a few crisp steps of opacity; how strong it shows is worked out per pixel when it is drawn.
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

# Soot, from thin to thick
ASH_SOOT = [(40, 36, 33, 125), (32, 29, 26, 165), (26, 23, 21, 200), (20, 18, 16, 228)]
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


# Ash: one seamless 64x64 sheet of soot (4x4 blocks) that every burnt face takes by its place in the world, so the
# pattern runs on across block edges; how dark it shows where is worked out per pixel when it is drawn
L = 64
rnd = random.Random(7)


def wrapped(cells, amp):
    """Clustered noise over the sheet that wraps round, so the sheet tiles."""
    g = [[rnd.uniform(-amp, amp) for _ in range(cells)] for _ in range(cells)]
    n = [[0.0] * L for _ in range(L)]
    for y in range(L):
        for x in range(L):
            gx, gy = x / L * cells, y / L * cells
            ix, iy, fx, fy = int(gx), int(gy), gx - int(gx), gy - int(gy)
            fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
            a, b = g[iy][ix], g[iy][(ix + 1) % cells]
            c, d = g[(iy + 1) % cells][ix], g[(iy + 1) % cells][(ix + 1) % cells]
            n[y][x] = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    return n


coarse, fine = wrapped(8, 1.0), wrapped(16, 0.5)
img = Image.new("RGBA", (L, L), (0, 0, 0, 0))
for y in range(L):
    for x in range(L):
        t = coarse[y][x] + fine[y][x]
        level = 0 if t < -0.55 else 1 if t < 0 else 2 if t < 0.5 else 3
        img.putpixel((x, y), ASH_SOOT[level])
img.save(f"{out}/ash_sheet.png")


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

# Tumbleweeds: balls of dry twigs or pale dry grass seen side on, 16x16, see-through between them; the mod crosses three
# of these at right angles and rolls them. Every twig curves: round the rim, and in long arcs across the middle, with a
# short side twig curling off here and there. Lit from the top left, as a ball: a tone lighter there, darker below right.
TWIG = [(198, 160, 104, 255), (176, 138, 84, 255), (146, 109, 63, 255), (116, 84, 47, 255), (90, 64, 36, 255)]
DRY_GRASS = [(236, 228, 194, 255), (226, 216, 176, 255), (209, 186, 138, 255), (186, 156, 104, 255),
             (163, 125, 72, 255)]  # short dry grass


def tumbleweed(seed, tones, rim, across):
    rnd = random.Random(seed)
    weed = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    c = 7.5

    def put(x, y, tone):
        x, y = int(round(x)), int(round(y))
        if 0 <= x < 16 and 0 <= y < 16 and math.hypot(x - c, y - c) <= 7.6:
            lit = round(((x - c) + (y - c)) / 9)
            weed.putpixel((x, y), tones[max(0, min(len(tones) - 1, tone + lit))])

    def curl(x, y, a, tone):
        # A side twig: two or three pixels, curling as it goes
        turn = rnd.choice((-1, 1)) * 0.5
        for _ in range(rnd.randint(2, 3)):
            x, y, a = x + math.cos(a), y + math.sin(a), a + turn
            put(x, y, tone)

    for _ in range(rim):
        a0, r = rnd.uniform(0, 2 * math.pi), rnd.uniform(4.6, 7.3)
        sweep, bend = rnd.choice((-1, 1)) * rnd.uniform(0.8, 1.7), rnd.uniform(-1.4, 1.4)
        tone = rnd.randrange(1, len(tones) - 1)
        for k in range(48):
            f = k / 47
            a = a0 + sweep * f
            rr = r + bend * math.sin(math.pi * f)
            put(c + math.cos(a) * rr, c + math.sin(a) * rr, tone)
            if k in (16, 32) and rnd.random() < 0.5:
                curl(c + math.cos(a) * rr, c + math.sin(a) * rr, a + math.copysign(math.pi / 2, sweep) * rnd.choice((-1, 1)), tone)
    for _ in range(across):
        # An arc of a big circle whose middle is off the ball, so it bows across the ball's middle
        side, far = rnd.uniform(0, 2 * math.pi), rnd.uniform(9, 15)
        ox, oy = c + math.cos(side) * far, c + math.sin(side) * far
        r = far + rnd.uniform(-3, 3)
        tone = rnd.randrange(1, len(tones) - 1)
        for k in range(90):
            a = side + math.pi + (k / 89 - 0.5) * 1.8
            put(ox + math.cos(a) * r, oy + math.sin(a) * r, tone)
    return weed


# Axe gashes on a shield, laid over its plate in the same 46px sheet as the snow splats (a 14px mark in a 16px clear
# margin): a slanting cut into the wood, dark down its middle, with splintered pale wood along its edges
GASH_DEEP, GASH_DARK, GASH_RAW = (38, 26, 16, 235), (78, 54, 32, 225), (196, 160, 110, 200)
for variant in range(3):
    rnd = random.Random(4040 + variant * 77)
    img = Image.new("RGBA", (N + 2 * M, N + 2 * M), (0, 0, 0, 0))
    length, slant = rnd.randint(9, 12), rnd.uniform(0.55, 0.95)
    x0, y0 = (N - length * 0.8) / 2, (N - length * slant * 0.8) / 2
    for k in range(length):
        x, y = x0 + k * 0.8, y0 + k * 0.8 * slant
        deep = 0 < k < length - 1  # thin at both ends
        cx, cy = int(round(x)), int(round(y))
        for px, py, tone in ((cx, cy, GASH_DEEP if deep else GASH_DARK), (cx, cy - 1, GASH_RAW), (cx, cy + 1, GASH_DARK)):
            if 0 <= px < N and 0 <= py < N and (tone != GASH_RAW or rnd.random() < 0.8):
                if img.getpixel((px + M, py + M))[3] == 0 or tone == GASH_DEEP:
                    img.putpixel((px + M, py + M), tone)
        if deep and rnd.random() < 0.25:  # a splinter sticking out
            sx, sy = cx + rnd.choice((-1, 1)), cy + rnd.choice((-2, 2))
            if 0 <= sx < N and 0 <= sy < N:
                img.putpixel((sx + M, sy + M), GASH_RAW)
    img.save(f"{out}/shield_scratch_{variant}.png")

tumbleweed(5150, TWIG, 10, 3).save(f"{out}/tumbleweed.png")
tumbleweed(6262, DRY_GRASS, 12, 3).save(f"{out}/tumbleweed_dry.png")

# Cracks in glass where an arrow or trident struck it, in a 52px sheet (a web 20px across in a 16px clear margin):
# a chipped spot where it hit, cracks running out from it that wander and fork, and broken rings between them.
# Pale where they catch the light, with a faint dark edge so they read against a bright sky too.
CRACK_CHIP, CRACK_LIT, CRACK_PALE = (255, 255, 255, 250), (244, 249, 252, 230), (210, 224, 234, 175)
CRACK_EDGE = (52, 62, 74, 80)
CRACK_N, CRACK_M = 20, 16
for variant in range(4):
    rnd = random.Random(7070 + variant * 131)
    size = CRACK_N + 2 * CRACK_M
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    c = size / 2 - 0.5
    reach = CRACK_N / 2 + 0.5

    def put(x, y, tone, over):
        px, py = int(round(x)), int(round(y))
        if (px - c) ** 2 + (py - c) ** 2 <= reach * reach and (over or img.getpixel((px, py))[3] == 0):
            img.putpixel((px, py), tone)

    def crack(x, y, a, length, depth):
        step, turning = 0.0, 0.0
        pts = [(int(round(x)), int(round(y)))]
        while step < length:
            # It swings one way then the other, and turns aside wherever three pixels would line up
            turning = turning * 0.6 + rnd.uniform(-0.35, 0.35)
            a += turning
            nx, ny = x + math.cos(a), y + math.sin(a)
            p = (int(round(nx)), int(round(ny)))
            if p == pts[-1]:
                x, y = nx, ny
                continue
            if len(pts) >= 2 and (p[0] - pts[-1][0], p[1] - pts[-1][1]) == (pts[-1][0] - pts[-2][0], pts[-1][1] - pts[-2][1]):
                a += rnd.choice((-1, 1)) * 0.75
                continue
            x, y = nx, ny
            pts.append(p)
            put(x, y, CRACK_LIT if step < length * 0.55 else CRACK_PALE, True)
            if rnd.random() < 0.3:
                put(x - math.sin(a), y + math.cos(a), CRACK_EDGE, False)
            if depth < 2 and rnd.random() < 0.12:
                crack(x, y, a + rnd.choice((-1, 1)) * rnd.uniform(0.6, 1.2), (length - step) * 0.6, depth + 1)
            step += 1

    rays = rnd.randint(5, 7)
    turn = rnd.uniform(0, 2 * math.pi)
    for k in range(rays):
        crack(c, c, turn + k * 2 * math.pi / rays + rnd.uniform(-0.4, 0.4), rnd.uniform(6, 10), 0)
    for ring in (rnd.uniform(3, 4.2), rnd.uniform(6, 7.5)):
        a = rnd.uniform(0, 2 * math.pi)
        for _ in range(rnd.randint(3, 5)):
            span, r = rnd.uniform(0.4, 0.9), ring
            t = a
            while t < a + span:
                r += rnd.uniform(-0.35, 0.35)
                put(c + math.cos(t) * r, c + math.sin(t) * r, CRACK_PALE, False)
                t += 0.7 / r
            a += span + rnd.uniform(0.3, 0.8)
    for dx, dy in [(0, 0), (1, 0), (0, 1), (1, 1)] + [(rnd.randint(-1, 2), rnd.randint(-1, 2)) for _ in range(3)]:
        put(c - 0.5 + dx, c - 0.5 + dy, CRACK_CHIP, True)
    img.save(f"{out}/crack_{variant}.png")

# Footprints pressed into snow and sand, blocky as the feet that made them, in a 40px sheet (an 8px print in a 16px
# clear margin), the toe at the top: print_N for N = surface * 3 + kind, surface 0 snow and 1 sand, kind 0 a left
# boot, 1 a right boot (each 4px across and 5 long, as a leg is, with a notch at the inner heel), 2 a paw (a square pad
# and three toes). The game lays them on the block's pixels by quarter turns. The hollow is a shade of the ground in
# its own shadow, darker along the edges the light comes over, where snow has crumbled in a pixel here and there.
PRINT_N, PRINT_M = 8, 16
PRINT_TONES = [((150, 172, 204, 105), (112, 134, 172, 155)), ((122, 96, 62, 85), (94, 72, 44, 130))]
BOOT = ["####",
        "####",
        "####",
        "####",
        "###."]
PAW = ["#.#.#",
       ".....",
       ".###.",
       ".###.",
       ".###."]
for surface, (hollow, shade) in enumerate(PRINT_TONES):
    for kind in range(3):
        rows = PAW if kind == 2 else [r[::-1] for r in BOOT] if kind == 1 else BOOT
        rnd = random.Random(9090 + surface * 101 + kind * 7)
        size = PRINT_N + 2 * PRINT_M
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        w, h = len(rows[0]), len(rows)
        x0, y0 = size // 2 - w // 2, size // 2 - (h + 1) // 2

        def inside(i, j):
            return 0 <= j < h and 0 <= i < w and rows[j][i] == "#"

        for j in range(h):
            for i in range(w):
                if inside(i, j):
                    # In shadow under the edge the light comes over (top and left), now and then not quite to it
                    shadowed = not inside(i, j - 1) or not inside(i - 1, j)
                    if shadowed and surface == 0 and rnd.random() < 0.2:
                        shadowed = False
                    img.putpixel((x0 + i, y0 + j), shade if shadowed else hollow)
        img.save(f"{out}/print_{surface * 3 + kind}.png")


# Coats that grow over whole faces, one seamless 64x64 sheet each (4x4 blocks) laid on by place in the world like the
# ash: hoarfrost on the tops of grass and leaves (rime_sheet), and snow driven onto walls (wall_snow_sheet). Alpha is
# when each pixel comes in as the coat grows: it shows once the coat's strength reaches 0.1 / alpha, so the thickest
# pixels (opaque) come first and the thinnest edge last, faint; clear pixels never come.
def growth(alpha_at):
    """The alpha that has a pixel come in at strength at (0..1], or 0 for one that never does."""
    return 0 if alpha_at > 1 else min(255, round(25.5 / max(0.1, alpha_at)))


def save_coat(img, name):
    """A coat's sheet for the settings preview, which shows it grown as far as its alpha lets it: in the game a coat
    grows by the noise of the world instead (DecalRenderer.coatShape), so it never repeats."""
    img.save(f"{out}/{name}.png")


def ranked(values):
    """Each value's rank among them, 0 for the lowest to 1 for the highest."""
    order = sorted(range(len(values)), key=values.__getitem__)
    rank = [0.0] * len(values)
    for k, i in enumerate(order):
        rank[i] = k / (len(values) - 1)
    return rank


def comes_in(rank, stops):
    """When a pixel of this rank comes in, from (rank, strength) stops: the highest ranks first, below the first stop never."""
    if rank < stops[0][0]:
        return 2
    for (r0, g0), (r1, g1) in zip(stops, stops[1:]):
        if rank <= r1:
            return g0 + (g1 - g0) * (rank - r0) / (r1 - r0)
    return stops[-1][1]


RIME = [(240, 247, 255), (222, 234, 248), (204, 220, 240)]
rnd = random.Random(1212)
coarse, fine = wrapped(4, 1.0), wrapped(16, 0.6)
# Crystals in clusters: a grainy speckle that thickens where the coarse noise is high; grown, three fifths of the face
t = [coarse[y][x] + fine[y][x] + rnd.uniform(-0.45, 0.45) for y in range(L) for x in range(L)]
rank = ranked(t)
img = Image.new("RGBA", (L, L), (0, 0, 0, 0))
for k, r in enumerate(rank):
    a = growth(comes_in(r, [(0.4, 1.0), (0.7, 0.22), (0.92, 0.1), (1.0, 0.06)]))
    if a:
        img.putpixel((k % L, k // L), RIME[0 if r > 0.85 else 1 if r > 0.6 else 2] + (a,))
save_coat(img, "rime_sheet")

WALL_SNOW = [(250, 252, 255), (232, 240, 248), (210, 222, 236)]
rnd = random.Random(3434)
coarse, fine = wrapped(6, 1.0), wrapped(16, 0.45)
# Blotches that cake on and spread, ragged at their edges; grown, half the face, most of that thick
t = [coarse[y][x] + fine[y][x] + rnd.uniform(-0.15, 0.15) for y in range(L) for x in range(L)]
rank = ranked(t)
img = Image.new("RGBA", (L, L), (0, 0, 0, 0))
for k, r in enumerate(rank):
    a = growth(comes_in(r, [(0.5, 1.0), (0.62, 0.2), (0.8, 0.1), (1.0, 0.05)]))
    if a:
        img.putpixel((k % L, k // L), WALL_SNOW[0 if r > 0.8 else 1 if r > 0.6 else 2] + (a,))
save_coat(img, "wall_snow_sheet")


# The hole an arrow leaves in a block, in a 36px sheet (a 4px mark in a 16px clear margin). It multiplies the block's
# own texture like the burn marks: a dark pit a couple of pixels across where the shaft went in, a few pixels round it
# darkened and chipped; alpha is when each pixel goes as the mark fades, the pit last.
HOLE_PIT, HOLE_CHIP, HOLE_NEUTRAL = (34, 30, 28), (92, 88, 84), (128, 128, 128, 0)
for variant in range(3):
    rnd = random.Random(5656 + variant * 89)
    size = 4 + 2 * 16
    img = Image.new("RGBA", (size, size), HOLE_NEUTRAL)
    c = size // 2 - 1
    pit = [(0, 0), (1, 0), (0, 1), (1, 1)][:4 - (variant == 2)]
    for dx, dy in pit:
        img.putpixel((c + dx, c + dy), HOLE_PIT + (255,))
    for _ in range(rnd.randint(3, 5)):
        dx, dy = rnd.choice([(-1, 0), (2, 0), (0, -1), (0, 2), (-1, 1), (2, 1), (1, -1), (1, 2), (-1, -1), (2, 2)])
        if img.getpixel((c + dx, c + dy))[3] == 0:
            img.putpixel((c + dx, c + dy), HOLE_CHIP + (rnd.choice((90, 120, 150)),))
    img.save(f"{out}/arrow_hole_{variant}.png")
print("ok")
