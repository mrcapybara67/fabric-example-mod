#!/usr/bin/env python3
"""Generates the Custom Knife's 3D models, textures and item model definitions.

Every knife is a real 3D model: cuboids with actual thickness, bevelled tips,
separate cutting edges and real hardware (pivot pins, finger rings, bolsters).
It is not a sprite with a shadow.  Held in the hand the thickness catches the
light, in the GUI the silhouette reads as an object, and other players see the
same geometry in third person.

Geometry is authored upright - handle at the bottom, blade pointing at +Y - and
then turned -45 degrees about the grip, because a vanilla sword sprite runs
along that diagonal and the ``item/handheld`` display transform grips the model
at that corner.  Every cuboid carries one Euler Z rotation, so the knife is
turned as a rigid body: no blade is ever twisted against its own guard.

Blades are built from a centre line.  A straight blade is one or two segments;
a kukri or a karambit is a polyline, and each segment is placed on the line it
belongs to, which is what makes a curved blade continuous instead of a row of
overlapping planks.  Each segment is a thin plate with a slightly proud strip
of bright steel along the cutting edge: at item scale that reads as a sharpened
blade from every angle, where a stack of bevels would read as corrugation.

Textures are shared, not per model: a blade texture exists once per sword
material and every style uses it with different geometry.  48 models therefore
share 18 textures, which is a fraction of the texture memory a per-model sheet
would need and keeps the bake cheap.

Run from the repository root:

    python3 tools/knife/generate.py
"""

import json
import math
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

TEX = 32          # texture edge, in pixels
UV = 16.0         # vanilla model uv space
DIAGONAL = -45.0  # the direction a vanilla sword sprite runs in

# --------------------------------------------------------------------- colours
# (blade base, blade light, blade dark, cutting edge) - order must match the
# MATERIALS array in CustomKnifeModule.java.
MATERIALS = {
    "wooden":    ("#6F4A24", "#9A6A34", "#43290F", "#C99A5C"),
    "stone":     ("#77777A", "#9C9CA0", "#4A4A4D", "#C4C4C8"),
    "iron":      ("#A8ACB2", "#D2D7DD", "#6E7278", "#F3F6FA"),
    "golden":    ("#D2A020", "#F3CD55", "#8A6308", "#FFE98A"),
    "diamond":   ("#3AAFA6", "#6FE0D5", "#1F6F6B", "#C6FFF7"),
    "netherite": ("#4B434C", "#6E6472", "#2A252C", "#9A90A2"),
}

WOOD_HANDLE = ("#5A3A1E", "#7C542E", "#38220E")
LEATHER = ("#3B3444", "#575063", "#221D28")
STEEL = ("#6E6878", "#A7A1B4", "#454052")
BRASS = ("#9C7A1C", "#D9B548", "#6B5210")

# --------------------------------------------------------------------- helpers


def rgb(value):
    value = value.lstrip("#")
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3.0 - 2.0 * t)


def shade(colour, amount):
    return tuple(max(0, min(255, int(round(c * amount)))) for c in colour)


def wrap(name):
    return f"capy:item/knife/{name}"


def rotate(x, y, ox, oy, degrees):
    rad = math.radians(degrees)
    cos, sin = math.cos(rad), math.sin(rad)
    dx, dy = x - ox, y - oy
    return ox + dx * cos - dy * sin, oy + dx * sin + dy * cos


# --------------------------------------------------------------------- textures
def blade_texture(material):
    """Brushed steel: dark spine, bright grind, lighter cutting side."""
    base, light, dark, edge = (rgb(v) for v in MATERIALS[material])
    image = Image.new("RGB", (TEX, TEX))
    pixels = image.load()
    for y in range(TEX):
        wave = 0.5 + 0.5 * math.sin(y * 0.55 + 0.4)
        for x in range(TEX):
            t = x / (TEX - 1)
            if t < 0.20:                       # spine bevel
                colour = mix(dark, base, smooth(t / 0.20))
            elif t < 0.66:                     # body
                colour = mix(base, light, smooth((t - 0.20) / 0.46) * 0.80)
            else:                              # grind towards the edge
                colour = mix(light, edge, smooth((t - 0.66) / 0.34) * 0.90)
            brushed = (wave - 0.5) * 0.09 + math.sin(y * 3.1 + x * 0.9) * 0.02
            pixels[x, y] = shade(colour, 1.0 + brushed)
        # a fuller down the length: a blade that is forged, not printed
        pixels[11, y] = shade(pixels[11, y], 0.92)
        pixels[12, y] = shade(pixels[12, y], 0.95)
    return image


