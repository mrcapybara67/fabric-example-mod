#!/usr/bin/env python3
"""Generates the smooth 3D knife meshes: one binary glTF file per style.

The cuboid models in ``models/item/knife/*.json`` are built from boxes. This
script builds the replacement meshes out of swept surfaces instead, because the
things that read as cheap on a knife - a blade with no taper, a handle that is a
rectangular stick, a guard that is a slab - are all consequences of boxes.

What it produces, per style, in ``models/item/knife/gltf/<style>.glb``:

  * a blade swept along a centre line with a real cross-section: thick at the
    spine, a bevel, a thin honed edge, and a taper to a point. A curved blade is
    a curve, not a staircase of boxes;
  * a handle swept along its own axis with a rounded, tapered section, so it
    reads as something a hand closes around;
  * hardware - guards, tsuba, bolsters, rivets, pommels, the karambit's finger
    ring - as proper solids rather than plates.

The space is the space a vanilla item model uses: Y up, one unit 1/16 block,
model inside 0..16, and the finished knife is turned -45 degrees about Z about
its grip and uniformly fitted to the same 15.2 unit height the cuboid models
use. That last part matters: it means a knife sits in the hand, in the GUI and
on the ground at exactly the size and angle the models it replaces did, so the
display transforms in the Java renderer stay correct.

Primitive order is the material contract the renderer relies on:

    0 blade   1 grip   2 guard   3 accent   4 edge   5 wrap

Run from the repository root:

    python3 tools/knife/gltf.py
"""

import json
import math
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "src", "client", "resources", "assets", "capy")
OUT = os.path.join(ASSETS, "models", "item", "knife", "gltf")

DIAGONAL = -45.0   # the direction a vanilla sword sprite runs in
TARGET = 15.2      # fitted height, matching tools/knife/generate.py
STYLES = ["butterfly", "karambit", "cleaver", "dagger", "tanto",
          "kukri", "machete", "stiletto", "katana"]

PART_BLADE, PART_GRIP, PART_GUARD, PART_ACCENT, PART_EDGE, PART_WRAP = range(6)


# ---------------------------------------------------------------- small maths
def norm3(v):
    length = math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    if length < 1e-12:
        return (0.0, 0.0, 1.0)
    return (v[0] / length, v[1] / length, v[2] / length)


def lerp(a, b, t):
    return a + (b - a) * t


def catmull(points, samples):
    """Samples a Catmull-Rom spline through the given 2D points."""
    if len(points) < 3:
        return list(points)
    padded = [points[0]] + list(points) + [points[-1]]
    out = []
    spans = len(points) - 1
    for i in range(samples):
        u = i / (samples - 1) * spans
        seg = min(int(u), spans - 1)
        t = u - seg
        p0, p1, p2, p3 = padded[seg], padded[seg + 1], padded[seg + 2], padded[seg + 3]
        t2, t3 = t * t, t * t * t
        x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t
                   + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                   + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
        y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t
                   + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                   + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
        out.append((x, y))
    return out


# ------------------------------------------------------------------- sections
def single_edge_section():
    """Cross-section of a blade with one cutting edge.

    Points are (u, v): u is across the blade from spine (-1) to edge (+1), v is
    half thickness. Thick at the spine, a single shoulder where the grind starts,
    then a thin hone. The alternation is deliberately gentle: a wide, bulging
    section reads as a tube, and the whole thing is only a fifth of a unit deep,
    so the shape that is left is the edge and the spine.
    """
    return [(-1.0, 0.30), (-0.72, 0.78), (0.5, 0.34), (1.0, 0.03),
            (1.0, -0.03), (0.5, -0.34), (-0.72, -0.78), (-1.0, -0.30)]


def double_edge_section():
    """Cross-section of a blade sharpened on both sides: a lens."""
    return [(-1.0, 0.03), (-0.72, 0.55), (-0.3, 0.92), (0.3, 0.92),
            (0.72, 0.55), (1.0, 0.03),
            (0.72, -0.55), (0.3, -0.92), (-0.3, -0.92), (-0.72, -0.55)]


def round_section(sides=12):
    """A circle, for handles and pins."""
    return [(math.cos(i * math.tau / sides), math.sin(i * math.tau / sides))
            for i in range(sides)]


