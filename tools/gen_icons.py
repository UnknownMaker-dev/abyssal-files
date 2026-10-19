#!/usr/bin/env python3
"""Regenerates the default pixel icons in app/src/main/assets/icons/*.txt.

WARNING: overwrites every default icon, including edits made by hand to those .txt files.
Usage: python3 tools/gen_icons.py [out_dir] [preview.png]
"""
import sys
from pathlib import Path

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "app/src/main/assets/icons"
PREVIEW = Path(sys.argv[2]) if len(sys.argv) > 2 else None
OUT.mkdir(parents=True, exist_ok=True)

HEADER = """# Abyssal Files pixel icon. Each character of the grid is one pixel, '.' is transparent.
# Colours: #RRGGBB, @accent (theme colour) or @type (this icon's own colour - it becomes
# the theme colour when Settings > Icon colours is "Theme colour").
# "*0.5" darkens and "+0.5" lightens a colour (0..1).
# Put an edited copy (or a PNG with the same name) in Internal storage/AbyssalFiles/icons
# and press Settings > Reload icons to replace it without rebuilding.
"""

FONT = {
    "A": [".#.", "#.#", "###", "#.#", "#.#"], "B": ["##.", "#.#", "##.", "#.#", "##."],
    "C": [".##", "#..", "#..", "#..", ".##"], "D": ["##.", "#.#", "#.#", "#.#", "##."],
    "E": ["###", "#..", "##.", "#..", "###"], "F": ["###", "#..", "##.", "#..", "#.."],
    "G": [".##", "#..", "#.#", "#.#", ".##"], "H": ["#.#", "#.#", "###", "#.#", "#.#"],
    "I": ["###", ".#.", ".#.", ".#.", "###"], "J": ["..#", "..#", "..#", "#.#", ".#."],
    "K": ["#.#", "#.#", "##.", "#.#", "#.#"], "L": ["#..", "#..", "#..", "#..", "###"],
    "M": ["#.#", "###", "###", "#.#", "#.#"], "N": ["##.", "#.#", "#.#", "#.#", "#.#"],
    "O": [".#.", "#.#", "#.#", "#.#", ".#."], "P": ["##.", "#.#", "##.", "#..", "#.."],
    "Q": [".#.", "#.#", "#.#", "##.", ".##"], "R": ["##.", "#.#", "##.", "#.#", "#.#"],
    "S": [".##", "#..", ".#.", "..#", "##."], "T": ["###", ".#.", ".#.", ".#.", ".#."],
    "U": ["#.#", "#.#", "#.#", "#.#", "###"], "V": ["#.#", "#.#", "#.#", "#.#", ".#."],
    "W": ["#.#", "#.#", "###", "###", "#.#"], "X": ["#.#", "#.#", ".#.", "#.#", "#.#"],
    "Y": ["#.#", "#.#", ".#.", ".#.", ".#."], "Z": ["###", "..#", ".#.", "#..", "###"],
    "+": ["...", ".#.", "###", ".#.", "..."], "#": ["#.#", "###", "#.#", "###", "#.#"],
    "<": ["..#", ".#.", "#..", ".#.", "..#"], ">": ["#..", ".#.", "..#", ".#.", "#.."],
    "/": ["..#", "..#", ".#.", "#..", "#.."], "{": [".##", ".#.", "##.", ".#.", ".##"],
    "}": ["##.", ".#.", ".##", ".#.", "##."],
    "3": ["##.", "..#", ".#.", "..#", "##."],
}

PAGE = [
    "................",
    "..OOOOOOOO......",
    "..OPPPPPPOFO....",
    "..OPPPPPPOFFO...",
    "..OPPPPPPOFFFO..",
    "..OPPPPPPOOOOO..",
    "..OPPPPPPPPPPO..",
    "..OPPPPPPPPPPO..",
    "..OPPPPPPPPPPO..",
    "..OPPPPPPPPPPO..",
    "..OPPPPPPPPPPO..",
    "..OPPPPPPPPPPO..",
    "..OPPPPPPPPPPO..",
    "..OPPPPPPPPPPO..",
    "..OOOOOOOOOOOO..",
    "................",
]
PAGE_COLORS = {"O": "@type", "P": "@type*0.22", "F": "@type*0.55", "G": "@type"}

ICONS = {}  # name -> (type, colors, grid)


def page_glyph(name, type_, glyph, extra=None):
    g = [list(r) for r in PAGE]
    for y, row in enumerate(glyph):
        for x, ch in enumerate(row):
            if ch != ".":
                g[6 + y][4 + x] = ch
    ICONS[name] = (type_, {**PAGE_COLORS, **(extra or {})}, ["".join(r) for r in g])