def edge_texture(material):
    _, _, _, edge = (rgb(v) for v in MATERIALS[material])
    image = Image.new("RGB", (TEX, TEX))
    pixels = image.load()
    for y in range(TEX):
        for x in range(TEX):
            t = x / (TEX - 1)
            colour = mix(edge, (255, 255, 255), smooth(t) * 0.60)
            pixels[x, y] = shade(colour, 1.0 + math.sin(y * 2.3) * 0.015)
    return image


def wood_grip_texture():
    base, light, dark = (rgb(v) for v in WOOD_HANDLE)
    image = Image.new("RGB", (TEX, TEX))
    pixels = image.load()
    for y in range(TEX):
        for x in range(TEX):
            grain = 0.5 + 0.5 * math.sin(x * 1.35 + math.sin(y * 0.30) * 2.2)
            colour = mix(dark, light, smooth(grain) * 0.85)
            pixels[x, y] = shade(colour, 1.0 + math.sin(y * 1.7) * 0.03)
    return image


def wrapped_grip_texture():
    base, light, dark = (rgb(v) for v in LEATHER)
    image = Image.new("RGB", (TEX, TEX))
    pixels = image.load()
    for y in range(TEX):
        for x in range(TEX):
            band = math.sin((x * 1.0 + y * 1.6) * 0.55) * 0.5 + 0.5
            colour = mix(dark, light, smooth(band) * 0.22)
            pixels[x, y] = shade(colour, 0.97 + band * 0.04)
    return image


