"""Recolour the wrapped grip once per knife style.

The grip is the one part of a knife that has no sword material behind it: every
style draws the same dark wrap, so six knives with six different metal finishes
all end up holding the same near-black handle. This writes one sheet per style,
derived from the shared sheet rather than redrawn, so a change to the wrap
pattern only has to be made once.

The recolour preserves the sheet's own shading: each texel keeps its place on the
sheet's brightness range and only its hue changes, so the cord's ridges and the
shadow between turns survive. A flat fill would do the opposite - it would erase
the pattern and leave a painted rectangle wrapped around the handle.
"""

import os

from PIL import Image

# Style -> the colour its cord is dyed. Each is chosen against that style's metal
# finish (see GltfRenderer.FINISH) so handle and fittings read as one object:
# ivory against lacquered red, tan against blued steel, indigo against gold.
STYLES = {
    "karambit": (0xE9, 0xDC, 0xC6),   # ivory cord
    "cleaver": (0x9A, 0x6B, 0x3F),    # tan leather
    "dagger": (0x3A, 0x34, 0x46),     # charcoal
    "kukri": (0x3E, 0x6B, 0x45),      # forest green
    "stiletto": (0x25, 0x30, 0x4E),   # midnight navy
    "katana": (0x33, 0x2E, 0x5C),     # indigo tsuka-ito
}

DIR = "src/client/resources/assets/capy/textures/item/knife"
SOURCE = os.path.join(DIR, "grip_wrapped.png")

# How far the target colour is taken at the darkest and at the brightest texel.
# The floor keeps the shadow between turns from going pure black, and the ceiling
# is just over one so the highlight on each ridge still catches the eye.
DARKEST = 0.62
BRIGHTEST = 1.17


def main():
    base = Image.open(SOURCE).convert("RGBA")
    pixels = list(base.getdata())
    luminance = [0.2126 * r + 0.7152 * g + 0.0722 * b for r, g, b, _ in pixels]
    low = min(luminance)
    span = max(1.0, max(luminance) - low)

    for style, (red, green, blue) in STYLES.items():
        recoloured = []
        for (r, g, b, a), lum in zip(pixels, luminance):
            shade = DARKEST + (BRIGHTEST - DARKEST) * (lum - low) / span
            recoloured.append((
                min(255, round(red * shade)),
                min(255, round(green * shade)),
                min(255, round(blue * shade)),
                a,
            ))
        out = Image.new("RGBA", base.size)
        out.putdata(recoloured)
        path = os.path.join(DIR, "grip_{}.png".format(style))
        out.save(path)
        print(path)


if __name__ == "__main__":
    main()