def page_label(name, type_, text, letters="#141418"):
    g = [list(r) for r in PAGE]
    for y in range(7, 14):
        for x in range(1, 15):
            g[y][x] = "B"
    w = 4 * len(text) - 1
    x0 = 1 + (14 - w) // 2
    for i, ch in enumerate(text):
        for y, row in enumerate(FONT[ch]):
            for x, px in enumerate(row):
                if px == "#":
                    g[8 + y][x0 + i * 4 + x] = "K"
    ICONS[name] = (type_, {**PAGE_COLORS, "B": "@type", "K": letters}, ["".join(r) for r in g])


# ───── pictogram files ─────
page_glyph("file", "#8B929B", ["........"] * 7)
page_glyph("image", "#4CAF7A", ["......Y.", ".....YYY", "......Y.", "..G.....", ".GGG..G.", "GGGGGGGG", "GGGGGGGG"], {"Y": "#FFD34D"})
page_glyph("video", "#A78BFA", ["........", "..G.....", "..GGG...", "..GGGGG.", "..GGG...", "..G.....", "........"])
page_glyph("audio", "#F472B6", ["...GGGGG", "...GGGGG", "...G...G", "...G...G", ".GGG.GGG", "GGGGGGGG", ".GG...GG"])
page_glyph("text", "#C9CDD6", ["GGGGGG..", "........", "GGGGGGGG", "........", "GGGGGGGG", "........", "GGGG...."])
page_glyph("document", "#4DA3FF", ["GGGGGGGG", "GGGGGGGG", "........", "GGGGGGGG", "........", "GGGGGGGG", "GGGGG..."])
page_glyph("code", "#22D3EE", ["........", ".GG..GG.", ".G....G.", "G......G", ".G....G.", ".GG..GG.", "........"])
page_glyph("archive", "#FFA94D", ["...G....", "....G...", "...G....", "....G...", "...GGG..", "...G.G..", "...GGG.."])
page_glyph("apk", "#A3E635", ["........", ".G....G.", "..GGGG..", ".GGGGGG.", ".GPGGPG.", ".GGGGGG.", "........"])
page_glyph("shell", "#4EAA25", ["........", "G.......", ".G......", "..G.....", ".G......", "G..GGGG.", "........"])
page_glyph("config", "#8FA1B3", ["...G....", ".G.G.G..", "..GGG...", "GGGPGGG.", "..GGG...", ".G.G.G..", "...G...."])
page_glyph("database", "#F29111", [".GGGGGG.", "GGGGGGGG", ".GGGGGG.", "G......G", ".GGGGGG.", "G......G", ".GGGGGG."])
page_glyph("font", "#E06C75", ["...GG...", "..GGGG..", ".GG..GG.", ".GG..GG.", ".GGGGGG.", ".GG..GG.", ".GG..GG."])
page_glyph("ebook", "#9C6ADE", ["GGGGGG..", "GPPPPGG.", "GPGGPGG.", "GPPPPGG.", "GPPPPGG.", "GGGGGGG.", ".GGGGGG."])
page_glyph("key", "#E5C07B", ["........", ".GG.....", "G..G....", "G..GGGGG", "G..G.G.G", ".GG.....", "........"])
page_glyph("disk", "#A0A8B8", ["..GGGG..", ".GGGGGG.", "GGG..GGG", "GG.PP.GG", "GGG..GGG", ".GGGGGG.", "..GGGG.."])

# ───── label files (VS Code style) ─────
LABELS = [
    ("pdf", "#E5534B", "PDF"), ("word", "#3B8EEA", "DOC"), ("excel", "#21A366", "XLS"),
    ("csv", "#3FB950", "CSV"), ("powerpoint", "#E8743B", "PPT"), ("json", "#F5C542", "{}"),
    ("html", "#E44D26", "</>"), ("css", "#2D9CDB", "CSS"), ("js", "#F7DF1E", "JS"),
    ("ts", "#3B82F6", "TS"), ("kotlin", "#A97BFF", "KT"), ("java", "#F89820", "JAV"),
    ("c", "#5C8DBC", "C"), ("cpp", "#659AD2", "C++"), ("csharp", "#B46FD8", "C#"),
    ("go", "#00ADD8", "GO"), ("rust", "#DEA584", "RS"), ("php", "#8892BF", "PHP"),
    ("ruby", "#E0524A", "RB"), ("swift", "#F05138", "SWF"), ("dart", "#29B6F6", "DRT"),
    ("lua", "#6C8EEF", "LUA"), ("vue", "#41B883", "VUE"), ("xml", "#F1662A", "XML"),
    ("svg", "#FFB13B", "SVG"), ("markdown", "#519ABA", "MD"), ("log", "#8B929B", "LOG"),
    ("subtitle", "#FFD34D", "SRT"), ("exe", "#A0A8B8", "EXE"), ("git", "#F05033", "GIT"),
]
for n, c, t in LABELS:
    page_label(n, c, t)
