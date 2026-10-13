# -*- coding: utf-8 -*-
"""弱类过采样（agripest 同款逻辑，CottonWeedDet12 版）：按目标框数重复含弱类图片。

动机：16.9:1 长尾（Waterhemp 1959 vs CutleafGroundcherry 116），直接训练压制弱类召回。

规则（确定性，无随机）：
- 目标框数锚点 target = max(各类框数中位数, 总框数/类别数/2)；
- 每张图的重复因子 k = clamp(ceil(target / 该图最稀有类的框数), 1, MAX_REPEAT)；
- 重复图片以新 id 追加（id 偏移 +1e7），其标注同步复制；
- 输出 dataset/annotations/train_balanced.json，原 train.json 不动。

用法（与 convert_to_coco.py 同目录运行）：python scripts/balance_train.py [--max-repeat 4]
"""
import argparse
import json
import statistics
from collections import Counter
from pathlib import Path

import pathsafe

ANNOT_DIR = (Path(__file__).resolve().parent.parent / "dataset" / "annotations").resolve()
ID_OFFSET = 10_000_000


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--src", default="train.json")
    ap.add_argument("--out", default="train_balanced.json")
    ap.add_argument("--max-repeat", type=int, default=4)
    args = ap.parse_args()

    src = pathsafe.under(ANNOT_DIR / args.src, ANNOT_DIR)
    data = json.loads(src.read_text(encoding="utf-8"))
    cats = {c["id"]: c["name"] for c in data["categories"]}

    boxes = Counter(a["category_id"] for a in data["annotations"])
    median = statistics.median(boxes.values())
    mean_half = sum(boxes.values()) / len(boxes) / 2
    target = max(median, mean_half)

    anns_by_img = {}
    for a in data["annotations"]:
        anns_by_img.setdefault(a["image_id"], []).append(a)

    images, annotations = list(data["images"]), list(data["annotations"])
    n_rep = 0
    for im in data["images"]:
        img_cats = {a["category_id"] for a in anns_by_img.get(im["id"], [])}
        if not img_cats:
            continue
        min_cnt = min(boxes.get(c, 0) for c in img_cats)
        k = min(args.max_repeat, max(1, -(-int(target) // max(min_cnt, 1))))
        for rep in range(1, k):
            new_id = im["id"] + ID_OFFSET * rep
            images.append({**im, "id": new_id})
            for a in anns_by_img[im["id"]]:
                annotations.append({**a, "id": a["id"] + ID_OFFSET * rep,
                                    "image_id": new_id})
            n_rep += 1

    before = {cats[cid]: n for cid, n in boxes.items()}
    after = Counter(cats[a["category_id"]] for a in annotations)
    out = {"images": images, "annotations": annotations,
           "categories": data["categories"]}
    dst = pathsafe.under(ANNOT_DIR / args.out, ANNOT_DIR)
    dst.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")

    print(f"target boxes/class = {target:.0f} (median {median:.0f}, mean/2 {mean_half:.0f})")
    print(f"images {len(data['images'])} -> {len(images)} (+{n_rep} copies), "
          f"anns {len(data['annotations'])} -> {len(annotations)}")
    print(f"{'class':22s} {'before':>7s} {'after':>7s}")
    for name, n in sorted(before.items(), key=lambda kv: kv[1]):
        print(f"{name:22s} {n:7d} {after[name]:7d}")
    print(f"-> {dst.name}")


if __name__ == "__main__":
    main()