def flat_texture(colours, highlight=0.0):
    base, light, dark = (rgb(v) for v in colours)
    image = Image.new("RGB", (TEX, TEX))
    pixels = image.load()
    for y in range(TEX):
        for x in range(TEX):
            t = x / (TEX - 1)
            colour = mix(dark, base, smooth(t * 1.6)) if t < 0.5 else mix(base, light, smooth((t - 0.5) * 1.9))
            pixels[x, y] = shade(colour, 1.0 + math.sin(y * 1.1) * 0.02)
        pixels[TEX // 3, y] = shade(pixels[TEX // 3, y], 1.0 + highlight)
    return image


def band_texture():
    base, light, dark = (rgb(v) for v in LEATHER)
    image = Image.new("RGB", (TEX, TEX))
    pixels = image.load()
    for y in range(TEX):
        for x in range(TEX):
            band = math.sin(y * 2.2) * 0.5 + 0.5
            pixels[x, y] = shade(mix(dark, base, band * 0.7), 0.92)
    return image


def hole_texture():
    dark = rgb("#171319")
    rim = rgb("#000000")
    image = Image.new("RGB", (TEX, TEX))
    pixels = image.load()
    for y in range(TEX):
        for x in range(TEX):
            d = math.hypot(x - TEX / 2, y - TEX / 2) / (TEX / 2)
            pixels[x, y] = mix(dark, rim, smooth(d))
    return image


# --------------------------------------------------------------------- geometry
class Part:
    """One cuboid, in the upright authoring frame.

    ``cx``/``cy`` is the centre, ``w``/``h`` the size in the knife's plane,
    ``depth`` the thickness, ``angle`` the in-plane rotation.  ``uv`` selects
    the horizontal slice of the part's texture, which is how the plate and the
    edge of one blade share a continuous gradient; ``flip`` mirrors that slice
    for parts on the other side of the blade.
    """

    __slots__ = ("role", "cx", "cy", "w", "h", "depth", "angle", "uv", "flip")

    def __init__(self, role, cx, cy, w, h, depth, angle=0.0,
                 uv=(0.0, 1.0), flip=False):
        if w <= 0.0 or h <= 0.0 or depth <= 0.0:
            raise ValueError(f"degenerate part: {role} {cx},{cy} {w}x{h}x{depth}")
        self.role = role
        self.cx, self.cy = cx, cy
        self.w, self.h = w, h
        self.depth = depth
        self.angle = angle
        self.uv = uv
        self.flip = flip


def seg(role, x0, y0, x1, y1, depth=1.0, angle=0.0, origin=None,
        uv=(0.0, 1.0), flip=False):
    """A part given by opposite corners; ``angle`` turns it about ``origin``."""
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    if angle:
        cx, cy = rotate(cx, cy, *(origin or (cx, cy)), angle)
    return Part(role, cx, cy, x1 - x0, y1 - y0, depth, angle, uv, flip)


def ring(role, cx, cy, radius, thickness, depth, sides=6, start=0.0):
    """A polygon ring, built from tangent cuboids.

    A torus is not possible with cuboids, but at item scale a hexagon reads as
    a ring - and a ring is what makes a karambit recognisable at a glance.
    """
    parts = []
    for i in range(sides):
        angle = start + i * (360.0 / sides)
        rad = math.radians(angle)
        px = cx + math.cos(rad) * radius
        py = cy + math.sin(rad) * radius
        half = radius * math.tan(math.pi / sides) * 1.25
        parts.append(Part(role, px, py, half * 2.0, thickness, depth, angle + 90.0))
    return parts


def catmull(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return tuple(
        0.5 * ((2 * p1[i]) + (-p0[i] + p2[i]) * t
               + (2 * p0[i] - 5 * p1[i] + 4 * p2[i] - p3[i]) * t2
               + (-p0[i] + 3 * p1[i] - 3 * p2[i] + p3[i]) * t3)
        for i in range(2)
    )


def resample(points, widths, steps=4):
    """A smooth centre line through the given points.

    A curved blade built from a handful of long segments shows a step at every
    joint. Resampling the same curve into short segments makes those steps
    small enough that the blade reads as one continuous piece of steel.
    """
    out_points, out_widths = [], []
    count = len(points)
    for i in range(count - 1):
        p0 = points[max(0, i - 1)]
        p1, p2 = points[i], points[i + 1]
        p3 = points[min(count - 1, i + 2)]
        for step in range(steps):
            t = step / float(steps)
            out_points.append(catmull(p0, p1, p2, p3, t))
            out_widths.append(widths[i] + (widths[i + 1] - widths[i]) * t)
    out_points.append(points[-1])
    out_widths.append(widths[-1])
    return out_points, out_widths


def is_curved(points, threshold=2.0):
    for i in range(1, len(points) - 1):
        ax = points[i][0] - points[i - 1][0]
        ay = points[i][1] - points[i - 1][1]
        bx = points[i + 1][0] - points[i][0]
        by = points[i + 1][1] - points[i][1]
        turn = abs(math.degrees(math.atan2(ax * by - ay * bx, ax * bx + ay * by)))
        if turn > threshold:
            return True
    return False


def blade_path(parts, points, widths, depth, edge=1, overlap=None,
               uv=(0.04, 0.78), spine=True):
    """A blade run along a centre line: plate, bright edge, forged spine.

    Placing each segment on the line it belongs to, and overlapping neighbours
    slightly, is what keeps a curved blade continuous: consecutive segments
    meet inside each other instead of showing a join.

    Three strips, not one. The plate carries the body of the steel, the bright
    strip along the cutting side reads as the grind, and a narrow, slightly
    proud strip along the spine reads as the forged back of the blade. That
    last strip is what gives the knife an edge in silhouette from the side:
    without it a blade is a flat card, whatever texture is painted on it.
    """
    curved = is_curved(points)
    if curved:
        # Dense sampling on purpose: a cuboid chain can only approximate a
        # curve, and the error at each joint is what shows as a tooth on the
        # silhouette. Ten slices per span pushes that error below a pixel at
        # the size a held knife is actually drawn.
        points, widths = resample(points, widths, 10)
    if overlap is None:
        # A curved blade is a chain of short segments; the overlap is what
        # hides the joint between them. On a straight blade there is no joint
        # to hide, and the overlap would only make the ends uneven.
        overlap = 0.18 if curved else 0.0
    for i in range(len(points) - 1):
        (x0, y0), (x1, y1) = points[i], points[i + 1]
        dx, dy = x1 - x0, y1 - y0
        length = math.hypot(dx, dy)
        if length < 1e-6:
            continue
        angle = math.degrees(math.atan2(dy, dx)) - 90.0
        half = (widths[i] + widths[i + 1]) / 2.0
        cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        ca, sa = math.cos(math.radians(angle)), math.sin(math.radians(angle))
        edge_w = max(0.085, 0.13 * half)
        # Alternate the thickness by a hair. Consecutive segments overlap, and
        # without this their two front faces would lie in exactly the same
        # plane and flicker against each other.
        jitter = 1.0 + 0.012 * (i % 2)
        if curved:
            # A curved blade is drawn as plate strips only, with the bright
            # grind painted into the strip. A separate proud edge strip would
            # show its own end face at every joint - which is what turns a
            # smooth hook into a saw blade at item scale.
            parts.append(Part("blade", cx, cy, half * 2.0, length + overlap,
                              depth * jitter, angle, (0.04, 1.0),
                              flip=edge < 0))
            continue
        plate_cx = cx - edge * (edge_w / 2.0) * ca
        plate_cy = cy - edge * (edge_w / 2.0) * sa
        parts.append(Part("blade", plate_cx, plate_cy, (half - edge_w) * 2.0,
                          length + overlap, depth * jitter, angle, uv,
                          flip=edge < 0))
        off = edge * (half - edge_w / 2.0)
        parts.append(Part("edge", cx + off * ca, cy + off * sa, edge_w,
                          length + overlap + 0.05,
                          depth * 1.14 * jitter, angle, (0.88, 1.0),
                          flip=edge < 0))
        # The spine: a thin, proud strip of the darkest part of the steel, so
        # the back of the blade catches a highlight instead of disappearing.
        # Straight blades only: on a curve, consecutive strips would step in
        # and out of each other and the back of the blade would look sawn.
        if spine and not curved:
            spine_w = max(0.075, 0.12 * half)
            spine_off = -edge * (half - spine_w / 2.0)
            parts.append(Part("blade", cx + spine_off * ca, cy + spine_off * sa,
                              spine_w, length + overlap + 0.02,
                              depth * 1.08 * jitter, angle, (0.0, 0.20),
                              flip=edge > 0))


def blade_double(parts, points, widths, depth, uv=(0.0, 0.16)):
    """A double edged blade: a dark fuller, bright edge on each side.

    Built on a centre line like the single edged blade, so a dagger can taper
    along its length instead of being a bar with a point glued on.
    """
    curved = is_curved(points)
    if curved:
        points, widths = resample(points, widths, 6)
    overlap = 0.18 if curved else 0.0
    for i in range(len(points) - 1):
        (x0, y0), (x1, y1) = points[i], points[i + 1]
        dx, dy = x1 - x0, y1 - y0
        length = math.hypot(dx, dy)
        if length < 1e-6:
            continue
        angle = math.degrees(math.atan2(dy, dx)) - 90.0
        half = (widths[i] + widths[i + 1]) / 2.0
        cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        edge_w = max(0.085, 0.13 * half)
        jitter = 1.0
        parts.append(Part("blade", cx, cy, (half - edge_w) * 2.0,
                          length + overlap, depth * jitter, angle, uv))
        ca, sa = math.cos(math.radians(angle)), math.sin(math.radians(angle))
        for side in (1, -1):
            off = side * (half - edge_w / 2.0)
            parts.append(Part("edge", cx + off * ca, cy + off * sa, edge_w,
                              length + overlap + 0.05,
                              depth * 1.14 * jitter, angle, (0.88, 1.0),
                              flip=side < 0))
        # A central ridge between the two grinds. A double edged blade without
        # one reads as a flat strip no matter how it is lit. Only on straight
        # blades, for the same reason as the spine strip.
        if not curved:
            ridge_w = max(0.085, 0.15 * half)
            parts.append(Part("blade", cx, cy, ridge_w, length + overlap,
                              depth * 1.10 * jitter, angle, (0.42, 0.60)))


def blade_tip(parts, x, y, half, depth, angle=0.0, flip=False):
    """A pointed tip, from a square turned forty five degrees.

    The lower half of the square sits inside the blade, so what shows is a
    clean triangular point of exactly the blade's width - no bulge and no
    cuboid step at the last and most visible millimetre of the knife.

    The square is exactly as wide as the blade on purpose: a narrower one
    leaves a step between the plate and the point, which is the one place a
    knife must not have one.
    """
    side = half * 2.0 / math.sqrt(2.0)
    # The tip is the full thickness of the blade.  A thinner point sits inside
    # the rim of the plate it ends, which reads as a hollow funnel from the
    # side - the one place a cuboid point must not be recessed.
    parts.append(Part("blade", x, y, side, side, depth * 1.02,
                      angle + 45.0, (0.10, 0.92), flip))


def styles():
    table = {}

    # ---- butterfly: a balisong, open, two handle bars either side of a pivot
    butterfly = []
    blade_path(butterfly, [(8.0, 5.30), (8.0, 9.90), (8.0, 12.30)],
               [0.86, 0.78, 0.58], 0.95)
    blade_tip(butterfly, 8.0, 12.30, 0.58, 0.95)
    butterfly += [
        seg("accent", 6.90, 4.45, 9.10, 5.32, 1.85),
        seg("grip", 6.72, 0.20, 7.70, 4.72, 1.90),
        seg("grip", 8.30, 0.20, 9.28, 4.72, 1.90),
        seg("accent", 6.62, -0.28, 7.62, 0.22, 2.00),
        seg("accent", 8.38, -0.28, 9.38, 0.22, 2.00),
        seg("accent", 7.05, 1.00, 7.33, 1.24, 1.96),
        seg("accent", 8.67, 1.00, 8.95, 1.24, 1.96),
        # The pivot boss: the pin the whole knife turns on, and the reason a
        # balisong is recognisable even as a still picture.
        seg("guard", 7.55, 4.30, 8.45, 5.20, 2.06),
        seg("hole", 7.88, 4.64, 8.12, 4.88, 2.12),
        # The latch ends at the base of the two handles.
        seg("accent", 6.92, -0.60, 7.44, -0.24, 2.06),
        seg("accent", 8.56, -0.60, 9.08, -0.24, 2.06),
    ]
    table["butterfly"] = butterfly

    # ---- karambit: hook blade, finger ring where the pommel would be
    # The ring is a ten sided polygon rather than a hexagon: at item scale the
    # difference between a ring and a nut is exactly the number of sides, and
    # the ring is the one feature that identifies a karambit at a glance.
    karambit = ring("guard", 7.55, -1.35, 1.32, 0.46, 1.12, 10, 18.0)
    karambit += [
        # The backspacer runs from the ring into the handle, which is what
        # makes the ring part of the knife instead of a hoop behind it.
        seg("grip", 6.98, -1.10, 8.42, 0.55, 1.28),
        seg("grip", 7.05, 0.15, 8.35, 4.15, 1.45, -8.0, (7.70, 0.55)),
        seg("accent", 6.90, 4.10, 8.80, 4.72, 1.65),
        # Thumb ramp jimping: three teeth on the spine where the thumb sits.
        seg("accent", 6.78, 3.62, 7.06, 3.80, 1.62),
        seg("accent", 6.82, 3.90, 7.10, 4.08, 1.62),
        seg("accent", 6.88, 4.18, 7.16, 4.36, 1.62),
    ]
    hook = [(7.95, 4.15), (8.30, 7.10), (9.15, 9.20), (10.45, 10.55), (11.75, 11.05)]
    blade_path(karambit, hook, [0.94, 0.99, 1.02, 0.88, 0.62], 0.92)
    last = hook[-1]
    blade_tip(karambit, last[0], last[1], 0.78, 0.92,
              math.degrees(math.atan2(last[1] - hook[-2][1], last[0] - hook[-2][0])) - 90.0)
    table["karambit"] = karambit

    # ---- cleaver: broad butchers blade, slab spine, hanging hole
    cleaver = [
        seg("accent", 7.05, -0.28, 8.95, 0.32, 1.75),
        seg("grip", 7.20, 0.28, 8.80, 6.45, 1.60),
        seg("guard", 6.90, 6.40, 9.10, 7.15, 1.70),
        # A forged bolster between the handle and the blade, proud of both.
        seg("accent", 6.86, 6.42, 9.14, 6.86, 1.86),
        # Rivets through the full tang.
        seg("accent", 7.24, 2.15, 7.50, 2.41, 1.72),
        seg("accent", 8.50, 2.15, 8.76, 2.41, 1.72),
        seg("accent", 7.24, 4.55, 7.50, 4.81, 1.72),
        seg("accent", 8.50, 4.55, 8.76, 4.81, 1.72),
    ]
    blade_path(cleaver, [(9.30, 7.15), (9.30, 14.40)], [2.55, 2.55], 1.02,
               edge=1)
    cleaver += [
        # the spine of a cleaver is a thick slab the plate hangs from
        seg("blade", 8.42, 7.15, 9.05, 14.45, 1.18, uv=(0.0, 0.34)),
        seg("blade", 9.05, 14.05, 14.42, 14.48, 1.06, uv=(0.0, 0.34)),
        seg("hole", 10.60, 12.55, 11.45, 13.40, 1.24, uv=(0.0, 1.0)),
        # Thumb notch on the spine, where a butcher braces the blade.
        seg("hole", 9.05, 13.55, 9.45, 13.95, 1.22, uv=(0.0, 1.0)),
    ]
    table["cleaver"] = cleaver

    # ---- dagger: double edged, slender, slim crossguard
    dagger = [
        seg("accent", 6.95, -0.22, 9.05, 0.38, 1.55),
        seg("grip", 7.32, 0.35, 8.68, 3.95, 1.16),
        seg("accent", 7.24, 0.55, 8.76, 0.78, 1.32),
        seg("accent", 7.24, 3.50, 8.76, 3.73, 1.32),
        seg("guard", 6.55, 3.98, 9.45, 4.46, 0.95),
        seg("guard", 7.65, 3.92, 8.35, 4.58, 1.35),
        # A pommel disc, so the grip ends in hardware rather than in a cut.
        seg("accent", 7.34, -0.86, 8.66, -0.42, 1.42),
        # Ricasso: the short unsharpened section a guard is fitted against.
        seg("guard", 7.54, 4.52, 8.46, 4.92, 1.08),
    ]
    blade_double(dagger, [(8.0, 4.52), (8.0, 8.80), (8.0, 11.60)],
                 [0.68, 0.60, 0.44], 0.95)
    blade_tip(dagger, 8.0, 11.60, 0.44, 0.95)
    table["dagger"] = dagger

    # ---- tanto: straight single edged blade, wrapped grip, small tsuba
    tanto = [
        seg("accent", 6.95, -0.22, 9.05, 0.36, 1.60),
        seg("grip", 7.30, 0.32, 8.70, 4.05, 1.32),
        seg("wrap", 7.22, 0.72, 8.78, 1.08, 1.38),
        seg("wrap", 7.22, 1.52, 8.78, 1.88, 1.38),
        seg("wrap", 7.22, 2.32, 8.78, 2.68, 1.38),
        seg("wrap", 7.22, 3.12, 8.78, 3.48, 1.38),
        seg("guard", 6.75, 4.02, 9.25, 4.48, 0.95),
        seg("guard", 7.65, 3.96, 8.35, 4.56, 1.32),
        # Kashira: the metal cap on the butt of a wrapped Japanese grip.
        seg("accent", 7.28, -0.52, 8.72, -0.18, 1.46),
        # Habaki: the collar that locks the blade into the handle.
        seg("accent", 7.44, 4.30, 8.56, 4.74, 1.44),
        # Mekugi pin through the tang.
        seg("accent", 7.68, 2.60, 7.92, 2.84, 1.42),
    ]
    blade_path(tanto, [(8.0, 4.52), (8.0, 9.60), (8.0, 12.10)],
               [0.94, 0.86, 0.62], 0.95)
    blade_tip(tanto, 8.0, 12.10, 0.62, 0.95)
    table["tanto"] = tanto

    # ---- kukri: forward curved heavy blade, flared butt
    kukri = [
        seg("grip", 6.62, -0.38, 8.72, 0.38, 1.70),
        seg("grip", 7.02, 0.12, 8.38, 4.10, 1.50, 7.0, (7.70, 0.18)),
        seg("accent", 6.78, 4.06, 8.86, 4.68, 1.70),
        # A pinned bolster, and the pin that holds it.
        seg("accent", 6.82, 4.02, 8.90, 4.42, 1.82),
        seg("hole", 7.74, 4.08, 7.96, 4.30, 1.88),
    ]
    curve = [(7.95, 4.35), (8.35, 7.20), (9.35, 9.55), (11.05, 11.35), (13.05, 12.55)]
    blade_path(kukri, curve, [0.90, 1.00, 1.12, 1.26, 1.22], 1.00)
    last = curve[-1]
    blade_tip(kukri, last[0], last[1], 1.22, 1.00,
              math.degrees(math.atan2(last[1] - curve[-2][1], last[0] - curve[-2][0])) - 90.0)
    table["kukri"] = kukri

    # ---- machete: long chopping blade, heavy spine slab, riveted full tang
    machete = [
        seg("accent", 6.85, -0.60, 9.15, 0.14, 1.85),
        seg("grip", 7.02, 0.00, 8.98, 4.75, 1.60),
        seg("guard", 6.78, 4.68, 9.22, 5.16, 1.52),
    ]
    for rivet_y in (0.95, 2.30, 3.65):
        machete.append(seg("accent", 7.30, rivet_y, 7.58, rivet_y + 0.26, 1.74))
    # Jimping on the spine, just forward of the grip: the notches a hand braces
    # against when the blade is pushed through something heavy.
    for jimp_y in (5.18, 5.50, 5.82, 6.14):
        machete.append(seg("accent", 7.08, jimp_y, 7.30, jimp_y + 0.18, 1.26))
    # A machete is a long broad blade with a heavy spine.  It is built from one
    # long plate tilted a few degrees off the grip axis, a proud edge strip on
    # the same line and a thick spine slab, so every blade surface stays a
    # single flat face.  A curve here would read as a row of steps, not a blade.
    tilt = -5.0
    root = (7.10, 4.95)
    machete += [
        seg("blade", 7.10, 4.95, 9.90, 14.70, 1.02, tilt, root),
        seg("edge", 9.74, 5.20, 10.06, 14.55, 1.16, tilt, root, (0.88, 1.0)),
        seg("blade", 6.96, 5.05, 7.30, 14.40, 1.20, tilt, root, (0.0, 0.34)),
    ]
    tip_x, tip_y = rotate(8.50, 14.70, root[0], root[1], tilt)
    blade_tip(machete, tip_x, tip_y, 1.40, 1.02, tilt)
    # The fuller: a shallow groove down the middle of the blade, darker than
    # the steel around it, which is what stops a long blade reading as a plank.
    machete.append(seg("blade", 8.02, 5.30, 8.44, 13.90, 1.09, tilt, root, (0.02, 0.24)))
    table["machete"] = machete

    # ---- stiletto: needle blade, slim crossguard, tapered grip
    stiletto = [
        seg("accent", 7.30, -0.60, 8.70, -0.02, 1.45),
        seg("grip", 7.54, 0.00, 8.46, 3.55, 1.00),
        seg("guard", 6.60, 3.52, 9.40, 3.92, 0.85),
        seg("guard", 7.62, 3.44, 8.38, 4.02, 1.30),
        # Two quillons: the long thin crossguard of a thrusting blade.
        seg("guard", 6.30, 3.60, 6.66, 3.84, 0.92),
        seg("guard", 9.34, 3.60, 9.70, 3.84, 0.92),
        # A weighted pommel, and a ricasso behind the guard.
        seg("accent", 7.56, -0.96, 8.44, -0.56, 1.34),
        seg("guard", 7.54, 3.98, 8.46, 4.22, 0.94),
    ]
    for band_y in (0.34, 1.24, 2.14):
        stiletto.append(seg("accent", 7.50, band_y, 8.50, band_y + 0.18, 1.06))
    blade_double(stiletto, [(8.0, 4.00), (8.0, 8.40), (8.0, 12.20)],
                 [0.58, 0.50, 0.34], 0.88)
    blade_tip(stiletto, 8.0, 12.20, 0.34, 0.88)
    table["stiletto"] = stiletto

    return table


# --------------------------------------------------------------------- display
# Copied from item/handheld and item/generated: a model with elements must not
# inherit builtin/generated, which would add a flat quad next to the geometry.
HANDHELD_DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]},
    "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}


