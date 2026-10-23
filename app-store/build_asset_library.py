"""Build the App Store Connect screenshot library from app-store/screenshots/.

Writes app-store/asset-library/en-US/ in the folder shape fastlane `deliver` reads
(`--screenshots_path app-store/asset-library`): one flat folder per locale, files sorted
by name, display type detected from pixel size.

One iPhone set, because the app is iPhone only (TARGETED_DEVICE_FAMILY = 1) and App Store
Connect requires only the Dynamic Island medium display:
  6.3"  1206 x 2622  the 1320 x 2868 simulator capture scaled to 2622 high (-> 1207 wide),
                     centre-cropped by 1 px of width
Add a row to SETS for another class (e.g. "iPhone65": (1284, 2778)); the scaling picks its
side from the aspect ratio.

Every file is a JPEG. The simulator PNGs carry an alpha channel and App Store Connect
refuses screenshots with transparency.

Run: python3 app-store/build_asset_library.py   (macOS, uses `sips`)
"""

import shutil
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "screenshots"
OUT = HERE / "asset-library" / "en-US"

# Store order, at most 10 per set. Apple's asset best practices: show the app in use and
# lead with the best features; the first three can appear in search results. So the
# setup screens are cut to the two that explain the study (01) and its privacy (04);
# 02-bluetooth, 03-camera, 05-login and 06-create-account stay out.
ORDER = [
    "07-home-quests",
    "08-quest-detail",
    "10-my-account-coverage",
    "09-notifications",
    "01-onboarding",
    "04-what-leaves-the-phone",
    "11-notification-settings",
]

# Only the required class. App Store Connect requires "at least one screenshot for iPhone
# with Dynamic Island (medium display)" (1179 x 2556 or 1206 x 2622) and scales it for
# the other iPhone sizes.
SETS = {
    "iPhone63": (1206, 2622),
}


def sips(*args: str) -> None:
    subprocess.run(["sips", *args], check=True, stdout=subprocess.DEVNULL)


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    for position, stem in enumerate(ORDER, start=1):
        src = SOURCE / f"{stem}.png"
        for label, (width, height) in SETS.items():
            dst = OUT / f"{label}_{position:02d}_{stem[3:]}.jpg"
            sips("-s", "format", "jpeg", "-s", "formatOptions", "95", str(src), "--out", str(dst))
            if (width, height) != (1320, 2868):
                # Scale on the side that leaves the other one at least as large as the
                # target, then centre-crop the excess. 1320 x 2868 is a hair taller than
                # 6.5" and a hair wider than 6.3", so the two sets scale on different sides.
                if width / height < 1320 / 2868:
                    sips("--resampleHeight", str(height), str(dst))
                else:
                    sips("--resampleWidth", str(width), str(dst))
                sips("--cropToHeightWidth", str(height), str(width), str(dst))
    print(f"wrote {len(list(OUT.iterdir()))} files to {OUT}")


if __name__ == "__main__":
    main()
