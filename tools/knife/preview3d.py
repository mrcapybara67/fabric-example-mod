#!/usr/bin/env python3
"""Renders the generated knife models offline so the geometry can be inspected.

This is a small, deliberate reimplementation of the parts of the vanilla model
pipeline that matter for judging the result: cube elements, Euler Z/Y/X
rotations about an origin, the six faces of a cube, and their uv mapping.  It
reads the *generated* files, so it checks the assets that actually ship rather
than the data they were generated from.

It is a development tool.  It is not shipped in the jar and the mod never calls
it.

    python3 tools/knife/preview3d.py            # every style, every material
    python3 tools/knife/preview3d.py dagger     # one style
"""

import json
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "src", "client", "resources", "assets", "capy")
MODELS = os.path.join(ASSETS, "models", "item", "knife")
OUT = "/tmp/knife3d"

MATERIALS = ["wooden", "stone", "iron", "golden", "diamond", "netherite"]
STYLES = ["butterfly", "karambit", "cleaver", "dagger", "tanto", "kukri", "machete", "stiletto"]

LIGHT = (-0.42, 0.68, 0.60)


# ------------------------------------------------------------------ model data
def load(name):
    with open(os.path.join(MODELS, name + ".json"), encoding="utf-8") as handle:
        raw = json.load(handle)
    textures = {}
    for slot, path in raw["textures"].items():
        textures[slot] = os.path.join(ASSETS, "textures", path.split(":", 1)[1] + ".png")
    return raw["elements"], textures


def norm(vector):
    length = math.sqrt(sum(v * v for v in vector)) or 1.0
    return tuple(v / length for v in vector)


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def mat_vec(m, v):
    x, y, z = v
    return (
        m[0][0] * x + m[0][1] * y + m[0][2] * z + m[0][3],
        m[1][0] * x + m[1][1] * y + m[1][2] * z + m[1][3],
        m[2][0] * x + m[2][1] * y + m[2][2] * z + m[2][3],
    )


def translation(t):
    return [[1, 0, 0, t[0]], [0, 1, 0, t[1]], [0, 0, 1, t[2]], [0, 0, 0, 1]]


def rot_z(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]


def rot_y(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]]


def rot_x(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]]


# Vanilla: rotationZYX(z, y, x) == Rz * Ry * Rx.
def element_matrix(rotation):
    origin = rotation["origin"]
    rz = rot_z(rotation.get("z", 0.0))
    ry = rot_y(rotation.get("y", 0.0))
    rx = rot_x(rotation.get("x", 0.0))
    rot = mat_mul(mat_mul(rz, ry), rx)
    # origin is in 16-unit model space; the renderer works in that space too.
    return mat_mul(mat_mul(translation(origin), rot), translation(tuple(-v for v in origin)))


FACES = {
    # direction: (four corners as (x,y,z) selectors, in winding order)
    "north": ((1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)),
    "south": ((0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)),
    "west": ((0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)),
    "east": ((1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)),
    "up": ((0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0)),
    "down": ((0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)),
}


def build_faces(elements, textures):
    faces = []
    cache = {}
    for element in elements:
        x0, y0, z0 = element["from"]
        x1, y1, z1 = element["to"]
        matrix = element_matrix(element["rotation"]) if "rotation" in element else None
        for direction, keys in FACES.items():
            face = element["faces"].get(direction)
            if not face:
                continue
            corners = []
            for (ax, ay, az) in keys:
                point = (x1 if ax else x0, y1 if ay else y0, z1 if az else z0)
                corners.append(mat_vec(matrix, point) if matrix else point)
            slot = face["texture"].lstrip("#")
            path = textures[slot]
            if path not in cache:
                cache[path] = Image.open(path).convert("RGB")
            faces.append({
                "corners": corners,
                "uv": face["uv"],
                "texture": cache[path],
            })
    return faces


