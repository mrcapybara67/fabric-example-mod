#!/usr/bin/env python3
"""Renders the glTF knives to PNG so the geometry can be looked at.

This is a small software rasteriser, on purpose: the point is to see the mesh
the game will draw without running the game. It reads the GLB the same way the
Java loader does, samples the same textures the renderer will, and shades with
one directional light, so a blade with an inside-out normal or a section that
collapsed shows up here before it shows up in a hand.

Run from the repository root:

    python3 tools/knife/preview_gltf.py
"""

import json
import math
import os
import struct
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "src", "client", "resources", "assets", "capy")
MESHES = os.path.join(ASSETS, "models", "item", "knife", "gltf")
TEXTURES = os.path.join(ASSETS, "textures", "item", "knife")
OUT = os.path.join(ROOT, "build", "knife3d_gltf")

STYLES = ["butterfly", "karambit", "cleaver", "dagger", "tanto",
          "kukri", "machete", "stiletto", "katana"]

TEXTURE_FOR_PART = {
    0: "blade_iron", 1: "grip_wrapped", 2: "guard",
    3: "accent", 4: "edge_iron", 5: "wrap",
}

SIZE = 520
LIGHT = (0.45, 0.62, 0.65)

# With --parts every material part is drawn in a flat colour, so a piece of
# geometry can be attributed to a primitive at a glance.
PART_DEBUG = {0: (214, 72, 72), 1: (70, 190, 90), 2: (72, 120, 230),
              3: (232, 200, 60), 4: (220, 80, 220), 5: (60, 210, 210)}


def read_glb(path):
    data = open(path, "rb").read()
    magic, version, length = struct.unpack_from("<III", data, 0)
    if magic != 0x46546C67 or version != 2:
        raise SystemExit(f"{path}: not a GLB")
    document, binary = None, b""
    offset = 12
    while offset + 8 <= length:
        chunk_length, chunk_type = struct.unpack_from("<II", data, offset)
        chunk = data[offset + 8:offset + 8 + chunk_length]
        if chunk_type == 0x4E4F534A:
            document = json.loads(chunk.decode("utf-8"))
        elif chunk_type == 0x004E4942:
            binary = chunk
        offset += 8 + chunk_length
    return document, binary


