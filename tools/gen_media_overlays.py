"""Documentation images of the overlay textures (media/overlays), all in one style: one block's worth of texture as the
mod draws it, on a clear background, as its 16x16 original (media/overlays/16x16) and blown up 48 times to 768x768 with
hard pixel edges. Fog and frost are those of a window one block big, framed on every side; of the sheets that span
several blocks (outside ice, desert dust) the block with the most crystals or the first one; dust tinted as in the game.

Usage: gen_media_overlays.py TEXTURES OUT
"""
import os
import sys
from PIL import Image

tex, out = sys.argv[1], sys.argv[2]
os.makedirs(f"{out}/16x16", exist_ok=True)
SAND, RED_SAND = (0xED, 0xE3, 0xC4), (0xBE, 0x66, 0x21)  # Climate.SAND, Climate.RED_SAND


def load(name):
    return Image.open(f"{tex}/{name}.png").convert("RGBA")


def tile(sheet, column, row):
    return sheet.crop((column * 16, row * 16, column * 16 + 16, row * 16 + 16))


def tint(img, rgb):
    out = img.copy()
    out.putdata([(r * rgb[0] // 255, g * rgb[1] // 255, b * rgb[2] // 255, a) for r, g, b, a in img.getdata()])
    return out


def over(*layers):
    img = Image.new("RGBA", (16, 16))
    for layer in layers:
        img.alpha_composite(layer)
    return img


ice = load("ice_sheet_3")
crystal = max(((c, r) for c in range(8) for r in range(8)),
              key=lambda t: sum(a > 200 for *_, a in tile(ice, *t).getdata()))
sand = tile(load("sand_sheet"), 0, 0)
images = {
    "condensation": load("condensation_0_15"),
    **{f"frost_stage_{k + 1}": load(f"frost_0_15_{k}") for k in range(4)},
    "frost": load("frost_0_15_3"),
    "condensation_and_frost": over(load("condensation_0_15"), load("frost_0_15_3")),
    "outside_ice": tile(ice, *crystal),
    "desert_dust": tint(sand, SAND),
    "badlands_dust": tint(sand, RED_SAND),
    "tumbleweed": load("tumbleweed"),
    "tumbleweed_dry_grass": load("tumbleweed_dry"),
}
for name, img in images.items():
    img.save(f"{out}/16x16/{name}.png")
    img.resize((768, 768), Image.NEAREST).save(f"{out}/{name}.png")
print(len(images), "images")