# -------------------------------------------------------------------- renderer
def render(name, yaw=-28.0, pitch=16.0, size=256, supersample=2):
    elements, textures = load(name)
    faces = build_faces(elements, textures)

    # item model space: 16 units across, centred on the origin
    def view(point):
        x, y, z = point[0] - 8.0, point[1] - 8.0, point[2] - 8.0
        # yaw about Y, then pitch about X
        cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
        x, z = x * cy + z * sy, -x * sy + z * cy
        cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
        y, z = y * cp - z * sp, y * sp + z * cp
        return x, y, z

    scale = size * supersample / 17.0
    cx = cy = size * supersample / 2.0
    canvas = Image.new("RGB", (size * supersample, size * supersample), (18, 14, 22))
    pixels = canvas.load()

    light = norm(LIGHT)
    prepared = []
    for face in faces:
        points = [view(p) for p in face["corners"]]
        e1 = tuple(points[1][i] - points[0][i] for i in range(3))
        e2 = tuple(points[3][i] - points[0][i] for i in range(3))
        normal = (e1[1] * e2[2] - e1[2] * e2[1],
                  e1[2] * e2[0] - e1[0] * e2[2],
                  e1[0] * e2[1] - e1[1] * e2[0])
        if normal[2] <= 0.0:      # back face of a closed box
            continue
        normal = norm(normal)
        lambert = max(0.0, sum(normal[i] * light[i] for i in range(3)))
        prepared.append((sum(p[2] for p in points) / 4.0, points, face, 0.58 + 0.42 * lambert))

    prepared.sort(key=lambda item: item[0])

    for depth, points, face, brightness in prepared:
        screen = [(cx + p[0] * scale, cy - p[1] * scale) for p in points]
        texture = face["texture"]
        tw, th = texture.size
        u0, v0, u1, v1 = face["uv"]
        tex = texture.load()
        ox, oy = screen[0]
        ax, ay = screen[1][0] - ox, screen[1][1] - oy
        bx, by = screen[3][0] - ox, screen[3][1] - oy
        det = ax * by - ay * bx
        if abs(det) < 1e-9:
            continue
        minx = max(0, int(min(p[0] for p in screen)))
        maxx = min(canvas.width - 1, int(max(p[0] for p in screen)) + 1)
        miny = max(0, int(min(p[1] for p in screen)))
        maxy = min(canvas.height - 1, int(max(p[1] for p in screen)) + 1)
        for py in range(miny, maxy + 1):
            dy = py - oy
            for px in range(minx, maxx + 1):
                dx = px - ox
                s = (dx * by - dy * bx) / det
                t = (ax * dy - ay * dx) / det
                if s < 0.0 or s > 1.0 or t < 0.0 or t > 1.0:
                    continue
                u = u0 + (u1 - u0) * s
                v = v0 + (v1 - v0) * t
                tx = min(tw - 1, max(0, int(u / 16.0 * tw)))
                ty = min(th - 1, max(0, int(v / 16.0 * th)))
                colour = tex[tx, ty]
                pixels[px, py] = (
                    min(255, int(colour[0] * brightness)),
                    min(255, int(colour[1] * brightness)),
                    min(255, int(colour[2] * brightness)),
                )

    return canvas.resize((size, size), Image.LANCZOS)


def contact_sheet(styles):
    os.makedirs(OUT, exist_ok=True)
    for style in styles:
        cell = 200
        sheet = Image.new("RGB", (cell * (len(MATERIALS) + 1), cell), (18, 14, 22))
        draw = ImageDraw.Draw(sheet)
        for column, material in enumerate(MATERIALS):
            image = render(f"{style}_{material}", -28.0, 16.0, cell)
            sheet.paste(image, (column * cell, 0))
            draw.text((column * cell + 6, 4), material, fill=(190, 170, 200))
        sheet.paste(render(f"{style}_iron", -75.0, 8.0, cell), (len(MATERIALS) * cell, 0))
        large = sheet.resize((sheet.width * 2, sheet.height * 2), Image.LANCZOS)
        path = os.path.join(OUT, style + ".png")
        large.save(path)
        print(path)


def main(argv):
    styles = argv[1:] or STYLES
    contact_sheet(styles)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
