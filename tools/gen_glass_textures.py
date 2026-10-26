"""Condensation, frost and outside ice overlay textures for Living Glass.

16x16 pixel art in the vanilla manner: a few fixed shades per effect grouped into clean clusters, no per-pixel
noise. VARIANTS random variants. One texture per frame mask (bit 1 left, 2 right, 4 bottom, 8 top = that side of
the block is the frame of the whole merged window), so large windows are dense along their border only.
Frost has 4 growth stages per mask; each stage is a superset of the previous one. Outside frost is a separate
seamless 128x128 sheet (ice_sheet_{stage}, 8x8 blocks) sampled by world position.
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

# Palettes (RGBA)
HAZE = [(232, 238, 244, 150), (232, 238, 244, 128), (232, 238, 244, 108)]
DROP_LIGHT, DROP_BODY, DROP_SHADOW = (255, 255, 255, 235), (214, 225, 236, 70), (182, 197, 212, 150)
FROST_CRYSTAL, FROST_RIME, FROST_THIN = (240, 248, 255, 235), (206, 226, 248, 190), (190, 214, 244, 125)
# Outside frost: cool whites with a little grey-blue in the barbs, over a thin grainy rime
FEATHER_SPINE, FEATHER, FEATHER_TIP = (246, 249, 252, 240), (214, 226, 238, 220), (232, 240, 247, 230)
RIME, RIME_LIGHT = (205, 218, 232, 95), (230, 238, 246, 120)


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


def ice_sheet():
    """Outside frost as one seamless 128x128 sheet (8x8 blocks, so big windows show no repeat) that windows sample by world position, so feathers run
    across block edges like on a real pane: long curved fronds with fine barbs over a grainy rime. Returns one image
    per growth stage; each stage adds feathers to the previous one and thickens the rime."""
    L = 128
    rnd = random.Random(4242)
    # Rime in soft patches (clustered noise over the whole sheet, wrapping round so it tiles)
    cells = L // 8
    g = [[rnd.random() for _ in range(cells)] for _ in range(cells)]
    grain = [[0.0] * L for _ in range(L)]
    for y in range(L):
        for x in range(L):
            gx, gy = x / L * cells, y / L * cells
            ix, iy, fx, fy = int(gx), int(gy), gx - int(gx), gy - int(gy)
            fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
            a, b = g[iy][ix], g[iy][(ix + 1) % cells]
            c, d = g[(iy + 1) % cells][ix], g[(iy + 1) % cells][(ix + 1) % cells]
            grain[y][x] = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    order = [[float("inf")] * L for _ in range(L)]   # stage in which a feather pixel appears
    tone = [[None] * L for _ in range(L)]

    def put(x, y, colour, stage):
        px, py = int(round(x)) % L, int(round(y)) % L  # wrap round, so the sheet tiles
        if stage <= order[py][px]:
            order[py][px] = stage
            tone[py][px] = colour

    count = 104  # same density as before over four times the area
    for i in range(count):
        stage = min(3, i * 4 // count)
        x, y = rnd.uniform(0, L), rnd.uniform(0, L)
        angle = -math.pi / 2 + rnd.uniform(-1.1, 1.1)   # fronds mostly reach upward
        bend = rnd.uniform(-0.05, 0.05)
        length = rnd.randint(14, 30)
        reach = rnd.randint(3, 6)
        both = rnd.random() < 0.65
        for step in range(length):
            put(x, y, FEATHER_SPINE, stage)
            if step % 3 == 0:  # barbs spaced out, so they read as fine lines
                barb = max(1, round(reach * (1 - step / length)) + 1)
                for side in ((-1, 1) if both else (rnd.choice((-1, 1)),)):
                    bx, by, ba = x, y, angle + side * 0.65
                    for k in range(barb):
                        bx += math.cos(ba)
                        by += math.sin(ba)
                        put(bx, by, FEATHER if k < barb - 1 else FEATHER_TIP, stage)
            x += math.cos(angle)
            y += math.sin(angle)
            angle += bend
    sheets = []
    for stage in range(4):
        img = Image.new("RGBA", (L, L), (0, 0, 0, 0))
        rime = 0.3 + 0.12 * stage
        for y in range(L):
            for x in range(L):
                if order[y][x] <= stage:
                    img.putpixel((x, y), tone[y][x])
                elif grain[y][x] < rime:
                    img.putpixel((x, y), RIME_LIGHT if grain[y][x] < rime - 0.12 else RIME)
        sheets.append(img)
    return sheets


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
    for mask in range(16):
        condensation(mask, rag, drops, beads).save(f"{out}/condensation_{variant}_{mask}.png")
        fern = ferns(mask, random.Random(100 + mask + variant * 1000))
        for stage in range(4):
            frost(mask, stage, fern, rag).save(f"{out}/frost_{variant}_{mask}_{stage}.png")
for stage, sheet in enumerate(ice_sheet()):
    sheet.save(f"{out}/ice_sheet_{stage}.png")
print("ok")
