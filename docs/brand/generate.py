"""Generates every BeanDesk logo file from one set of shapes, so they never drift apart.

    python3 docs/brand/generate.py

Writes the SVGs in docs/brand and the Android launcher icons and logo tiles in app/src/main/res.
The Play Store PNG (play-store-icon-512.png) is an export of icon.svg at 512 x 512.

The idea: the coffee bean is the dot of a wifi symbol. Coffee and a connection, which is
what the app helps you find. Coordinates use the 108 x 108 grid of Android adaptive icons;
everything stays inside the 66 dp safe zone.

The app offers several colour variants as its launcher icon (see AppIcon.kt); espresso is
the default.
"""
import math
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BRAND = ROOT / "docs" / "brand"
RES = ROOT / "app" / "src" / "main" / "res"


@dataclass(frozen=True)
class Variant:
    name: str
    bg_top: str
    bg_bottom: str
    bean: str
    inner_wave: str
    outer_wave: str


VARIANTS = [
    Variant("espresso", "#4A2C1A", "#1E120B", bean="#F2A65A", inner_wave="#FFE6C4", outer_wave="#FFE6C4"),
    Variant("terracotta", "#F07A45", "#C9472A", bean="#2B1A12", inner_wave="#FFF1DD", outer_wave="#FFF1DD"),
    Variant("crema", "#FFF1DC", "#F4CF9E", bean="#6F4E37", inner_wave="#3A2416", outer_wave="#C8743A"),
    Variant("teal", "#16615D", "#0B3534", bean="#F2A65A", inner_wave="#FFE6C4", outer_wave="#FFE6C4"),
    Variant("night", "#2B2F4A", "#14162A", bean="#F2A65A", inner_wave="#FFE6C4", outer_wave="#F2A65A"),
]
DEFAULT = VARIANTS[0]

CX, CY = 54, 64  # centre of the wifi waves
BEAN_X, BEAN_Y = 54, 68
BEAN_RX, BEAN_RY, BEAN_ROT = 10.6, 14.3, -32
INNER_RADIUS, OUTER_RADIUS, WAVE_WIDTH, WAVE_SPREAD = 21.5, 32.5, 7, 42
# Launchers show the middle 72 x 72 of the grid; the tile versions crop to that.
VISIBLE, VISIBLE_SIZE, TILE_RADIUS = 18, 72, 16


def num(v: float) -> str:
    return f"{v:.2f}".rstrip("0").rstrip(".")


def bean_path() -> str:
    """An ellipse plus an S-shaped sliver; filled even-odd, the sliver becomes the crease."""
    rx, ry = BEAN_RX, BEAN_RY
    h, k, w = ry * 0.84, rx * 0.72, 1.75
    ellipse = f"M{num(-rx)},0A{num(rx)},{num(ry)} 0 1,0 {num(rx)},0A{num(rx)},{num(ry)} 0 1,0 {num(-rx)},0Z"
    crease = (
        f"M0,{num(-h)}C{num(-k - w)},{num(-h * 0.35)} {num(k - w)},{num(h * 0.35)} 0,{num(h)}"
        f"C{num(k + w)},{num(h * 0.35)} {num(-k + w)},{num(-h * 0.35)} 0,{num(-h)}Z"
    )
    return ellipse + crease


def wave_path(r: float) -> str:
    a0, a1 = math.radians(-90 - WAVE_SPREAD), math.radians(-90 + WAVE_SPREAD)
    x0, y0 = CX + r * math.cos(a0), CY + r * math.sin(a0)
    x1, y1 = CX + r * math.cos(a1), CY + r * math.sin(a1)
    return f"M{num(x0)},{num(y0)}A{num(r)},{num(r)} 0 0,1 {num(x1)},{num(y1)}"


def rounded_rect(x: float, y: float, size: float, r: float) -> str:
    end = x + size
    return (
        f"M{num(x + r)},{num(y)}H{num(end - r)}A{num(r)},{num(r)} 0 0,1 {num(end)},{num(y + r)}"
        f"V{num(end - r)}A{num(r)},{num(r)} 0 0,1 {num(end - r)},{num(end)}"
        f"H{num(x + r)}A{num(r)},{num(r)} 0 0,1 {num(x)},{num(end - r)}"
        f"V{num(y + r)}A{num(r)},{num(r)} 0 0,1 {num(x + r)},{num(y)}Z"
    )


# --- SVG ---------------------------------------------------------------------------------

def svg_mark(bean: str, inner_wave: str, outer_wave: str) -> str:
    waves = "".join(
        f'<path d="{wave_path(r)}" fill="none" stroke="{color}" stroke-width="{WAVE_WIDTH}" stroke-linecap="round"/>'
        for r, color in ((INNER_RADIUS, inner_wave), (OUTER_RADIUS, outer_wave))
    )
    return (
        waves
        + f'<g transform="translate({BEAN_X},{BEAN_Y}) rotate({BEAN_ROT})">'
        + f'<path d="{bean_path()}" fill="{bean}" fill-rule="evenodd"/></g>'
    )


def svg_icon(v: Variant, rounded: bool) -> str:
    """The icon as a launcher shows it: the visible 72 x 72, square or as a rounded tile."""
    o, size = VISIBLE, VISIBLE_SIZE
    background = (
        f'<path d="{rounded_rect(o, o, size, TILE_RADIUS)}" fill="url(#bg)"/>'
        if rounded
        else f'<rect x="{o}" y="{o}" width="{size}" height="{size}" fill="url(#bg)"/>'
    )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{o} {o} {size} {size}" width="512" height="512">'
        "<title>BeanDesk</title>"
        '<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0" stop-color="{v.bg_top}"/><stop offset="1" stop-color="{v.bg_bottom}"/>'
        f"</linearGradient></defs>{background}{svg_mark(v.bean, v.inner_wave, v.outer_wave)}</svg>\n"
    )


