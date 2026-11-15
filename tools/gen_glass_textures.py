"""Condensation, frost and outside ice overlay textures for Residuum.

16x16 pixel art in the vanilla manner: a few fixed shades per effect grouped into clean clusters, no per-pixel
noise, and no straight lines: edges wave, fronds curve, drops are round blobs. VARIANTS random variants. One texture per frame mask (bit 1 left, 2 right, 4 bottom, 8 top = that side of
the block is the frame of the whole merged window), so large windows are dense along their border only.
Frost has 4 growth stages per mask; each stage is a superset of the previous one. Outside frost is a separate
seamless 128x128 sheet (ice_sheet_{stage}, 8x8 blocks) sampled by world position, and so is the dust that settles
on windows in deserts (sand_sheet, in greys that the sand's colour tints).
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
# How far frost reaches in from the frame at each growth stage, in pixels: its rime band, and the fronds past it
RIME_BAND = [1.5, 2.3, 3.2, 4.1]
FROND_REACH = [3, 6, 9, 13]

# Palettes (RGBA)
# Mist, densest along the frame of the window and thinnest in its middle
HAZE_RIM, HAZE_MID, HAZE_THIN = (232, 238, 244, 150), (232, 238, 244, 128), (232, 238, 244, 108)
# Droplets are clearer than the mist round them, with a glint at the top and a darker rim at the bottom; round a big
# one the mist is thinner, its water taken into the drop. Beads are the smallest, single bright pixels
DROP_LIGHT, DROP_BODY, DROP_RIM = (255, 255, 255, 235), (214, 225, 236, 64), (178, 194, 212, 160)
BEAD = (248, 251, 255, 178)
HALO = 36
# Frost: bright crystal spines, their barbs, rime in two thicknesses with specks of crystal in it
FROST_SPINE, FROST_BARB = (244, 250, 255, 240), (222, 236, 252, 214)
RIME_THICK, RIME_THIN, RIME_SPECK = (210, 228, 248, 192), (192, 214, 242, 124), (236, 246, 255, 222)
# Outside dust, in greys for the sand's colour to tint, as thick as the deposit at the bottom of a window gets (the film
# over the rest of it is drawn much fainter): nearly even, in a few close shades like the vanilla sand texture, with
# light grains here and there
DUST_SHADES = [222, 233, 243, 252]
DUST_GRAIN = (255, 255, 255, 222)
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


def open_dist(x, y, mask):
    """Distance to the nearest side of the block that is not the frame, where the window runs on into the next block."""
    d = [99]
    if not mask & 1: d.append(x)
    if not mask & 2: d.append(S - 1 - x)
    if not mask & 4: d.append(S - 1 - y)
    if not mask & 8: d.append(y)
    return min(d)


def wobble(rnd, mask, amp):
    """Smooth noise of up to amp pixels either way, so bands along the frame have a wavy edge rather than a straight
    one. It dies away right at the sides where the window runs on, so a band meets the next block's at the same height
    whatever variant and mirroring that block shows."""
    a, b = smooth(rnd, 3, 1), smooth(rnd, 6, 1)
    return [[(0.65 * a[y][x] + 0.35 * b[y][x] - 0.5) * 2 * amp * min(1.0, open_dist(x, y, mask) / 2.5)
             for x in range(S)] for y in range(S)]


def put_drop(img, alpha, x, y, shape):
    """A drop with its top-left at x, y, shape a list of rows of 'L' glint, 'B' clear body, 'R' rim, '.' nothing; round a
    big one the mist thins."""
    cells = {(x + i, y + j): c for j, row in enumerate(shape) for i, c in enumerate(row) if c != "."}
    if len(cells) > 6:
        for cx, cy in cells:
            for nx, ny in ((cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1)):
                if (nx, ny) not in cells and 0 <= nx < S and 0 <= ny < S:
                    alpha[ny][nx] = min(alpha[ny][nx], HAZE_THIN[3] - HALO)
    for (cx, cy), c in cells.items():
        img[(cx, cy)] = {"L": DROP_LIGHT, "B": DROP_BODY, "R": DROP_RIM}[c]


# Drop shapes, each a little round blob lit at the top left and rimmed at the bottom, most of them small; the bigger
# ones sag, and are rare
DROPS = [["LB", "BR"], ["LB", "RR"], ["L", "R"], ["LB", "BB", "RR"], [".L.", "LBB", ".RR"], [".LB.", "LBBB", "BBBR", ".RR."]]
DROP_ODDS = [5, 4, 3, 2, 2, 1]


def condensation(mask, rnd):
    wob = wobble(rnd, mask, 2.2)
    alpha = [[0] * S for _ in range(S)]
    for y in range(S):
        for x in range(S):
            d = edge_dist(x, y, mask) + wob[y][x]
            alpha[y][x] = HAZE_RIM[3] if d < 2 else HAZE_MID[3] if d < 4.5 else HAZE_THIN[3]
    pixels = {}
    # Drops, bigger and more of them low down where the water runs to, never cut by a side of the block
    taken = []
    bottom = mask & 4
    for _ in range(rnd.randint(1, 3)):
        for _ in range(40):
            shape = rnd.choices(DROPS, DROP_ODDS)[0]
            w, h = len(shape[0]), len(shape)
            x = rnd.randrange(1, S - w)
            y = rnd.randrange(1, S - h)
            if bottom and rnd.random() < 0.5:
                y = rnd.randrange(S // 2, S - h)
            if all(abs(x - a) > 3 or abs(y - b) > 3 for a, b in taken):
                taken.append((x, y))
                put_drop(pixels, alpha, x, y, shape)
                break
    # Beads: scattered single pixels, never touching a drop or each other
    for _ in range(rnd.randint(7, 11)):
        for _ in range(40):
            x, y = rnd.randrange(1, S - 1), rnd.randrange(1, S - 1)
            if all((x + i, y + j) not in pixels for i in (-1, 0, 1) for j in (-1, 0, 1)):
                pixels[(x, y)] = BEAD
                break
    img = Image.new("RGBA", (S, S))
    for y in range(S):
        for x in range(S):
            img.putpixel((x, y), pixels.get((x, y), HAZE_RIM[:3] + (alpha[y][x],)))
    return img


def fronds(mask, rnd):
    """Growth time of frost crystal pixels (inf where there are none) and their colours. Fronds like feathers start in
    the rime along the frame and grow inward, each curving its own way: a spine with fine barbs slanting toward its tip,
    by turns to either side, longest near the base, and a side frond or two branching off. Once frost is thick a few
    small fronds start up in the open middle of the glass too, so big windows frost over a little there as well."""
    t = [[float("inf")] * S for _ in range(S)]
    tone = [[None] * S for _ in range(S)]

    def put(x, y, time, colour):
        px, py = int(round(x)), int(round(y))
        if 0 <= px < S and 0 <= py < S and time < t[py][px]:
            t[py][px] = time
            tone[py][px] = colour

    def frond(x, y, angle, bend, time, length, branches):
        side = rnd.choice((-1, 1))
        forks = set(rnd.sample(range(2, max(3, length - 3)), min(branches, max(1, length - 5))))
        for step in range(length):
            put(x, y, time, FROST_SPINE)
            if step > 0:
                bx, by, ba = x, y, angle + side * 0.9
                for k in range(2 if step < length * 0.45 else 1):
                    bx, by = bx + math.cos(ba), by + math.sin(ba)
                    put(bx, by, time + 0.6 + 0.5 * k, FROST_BARB)
                    ba -= side * 0.3
                side = -side
            if step in forks:
                turn = rnd.choice((-1, 1))
                frond(x, y, angle + turn * rnd.uniform(0.6, 0.9), turn * rnd.uniform(0.06, 0.14), time + 1,
                      max(3, (length - step) * 2 // 3), 0)
            x, y = x + math.cos(angle), y + math.sin(angle)
            angle += bend + rnd.uniform(-0.3, 0.3)  # a wavering curve, never a ruler-straight run of pixels
            time += 1

    sides = []
    if mask & 1: sides.append(lambda k: (0.5, k, 0.0))
    if mask & 2: sides.append(lambda k: (S - 1.5, k, math.pi))
    if mask & 4: sides.append(lambda k: (k, S - 1.5, -math.pi / 2))
    if mask & 8: sides.append(lambda k: (k, 0.5, math.pi / 2))
    for start in sides:
        for k in range(2):
            x, y, angle = start(rnd.uniform(2 + 6 * k, 7 + 6 * k))
            frond(x, y, angle + rnd.uniform(-0.6, 0.6), rnd.choice((-1, 1)) * rnd.uniform(0.06, 0.15),
                  rnd.uniform(0, 1.5), FROND_REACH[-1], rnd.randint(1, 2))
    for _ in range(rnd.randint(1, 2)):
        x, y = rnd.uniform(4, S - 5), rnd.uniform(4, S - 5)
        if edge_dist(int(x), int(y), mask) > 5:
            frond(x, y, rnd.uniform(0, 2 * math.pi), rnd.choice((-1, 1)) * rnd.uniform(0.1, 0.2), FROND_REACH[2],
                  rnd.randint(4, 6), 1)
    return t, tone


def frost(mask, stage, crystals, wob, specks):
    t, tone = crystals
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    band = RIME_BAND[stage]
    for y in range(S):
        for x in range(S):
            d = edge_dist(x, y, mask) + wob[y][x]
            if t[y][x] < FROND_REACH[stage]:
                img.putpixel((x, y), tone[y][x])
            elif d < band:
                img.putpixel((x, y), RIME_SPECK if (x, y) in specks else RIME_THICK)
            elif d < band + 1.2:
                img.putpixel((x, y), RIME_THIN)
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


def wrap_noise(rnd, L, cells):
    """Value noise over an L x L sheet on a grid of cells, wrapping round so the sheet tiles."""
    g = [[rnd.random() for _ in range(cells)] for _ in range(cells)]
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


def sand_sheet():
    """Desert dust on the outside of windows, as a seamless 128x128 sheet like the ice: a see-through film of nearly even
    thickness, its shade changing in soft patches as sand's does, with a light grain of sand here and there."""
    L = 128
    rnd = random.Random(7171)
    patches, grain = wrap_noise(rnd, L, L // 16), wrap_noise(rnd, L, L // 4)
    grains = [(rnd.randrange(L), rnd.randrange(L)) for _ in range(L * L // 40)]
    img = Image.new("RGBA", (L, L), (0, 0, 0, 0))
    for y in range(L):
        for x in range(L):
            t = 0.7 * patches[y][x] + 0.3 * grain[y][x]
            shade = DUST_SHADES[min(3, int(t * 4))]
            img.putpixel((x, y), (shade, shade, shade, 200 + int(30 * t)))
    for x, y in grains:
        img.putpixel((x, y), DUST_GRAIN)
    return img


for variant in range(VARIANTS):
    for mask in range(16):
        condensation(mask, random.Random(5 + variant * 1000 + mask * 37)).save(f"{out}/condensation_{variant}_{mask}.png")
        crystals = fronds(mask, random.Random(100 + mask + variant * 1000))
        rnd = random.Random(300 + mask + variant * 1000)
        wob = wobble(rnd, mask, 1.8)
        specks = {(rnd.randrange(S), rnd.randrange(S)) for _ in range(14)}
        for stage in range(4):
            frost(mask, stage, crystals, wob, specks).save(f"{out}/frost_{variant}_{mask}_{stage}.png")
for stage, sheet in enumerate(ice_sheet()):
    sheet.save(f"{out}/ice_sheet_{stage}.png")
sand_sheet().save(f"{out}/sand_sheet.png")
print("ok")