# -------------------------------------------------------------------- builder
class Builder:
    """Collects triangles per material part."""

    def __init__(self):
        self.parts = {}

    def _part(self, part):
        if part not in self.parts:
            self.parts[part] = {"positions": [], "normals": [], "uvs": [], "indices": []}
        return self.parts[part]

    def vertex(self, part, position, normal, uv):
        p = self._part(part)
        index = len(p["positions"])
        p["positions"].append(position)
        p["normals"].append(normal)
        p["uvs"].append(uv)
        return index

    def triangle(self, part, a, b, c):
        self._part(part)["indices"].extend((a, b, c))

    def quad(self, part, a, b, c, d):
        # Two triangles, wound consistently with the section order.
        self.triangle(part, a, b, c)
        self.triangle(part, a, c, d)


def sweep(builder, part, stations, section, cap_start=True, cap_end=True,
          u_from_angle=False, uv_v_range=(0.0, 1.0)):
    """Sweeps a cross-section along a series of stations.

    Each station is ``(x, y, width, thickness)``: the centre of the section in
    the blade plane, and the size of the section at that point. Width runs
    across the knife, thickness is the depth in Z, so a blade tapers by shrinking
    both along its length.
    """
    rings = []
    for index, (x, y, width, thickness) in enumerate(stations):
        if index == 0:
            nx, ny = stations[1][0] - x, stations[1][1] - y
        elif index == len(stations) - 1:
            nx, ny = x - stations[-2][0], y - stations[-2][1]
        else:
            nx, ny = stations[index + 1][0] - stations[index - 1][0], \
                     stations[index + 1][1] - stations[index - 1][1]
        length = math.hypot(nx, ny)
        if length < 1e-9:
            nx, ny = 0.0, 1.0
        else:
            nx, ny = nx / length, ny / length
        # Across the blade, in the XY plane.
        ax, ay = -ny, nx
        v = uv_v_range[0] + (uv_v_range[1] - uv_v_range[0]) * index / max(1, len(stations) - 1)

        ring = []
        for k, (u, w) in enumerate(section):
            px = x + ax * u * width
            py = y + ay * u * width
            pz = w * thickness
            normal = norm3((ax * u * width, ay * u * width, w * thickness))
            # The blade sheet ends in a near-white hone; stopping the UV at
            # 0.88 keeps the edge bright without blowing it out to flat white.
            tu = k / len(section) if u_from_angle else (u + 1.0) * 0.5 * 0.9
            ring.append(builder.vertex(part, (px, py, pz), normal, (tu, v)))
        rings.append(ring)

    count = len(section)
    for i in range(len(rings) - 1):
        for k in range(count):
            a = rings[i][k]
            b = rings[i][(k + 1) % count]
            c = rings[i + 1][(k + 1) % count]
            d = rings[i + 1][k]
            builder.quad(part, a, b, c, d)

    # Caps: a fan from the ring's centre, facing along the sweep.
    for which, ring, station, flip in (
            ("start", rings[0], stations[0], -1),
            ("end", rings[-1], stations[-1], 1)):
        if (which == "start" and not cap_start) or (which == "end" and not cap_end):
            continue
        if which == "start":
            nx, ny = stations[1][0] - station[0], stations[1][1] - station[1]
        else:
            nx, ny = station[0] - stations[-2][0], station[1] - stations[-2][1]
        tangent = norm3((nx * flip, ny * flip, 0.0))
        centre = builder.vertex(part, (station[0], station[1], 0.0), tangent, (0.5, 0.5))
        for k in range(count):
            builder.triangle(part, centre, ring[k], ring[(k + 1) % count])


