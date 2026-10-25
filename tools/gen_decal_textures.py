"""Ash and snow splat textures for Living Glass. 16x16 pixel art, VARIANTS random variants.

ash_full_{variant}_{mask}: scorch under or behind a fire, covering nearly the whole face. Mask bits (1 left,
2 right, 4 bottom, 8 top) mark edges that fade out; an edge without its bit continues into the next block's ash.
ash_side_{variant}_{mask}: ash on a block next to a fire, from the bottom up to about half the face. Only the
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


def edge_dist(x, y, mask):
    d = [99.0]
    if mask & 1: d.append(x)
    if mask & 2: d.append(S - 1 - x)
    if mask & 4: d.append(S - 1 - y)
    if mask & 8: d.append(y)
    return min(d)


def ash_colour(rnd):
    # Mostly soot, some grey flakes
    return (78, 72, 66) if rnd.random() < 0.12 else (28 + rnd.randint(-6, 6), 24 + rnd.randint(-6, 6), 21 + rnd.randint(-5, 5))


for variant in range(VARIANTS):
    rnd = random.Random(7 + variant * 1000)
    rag = [[rnd.uniform(0, 2.2) for _ in range(S)] for _ in range(S)]
    colour = [[ash_colour(rnd) for _ in range(S)] for _ in range(S)]
    grain = [[rnd.randint(-20, 10) for _ in range(S)] for _ in range(S)]

    for mask in range(16):
        img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        for y in range(S):
            for x in range(S):
                d = edge_dist(x, y, mask) + rag[y][x]
                a = 225 if d > 3.5 else 170 if d > 2.2 else 95 if d > 1.2 else 0
                if a:
                    img.putpixel((x, y), (*colour[y][x], max(0, a + grain[y][x])))
        img.save(f"{out}/ash_full_{variant}_{mask}.png")

    for mask in range(4):
        img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        for y in range(S):
            for x in range(S):
                rise = (S - 1 - y) + rag[y][x] * 1.5  # pixels up from the bottom, ragged
                side = edge_dist(x, y, mask) + rag[y][x]
                if rise > 8.5 or side < 1.2:
                    continue
                a = 220 if rise < 3 else 175 if rise < 5.5 else 110
                if side < 2.4:
                    a = min(a, 110)
                img.putpixel((x, y), (*colour[y][x], max(0, a + grain[y][x])))
        img.save(f"{out}/ash_side_{variant}_{mask}.png")

    # Snow splat: a packed blob with a few flecks thrown out around it
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    for y in range(S):
        for x in range(S):
            r = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5 + rnd.uniform(-1.2, 1.2)
            if r < 3.5:
                img.putpixel((x, y), (246, 249, 255, 235))
            elif r < 5:
                img.putpixel((x, y), (232, 240, 250, 190))
    for _ in range(10):
        x, y = rnd.randrange(S), rnd.randrange(S)
        if ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5 < 7.5:
            img.putpixel((x, y), (240, 245, 252, 170))
    img.save(f"{out}/snow_{variant}.png")
print("ok")
