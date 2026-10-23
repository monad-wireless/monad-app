"""Turn a csi_plot `doppler` PNG into the header's background texture.

The data is real: a Doppler spectrogram (rx0*tx0 x conj(rx1*tx0)) from
csid:monad05/monad05_walk-survey-48_20260911-070050-seg0003, 2026-09-11 07:20:53-07:30:54 UTC,
scoped to the illuminator (ef:be:ad:de:ad:de), rendered by the monad-knowledge `csi_plot` tool.
Only the plot area is kept. The 0 Hz guide line the plot draws is not data, so its rows are
replaced by the row above. Viridis lightness rises monotonically, so luminance is remapped to
a black -> indigo -> paper duotone without reordering any value.

Run: uv run --no-project --with pillow python app-store/creative/doppler_texture.py IN.png OUT.png
"""

import sys

from PIL import Image

# Plot-area box inside the 1492 x 558 csi_plot PNG, below the in-plot annotation line.
BOX = (139, 112, 915, 464)
RAMP = [(0.00, (11, 11, 12)), (0.45, (34, 40, 96)), (0.75, (91, 110, 204)), (1.00, (242, 238, 227))]


def ramp(t: float) -> tuple[int, int, int]:
    for (t0, c0), (t1, c1) in zip(RAMP, RAMP[1:]):
        if t <= t1:
            f = (t - t0) / (t1 - t0)
            return tuple(round(a + (b - a) * f) for a, b in zip(c0, c1))
    return RAMP[-1][1]


src, dst = sys.argv[1], sys.argv[2]
img = Image.open(src).convert("RGB").crop(BOX)
w, h = img.size
px = img.load()

# The guide line is a row (anti-aliased over two or three) far brighter than the rows around
# it; real Doppler rows never are, because the spectrogram is smooth across bins.
def row_mean(y: int) -> float:
    return sum(sum(px[x, y]) for x in range(w)) / (3 * w)


means = [row_mean(y) for y in range(h)]
flagged = [y for y in range(3, h - 3) if means[y] > (means[y - 3] + means[y + 3]) / 2 * 1.12]
if flagged:
    above, below = min(flagged) - 1, max(flagged) + 1
    span = below - above
    for y in flagged:
        f = (y - above) / span
        for x in range(w):
            a, b = px[x, above], px[x, below]
            px[x, y] = tuple(round(ca + (cb - ca) * f) for ca, cb in zip(a, b))

lum = img.convert("L")
lo, hi = min(lum.getdata()), max(lum.getdata())
lut = [ramp((v - lo) / max(hi - lo, 1)) for v in range(256)]
out = Image.new("RGB", (w, h))
out.putdata([lut[v] for v in lum.getdata()])
out.save(dst)
print(f"{dst}: {w}x{h}")