def blade(builder, path_points, widths, thickness, part=PART_BLADE,
          section=None, samples=None, uv_v=(0.30, 0.42), point=True):
    """A blade through the given centre line, tapering to a point.

    ``thickness`` is the half thickness of the blade. A real blade is about
    three millimetres thick, which at 1/16 of a block is under 0.2 units of
    total depth - hence the small numbers at every call site. Anything thicker
    stops reading as a blade and starts reading as a sausage.

    ``uv_v`` keeps the length of the blade on a single narrow band of the sheet.
    The blade texture has horizontal brush grain across its whole height, which
    is fine on a cuboid face but turns into corrugation when it is stretched
    down a swept blade; sampling one calm band leaves only the spine-to-hone
    gradient, which is the part that should be visible.
    """
    if section is None:
        section = single_edge_section()
    samples = samples or max(7, len(path_points) * 3)
    line = catmull(path_points, samples)
    stations = []
    for i, (x, y) in enumerate(line):
        t = i / (samples - 1)
        width = widths(i / (samples - 1), t)
        stations.append((x, y, width, thickness))
    if point:
        # A point, not a chopped end. The tip station continues along the last
        # segment for nearly two sample steps and collapses the section to
        # almost nothing, so the last stretch of blade is a real taper instead
        # of a chisel cut across the end.
        x, y = line[-1]
        px, py = line[-2]
        step = math.hypot(x - px, y - py) or 1.0
        dx, dy = (x - px) / step, (y - py) / step
        stations.append((x + dx * step * 1.8, y + dy * step * 1.8,
                         0.03, thickness * 0.15))
    sweep(builder, part, stations, section, cap_start=True, cap_end=True,
          uv_v_range=uv_v)


def taper(start, mid, end):
    """Width profile: root, belly, then a short taper into the point.

    The blade holds its width for most of its length and narrows only near the
    tip, because that is what a blade does. Tapering the whole length instead
    produces a carrot: recognisable as a knife, but not as any particular one.
    """
    def width(t, _=None):
        if t < 0.22:
            return lerp(start, mid, t / 0.22)
        if t < 0.76:
            return mid
        return lerp(mid, end, (t - 0.76) / 0.24)
    return width


def handle(builder, x0, y0, x1, y1, radius, thick, part=PART_GRIP, taper_to=0.82,
           rings=9):
    """A grip: an eleven-sided section swept from butt to guard."""
    stations = []
    for i in range(rings):
        t = i / (rings - 1)
        # Slight swell in the middle, so the hand position is implied.
        swell = 1.0 + 0.12 * math.sin(math.pi * t)
        scale = lerp(1.0, taper_to, t) * swell
        stations.append((lerp(x0, x1, t), lerp(y0, y1, t),
                         radius * scale, thick * scale))
    sweep(builder, part, stations, round_section(11), cap_start=True, cap_end=True,
          u_from_angle=True)


def sphere(builder, centre, radius, part=PART_ACCENT, rings=7, sides=12):
    cx, cy, cz = centre
    top = builder.vertex(part, (cx, cy + radius, cz), (0.0, 1.0, 0.0), (0.5, 0.0))
    bottom = builder.vertex(part, (cx, cy - radius, cz), (0.0, -1.0, 0.0), (0.5, 1.0))
    grid = []
    for i in range(1, rings):
        phi = math.pi * i / rings
        row = []
        for k in range(sides):
            theta = math.tau * k / sides
            nx = math.sin(phi) * math.cos(theta)
            ny = math.cos(phi)
            nz = math.sin(phi) * math.sin(theta)
            row.append(builder.vertex(part, (cx + nx * radius, cy + ny * radius, cz + nz * radius),
                                      (nx, ny, nz), (k / sides, i / rings)))
        grid.append(row)
    for k in range(sides):
        builder.triangle(part, top, grid[0][k], grid[0][(k + 1) % sides])
        builder.triangle(part, bottom, grid[-1][(k + 1) % sides], grid[-1][k])
    for i in range(len(grid) - 1):
        for k in range(sides):
            builder.quad(part, grid[i][k], grid[i][(k + 1) % sides],
                         grid[i + 1][(k + 1) % sides], grid[i + 1][k])


def cylinder(builder, x, y, z0, z1, radius, part=PART_ACCENT, sides=10):
    """A pin or rivet through the thin axis of the knife."""
    ring0, ring1 = [], []
    for k in range(sides):
        theta = math.tau * k / sides
        nx, ny = math.cos(theta), math.sin(theta)
        ring0.append(builder.vertex(part, (x + nx * radius, y + ny * radius, z0),
                                    (nx, ny, 0.0), (k / sides, 0.0)))
        ring1.append(builder.vertex(part, (x + nx * radius, y + ny * radius, z1),
                                    (nx, ny, 0.0), (k / sides, 1.0)))
    for k in range(sides):
        builder.quad(part, ring0[k], ring0[(k + 1) % sides],
                     ring1[(k + 1) % sides], ring1[k])