def solve(parts, grip, target=15.2):
    """Turns the authored parts into final model-space cuboids.

    Every part is turned with the whole knife about the grip point, so the fit
    is a rigid transform and the knife keeps its proportions.
    """
    placed = []
    for part in parts:
        mx, my = rotate(part.cx, part.cy, grip[0], grip[1], DIAGONAL)
        placed.append({
            "role": part.role,
            "cx": mx, "cy": my,
            "w": part.w, "h": part.h,
            "depth": part.depth,
            "angle": part.angle + DIAGONAL,
            "uv": part.uv,
            "flip": part.flip,
        })

    # Uniform fit: every style ends up the same overall size, so switching
    # styles never makes the knife jump in the hand.
    xs, ys = [], []
    for p in placed:
        rad = math.radians(p["angle"])
        cos, sin = math.cos(rad), math.sin(rad)
        for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            hx, hy = p["w"] / 2.0 * sx, p["h"] / 2.0 * sy
            xs.append(p["cx"] + hx * cos - hy * sin)
            ys.append(p["cy"] + hx * sin + hy * cos)
    width, height = max(xs) - min(xs), max(ys) - min(ys)
    scale = target / max(width, height, 1e-6)
    mid_x = (max(xs) + min(xs)) / 2.0
    mid_y = (max(ys) + min(ys)) / 2.0
    for p in placed:
        p["cx"] = 8.0 + (p["cx"] - mid_x) * scale
        p["cy"] = 8.0 + (p["cy"] - mid_y) * scale
        p["w"] *= scale
        p["h"] *= scale
        p["depth"] *= scale
    return placed


