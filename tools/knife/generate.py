#!/usr/bin/env python3
"""Generates the Custom Knife item models, textures and item model definitions.

Every knife is a 16x16 sprite, exactly like a vanilla sword: the same
``minecraft:item/handheld`` parent, the same grip corner, the same display
transforms. That is what makes the knife sit in the hand where the sword was,
instead of floating at a different angle.

Sprites are written as character grids so the shape is reviewable in the source
itself, and every sword material gets its own palette, so a wooden sword keeps
looking wooden and a netherite one keeps looking like netherite.

Run from the repository root:

    python3 tools/knife/generate.py
"""

import json
import os
import re
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "src", "client", "resources", "assets", "capy")
JAVA_MODULE = os.path.join(
    ROOT, "src", "client", "java", "com", "capy", "client", "module", "modules",
    "CustomKnifeModule.java",
)

# --------------------------------------------------------------------- sprites
# Legend:  . transparent   d blade dark   b blade base   l blade light
#          e cutting edge  h handle       m handle highlight   k handle dark
#          g guard/metal   a accent
#
# Every blade runs bottom-left to top-right on the same 45 degree diagonal a
# vanilla sword uses: the handheld display transform grips the sprite at that
# corner, so a blade drawn any other way would sit at the wrong angle in hand.
SPRITES = {
    "butterfly": [
        "..............de",
        ".............dbe",
        "............dbe.",
        "...........dbe..",
        "..........dbe...",
        ".........dbe....",
        "........dbe.....",
        ".......dbe......",
        "......dbe.......",
        ".....dbe........",
        "....gagg........",
        "...mhh.mhh......",
        "..mhh...mhh.....",
        ".mhh.....mhh....",
        "mhh.......mhh...",
        "kk.........kk...",
    ],
    "karambit": [
        "............de..",
        "...........dbee.",
        "..........dbbee.",
        ".........dbbee..",
        "........dbbee...",
        ".......dbbee....",
        "......dbbee.....",
        ".....dbbee......",
        "....dbbee.......",
        "....dbbe........",
        "....aggg........",
        "...mhhhm........",
        "...mh.hm........",
        "...mh.hm........",
        "...mhhhm........",
        "....kkk.........",
    ],
    "cleaver": [
        ".........ddddd..",
        "........dbbbbbe.",
        ".......dbbbbbe..",
        "......dbbbbbe...",
        ".....dbbbbbe....",
        "....dbbbbbe.....",
        "...dbbbbbe......",
        "..dbbbbbe.......",
        "..ddbbbbe.......",
        "...gggggg.......",
        "...mhhh.........",
        "..mhhh..........",
        ".mhhh...........",
        "mhhh............",
        "khh.............",
        "kk..............",
    ],
    "dagger": [
        "...........de...",
        "..........dbe...",
        ".........dbe....",
        "........dbe.....",
        ".......dbe......",
        "......dbe.......",
        ".....dbe........",
        "....dbe.........",
        "...dbe..........",
        "..dbe...........",
        ".gggggg.........",
        "..mhh...........",
        ".mhh............",
        ".mhh............",
        "mhh.............",
        "khh.............",
    ],
}

# -------------------------------------------------------------------- palettes
# (blade base, blade light, blade dark, cutting edge)
MATERIALS = {
    "wooden":    ("#7B5327", "#A6743A", "#4E3317", "#C79A5B"),
    "stone":     ("#7D7D7D", "#9E9E9E", "#4F4F4F", "#C0C0C0"),
    "iron":      ("#B4B4B4", "#D6D6D6", "#7A7A7A", "#F2F2F2"),
    "golden":    ("#D9A521", "#F2CB4E", "#8E6A0C", "#FFE98A"),
    "diamond":   ("#3FBFB4", "#6FE3D8", "#23807A", "#BFFFF7"),
    "netherite": ("#4A4249", "#6A6068", "#2E282F", "#938A98"),
}

HANDLE = "#3B3444"
HANDLE_LIGHT = "#575063"
HANDLE_DARK = "#221D28"
GUARD = "#8C8496"


def rgb(hex_colour):
    hex_colour = hex_colour.lstrip("#")
    return (int(hex_colour[0:2], 16), int(hex_colour[2:4], 16), int(hex_colour[4:6], 16), 255)


def palette(material):
    base, light, dark, edge = MATERIALS[material]
    return {
        ".": (0, 0, 0, 0),
        "b": rgb(base),
        "l": rgb(light),
        "d": rgb(dark),
        "e": rgb(edge),
        "h": rgb(HANDLE),
        "m": rgb(HANDLE_LIGHT),
        "k": rgb(HANDLE_DARK),
        "g": rgb(GUARD),
        # The pivot pin is brass on every material, so it reads as hardware
        # rather than as a gap in the blade.
        "a": rgb("#C9A227"),
    }


def render_sprite(rows, material):
    colours = palette(material)
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        if len(row) != 16:
            raise SystemExit(f"sprite row {y} is {len(row)} wide, expected 16: {row!r}")
        for x, key in enumerate(row):
            if key not in colours:
                raise SystemExit(f"unknown sprite key {key!r} at ({x},{y})")
            image.putpixel((x, y), colours[key])
    return image


def java_constants(name):
    with open(JAVA_MODULE, encoding="utf-8") as handle:
        source = handle.read()
    match = re.search(rf"{name}\s*=\s*\{{([^}}]*)\}}", source)
    if not match:
        raise SystemExit(f"could not find {name} in {JAVA_MODULE}")
    return re.findall(r'"([^"]+)"', match.group(1))


def write_json(path, payload):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")


def main():
    styles = java_constants("STYLES")
    if list(SPRITES) != styles:
        raise SystemExit(f"sprite order {list(SPRITES)} does not match STYLES {styles}")
    materials = java_constants("MATERIALS")
    if list(MATERIALS) != materials:
        raise SystemExit(f"palette order {list(MATERIALS)} does not match MATERIALS {materials}")
    missing = set(styles) - set(SPRITES)
    if missing:
        raise SystemExit(f"no sprite for styles: {sorted(missing)}")

    textures = os.path.join(ASSETS, "textures", "item", "knife")
    models = os.path.join(ASSETS, "models", "item", "knife")
    definitions = os.path.join(ASSETS, "items", "knife")
    os.makedirs(textures, exist_ok=True)

    sheet_cols = len(MATERIALS)
    sheet_rows = len(styles)
    sheet = Image.new("RGBA", (sheet_cols * 20, sheet_rows * 20), (24, 18, 28, 255))

    for row, style in enumerate(styles):
        for col, material in enumerate(MATERIALS):
            name = f"{style}_{material}"
            sprite = render_sprite(SPRITES[style], material)
            sprite.save(os.path.join(textures, name + ".png"))
            sheet.paste(sprite, (col * 20 + 2, row * 20 + 2), sprite)

            write_json(os.path.join(models, name + ".json"), {
                "parent": "minecraft:item/handheld",
                "textures": {"layer0": f"capy:item/knife/{name}"},
            })
            write_json(os.path.join(definitions, name + ".json"), {
                "model": {"type": "minecraft:model", "model": f"capy:item/knife/{name}"},
            })

    out = os.path.join("/tmp", "knife-sheet.png")
    sheet.resize((sheet.width * 8, sheet.height * 8), Image.NEAREST).save(out)
    print(f"{len(styles) * len(MATERIALS)} knives written")
    print(f"{len(styles)} styles x {len(MATERIALS)} materials")
    print(f"contact sheet: {out}")


if __name__ == "__main__":
    sys.exit(main())
