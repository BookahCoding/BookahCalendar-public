"""Makes the 12 month banners (WebP, 3:1) from the 16:9 source pictures.

Usage:  python -I -X utf8 tools/make-banners.py <folder with the source jpg files> [--sheet contact.jpg]
The source folder is not part of the repository. Each month names its source file by the last digits of the file name
(grok_image_<digits>.jpg), an optional inner box (left, top, right, bottom) that cuts away a frame or border, and the
vertical position of the 3:1 band (0 = top, 1 = bottom). Output goes to android/app/src/main/res/drawable-nodpi/banner_MM.webp.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

W, H = 1200, 400
# month: (source id, inner box or None, vertical position of the band)
MONTHS = {
    1: ("1794895746461", None, 0.55),
    2: ("1794895719828", None, 0.30),
    3: ("1794895628242", None, 0.50),
    4: ("1794895726688", (40, 30, 1752, 880), 0.45),
    5: ("1794895731371", (30, 30, 1762, 978), 0.35),
    6: ("1794895729270", None, 0.45),
    7: ("1794895735877", (30, 30, 1762, 978), 0.30),
    8: ("1794895733548", (30, 30, 1762, 978), 0.35),
    9: ("1794895738459", None, 0.30),
    10: ("1794895741360", (40, 40, 1752, 968), 0.60),
    11: ("1794895743720", (20, 20, 1580, 980), 0.30),
    12: ("1794895724660", None, 0.55),
}

def band(im, box, fy):
    if box: im = im.crop(box)
    w, h = im.size
    bh = int(w / 3)
    if bh > h:  # too flat: crop the width instead
        bh = h; bw = h * 3; x0 = (w - bw) // 2; im = im.crop((x0, 0, x0 + bw, h))
    else:
        y0 = int((h - bh) * fy); im = im.crop((0, y0, w, y0 + bh))
    return im.resize((W, H), Image.LANCZOS)

def main():
    src = Path(sys.argv[1])
    out = Path(__file__).resolve().parent.parent / "android/app/src/main/res/drawable-nodpi"
    out.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (W // 2 * 3, H // 2 * 4), "white")
    for m, (sid, box, fy) in MONTHS.items():
        f = next(src.glob(f"*{sid}*.jpg"))
        im = band(Image.open(f).convert("RGB"), box, fy)
        im.save(out / f"banner_{m:02d}.webp", "WEBP", quality=78, method=6)
        t = im.resize((W // 2, H // 2)); ImageDraw.Draw(t).text((6, 6), str(m), fill="red")
        sheet.paste(t, (((m - 1) % 3) * W // 2, ((m - 1) // 3) * H // 2))
    if "--sheet" in sys.argv: sheet.save(sys.argv[sys.argv.index("--sheet") + 1], quality=85)

main()