def elements(placed):
    out = []
    for p in placed:
        theta = p["angle"]
        rad = math.radians(theta)
        hx, hy = p["w"] / 2.0, p["h"] / 2.0
        # The element rotates about its own ``from`` corner. Choosing that
        # corner so the rotated box lands on the solved centre keeps the
        # geometry exact without a second transform.
        px = p["cx"] - (hx * math.cos(rad) - hy * math.sin(rad))
        py = p["cy"] - (hx * math.sin(rad) + hy * math.cos(rad))
        z0 = 8.0 - p["depth"] / 2.0
        z1 = 8.0 + p["depth"] / 2.0
        u0, u1 = p["uv"]
        faces = {}
        for direction in ("north", "south", "east", "west", "up", "down"):
            face = {"texture": "#" + p["role"]}
            if direction in ("north", "south"):
                face["uv"] = [round(u0 * UV, 4), 0, round(u1 * UV, 4), UV]
                if p["flip"]:
                    face["rotation"] = 180
            else:
                face["uv"] = [0, 0, UV, UV]
            faces[direction] = face
        out.append({
            "from": [round(px, 4), round(py, 4), round(z0, 4)],
            "to": [round(px + p["w"], 4), round(py + p["h"], 4), round(z1, 4)],
            "rotation": {
                "origin": [round(px, 4), round(py, 4), 8.0],
                "x": 0.0, "y": 0.0, "z": round(theta, 3),
            },
            "faces": faces,
        })
    return out