def torus(builder, centre, major, minor, part=PART_GUARD, segs=28, sides=10,
          axis="y"):
    """A ring: a fair finger ring, a tsuba, or the hole in a cleaver."""
    cx, cy, cz = centre
    grid = []
    for i in range(segs):
        u = math.tau * i / segs
        cu, su = math.cos(u), math.sin(u)
        row = []
        for k in range(sides):
            v = math.tau * k / sides
            cv, sv = math.cos(v), math.sin(v)
            r = major + minor * cv
            if axis == "y":
                pos = (cx + r * cu, cy + minor * sv, cz + r * su)
                nrm = (cv * cu, sv, cv * su)
            else:
                pos = (cx + r * cu, cy + r * su, cz + minor * sv)
                nrm = (cv * cu, cv * su, sv)
            row.append(builder.vertex(part, pos, norm3(nrm), (i / segs, k / sides)))
        grid.append(row)
    for i in range(segs):
        for k in range(sides):
            a = grid[i][k]
            b = grid[(i + 1) % segs][k]
            c = grid[(i + 1) % segs][(k + 1) % sides]
            d = grid[i][(k + 1) % sides]
            builder.quad(part, a, b, c, d)


def plate(builder, cx, cy, half_x, half_y, thick, part=PART_GUARD, roundness=0.35):
    """A small flat plate with chamfered ends: a bolster or a tsuba plate."""
    stations = []
    steps = 6
    for i in range(steps + 1):
        t = i / steps
        x = cx - half_x + 2 * half_x * t
        # Rounded ends: the section collapses over the last fifth of the plate.
        edge = min(t, 1.0 - t) / roundness
        scale = 1.0 if edge >= 1.0 else math.sin(edge * math.pi / 2) ** 0.6
        stations.append((x, cy, max(0.02, half_y * scale), thick * max(0.35, scale)))
    sweep(builder, part, stations, round_section(10), cap_start=True, cap_end=True,
          u_from_angle=True)


def wrap_bands(builder, x0, y0, x1, y1, radius, thick, count, part=PART_GRIP,
               width=0.16):
    """Raised cord bands around a grip.

    Drawn with the grip sheet, not with ``wrap.png``: that sheet is transparent
    apart from its lines, and these meshes are cut out by alpha, so a band drawn
    with it would disappear entirely. A band is a ring of grip that sits a
    little proud of the handle, which is what a wrapped cord is.
    """
    for i in range(count):
        t = (i + 0.5) / count
        x, y = lerp(x0, x1, t), lerp(y0, y1, t)
        station_scale = 1.06
        stations = []
        for dy in (-width, width):
            stations.append((x, y + dy, radius * station_scale, thick * station_scale))
        sweep(builder, part, stations, round_section(11), cap_start=False,
              cap_end=False, u_from_angle=True)


# ------------------------------------------------------------------- the knives
def style_butterfly(b):
    # Two handle bars, a pivot and a latch: the silhouette of an opened balisong.
    # The bars are separate geometry with a gap between them, because that gap
    # is what a balisong is; merged, it is just a fat grip.
    for side in (-1, 1):
        x0, x1 = 8.0 + side * 1.3, 8.0 + side * 1.12
        handle(b, x0, 0.5, x1, 5.0, 0.52, 0.4, rings=8, taper_to=0.96)
        wrap_bands(b, x0, 0.5, x1, 5.0, 0.52, 0.4, 3, width=0.17)
    plate(b, 8.0, 0.32, 1.55, 0.34, 0.4, PART_ACCENT, roundness=0.5)
    cylinder(b, 8.0, 5.0, -0.68, 0.68, 0.24, PART_ACCENT, sides=12)
    plate(b, 8.0, 5.12, 0.86, 0.22, 0.36, PART_GUARD, roundness=0.6)
    blade(b, [(8.0, 5.1), (8.0, 9.9), (8.0, 14.3)],
          taper(0.76, 0.8, 0.24), 0.075)


