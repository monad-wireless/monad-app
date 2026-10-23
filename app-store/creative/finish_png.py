"""Flatten a Chrome screenshot to an opaque RGB PNG and report where the content sits.

App Store Connect refuses images with an alpha channel, and headless Chrome writes RGBA.
The bounding box of everything that differs from the background corner colour is printed
as fractions of the frame, so the safe-area assumption in header.html can be checked:
it must stay inside 0.16..0.84 on both axes.

Run: uv run --with pillow python app-store/creative/finish_png.py IN.png OUT.png
"""

import sys

from PIL import Image, ImageChops, ImageFilter

src, dst = sys.argv[1], sys.argv[2]
img = Image.open(src).convert("RGB")
img.save(dst, "PNG", optimize=True)

# Content box: the background is a smooth gradient, so compare against a heavy blur of the
# image itself; text, outlines and points stand out from it, the gradient does not.
blur = img.filter(ImageFilter.GaussianBlur(60))
diff = ImageChops.difference(img, blur).convert("L").point(lambda v: 255 if v > 40 else 0)
left, top, right, bottom = diff.getbbox()
w, h = img.size
print(f"{dst}: {w}x{h} mode={img.mode}")
print(f"content box x {left / w:.3f}..{right / w:.3f}  y {top / h:.3f}..{bottom / h:.3f}")
