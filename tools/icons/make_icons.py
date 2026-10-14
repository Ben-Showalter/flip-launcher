#!/usr/bin/env python3
"""Renders Flip Launcher's built-in "ic_" icons from icons.csv.

Each icon is a full-bleed square - a deep diagonal gradient with a faint top
sheen - and a white glyph from Material Design Icons (Pictogrammers,
Apache-2.0) over a soft shadow. The launcher clips it to its squircle,
matching the stock Kyocera tiles. Writes
app/src/main/res/drawable-nodpi/ic_<name>.png and
app/src/main/java/com/flipos/launcher/data/BuiltInIconMap.kt.

    pip install cairosvg pillow
    python3 tools/icons/make_icons.py

Glyphs are downloaded from GitHub (Templarian/MaterialDesign) on each run,
or kept in GLYPH_CACHE (a directory) if that's set.
Behind a TLS-intercepting proxy, point SSL_CERT_FILE at its CA bundle.
"""

import csv
import io
import os
import re
import ssl
import sys
import urllib.request

import cairosvg
from PIL import Image, ImageFilter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CSV_PATH = os.path.join(os.path.dirname(__file__), "icons.csv")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
KOTLIN_PATH = os.path.join(ROOT, "app", "src", "main", "java", "com", "flipos", "launcher", "data", "BuiltInIconMap.kt")
GLYPH_URL = "https://raw.githubusercontent.com/Templarian/MaterialDesign/master/svg/{}.svg"

SIZE = 192  # px; drawable-nodpi, scaled down by the launcher
GLYPH_FRACTION = 0.50  # glyph box as a share of the tile

# Gradient start (top-left) / end (bottom-right) per color: deep, slightly
# desaturated tones with a small hue shift, rather than flat neon.
PALETTE = {
    "yellow": ("#F9C74F", "#E89A12"),
    "amber": ("#F4B544", "#D9820F"),
    "orange": ("#F59A55", "#D9601E"),
    "red": ("#EE6A65", "#C2353A"),
    "pink": ("#EC6C9C", "#B93A70"),
    "purple": ("#A174E0", "#6A42B5"),
    "indigo": ("#7A72E6", "#463CB0"),
    "blue": ("#4F8DEB", "#2357C2"),
    "sky": ("#4DB4E8", "#1F7FC2"),
    "teal": ("#3FC2A2", "#12876E"),
    "green": ("#5CC27A", "#2A8C4A"),
    "brown": ("#B98A64", "#7E5636"),
    "navy": ("#52698F", "#26364F"),
    "slate": ("#8794A6", "#525D6E"),
    "gray": ("#A2A7AF", "#686D75"),
    "charcoal": ("#5E636B", "#2E3237"),
}

SHEEN_ALPHA = 0.16  # white sheen at the very top, fading out by SHEEN_END
SHEEN_END = 0.55
SHADOW_ALPHA = 0.30  # soft shadow under the glyph
SHADOW_BLUR = 0.022  # as a share of SIZE
SHADOW_OFFSET = 0.018

CATEGORIES = {"dialer", "sms", "contacts", "camera", "gallery", "calendar", "email", "music", "settings"}


def ssl_context():
    cafile = os.environ.get("SSL_CERT_FILE")
    return ssl.create_default_context(cafile=cafile) if cafile else ssl.create_default_context()


def fetch_glyph(name, context):
    cache = os.environ.get("GLYPH_CACHE")
    cached = os.path.join(cache, f"{name}.svg") if cache else None
    if cached and os.path.exists(cached):
        with open(cached) as f:
            return f.read()
    with urllib.request.urlopen(GLYPH_URL.format(name), context=context, timeout=30) as response:
        svg = response.read().decode("utf-8")
    if cached:
        os.makedirs(cache, exist_ok=True)
        with open(cached, "w") as f:
            f.write(svg)
    return svg