def svg_mark_only() -> str:
    # For light backgrounds: espresso waves and a caramel bean, no tile.
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="16 22 76 64" width="380" height="320">'
        f"<title>BeanDesk</title>{svg_mark(DEFAULT.bean, DEFAULT.bg_top, DEFAULT.bg_top)}</svg>\n"
    )


# --- Android -----------------------------------------------------------------------------

def vector(comment: str, body: str, size: int = 108) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {comment} Generated by docs/brand/generate.py; edit that instead. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        f'    android:width="{size}dp"\n'
        f'    android:height="{size}dp"\n'
        f'    android:viewportWidth="{size}"\n'
        f'    android:viewportHeight="{size}">\n'
        f"{body}"
        "</vector>\n"
    )


def android_color(hex_color: str) -> str:
    return "#FF" + hex_color[1:].upper()


def android_gradient_path(v: Variant, path: str, indent: str, size: int = 108) -> str:
    return (
        f'{indent}<path android:pathData="{path}">\n'
        f'{indent}    <aapt:attr name="android:fillColor">\n'
        f"{indent}        <gradient\n"
        f'{indent}            android:type="linear"\n'
        f'{indent}            android:startX="0"\n'
        f'{indent}            android:startY="0"\n'
        f'{indent}            android:endX="{size}"\n'
        f'{indent}            android:endY="{size}">\n'
        f'{indent}            <item android:offset="0" android:color="{android_color(v.bg_top)}" />\n'
        f'{indent}            <item android:offset="1" android:color="{android_color(v.bg_bottom)}" />\n'
        f"{indent}        </gradient>\n"
        f"{indent}    </aapt:attr>\n"
        f"{indent}</path>\n"
    )


def android_mark(bean: str, inner_wave: str, outer_wave: str, indent: str = "    ") -> str:
    out = ""
    for r, color in ((INNER_RADIUS, inner_wave), (OUTER_RADIUS, outer_wave)):
        out += (
            f"{indent}<path\n"
            f'{indent}    android:pathData="{wave_path(r)}"\n'
            f'{indent}    android:strokeColor="{android_color(color)}"\n'
            f'{indent}    android:strokeWidth="{WAVE_WIDTH}"\n'
            f'{indent}    android:strokeLineCap="round" />\n'
        )
    out += (
        f"{indent}<group\n"
        f'{indent}    android:translateX="{BEAN_X}"\n'
        f'{indent}    android:translateY="{BEAN_Y}"\n'
        f'{indent}    android:rotation="{BEAN_ROT}">\n'
        f"{indent}    <path\n"
        f'{indent}        android:fillColor="{android_color(bean)}"\n'
        f'{indent}        android:fillType="evenOdd"\n'
        f'{indent}        android:pathData="{bean_path()}" />\n'
        f"{indent}</group>\n"
    )
    return out


def write_android(v: Variant) -> None:
    drawable, mipmap = RES / "drawable", RES / "mipmap-anydpi"
    (drawable / f"ic_launcher_background_{v.name}.xml").write_text(
        vector(f"Launcher icon background, {v.name}.", android_gradient_path(v, "M0,0h108v108h-108z", "    "))
    )
    (drawable / f"ic_launcher_foreground_{v.name}.xml").write_text(
        vector(f"Launcher icon foreground, {v.name}.", android_mark(v.bean, v.inner_wave, v.outer_wave))
    )
    (mipmap / f"ic_launcher_{v.name}.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by docs/brand/generate.py; edit that instead. -->\n"
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        f'    <background android:drawable="@drawable/ic_launcher_background_{v.name}" />\n'
        f'    <foreground android:drawable="@drawable/ic_launcher_foreground_{v.name}" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )
    # The icon as a rounded tile for inside the app, cropped like a launcher shows it.
    size = VISIBLE_SIZE
    badge = (
        "    <group>\n"
        f'        <clip-path android:pathData="{rounded_rect(0, 0, size, TILE_RADIUS)}" />\n'
        + android_gradient_path(v, f"M0,0h{size}v{size}h-{size}z", "        ", size)
        + "        <group\n"
        + f'            android:translateX="-{VISIBLE}"\n'
        + f'            android:translateY="-{VISIBLE}">\n'
        + android_mark(v.bean, v.inner_wave, v.outer_wave, indent="            ")
        + "        </group>\n"
        + "    </group>\n"
    )
    (drawable / f"logo_badge_{v.name}.xml").write_text(vector(f"BeanDesk logo as a rounded tile, {v.name}.", badge, size))


def main() -> None:
    (BRAND / "icon.svg").write_text(svg_icon(DEFAULT, rounded=False))
    (BRAND / "icon-rounded.svg").write_text(svg_icon(DEFAULT, rounded=True))
    (BRAND / "mark.svg").write_text(svg_mark_only())
    (BRAND / "variants").mkdir(exist_ok=True)
    for v in VARIANTS:
        (BRAND / "variants" / f"icon-{v.name}.svg").write_text(svg_icon(v, rounded=True))
        write_android(v)
    # Themed icons (Android 13+) only use the shape; the system picks the colour.
    (RES / "drawable" / "ic_launcher_monochrome.xml").write_text(
        vector("Launcher icon for themed icons.", android_mark("#000000", "#000000", "#000000"))
    )


if __name__ == "__main__":
    main()
