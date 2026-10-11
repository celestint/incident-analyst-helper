"""把 demo/frames 截帧拼成演示 GIF：相邻去重、缩放、首尾停留加长。"""
import sys
from pathlib import Path

from PIL import Image, ImageSequence

FRAMES_DIR = Path(__file__).parent / "frames"
OUT = Path(__file__).parent / "analysis-demo.gif"
WIDTH = 960
PLAY_MS = 400          # 普通帧播放时长
HOLD_MS = 1600         # 首帧/末帧停留
DIFF_THRESHOLD = 0.3   # 灰度均值差低于该值视为重复帧


def main():
    files = sorted(FRAMES_DIR.glob("f*.png"))
    if not files:
        sys.exit(f"no frames in {FRAMES_DIR}")

    kept: list[tuple[Image.Image, bytes]] = []
    last_gray = None
    for f in files:
        im = Image.open(f).convert("RGB").resize((WIDTH, int(WIDTH * 800 / 1280)), Image.LANCZOS)
        gray = im.convert("L").resize((64, 40))
        if last_gray is not None:
            diff = sum(
                abs(a - b) for a, b in zip(gray.getdata(), last_gray.getdata())
            ) / (64 * 40)
            if diff < DIFF_THRESHOLD:
                continue
        last_gray = gray
        # 帧 GIF 调色板：自适应 128 色，避免全帧真彩导致体积爆炸
        pal = im.quantize(colors=128, method=Image.MEDIANCUT, dither=Image.FLOYDSTEINBERG)
        kept.append((im, pal.tobytes()))

    if len(kept) < 2:
        sys.exit("after dedup less than 2 frames")

    pal_frames = [im.quantize(colors=128, method=Image.MEDIANCUT, dither=Image.FLOYDSTEINBERG) for im, _ in kept]
    durations = [HOLD_MS] + [PLAY_MS] * (len(pal_frames) - 2) + [HOLD_MS]
    pal_frames[0].save(
        OUT,
        save_all=True,
        append_images=pal_frames[1:],
        duration=durations,
        loop=0,
        optimize=True,
    )
    size_mb = OUT.stat().st_size / 1024 / 1024
    print(f"{OUT}: {len(pal_frames)} frames, {size_mb:.1f} MB")


if __name__ == "__main__":
    main()
