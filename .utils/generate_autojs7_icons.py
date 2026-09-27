"""Rebuild the bundled AutoJs7 numerals from the checked-in AutoJs6 art.

Requires Pillow and OpenCV. Run from the repository root. The source images are
read from the pre-rebrand commit so repeated runs do not erode the artwork.
"""

from __future__ import annotations

import argparse
import io
import subprocess
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE_REF = "f15bcf99106078aed7c4e5761130f9a08618c7b6"
RES = Path("app/src/main/res")
MATERIAL = RES / "drawable/autojs6_material.png"
ADAPTIVE = RES / "mipmap/ic_app_launcher_adaptive.png"
FOREGROUND = RES / "mipmap-xxxhdpi/ic_app_launcher_adaptive_foreground.png"
LAUNCHER = RES / "mipmap/ic_launcher.png"
LEGACY = RES / "mipmap/ic_launcher_legacy.png"
SCALED_FOREGROUNDS = (
    RES / "mipmap-mdpi/ic_app_launcher_adaptive_foreground.png",
    RES / "mipmap-hdpi/ic_launcher_adaptive_foreground.png",
    RES / "mipmap-xhdpi/ic_app_launcher_adaptive_foreground.png",
    RES / "mipmap-xxhdpi/ic_app_launcher_adaptive_foreground.png",
)


def original(path: Path) -> Image.Image:
    data = subprocess.check_output(["git", "show", f"{SOURCE_REF}:{path.as_posix()}"], cwd=ROOT)
    return Image.open(io.BytesIO(data)).convert("RGBA")


def digit_mask(size: tuple[int, int]) -> Image.Image:
    """A crisp, wide-topped seven occupying the original numeral's box."""
    width, height = size
    scale = 8
    mask = Image.new("L", (width * scale, height * scale))
    draw = ImageDraw.Draw(mask)
    points = (
        (0.04, 0.03), (0.97, 0.03), (0.97, 0.25),
        (0.43, 0.97), (0.16, 0.97), (0.69, 0.25), (0.04, 0.25),
    )
    draw.polygon([(round(x * width * scale), round(y * height * scale)) for x, y in points], fill=255)
    return mask.resize(size, Image.Resampling.LANCZOS)


def draw_seven(image: Image.Image, box: tuple[int, int, int, int], color: tuple[int, int, int]) -> None:
    x, y, width, height = box
    glyph = Image.new("RGBA", (width, height), (*color, 0))
    glyph.putalpha(digit_mask((width, height)))
    image.alpha_composite(glyph, (x, y))


def replace_detached_digit(path: Path) -> tuple[Image.Image, tuple[int, int, int, int]]:
    image = original(path)
    array = np.asarray(image)
    count, labels, stats, _ = cv2.connectedComponentsWithStats((array[:, :, 3] > 10).astype("uint8"))
    candidates = [
        (index, tuple(map(int, stats[index, :4])))
        for index in range(1, count)
        if stats[index, cv2.CC_STAT_LEFT] > image.width * 0.6
        and stats[index, cv2.CC_STAT_TOP] > image.height * 0.6
        and stats[index, cv2.CC_STAT_AREA] < image.width * image.height * 0.04
    ]
    if len(candidates) != 1:
        raise RuntimeError(f"Expected one detached numeral in {path}; got {candidates}")
    index, box = candidates[0]
    x, y, width, height = box
    if width < 10 or height < 10:
        raise RuntimeError(f"Unexpected numeral dimensions in {path}: {box}")
    solid = array[(labels == index) & (array[:, :, 3] > 230), :3]
    color = tuple(int(value) for value in np.median(solid, axis=0))
    image.paste((0, 0, 0, 0), (x - 1, y - 1, x + width + 1, y + height + 1))
    draw_seven(image, box, color)
    return image, box


def replace_launcher_digit() -> tuple[Image.Image, tuple[int, int, int, int]]:
    image = original(LAUNCHER)
    array = np.asarray(image)
    yy, xx = np.indices(array.shape[:2])
    white = (
        (array[:, :, :3].min(axis=2) > 225)
        & (array[:, :, 3] > 200)
        & (xx > image.width * 0.7)
        & (yy > image.height * 0.65)
    ).astype("uint8")
    count, labels, stats, _ = cv2.connectedComponentsWithStats(white)
    candidates = [
        tuple(map(int, stats[index, :4]))
        for index in range(1, count)
        if stats[index, cv2.CC_STAT_AREA] > 30
    ]
    if len(candidates) != 1:
        raise RuntimeError(f"Expected one white numeral in {LAUNCHER}; got {candidates}")
    x, y, width, height = candidates[0]
    # Reconstruct the flat green panel beneath the white glyph, including its
    # antialiased edge, before drawing the new white numeral.
    patch = np.zeros(white.shape, dtype="uint8")
    patch[y - 3 : y + height + 3, x - 3 : x + width + 3] = 255
    rgb = cv2.inpaint(cv2.cvtColor(array[:, :, :3], cv2.COLOR_RGB2BGR), patch, 7, cv2.INPAINT_TELEA)
    restored = np.dstack((cv2.cvtColor(rgb, cv2.COLOR_BGR2RGB), array[:, :, 3]))
    image = Image.fromarray(restored, "RGBA")
    draw_seven(image, (x, y, width, height), (255, 255, 255))
    return image, (x, y, width, height)


def save(path: Path, image: Image.Image, dry_run: bool) -> None:
    if dry_run:
        return
    image.save(ROOT / path, optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    material, material_box = replace_detached_digit(MATERIAL)
    foreground, foreground_box = replace_detached_digit(FOREGROUND)
    launcher, launcher_box = replace_launcher_digit()
    legacy = original(LEGACY)
    legacy_box = (round(legacy.width * 0.77), round(legacy.height * 0.74), 13, 21)
    draw_seven(legacy, legacy_box, (255, 255, 255))

    for path, image, box in (
        (MATERIAL, material, material_box),
        (ADAPTIVE, material, material_box),
        (FOREGROUND, foreground, foreground_box),
        (LAUNCHER, launcher, launcher_box),
        (LEGACY, legacy, legacy_box),
    ):
        print(f"{path}: {box}")
        save(path, image, args.dry_run)

    for path in SCALED_FOREGROUNDS:
        size = original(path).size
        save(path, foreground.resize(size, Image.Resampling.LANCZOS), args.dry_run)
        print(f"{path}: {size}")


if __name__ == "__main__":
    main()
