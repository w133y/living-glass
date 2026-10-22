"""Condensation and frost overlay textures for Living Glass.

16x16 pixel art, VARIANTS random variants. One texture per frame mask (bit 1 left, 2 right, 4 bottom, 8 top = that side of the
block is the frame of the whole merged window), so large windows are dense along their border only.
Frost has 4 growth stages per mask; each stage is a superset of the previous one.
"""
import random, sys, os
from PIL import Image

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
S = 16
STAGE_REACH = [3.0, 5.5, 8.5, 12.0]


def edge_dist(x, y, mask):
    d = [99.0]
    if mask & 1: d.append(x)
    if mask & 2: d.append(S - 1 - x)
    if mask & 4: d.append(S - 1 - y)
    if mask & 8: d.append(y)
    return min(d)


VARIANTS = 3


def roll(variant):
    """Per-variant randomness: grain, ragged edges, droplets and beads."""
    global grain, fuzz, drops, beads
    base = random.Random(5 + variant * 1000)
    grain = [[base.randint(-7, 7) for _ in range(S)] for _ in range(S)]
    fuzz = [[base.uniform(0, 1.6) for _ in range(S)] for _ in range(S)]
    # Droplets: clear spots in the fog with a bright highlight pixel
    drops = []
    while len(drops) < 6:
        x, y, big = base.randrange(1, S - 2), base.randrange(1, S - 2), base.random() < 0.5
        if all(abs(x - a) > 2 or abs(y - b) > 2 for a, b, _ in drops):
            drops.append((x, y, big))
    beads = {(base.randrange(S), base.randrange(S)) for _ in range(8)}


def condensation(mask):
    img = Image.new("RGBA", (S, S))
    for y in range(S):
        for x in range(S):
            d = edge_dist(x, y, mask) + fuzz[y][x]
            # Milky haze, denser along the frame in pixel steps
            a = 150 if d < 1.6 else 132 if d < 3.2 else 116 if d < 5 else 100
            img.putpixel((x, y), (238, 242, 247, a + grain[y][x]))
    for x, y in beads:
        img.putpixel((x, y), (252, 253, 255, 185))
    for x, y, big in drops:
        cells = [(x, y), (x + 1, y), (x, y + 1), (x + 1, y + 1)] if big else [(x, y), (x, y + 1)]
        for cx, cy in cells:
            img.putpixel((cx, cy), (225, 232, 240, 35))  # clear body
        img.putpixel((x, y), (255, 255, 255, 235))  # highlight
        lx, ly = cells[-1]
        img.putpixel((lx, ly), (200, 210, 222, 120))  # darker lower rim
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
        for _ in range(3):
            x, y, nx, ny = make(rnd.randrange(2, S - 2))
            side = rnd.choice([-1, 1])
            dx, dy = (nx or side), (ny or side)
            for k in range(rnd.randint(8, 12)):
                if not (0 <= x < S and 0 <= y < S):
                    break
                t[y][x] = min(t[y][x], k * 0.9)
                if k % 2 == 1:  # short barbs off the spine
                    for bx, by in ((x - dy, y + dx), (x + dy, y - dx)):
                        if 0 <= bx < S and 0 <= by < S:
                            t[by][bx] = min(t[by][bx], k * 0.9 + 1.5)
                x += dx
                y += dy
                if rnd.random() < 0.3:
                    if nx: y += rnd.choice([-1, 1])
                    else: x += rnd.choice([-1, 1])
    return t


def frost(mask, stage, fern):
    reach = STAGE_REACH[stage]
    band = reach * 0.55  # the rime band along the frame grows slower than the ferns
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    for y in range(S):
        for x in range(S):
            d = edge_dist(x, y, mask) + fuzz[y][x]
            if fern[y][x] < reach:
                px = (248, 252, 255, 235)  # bright crystal
            elif d < band - 1.2:
                px = (225, 238, 252, 175 if d < 1.2 else 140)
            elif d < band:
                px = (205, 225, 248, 95)  # soft front of the band
            else:
                continue
            img.putpixel((x, y), px)
    return img


for variant in range(VARIANTS):
    roll(variant)
    for mask in range(16):
        condensation(mask).save(f"{out}/condensation_{variant}_{mask}.png")
        fern = ferns(mask, random.Random(100 + mask + variant * 1000))
        for stage in range(4):
            frost(mask, stage, fern).save(f"{out}/frost_{variant}_{mask}_{stage}.png")
print("ok")
