"""Condensation, frost and outside ice overlay textures for Living Glass.

16x16 pixel art in the vanilla manner: a few fixed shades per effect grouped into clean clusters, no per-pixel
noise. VARIANTS random variants. One texture per frame mask (bit 1 left, 2 right, 4 bottom, 8 top = that side of
the block is the frame of the whole merged window), so large windows are dense along their border only.
Frost and ice have 4 growth stages per mask; each stage is a superset of the previous one.
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
FROST_REACH = [3, 5, 8, 11]
ICE_REACH = [4, 7, 10, 13]

# Palettes (RGBA)
HAZE = [(232, 238, 244, 150), (232, 238, 244, 128), (232, 238, 244, 108)]
DROP_LIGHT, DROP_BODY, DROP_SHADOW = (255, 255, 255, 235), (214, 225, 236, 70), (182, 197, 212, 150)
FROST_CRYSTAL, FROST_RIME, FROST_THIN = (240, 248, 255, 235), (206, 226, 248, 190), (190, 214, 244, 125)
# Outside ice: nearly white with grey-blue shading, and a bank of snow along the bottom
SNOW_TOP, SNOW_BANK = (250, 252, 255, 255), (228, 235, 242, 255)
FEATHER_SPINE, FEATHER, FEATHER_SHADE = (242, 247, 252, 235), (216, 227, 238, 215), (190, 205, 221, 200)
ICE_HAZE, ICE_RIM = (222, 231, 240, 60), (214, 226, 238, 140)


def smooth(rnd, cells, amp):
    """Value noise on a coarse grid, blended smoothly: gives clustered, blotchy variation instead of salt and pepper."""
    g = [[rnd.uniform(0, amp) for _ in range(cells + 1)] for _ in range(cells + 1)]
    n = [[0.0] * S for _ in range(S)]
    for y in range(S):
        for x in range(S):
            gx, gy = x / (S - 1) * cells, y / (S - 1) * cells
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


def condensation(mask, rag, drops, beads):
    img = Image.new("RGBA", (S, S))
    for y in range(S):
        for x in range(S):
            d = edge_dist(x, y, mask) + rag[y][x]
            img.putpixel((x, y), HAZE[0] if d < 2 else HAZE[1] if d < 4 else HAZE[2])
    for x, y in beads:
        img.putpixel((x, y), (248, 250, 255, 190))
    for x, y in drops:
        # Droplet: clear body, bright top-left, darker bottom-right
        for cx, cy in ((x, y), (x + 1, y), (x, y + 1), (x + 1, y + 1)):
            img.putpixel((cx, cy), DROP_BODY)
        img.putpixel((x, y), DROP_LIGHT)
        img.putpixel((x + 1, y + 1), DROP_SHADOW)
    return img


def ferns(mask, rnd):
    """Growth time of fern pixels (inf where there is no fern): spines run diagonally in from the frame."""
    t = [[float("inf")] * S for _ in range(S)]
    starts = []
    if mask & 1: starts.append(lambda k: (0, k, 1, 0))
    if mask & 2: starts.append(lambda k: (S - 1, k, -1, 0))
    if mask & 4: starts.append(lambda k: (k, S - 1, 0, -1))
    if mask & 8: starts.append(lambda k: (k, 0, 0, 1))
    for make in starts:
        for _ in range(2):
            x, y, nx, ny = make(rnd.randrange(3, S - 3))
            side = rnd.choice([-1, 1])
            dx, dy = (nx or side), (ny or side)
            for k in range(rnd.randint(6, 9)):
                if not (0 <= x < S and 0 <= y < S):
                    break
                t[y][x] = min(t[y][x], k)
                if k % 2 == 1:  # one barb either side every other step
                    for bx, by in ((x - dy, y + dx), (x + dy, y - dx)):
                        if 0 <= bx < S and 0 <= by < S:
                            t[by][bx] = min(t[by][bx], k + 1.5)
                x += dx
                y += dy
    return t


def frost(mask, stage, fern, rag):
    reach = FROST_REACH[stage]
    band = reach * 0.55  # the rime band along the frame grows slower than the ferns
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    for y in range(S):
        for x in range(S):
            d = edge_dist(x, y, mask) + rag[y][x]
            if fern[y][x] < reach:
                img.putpixel((x, y), FROST_CRYSTAL)
            elif d < band - 1:
                img.putpixel((x, y), FROST_RIME)
            elif d < band:
                img.putpixel((x, y), FROST_THIN)
    return img


def feathers(rnd):
    """Feathery frost like on a real window: a few curved spines rising from the bottom with dense barbs slanting up
    on both sides. Returns (height, kind) grids: how high above the bottom each pixel sits (it appears once the ice
    has grown that high) and whether it is a spine (2) or a barb (1)."""
    height = [[float("inf")] * S for _ in range(S)]
    kind = [[0] * S for _ in range(S)]

    def mark(x, y, k):
        px, py = int(round(x)), int(round(y))
        if 0 <= px < S and 0 <= py < S:
            height[py][px] = min(height[py][px], S - 1 - py)
            kind[py][px] = max(kind[py][px], k)

    starts = [rnd.uniform(2.5, 5.5), rnd.uniform(10.5, 13.5)] + ([rnd.uniform(7, 9)] if rnd.random() < 0.6 else [])
    for x in starts:
        y = S - 2.0
        # Lean away from the middle of the block, so feathers fan out
        angle = -math.pi / 2 + (0.35 if x > 8 else -0.35) * rnd.uniform(0.4, 1.0)
        bend = rnd.uniform(-0.06, 0.06)
        length = rnd.randint(10, 13)
        for step in range(length):
            mark(x, y, 2)
            # Barbs every other step, longer low down, so the feather narrows to its tip with gaps between barbs
            barb = 0 if step % 2 else 2 + (length - step) // 5
            for side in (-1, 1):
                bx, by, ba = x, y, angle + side * 0.7
                for _ in range(barb):
                    bx += math.cos(ba)
                    by += math.sin(ba)
                    mark(bx, by, 1)
            x += math.cos(angle)
            y += math.sin(angle)
            angle += bend
    return height, kind


def ice(mask, stage, wave, feather):
    """Outside ice: pale, nearly white feathery frost growing up from the bottom frame, on a little bank of snow.
    The other frame edges only get a thin rim once it has grown."""
    reach = ICE_REACH[stage]
    height, kind = feather
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    for y in range(S):
        for x in range(S):
            up = S - 1 - y
            if mask & 4:
                snow = 1 + (wave[x] if stage >= 1 else 0)
                if up < snow:
                    img.putpixel((x, y), SNOW_TOP if up == snow - 1 else SNOW_BANK)
                    continue
                if height[y][x] < reach:
                    img.putpixel((x, y), FEATHER_SPINE if kind[y][x] == 2 else FEATHER_SHADE if (x + y) % 3 == 0 else FEATHER)
                    continue
                if up < reach * 0.3:
                    img.putpixel((x, y), ICE_HAZE)  # thin rime between the feathers near the bottom
                    continue
            if stage >= 2 and ((mask & 1 and x == 0) or (mask & 2 and x == S - 1) or (mask & 8 and y == 0)):
                img.putpixel((x, y), ICE_RIM)
    return img


for variant in range(VARIANTS):
    rnd = random.Random(5 + variant * 1000)
    # Edge raggedness in whole pixels, clustered
    rag = [[round(v) for v in row] for row in smooth(rnd, 3, 1.6)]
    drops = []
    while len(drops) < 4:
        x, y = rnd.randrange(1, S - 3), rnd.randrange(1, S - 3)
        if all(abs(x - a) > 3 or abs(y - b) > 3 for a, b in drops):
            drops.append((x, y))
    beads = {(rnd.randrange(S), rnd.randrange(S)) for _ in range(5)}
    # Snow bank top: a wave that is zero at both block edges, so neighbouring blocks of a window meet without a step
    k = rnd.choice([1, 2, 3])
    wave = [max(0, round(2.2 * math.sin(math.pi * k * x / S))) for x in range(S)]
    feather = feathers(random.Random(300 + variant * 1000))
    for mask in range(16):
        condensation(mask, rag, drops, beads).save(f"{out}/condensation_{variant}_{mask}.png")
        fern = ferns(mask, random.Random(100 + mask + variant * 1000))
        for stage in range(4):
            frost(mask, stage, fern, rag).save(f"{out}/frost_{variant}_{mask}_{stage}.png")
            ice(mask, stage, wave, feather).save(f"{out}/ice_{variant}_{mask}_{stage}.png")
print("ok")