def style_karambit(b):
    # The ring is the identifier: a real torus, not a hoop of boxes.
    torus(b, (8.0, 0.25, 0.0), 0.92, 0.24, PART_GUARD, segs=26, sides=9, axis="z")
    handle(b, 8.0, 1.15, 7.95, 4.5, 0.68, 0.5, rings=8, taper_to=0.9)
    wrap_bands(b, 8.0, 1.15, 7.95, 4.5, 0.68, 0.5, 4, width=0.14)
    plate(b, 7.95, 4.55, 0.82, 0.22, 0.5, PART_ACCENT, roundness=0.55)
    blade(b, [(7.95, 4.55), (8.4, 7.7), (10.2, 10.3), (12.9, 11.3)],
          taper(0.7, 0.92, 0.26), 0.085, samples=13)


def style_cleaver(b):
    handle(b, 8.0, 0.5, 8.0, 6.4, 0.84, 0.52, rings=8, taper_to=0.92)
    for rivet_y in (1.9, 3.6, 5.2):
        for side in (-1, 1):
            cylinder(b, 8.0 + side * 0.48, rivet_y, -0.32, 0.32, 0.11)
    plate(b, 8.0, 6.62, 1.3, 0.28, 0.62, PART_GUARD, roundness=0.45)
    # A broad plate with a thick spine and a thin edge: the cleaver's whole idea.
    # The width grows slightly toward the tip and squares off at the corner,
    # which is what separates a cleaver from a paddle.
    # A cleaver ends in a square corner, not a point: `point=False` keeps the
    # full width all the way to the cap, which is the whole read of the shape.
    blade(b, [(8.0, 6.78), (8.0, 10.6), (8.0, 14.4)],
          lambda t, _: lerp(1.2, 1.4, t), 0.1, samples=8, point=False)
    # The hanging hole, inside the blade rather than off its corner.
    torus(b, (9.05, 12.6, 0.0), 0.4, 0.09, PART_ACCENT, segs=18, sides=7, axis="z")


def style_dagger(b):
    handle(b, 8.0, 0.55, 8.0, 3.9, 0.68, 0.52, rings=7, taper_to=0.86)
    sphere(b, (8.0, 0.3, 0.0), 0.55, PART_ACCENT)
    plate(b, 8.0, 4.05, 1.45, 0.22, 0.56, PART_GUARD, roundness=0.5)
    blade(b, [(8.0, 4.1), (8.0, 8.6), (8.0, 12.6)],
          taper(0.58, 0.54, 0.24), 0.07, section=double_edge_section())


def style_tanto(b):
    handle(b, 8.0, 0.5, 8.0, 4.0, 0.74, 0.54, rings=8, taper_to=0.9)
    wrap_bands(b, 8.0, 0.5, 8.0, 4.0, 0.74, 0.54, 4, width=0.14)
    plate(b, 8.0, 0.26, 0.88, 0.22, 0.5, PART_ACCENT, roundness=0.5)
    plate(b, 8.0, 4.1, 1.1, 0.2, 0.5, PART_GUARD, roundness=0.55)
    plate(b, 8.0, 4.38, 0.62, 0.22, 0.46, PART_ACCENT, roundness=0.4)
    # A straight edge with an angled kissaki: the tanto's defining break.
    blade(b, [(8.0, 4.35), (8.0, 11.6), (8.0, 13.4)],
          lambda t, _: 0.72 if t < 0.9 else lerp(0.72, 0.38, (t - 0.9) / 0.1), 0.085,
          samples=11)


def style_kukri(b):
    handle(b, 7.9, 0.5, 7.95, 4.4, 0.82, 0.58, rings=8, taper_to=1.04)
    for rivet_y in (1.4, 2.5, 3.6):
        cylinder(b, 7.95, rivet_y, -0.34, 0.34, 0.12)
    plate(b, 7.95, 4.5, 0.95, 0.26, 0.66, PART_ACCENT, roundness=0.5)
    # The forward belly is the kukri: it leads the edge and carries the weight.
    blade(b, [(7.95, 4.45), (8.2, 8.0), (9.6, 11.1), (12.4, 12.6)],
          taper(0.72, 1.15, 0.28), 0.11, samples=13)


