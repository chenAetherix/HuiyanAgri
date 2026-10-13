# -*- coding: utf-8 -*-
"""构建 640 预缩存数据集：原图解码是 4070 训练瓶颈（3024×4032 JPEG，GPU util 9%）。

产物: dataset640/
    images/        —— 全量 5648 图缩到 640×640（拉伸，与训练 reader keep_ratio:False 对齐；
                      Roboflow 镜像同口径），JPEG q90，多线程
    annotations/   —— 全部划分 json 同名重建，bbox/area 按每图 (640/W, 640/H) 缩放
原图与 dataset/（原坐标标注）保留不动：评估展示、CWFID 对照、二次实验仍用原分辨率。
用法（与 convert_to_coco.py 同目录运行）：python scripts/build_640_cache.py [--workers 8]
"""
import argparse
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

import pathsafe

ROOT = Path(__file__).resolve().parent.parent.parent
SRC_IMG = ROOT / "dataset" / "images"
SRC_ANN = ROOT / "dataset" / "annotations"
DST = ROOT / "dataset640"
SIZE = 640


def resize_one(args):
    src_path, dst_path = args
    if dst_path.exists():
        return 0
    try:
        with Image.open(src_path) as im:
            im.convert("RGB").resize((SIZE, SIZE), Image.BILINEAR).save(
                dst_path, format="JPEG", quality=90, optimize=True)
        return 1
    except Exception as e:
        return f"{src_path.name}:{type(e).__name__}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()

    (DST / "images").mkdir(parents=True, exist_ok=True)
    (DST / "annotations").mkdir(parents=True, exist_ok=True)

    jobs = []
    for p in sorted(SRC_IMG.iterdir()):
        if p.suffix.lower() in {".jpg", ".jpeg", ".png"}:
            jobs.append((p, DST / "images" / (p.stem + ".jpg")))
    print(f"resize {len(jobs)} images -> {DST/'images'}")
    with ThreadPoolExecutor(args.workers) as ex:
        results = list(ex.map(resize_one, jobs))
    errs = [r for r in results if isinstance(r, str)]
    done = sum(1 for r in results if r == 1)
    print(f"resized {done}, skipped(existing) {len(results) - done - len(errs)}, errors {len(errs)}")
    if errs:
        sys.exit(f"FAIL resize 错误: {errs[:5]}")

    # 标注缩放重建（width/height 已在原 json 里，无需重读图）
    for jf in sorted(SRC_ANN.glob("*.json")):
        data = json.loads(pathsafe.under(jf, SRC_ANN).read_text(encoding="utf-8"))
        scale = {im["id"]: (SIZE / im["width"], SIZE / im["height"]) for im in data["images"]}
        for im in data["images"]:
            im["width"], im["height"] = SIZE, SIZE
        for a in data["annotations"]:
            sx, sy = scale[a["image_id"]]
            x, y, w, h = a["bbox"]
            a["bbox"] = [x * sx, y * sy, w * sx, h * sy]
            a["area"] = a["bbox"][2] * a["bbox"][3]
        out = pathsafe.under(DST / "annotations" / jf.name, DST)
        out.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        print(f"  {jf.name}: {len(data['images'])} 图 {len(data['annotations'])} 框 已缩放")
    print("-> dataset640/ 就绪（config 改 dataset_dir 指向它）")


if __name__ == "__main__":
    main()
