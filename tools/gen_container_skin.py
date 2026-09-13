#!/usr/bin/env python3
"""容器界面贴图替换生成器：把箱子 UI 底图改为 ProjectE 风格。

技术路线：**直接替换 `minecraft:textures/gui/container/generic_54.png`**
（6 行箱子界面底图）。以原版贴图为骨架（保留槽位凹陷/边框结构），
将配色映射为 ProjectE 面板的主色调，使机器界面呈现原模组观感。

配色映射：
    原版背景 #C6C6C6（浅灰）→ ProjectE 面板主色
    原版槽位 #8B8B8B（深灰）→ 主色压暗
    边框/高光/文字区保持原样（结构可读性）

用法：
    python tools/gen_container_skin.py <客户端 jar> <ProjectE jar> [输出目录]
"""
import sys
import zipfile
from collections import Counter
from pathlib import Path

from PIL import Image

# 原版箱子 GUI 调色板（浅灰背景 / 深灰槽位）
VANILLA_BG = (198, 198, 198)      # #C6C6C6
VANILLA_SLOT = (139, 139, 139)    # #8B8B8B
TOLERANCE = 12

# 面板配色采样源（ProjectE 的通用界面贴图）
SAMPLING_TEXTURES = ["transmute.png", "alchchest.png", "collector1.png"]


def sample_palette(z: zipfile.ZipFile):
    """从 ProjectE 面板采样主色与次色（非透明像素的高频色）。"""
    counter = Counter()
    for name in SAMPLING_TEXTURES:
        try:
            with z.open(f"assets/projecte/textures/gui/{name}") as handle:
                img = Image.open(handle).convert("RGBA")
        except KeyError:
            continue
        pixels = img.load()
        for y in range(0, img.height, 2):
            for x in range(0, img.width, 2):
                rgba = pixels[x, y]
                if rgba[3] > 200:
                    counter[rgba[:3]] += 1
    if not counter:
        return (60, 40, 80), (40, 26, 56)
    top = counter.most_common(6)
    primary = top[0][0]
    # 次色：与主色差异最大的高频色，退化时用主色压暗
    secondary = None
    for color, _ in top[1:]:
        diff = sum(abs(a - b) for a, b in zip(color, primary))
        if diff > 48:
            secondary = color
            break
    if secondary is None:
        secondary = tuple(max(0, int(c * 0.62)) for c in primary)
    return primary, secondary


def close_to(pixel, target, tolerance=TOLERANCE):
    return all(abs(pixel[i] - target[i]) <= tolerance for i in range(3))


def recolor(img: Image.Image, primary, secondary) -> Image.Image:
    out = img.copy()
    pixels = out.load()
    for y in range(out.height):
        for x in range(out.width):
            rgba = pixels[x, y]
            if rgba[3] == 0:
                continue
            rgb = rgba[:3]
            if close_to(rgb, VANILLA_BG):
                # 背景 → 主色（按与原版背景的明度差微调，保留光影层次）
                delta = (sum(rgb) - sum(VANILLA_BG)) / 3.0
                pixels[x, y] = (
                    max(0, min(255, int(primary[0] + delta))),
                    max(0, min(255, int(primary[1] + delta))),
                    max(0, min(255, int(primary[2] + delta))),
                    rgba[3],
                )
            elif close_to(rgb, VANILLA_SLOT):
                pixels[x, y] = (secondary[0], secondary[1], secondary[2], rgba[3])
    return out


def main() -> None:
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    vanilla_jar = Path(sys.argv[1])
    projecte_jar = Path(sys.argv[2])
    out_root = (Path(sys.argv[3]) if len(sys.argv) > 3 else
                Path(__file__).resolve().parent.parent
                / "src/main/resources/content/projecte/assets/minecraft/textures/gui/container")

    with zipfile.ZipFile(vanilla_jar) as vanilla, zipfile.ZipFile(projecte_jar) as projecte:
        primary, secondary = sample_palette(projecte)
        print(f"ProjectE 配色采样：主色 {primary} / 次色 {secondary}")

        out_root.mkdir(parents=True, exist_ok=True)
        with vanilla.open("assets/minecraft/textures/gui/container/generic_54.png") as handle:
            base = Image.open(handle).convert("RGBA")
        skin = recolor(base, primary, secondary)

        # 顶部标题条：取转化桌面板顶部花纹（ProjectE 标志性界面），缩放到 176 宽
        title_src = None
        for name in ("transmute.png", "alchchest.png", "collector1.png"):
            try:
                with projecte.open(f"assets/projecte/textures/gui/{name}") as handle:
                    title_src = Image.open(handle).convert("RGBA")
                break
            except KeyError:
                continue
        if title_src is not None:
            from PIL import ImageChops
            bbox = title_src.getchannel("A").getbbox() or (0, 0, title_src.width, title_src.height)
            strip = title_src.crop((bbox[0], bbox[1], bbox[2], min(bbox[3], bbox[1] + 17)))
            if strip.height > 0:
                strip = strip.resize((176, min(17, strip.height)), Image.LANCZOS)
                skin.paste(strip, (0, 0), strip)
                print(f"标题条已应用（宽 {strip.width} × 高 {strip.height}）")

        skin.save(out_root / "generic_54.png")
        print(f"已生成 generic_54.png（{skin.width}×{skin.height}）→ {out_root}")


if __name__ == "__main__":
    main()