def accessor(document, binary, index):
    acc = document["accessors"][index]
    view = document["bufferViews"][acc["bufferView"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = view.get("byteStride", 0)
    components = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[acc["type"]]
    size = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}[acc["componentType"]]
    if not stride:
        stride = size * components
    fmt = {5120: "b", 5121: "B", 5122: "h", 5123: "H", 5125: "I", 5126: "f"}[acc["componentType"]]
    out = []
    for i in range(acc["count"]):
        base = start + i * stride
        out.append(struct.unpack_from("<" + fmt * components, binary, base))
    return out


def load_triangles(path):
    document, binary = read_glb(path)
    triangles = []
    for part_index, primitive in enumerate(document["meshes"][0]["primitives"]):
        attributes = primitive["attributes"]
        positions = accessor(document, binary, attributes["POSITION"])
        normals = accessor(document, binary, attributes["NORMAL"])
        uvs = accessor(document, binary, attributes["TEXCOORD_0"])
        indices = [i[0] for i in accessor(document, binary, primitive["indices"])]
        for t in range(0, len(indices), 3):
            i0, i1, i2 = indices[t], indices[t + 1], indices[t + 2]
            triangles.append((part_index, positions[i0], positions[i1], positions[i2],
                              normals[i0], normals[i1], normals[i2],
                              uvs[i0], uvs[i1], uvs[i2]))
    return triangles


def load_texture(name):
    path = os.path.join(TEXTURES, name + ".png")
    return Image.open(path).convert("RGB")


def sample(texture, u, v):
    w, h = texture.size
    x = int((u % 1.0) * (w - 1))
    y = int((v % 1.0) * (h - 1))
    return texture.getpixel((x, y))


def rotate_y(p, degrees):
    rad = math.radians(degrees)
    cos, sin = math.cos(rad), math.sin(rad)
    return (p[0] * cos + p[2] * sin, p[1], -p[0] * sin + p[2] * cos)


def render(triangles, textures, yaw=0.0, zoom=1.0):
    image = Image.new("RGB", (SIZE, SIZE), (26, 27, 32))
    pixels = image.load()
    depth = [[-1e9] * SIZE for _ in range(SIZE)]
    # 17 units across the frame, the same projection tools/knife/preview3d.py
    # uses, so a render from each can be put side by side without rescaling.
    scale = SIZE / 17.0 * zoom
    cx = cy = 8.0
    light = (LIGHT[0], LIGHT[1], LIGHT[2])

    for (part, p0, p1, p2, n0, n1, n2, t0, t1, t2) in triangles:
        texture = textures[part]
        a = rotate_y(p0, yaw)
        b = rotate_y(p1, yaw)
        c = rotate_y(p2, yaw)
        na = rotate_y(n0, yaw)
        nb = rotate_y(n1, yaw)
        nc = rotate_y(n2, yaw)

        # Screen space: orthographic, Y down.
        ax = (a[0] - cx) * scale + SIZE / 2
        ay = SIZE / 2 - (a[1] - cy) * scale
        bx = (b[0] - cx) * scale + SIZE / 2
        by = SIZE / 2 - (b[1] - cy) * scale
        cx2 = (c[0] - cx) * scale + SIZE / 2
        cy2 = SIZE / 2 - (c[1] - cy) * scale

        area = (bx - ax) * (cy2 - ay) - (by - ay) * (cx2 - ax)
        if abs(area) < 1e-9:
            continue
        min_x = max(0, int(min(ax, bx, cx2)))
        max_x = min(SIZE - 1, int(max(ax, bx, cx2)) + 1)
        min_y = max(0, int(min(ay, by, cy2)))
        max_y = min(SIZE - 1, int(max(ay, by, cy2)) + 1)

        for y in range(min_y, max_y + 1):
            for x in range(min_x, max_x + 1):
                px, py = x + 0.5, y + 0.5
                w0 = ((bx - px) * (cy2 - py) - (by - py) * (cx2 - px)) / area
                w1 = ((cx2 - px) * (ay - py) - (cy2 - py) * (ax - px)) / area
                w2 = 1.0 - w0 - w1
                if w0 < -0.001 or w1 < -0.001 or w2 < -0.001:
                    continue
                z = w0 * a[2] + w1 * b[2] + w2 * c[2]
                if z <= depth[y][x]:
                    continue
                depth[y][x] = z
                nx = w0 * na[0] + w1 * nb[0] + w2 * nc[0]
                ny = w0 * na[1] + w1 * nb[1] + w2 * nc[1]
                nz = w0 * na[2] + w1 * nb[2] + w2 * nc[2]
                length = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
                lambert = (nx * light[0] + ny * light[1] + nz * light[2]) / length
                shade = 0.45 + 0.55 * max(0.0, lambert)
                u = w0 * t0[0] + w1 * t1[0] + w2 * t2[0]
                v = w0 * t0[1] + w1 * t1[1] + w2 * t2[1]
                r, g, bl = sample(texture, u, v)
                pixels[x, y] = (min(255, int(r * shade)),
                                min(255, int(g * shade)),
                                min(255, int(bl * shade)))
    return image


def main():
    os.makedirs(OUT, exist_ok=True)
    parts_only = "--parts" in sys.argv
    wanted = [a for a in sys.argv[1:] if a in STYLES] or STYLES
    tiles = []
    for style in wanted:
        path = os.path.join(MESHES, style + ".glb")
        if not os.path.exists(path):
            raise SystemExit(f"missing {path}")
        triangles = load_triangles(path)
        if parts_only:
            textures = {part: Image.new("RGB", (4, 4), colour)
                        for part, colour in PART_DEBUG.items()}
        else:
            textures = {part: load_texture(name) for part, name in TEXTURE_FOR_PART.items()}
        front = render(triangles, textures, yaw=0.0)
        quarter = render(triangles, textures, yaw=-38.0)
        for image, suffix in ((front, "front"), (quarter, "quarter")):
            image.save(os.path.join(OUT, f"{style}_{suffix}.png"))
        tile = Image.new("RGB", (SIZE * 2, SIZE + 26), (16, 17, 20))
        tile.paste(front, (0, 26))
        tile.paste(quarter, (SIZE, 26))
        ImageDraw.Draw(tile).text((8, 7), f"{style}  ({len(triangles)} triangles)", fill=(235, 235, 240))
        tiles.append(tile)
        if parts_only:
            tile.save(os.path.join(OUT, f"parts_{style}.png"))

        xs = [p[0] for t in triangles for p in (t[1], t[2], t[3])]
        ys = [p[1] for t in triangles for p in (t[1], t[2], t[3])]
        zs = [p[2] for t in triangles for p in (t[1], t[2], t[3])]
        print(f"{style:10s} tris={len(triangles):5d} "
              f"x={min(xs):5.2f}..{max(xs):5.2f} "
              f"y={min(ys):5.2f}..{max(ys):5.2f} "
              f"z={min(zs):5.2f}..{max(zs):5.2f}")

    columns = 3
    rows = (len(tiles) + columns - 1) // columns
    sheet = Image.new("RGB", (SIZE * 2 * columns, (SIZE + 26) * rows), (10, 10, 12))
    for index, tile in enumerate(tiles):
        sheet.paste(tile, ((index % columns) * SIZE * 2, (index // columns) * (SIZE + 26)))
    name = "parts.png" if parts_only else "contact.png"
    sheet.save(os.path.join(OUT, name))
    print(f"wrote {os.path.join(OUT, name)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