# ------------------------------------------------------------------------ main
def java_constants(name):
    with open(JAVA_MODULE, encoding="utf-8") as handle:
        source = handle.read()
    match = re.search(rf"{name}\s*=\s*\{{([^}}]*)\}}", source)
    if not match:
        raise SystemExit(f"could not find {name} in {JAVA_MODULE}")
    return re.findall(r'"([^"]+)"', match.group(1))


def write_json(path, payload):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # Compact on purpose: a generated model is 100+ cuboids with six faces
    # each, and indentation tripled the size of the 48 shipped models without
    # making a single number easier to read. The generator is the source these
    # files are read from when a human wants to inspect them.
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, separators=(",", ":"))
        handle.write("\n")


def main():
    order = java_constants("STYLES")
    materials = java_constants("MATERIALS")
    table = styles()
    if list(table) != order:
        raise SystemExit(f"style order {list(table)} does not match STYLES {order}")
    if list(MATERIALS) != materials:
        raise SystemExit(f"material order {list(MATERIALS)} does not match MATERIALS {materials}")

    textures_dir = os.path.join(ASSETS, "textures", "item", "knife")
    models_dir = os.path.join(ASSETS, "models", "item", "knife")
    items_dir = os.path.join(ASSETS, "items", "knife")
    for directory in (textures_dir, models_dir, items_dir):
        os.makedirs(directory, exist_ok=True)

    # ---- textures
    written = 0
    for material in materials:
        blade_texture(material).save(os.path.join(textures_dir, f"blade_{material}.png"))
        edge_texture(material).save(os.path.join(textures_dir, f"edge_{material}.png"))
        written += 2
    wood_grip_texture().save(os.path.join(textures_dir, "grip_wooden.png"))
    wrapped_grip_texture().save(os.path.join(textures_dir, "grip_wrapped.png"))
    flat_texture(STEEL, 0.10).save(os.path.join(textures_dir, "guard.png"))
    flat_texture(BRASS, 0.16).save(os.path.join(textures_dir, "accent.png"))
    band_texture().save(os.path.join(textures_dir, "wrap.png"))
    hole_texture().save(os.path.join(textures_dir, "hole.png"))
    written += 6

    # ---- models
    grip_points = {
        "butterfly": (8.0, 2.5), "karambit": (7.7, 2.1), "cleaver": (8.0, 3.3),
        "dagger": (8.0, 2.2), "tanto": (8.0, 2.2), "kukri": (7.75, 2.2),
        "machete": (8.0, 2.6), "stiletto": (8.0, 2.0),
    }
    for style in order:
        placed = solve(table[style], grip_points[style])
        geo = elements(placed)
        for material in materials:
            slots = {
                "blade": wrap(f"blade_{material}"),
                "edge": wrap(f"edge_{material}"),
                "grip": wrap("grip_wooden" if material == "wooden" else "grip_wrapped"),
                "guard": wrap("guard"),
                "accent": wrap("accent"),
                "wrap": wrap("wrap"),
                "hole": wrap("hole"),
            }
            name = f"{style}_{material}"
            write_json(os.path.join(models_dir, name + ".json"), {
                "gui_light": "front",
                "textures": slots,
                "display": HANDHELD_DISPLAY,
                "elements": geo,
            })
            write_json(os.path.join(items_dir, name + ".json"), {
                "model": {"type": "minecraft:model", "model": wrap(name)},
            })

    print(f"{len(order)} styles x {len(materials)} materials = {len(order) * len(materials)} knives")
    print(f"{written} textures, {len(order) * len(materials)} models, "
          f"{len(order) * len(materials)} item definitions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