def style_machete(b):
    handle(b, 8.0, 0.5, 8.0, 4.9, 0.86, 0.56, rings=8, taper_to=0.95)
    for rivet_y in (1.3, 2.7, 4.1):
        cylinder(b, 8.0, rivet_y, -0.34, 0.34, 0.12)
    plate(b, 8.0, 5.02, 1.45, 0.24, 0.6, PART_GUARD, roundness=0.45)
    blade(b, [(8.0, 5.1), (8.3, 10.2), (8.55, 14.6)],
          lambda t, _: lerp(1.02, 0.72, (t - 0.78) / 0.22) if t > 0.78 else lerp(0.95, 1.02, t / 0.78),
          0.1, samples=11)


def style_stiletto(b):
    handle(b, 8.0, 0.55, 8.0, 3.6, 0.56, 0.48, rings=7, taper_to=0.82)
    sphere(b, (8.0, 0.25, 0.0), 0.46, PART_ACCENT)
    plate(b, 8.0, 3.75, 1.85, 0.17, 0.46, PART_GUARD, roundness=0.5)
    # A needle: very narrow, double edged, tapering the whole way.
    blade(b, [(8.0, 3.8), (8.0, 8.6), (8.0, 12.8)],
          taper(0.36, 0.32, 0.07), 0.06, section=double_edge_section(), samples=10)


def style_katana(b):
    # Proportions are the whole thing: five units of grip under twelve of blade,
    # with a shallow curve (sori) along the entire run and a point that is a
    # point. A katana that is not visibly longer than the other knives is just
    # a tanto with a bigger guard.
    handle(b, 8.0, 0.5, 8.0, 5.0, 0.78, 0.52, rings=9, taper_to=0.92)
    for i in range(5):
        t = (i + 0.5) / 5
        wrap_bands(b, 8.0, 0.5 + t * 4.5, 8.0, 0.5 + t * 4.5, 0.78, 0.52, 1, width=0.21)
    plate(b, 8.0, 0.28, 0.78, 0.2, 0.54, PART_ACCENT, roundness=0.5)   # kashira
    torus(b, (8.0, 5.1, 0.0), 1.1, 0.2, PART_GUARD, segs=30, sides=9, axis="y")
    plate(b, 8.0, 5.36, 0.54, 0.24, 0.52, PART_ACCENT, roundness=0.35)   # habaki
    # Sori is subtle: a katana is very nearly straight, and a curve this shallow
    # is what separates it from a scimitar.
    blade(b, [(8.0, 5.3), (8.15, 9.0), (8.55, 12.9), (8.9, 16.2)],
          lambda t, _: 0.68 if t < 0.92 else lerp(0.68, 0.36, (t - 0.92) / 0.08),
          0.08, samples=18)


STYLE_BUILDERS = {
    "butterfly": style_butterfly,
    "karambit": style_karambit,
    "cleaver": style_cleaver,
    "dagger": style_dagger,
    "tanto": style_tanto,
    "kukri": style_kukri,
    "machete": style_machete,
    "stiletto": style_stiletto,
    "katana": style_katana,
}

GRIP_POINTS = {
    "butterfly": (8.0, 2.5), "karambit": (7.7, 2.1), "cleaver": (8.0, 3.3),
    "dagger": (8.0, 2.2), "tanto": (8.0, 2.2), "kukri": (7.75, 2.2),
    "machete": (8.0, 2.6), "stiletto": (8.0, 2.0), "katana": (8.0, 2.2),
}


def fit(builder, grip):
    """Turns and fits the whole knife, exactly as the cuboid generator does.

    Same rotation, same target height, same centre: a knife therefore lands in
    the hand at the size and angle the model it replaces did, and the display
    transforms stay valid.
    """
    rad = math.radians(DIAGONAL)
    cos, sin = math.cos(rad), math.sin(rad)
    for part in builder.parts.values():
        turned = []
        for (x, y, z) in part["positions"]:
            dx, dy = x - grip[0], y - grip[1]
            turned.append((grip[0] + dx * cos - dy * sin, grip[1] + dx * sin + dy * cos, z))
        part["positions"] = turned

    xs = [p[0] for part in builder.parts.values() for p in part["positions"]]
    ys = [p[1] for part in builder.parts.values() for p in part["positions"]]
    width, height = max(xs) - min(xs), max(ys) - min(ys)
    scale = TARGET / max(width, height, 1e-6)
    mid_x, mid_y = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    for part in builder.parts.values():
        part["positions"] = [
            (8.0 + (x - mid_x) * scale, 8.0 + (y - mid_y) * scale, 8.0 + z * scale)
            for (x, y, z) in part["positions"]
        ]


