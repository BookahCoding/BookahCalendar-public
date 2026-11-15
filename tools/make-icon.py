"""BookahCalendar icon drafts: ukiyo-e style, Hikyaku for Windows default palette (Washi & Ink).
Run: python -I -X utf8 tools/make-icon.py docs/icon-drafts
Writes draft-a/b/c.svg and sheet.html (each draft in 4 masks + 48 px + a flat silhouette).
Every shape is drawn here from scratch (no traced print, no downloaded art). Day number is <text> in drafts; the final icon will use outlined paths."""
import pathlib
import sys

VERMILION = "#A63F2D"   # Hikyaku icon / Shisha mark vermilion
VERM_D = "#7E2C20"
WASHI = "#F4EDD9"
WASHI_D = "#E6DCC2"
INK = "#2B2822"
INDIGO = "#245A86"
INDIGO_D = "#173B58"
SKY = "#73B6E3"
CORAL = "#E5927E"

# ---- shared pieces, drawn in a 100x100 local box ----
WAVE_BODY = ("M0,100 L0,62 C4,40 22,22 44,22 C62,22 76,32 80,46 C82,52 80,58 76,60 "
             "C78,52 70,44 60,44 C48,44 44,56 50,66 C56,76 70,80 100,80 L100,100 Z")
WAVE_CREST = "M0,62 C4,40 22,22 44,22 C62,22 76,32 80,46 C82,52 80,58 76,60"
WAVE_FOAM = "M2,70 C6,52 22,36 44,34 C60,34 72,40 76,50"   # inner line, washi, follows the crest


def wave(fill=INDIGO, foam=WASHI, dark=INDIGO_D):
    return (f'<path d="{WAVE_BODY}" fill="{fill}"/>'
            f'<path d="{WAVE_FOAM}" fill="none" stroke="{dark}" stroke-width="1.6" stroke-linecap="round" opacity=".55"/>'
            f'<path d="{WAVE_CREST}" fill="none" stroke="{foam}" stroke-width="3.4" stroke-linecap="round" stroke-dasharray="0.1 6.2"/>'
            f'<circle cx="74" cy="41" r="3.2" fill="{foam}"/><circle cx="79.5" cy="49" r="2.6" fill="{foam}"/>'
            f'<circle cx="77.5" cy="57" r="2.1" fill="{foam}"/><circle cx="68" cy="35" r="2.2" fill="{foam}"/>'
            f'<path d="M0,84 C20,78 34,90 52,84 C70,78 84,90 100,84" fill="none" stroke="{foam}" stroke-width="1.4" stroke-linecap="round" opacity=".7"/>')


def fuji(fill=INDIGO, snow=WASHI):
    return (f'<path d="M50,24 L88,100 L12,100 Z" fill="{fill}"/>'
            f'<path d="M50,24 L61,46 L55,42 L50,50 L45,42 L39,46 Z" fill="{snow}"/>')


def ring(x, y, col=INK):
    return f'<rect x="{x - 2}" y="{y - 6}" width="4" height="11" rx="2" fill="{WASHI}" stroke="{col}" stroke-width="1.1"/>'


def page(x, y, w, h, header, body, header_h=12, rx=4.5):
    """A calendar page with a header band and two binding rings; body is drawn clipped to the page below the header."""
    cid = f"pg{abs(hash((x, y, w, h))) % 9999}"
    return (f'<clipPath id="{cid}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}"/></clipPath>'
            f'<rect x="{x}" y="{y + 2}" width="{w}" height="{h}" rx="{rx}" fill="{INK}" opacity=".22"/>'
            f'<g clip-path="url(#{cid})"><rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{WASHI}"/>'
            f'<rect x="{x}" y="{y}" width="{w}" height="{header_h}" fill="{header}"/>{body}</g>'
            f'{ring(x + w * 0.3, y)}{ring(x + w * 0.7, y)}')


def place(inner, x, y, w, h):
    """Draw a 100x100 local drawing into the box (x, y, w, h)."""
    return f'<g transform="translate({x} {y}) scale({w / 100:.4f} {h / 100:.4f})">{inner}</g>'


def square(inner):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">'
            f'<rect width="108" height="108" fill="{VERMILION}"/>{inner}</svg>')