page_label("python", "#3776AB", "PY", letters="#FFD43B")

for n, c, t in [
    ("model3d", "#F5793A", "3D"), ("psd", "#31A8FF", "PSD"), ("illustrator", "#FF9A00", "AI"),
    ("torrent", "#6CC24A", "TOR"), ("jar", "#F89820", "JAR"), ("library", "#A0A8B8", "LIB"),
    ("backup", "#8B929B", "BAK"), ("notebook", "#F37626", "NB"), ("docker", "#2496ED", "DKR"),
    ("npm", "#E5534B", "NPM"), ("gradle", "#4DB6AC", "GRD"), ("license", "#E5C07B", "LIC"),
    ("mcfunction", "#5BAE3C", "MCF"), ("nbt", "#5BAE3C", "NBT"), ("mca", "#5BAE3C", "MCA"),
    ("yaml", "#CB6C6C", "YML"), ("sql", "#F29111", "SQL"), ("bat", "#C1F12E", "BAT"),
]:
    page_label(n, c, t)

page_glyph("game", "#E06CDF", ["........", ".GGGGGG.", "GGPGGGGG", "GPPPGGPG", "GGPGGPGG", ".GG..GG.", "........"])
page_glyph("mail", "#4FC3F7", ["GGGGGGGG", "GG....GG", "G.G..G.G", "G..GG..G", "G......G", "GGGGGGGG", "........"])
page_glyph("link", "#58A6FF", ["..GGGG..", ".G.GG.G.", "GGGGGGGG", "G..GG..G", "GGGGGGGG", ".G.GG.G.", "..GGGG.."])
page_glyph("contact", "#3FB950", ["..GGGG..", "..GGGG..", "..GGGG..", "........", ".GGGGGG.", "GGGGGGGG", "GGGGGGGG"])
page_glyph("calendar", "#4DA3FF", [".G....G.", "GGGGGGGG", "G......G", "G.G.G..G", "G......G", "G.G.G..G", "GGGGGGGG"])
page_glyph("lock", "#E5C07B", ["..GGGG..", ".G....G.", ".G....G.", "GGGGGGGG", "GGGPPGGG", "GGGPPGGG", "GGGGGGGG"])
page_glyph("readme", "#58A6FF", ["...GG...", "........", "..GGG...", "...GG...", "...GG...", "...GG...", "..GGGG.."])

# ───── Minecraft ─────
import random


def iso_block(name, type_, colors, top, left, right, seed):
    """Isometric cube; top/left/right(x, y, rng) -> colour key."""
    rnd = random.Random(seed)

    def inside(px, py, poly):
        n, c = len(poly), False
        for i in range(n):
            (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % n]
            if (y1 > py) != (y2 > py) and px < (x2 - x1) * (py - y1) / (y2 - y1) + x1:
                c = not c
        return c

    T = [(8, 0.5), (15.5, 4.25), (8, 8), (0.5, 4.25)]
    L = [(0.5, 4.25), (8, 8), (8, 15.5), (0.5, 11.75)]
    R = [(8, 8), (15.5, 4.25), (15.5, 11.75), (8, 15.5)]
    g = []
    for y in range(16):
        row = ""
        for x in range(16):
            p = (x + 0.5, y + 0.5)
            if inside(*p, T): row += top(x, y, rnd)
            elif inside(*p, L): row += left(x, y - (4.25 + (x + 0.5 - 0.5) * 0.5), rnd)
            elif inside(*p, R): row += right(x, y - (8 - (x + 0.5 - 8) * 0.5), rnd)
            else: row += "."
        g.append(row)
    ICONS[name] = (type_, colors, g)


def grass_top(x, y, r): return r.choice("GGGGHHD")
def grass_side(dark):
    def f(x, d, r):
        if d < 1.6 or (d < 2.6 and r.random() < 0.45): return "D" if dark else "G"
        return r.choice("BBBBC") if not dark else r.choice("CCCCE")
    return f


iso_block("mcworld", "#5BAE3C",
          {"G": "@type", "H": "@type+0.25", "D": "@type*0.72", "B": "#8B5A2B", "C": "#6B4220", "E": "#55361A"},
          grass_top, grass_side(False), grass_side(True), seed=7)