def hex_rgb(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def tile_background(start, end):
    """Diagonal gradient (mostly top to bottom) with a soft white sheen up top."""
    start, end = hex_rgb(start), hex_rgb(end)
    tile = Image.new("RGBA", (SIZE, SIZE))
    pixels = tile.load()
    last = SIZE - 1
    for y in range(SIZE):
        sheen = max(0.0, 1 - (y / last) / SHEEN_END) ** 2 * SHEEN_ALPHA
        for x in range(SIZE):
            t = 0.82 * (y / last) + 0.18 * (x / last)
            t = t * t * (3 - 2 * t)  # smoothstep: gentler ends
            base = [start[i] + (end[i] - start[i]) * t for i in range(3)]
            color = tuple(round(c + (255 - c) * sheen) for c in base)
            pixels[x, y] = color + (255,)
    return tile


def white_glyph(svg):
    # MDI paths carry no fill; make every shape white.
    svg = re.sub(r"<path ", '<path fill="#FFFFFF" ', svg)
    box = round(SIZE * GLYPH_FRACTION)
    png = cairosvg.svg2png(bytestring=svg.encode("utf-8"), output_width=box, output_height=box)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def compose(background, glyph):
    """Centers [glyph] on [background] over a soft drop shadow."""
    offset = (SIZE - glyph.width) // 2
    alpha = glyph.getchannel("A").point(lambda a: round(a * SHADOW_ALPHA))
    shadow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    shadow_layer = Image.new("RGBA", glyph.size, (0, 0, 0, 255))
    shadow_layer.putalpha(alpha)
    shadow.alpha_composite(shadow_layer, (offset, offset + round(SIZE * SHADOW_OFFSET)))
    shadow = shadow.filter(ImageFilter.GaussianBlur(SIZE * SHADOW_BLUR))
    background.alpha_composite(shadow)
    background.alpha_composite(glyph, (offset, offset))
    return background


def read_rows():
    rows = []
    with open(CSV_PATH, newline="") as f:
        for row in csv.reader(line for line in f if line.strip() and not line.startswith("#")):
            name, glyph, color, match = (row + [""] * 4)[:4]
            if color not in PALETTE:
                sys.exit(f"{name}: unknown color '{color}'")
            rules = match.split()
            for rule in rules:
                kind, _, value = rule.partition(":")
                if kind not in ("cat", "pkg", "cmp") or not value or (kind == "cat" and value not in CATEGORIES):
                    sys.exit(f"{name}: bad match rule '{rule}'")
            rows.append((name, glyph, color, rules))
    names = [r[0] for r in rows]
    if len(names) != len(set(names)):
        sys.exit("duplicate icon names in icons.csv")
    return rows


def write_kotlin(rows):
    lines = [
        "// Generated by tools/icons/make_icons.py from tools/icons/icons.csv - don't edit by hand.",
        "package com.flipos.launcher.data",
        "",
        "/**",
        " * The launcher's generated \"ic_\" icons, in picker order, each with the",
        " * rules that match it to installed apps for the one-time \"Replace app",
        " * icons\" action (see [BuiltInIcons.autoAssignments]): `cat:` a CategoryApps",
        " * category, `pkg:` a package's main entry, `cmp:` one exact component.",
        " */",
        "object BuiltInIconMap {",
        "",
        "    class Entry(val name: String, val rules: List<String>)",
        "",
        "    val ENTRIES = listOf(",
    ]
    for name, _, _, rules in rows:
        rule_list = ", ".join(f'"{r}"' for r in rules)
        lines.append(f'        Entry("ic_{name}", listOf({rule_list})),')
    lines += ["    )", "}", ""]
    with open(KOTLIN_PATH, "w") as f:
        f.write("\n".join(lines))


def main():
    rows = read_rows()
    context = ssl_context()
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, glyph, color, _ in rows:
        tile = compose(tile_background(*PALETTE[color]), white_glyph(fetch_glyph(glyph, context)))
        tile.save(os.path.join(OUT_DIR, f"ic_{name}.png"), optimize=True)
        print(f"ic_{name}.png")
    write_kotlin(rows)
    print(f"{len(rows)} icons, {os.path.relpath(KOTLIN_PATH, ROOT)}")


if __name__ == "__main__":
    main()