# A: washi page; Fuji with a vermilion sun and a bold wave at its foot; big day number is replaced by a seal.
def draft_a():
    px, py, pw, ph = 24, 24, 60, 60
    body = (f'<rect x="{px}" y="{py + 12}" width="{pw}" height="{ph - 12}" fill="{WASHI}"/>'
            f'<circle cx="{px + 43}" cy="{py + 27}" r="10" fill="{VERMILION}"/>'
            + place(fuji(), px + 14, py + 13, 46, 42)
            + place(wave(), px, py + 36, pw, 24))
    seal = (f'<rect x="{px + 5}" y="{py + 15}" width="15" height="15" rx="2" fill="{VERMILION}"/>'
            f'<text x="{px + 12.5}" y="{py + 26.6}" text-anchor="middle" font-family="Georgia, serif" font-weight="bold" font-size="10.5" fill="{WASHI}">14</text>')
    return square(page(px, py, pw, ph, INDIGO, body) + seal)


# B: the Great Wave fills the page; vermilion sun above it; day number in a seal on the wave.
def draft_b():
    px, py, pw, ph = 24, 24, 60, 60
    body = (f'<rect x="{px}" y="{py + 12}" width="{pw}" height="{ph - 12}" fill="{WASHI}"/>'
            f'<circle cx="{px + 42}" cy="{py + 29}" r="9" fill="{VERMILION}"/>'
            + place(wave(), px - 2, py + 18, pw + 4, ph - 14))
    seal = (f'<rect x="{px + 8}" y="{py + 36}" width="17" height="17" rx="2.2" fill="{VERMILION}"/>'
            f'<text x="{px + 16.5}" y="{py + 49}" text-anchor="middle" font-family="Georgia, serif" font-weight="bold" font-size="12" fill="{WASHI}">14</text>')
    return square(page(px, py, pw, ph, INDIGO, body, header_h=11) + seal)


# C: washi square with the vermilion sun, the wave and binding rings: the inverse family member.
def draft_c():
    inner = (f'<rect width="108" height="108" fill="{WASHI}"/>'
             f'<circle cx="54" cy="46" r="21" fill="{VERMILION}"/>'
             + place(fuji(INDIGO_D), 28, 40, 38, 44)
             + place(wave(), 4, 50, 104, 58)
             + f'<rect x="0" y="0" width="108" height="14" fill="{INDIGO}"/>'
             + ring(36, 14) + ring(72, 14)
             + f'<rect x="64" y="66" width="19" height="19" rx="2.4" fill="{VERMILION}"/>'
             f'<text x="73.5" y="80.4" text-anchor="middle" font-family="Georgia, serif" font-weight="bold" font-size="13" fill="{WASHI}">14</text>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">{inner}</svg>')


def main():
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "docs/icon-drafts")
    out.mkdir(parents=True, exist_ok=True)
    drafts = {"a": draft_a(), "b": draft_b(), "c": draft_c()}
    for k, svg in drafts.items():
        (out / f"draft-{k}.svg").write_text(svg, encoding="utf-8")
    cells = ""
    for k, svg in drafts.items():
        cells += (f'<figure><div class="m sq">{svg}</div><div class="row">'
                  f'<div class="m ci">{svg}</div><div class="m sg">{svg}</div><div class="m rr">{svg}</div>'
                  f'<div class="m sm">{svg}</div><div class="m mono">{svg}</div></div><figcaption>Draft {k.upper()}</figcaption></figure>')
    css = ("body{margin:0;padding:20px;background:#d9d9d4;font-family:sans-serif;display:flex;gap:24px}"
           "figure{margin:0;text-align:center}.m svg{display:block;width:100%;height:100%}"
           ".sq{width:260px;height:260px}.row{display:flex;gap:8px;margin-top:10px;align-items:center;justify-content:center}"
           ".ci,.sg,.rr{width:84px;height:84px;overflow:hidden}.ci{border-radius:50%}.sg{border-radius:32%}.rr{border-radius:22%}"
           ".sm{width:48px;height:48px;border-radius:50%;overflow:hidden}"
           ".mono{width:48px;height:48px;border-radius:50%;overflow:hidden;background:#222}.mono svg{filter:grayscale(1) brightness(1.6) contrast(3)}"
           "figcaption{margin-top:6px;font-weight:bold}")
    (out / "sheet.html").write_text(f'<!doctype html><meta charset="utf-8"><style>{css}</style>{cells}', encoding="utf-8")


main()