ICONS["mctemplate"] = ICONS["mcworld"]


def table_top(x, y, r):
    return "K" if (x + 2 * y) % 5 == 0 or (x - 2 * y) % 5 == 0 else "W"
def table_side(dark):
    def f(x, d, r):
        if d < 1.5: return "K"
        if 3 <= d < 4 and x % 7 in (2, 3, 4): return "T"
        return "V" if dark else "U"
    return f


iso_block("mcaddon", "#5BAE3C",
          {"W": "#B8945F", "K": "#6B4A2A", "U": "#8F6A3A", "V": "#6E5030", "T": "@type"},
          table_top, table_side(False), table_side(True), seed=3)

ICONS["mcpack"] = ("#5BAE3C", {"K": "#3D2611", "L": "#C8913F", "W": "#A0682B", "D": "#7A4E20", "Y": "@type+0.35"}, [
    "................",
    "................",
    ".KKKKKKKKKKKKKK.",
    ".KLLLLLLLLLLLLK.",
    ".KWWWWWWWWWWWWK.",
    ".KWWWWWWWWWWWWK.",
    ".KKKKKKYYKKKKKK.",
    ".KWWWWKYYKWWWWK.",
    ".KWWWWKYYKWWWWK.",
    ".KWWWWWKKWWWWWK.",
    ".KDDDDDDDDDDDDK.",
    ".KWWWWWWWWWWWWK.",
    ".KWWWWWWWWWWWWK.",
    ".KDDDDDDDDDDDDK.",
    ".KKKKKKKKKKKKKK.",
    "................",
])

# ───── folders ─────
FOLDER = [
    "................",
    "................",
    ".DDDDD..........",
    ".DDDDDDDDDDDDDD.",
    ".DDLLLLLLLLLLDD.",
    ".DDLLLLLLLLLLLD.",
    ".DLLBBBBBBBBBBB.",
    ".DBBBBBBBBBBBBB.",
    ".BBBBBBBBBBBBBM.",
    ".BBBBBBBBBBBBMM.",
    ".BBBBBBBBBBBMMS.",
    ".BBBBBBBBBMMMSS.",
    ".BBBBBBBMMMMSSS.",
    ".BBBBBMMMMSSSSS.",
    ".MMMMMMSSSSSSSS.",
    "................",
]
FOLDER_COLORS = {"D": "@type*0.6", "L": "@type+0.65", "B": "@type", "M": "@type*0.9", "S": "@type*0.79", "W": "@type+0.88"}


def folder(name, type_, badge=None, colors=None):
    g = [list(r) for r in FOLDER]
    if badge:
        h, w = len(badge), len(badge[0])
        y0, x0 = 8 + (5 - h) // 2, 8 - w // 2
        for y, row in enumerate(badge):
            for x, ch in enumerate(row):
                if ch != ".":
                    g[y0 + y][x0 + x] = ch
    ICONS[name] = (type_, {**FOLDER_COLORS, **(colors or {})}, ["".join(r) for r in g])


folder("folder", "@accent")
folder("folder-download", "#3FB950", ["..W..", "..W..", "WWWWW", ".WWW.", "..W.."])
folder("folder-camera", "#26A69A", [".WWW.", "WWWWW", "WW.WW", "WWWWW"])
folder("folder-images", "#4CAF7A", ["....W", ".....", ".W...", "WWW.W", "WWWWW"])
folder("folder-music", "#F472B6", [".WWWW", ".W..W", ".W..W", "WW.WW", "WW.WW"])
folder("folder-video", "#A78BFA", ["W....", "WWW..", "WWWWW", "WWW..", "W...."])
folder("folder-docs", "#4DA3FF", ["WWWWW", ".....", "WWWWW", ".....", "WWW.."])
folder("folder-android", "#A3E635", ["W...W", ".WWW.", "WWWWW", "WBWBW", "WWWWW"])
folder("folder-code", "#22D3EE", [".W.W.", "W...W", ".W.W."])
folder("folder-archive", "#FFA94D", ["..W..", "...W.", "..W..", ".WWW.", ".WWW."])
folder("folder-hidden", "@accent", None,
       {"D": "@type*0.3", "L": "@type*0.55", "B": "@type*0.45", "M": "@type*0.4", "S": "@type*0.35"})
folder("folder-minecraft", "#5BAE3C", ["WWWW.", "...WW", "..W.W", ".W..W", "W...."])
folder("folder-games", "#E06CDF", [".WWW.", "WWWWW", "WW.WW"])
folder("folder-app", "@accent", [".WWW.", "WW..W", "W....", "WW..W", ".WWW."])  # moon, like the logo