# ------------------------------------------------------------------ glb writer
def write_glb(path, builder):
    json_parts = []
    accessors = []
    views = []
    binary = bytearray()

    def add_view(payload, target):
        while len(binary) % 4:
            binary.append(0)
        offset = len(binary)
        binary.extend(payload)
        views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(payload),
                      "target": target})
        return len(views) - 1

    for part_index in sorted(builder.parts):
        part = builder.parts[part_index]
        positions = part["positions"]
        normals = part["normals"]
        uvs = part["uvs"]
        indices = part["indices"]

        pos_payload = b"".join(struct.pack("<3f", *p) for p in positions)
        nrm_payload = b"".join(struct.pack("<3f", *n) for n in normals)
        uv_payload = b"".join(struct.pack("<2f", *t) for t in uvs)
        idx_payload = b"".join(struct.pack("<I", i) for i in indices)

        pos_view = add_view(pos_payload, 34962)
        nrm_view = add_view(nrm_payload, 34962)
        uv_view = add_view(uv_payload, 34962)
        idx_view = add_view(idx_payload, 34963)

        xs = [p[0] for p in positions]
        ys = [p[1] for p in positions]
        zs = [p[2] for p in positions]

        pos_accessor = len(accessors)
        accessors.append({"bufferView": pos_view, "componentType": 5126,
                          "count": len(positions), "type": "VEC3",
                          "min": [round(min(xs), 5), round(min(ys), 5), round(min(zs), 5)],
                          "max": [round(max(xs), 5), round(max(ys), 5), round(max(zs), 5)]})
        nrm_accessor = len(accessors)
        accessors.append({"bufferView": nrm_view, "componentType": 5126,
                          "count": len(normals), "type": "VEC3"})
        uv_accessor = len(accessors)
        accessors.append({"bufferView": uv_view, "componentType": 5126,
                          "count": len(uvs), "type": "VEC2"})
        idx_accessor = len(accessors)
        accessors.append({"bufferView": idx_view, "componentType": 5125,
                          "count": len(indices), "type": "SCALAR"})

        json_parts.append({
            "attributes": {"POSITION": pos_accessor, "NORMAL": nrm_accessor,
                           "TEXCOORD_0": uv_accessor},
            "indices": idx_accessor,
            "mode": 4,
        })

    while len(binary) % 4:
        binary.append(0)

    document = {
        "asset": {"version": "2.0", "generator": "capy tools/knife/gltf.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0}],
        "meshes": [{"primitives": json_parts}],
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": len(binary)}],
    }
    text = json.dumps(document, separators=(",", ":")).encode("utf-8")
    while len(text) % 4:
        text += b" "

    total = 12 + 8 + len(text) + 8 + len(binary)
    glb = bytearray()
    glb += struct.pack("<III", 0x46546C67, 2, total)
    glb += struct.pack("<II", len(text), 0x4E4F534A)
    glb += text
    glb += struct.pack("<II", len(binary), 0x004E4942)
    glb += binary

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(glb)
    return len(json_parts), total


def main():
    os.makedirs(OUT, exist_ok=True)
    total_triangles = 0
    for style in STYLES:
        builder = Builder()
        STYLE_BUILDERS[style](builder)
        if PART_BLADE not in builder.parts or PART_GRIP not in builder.parts:
            raise SystemExit(f"{style}: missing a blade or a grip")
        fit(builder, GRIP_POINTS[style])
        parts, size = write_glb(os.path.join(OUT, style + ".glb"), builder)
        triangles = sum(len(p["indices"]) for p in builder.parts.values()) // 3
        total_triangles += triangles
        print(f"{style:10s} parts={parts} triangles={triangles:5d} bytes={size}")
    print(f"{len(STYLES)} meshes, {total_triangles} triangles")
    return 0


if __name__ == "__main__":
    sys.exit(main())