# ───── sections ─────
ICONS["storage"] = ("#8B929B", {"O": "#8B929B", "S": "@accent*0.35", "W": "#E6E8EB"}, [
    "................", "....OOOOOOOO....", "...OOOOOOOOOO...", "...OSSSSSSSSO...", "...OSSSSSSSSO...",
    "...OSSSSSSSSO...", "...OSSSSSSSSO...", "...OSSSSSSSSO...", "...OSSSSSSSSO...", "...OSSSSSSSSO...",
    "...OSSSSSSSSO...", "...OSSSSSSSSO...", "...OOOOOOOOOO...", "...OOOOWWOOOO...", "....OOOOOOOO....",
    "................"])
ICONS["sdcard"] = ("@accent", {"O": "@type", "Y": "#FFD34D", "W": "@type+0.85"}, [
    "................", "....OOOOOOOOO...", "...OOYOYOYOYO...", "..OOOYOYOYOYO...", "..OOOYOYOYOYO...",
    "..OOOOOOOOOOO...", "..OOOOOOOOOOO...", "..OOWWWWWWWOO...", "..OOWWWWWWWOO...", "..OOWWWWWWWOO...",
    "..OOOOOOOOOOO...", "..OOOOOOOOOOO...", "..OOOOOOOOOOO...", "..OOOOOOOOOOO...", "................",
    "................"])
ICONS["star"] = ("#FFC83D", {"Y": "@type"}, [
    "................", ".......Y........", "......YYY.......", "......YYY.......", ".....YYYYY......",
    ".YYYYYYYYYYYYY..", "..YYYYYYYYYYY...", "...YYYYYYYYY....", "....YYYYYYY.....", "....YYYYYYY.....",
    "...YYYYYYYYY....", "...YYYY.YYYY....", "..YYY.....YYY...", "..YY.......YY...", "................",
    "................"])
ICONS["history"] = ("@accent", {"O": "@type", "W": "@type*0.25", "H": "#E6E8EB"}, [
    "................", ".....OOOOOO.....", "...OOWWWWWWOO...", "..OWWWWHWWWWWO..", "..OWWWWHWWWWWO..",
    ".OWWWWWHWWWWWWO.", ".OWWWWWHWWWWWWO.", ".OWWWWWHHHHWWWO.", ".OWWWWWWWWWWWWO.", ".OWWWWWWWWWWWWO.",
    "..OWWWWWWWWWWO..", "..OWWWWWWWWWWO..", "...OOWWWWWWOO...", ".....OOOOOO.....", "................",
    "................"])

for name, (type_, colors, grid) in ICONS.items():
    used = {c for r in grid for c in r if c != "."}
    lines = [HEADER, f"type = {type_}"]
    lines += [f"{k} = {v}" for k, v in colors.items() if k in used]
    lines += [""] + grid
    (OUT / f"{name}.txt").write_text("\n".join(lines) + "\n")
print(len(ICONS), "icons")

# ───── preview (both modes) ─────
if PREVIEW:
    from PIL import Image, ImageDraw

    def hexc(s): s = s.lstrip("#"); return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))

    def resolve(expr, accent, type_):
        base, op, f = expr, None, 0
        for o in "*+":
            if o in expr:
                base, f = expr.split(o); op = o; f = float(f)
        c = accent if base == "@accent" else type_ if base == "@type" else hexc(base)
        if op == "*": c = tuple(round(v * f) for v in c)
        if op == "+": c = tuple(round(v + (255 - v) * f) for v in c)
        return c

    names = list(ICONS)
    cols, S = 12, 4 * 16 + 16
    accents = [(None, hexc("A324EC")), ("theme", hexc("A324EC")), ("theme", hexc("2DD4BF"))]
    rows = (len(names) + cols - 1) // cols
    im = Image.new("RGB", (cols * S, rows * S * len(accents) + 10 * len(accents)), (13, 14, 17))
    for block, (mode, acc) in enumerate(accents):
        for i, n in enumerate(names):
            type_, colors, grid = ICONS[n]
            t = acc if (mode == "theme" or type_ == "@accent") else hexc(type_)
            ox, oy = (i % cols) * S + 8, block * (rows * S + 10) + (i // cols) * S + 8
            for y, r in enumerate(grid):
                for x, ch in enumerate(r):
                    if ch != ".":
                        c = resolve(colors[ch], acc, t)
                        ImageDraw.Draw(im).rectangle((ox + x * 4, oy + y * 4, ox + x * 4 + 3, oy + y * 4 + 3), fill=c)
    im.save(PREVIEW)
