# -*- coding: utf-8 -*-
"""CottonWeedDet12 VIA -> COCO 转换 + 双轨划分 + YOLO txt 对照核查。

输入: raw/extracted/CottonWeedDet12/{weedImages, annotation_VGG_json, annotation_YOLO_txt}
输出: dataset/
    images/            —— 指向 weedImages 全量图的硬链接（同卷零拷贝，失败退化为复制）
    annotations/train.json|val.json|test.json          —— 分层划分 65/20/15（主口径，保尾部类覆盖）
    annotations/train_official.json|val_official.json|test_official.json
                       —— 随机划分（random.seed(0) 纯随机 65/20/15，跨文献量级参照口径；非官方划分文件）
    dataset_config.json（本脚本所在目录）—— 类别表/划分汇总/每集框数
设计要点（七轮评审累积）:
  - 未知类标签只丢该框、图剩 ≥1 框保留（体检实证 unknown=0，此处为防御）
  - VIA 包装器 json（顶层键=VIA 图像 ID）/ 平铺 / 主 json 三形态全兼容
  - 总数强校验: 5648 图 / 9370 框，不符即失败退出
  - YOLO txt 对照: 行数合计与逐文件对比 VIA，输出差异清单（核查外部分析 9388 vs 9370 口径）
用法（建议把本文件夹四个脚本放进你工程的 scripts/ 子目录，脚本按 ../dataset 相对路径工作）:
    python scripts/convert_to_coco.py [--src raw/extracted/CottonWeedDet12]
"""
import argparse
import json
import random
import shutil
import sys
from collections import Counter, defaultdict
from math import ceil
from pathlib import Path

OFFICIAL_12 = ["Waterhemp", "MorningGlory", "Purslane", "SpottedSpurge", "Carpetweed",
               "Ragweed", "Eclipta", "PricklySida", "PalmerAmaranth", "Sicklepod",
               "Goosegrass", "CutleafGroundcherry"]
CATS = [{"id": i, "name": n} for i, n in enumerate(OFFICIAL_12)]
TARGET_IMGS, TARGET_BOXES = 5648, 9370
IMG_EXT = {".jpg", ".jpeg", ".png"}


def load_via_entries(anno_dir: Path):
    """返回 {filename: [(cls_idx, x, y, w, h), ...]}，丢未知框并计数。"""
    entries, dropped_unknown, failed = {}, Counter(), []
    for p in sorted(anno_dir.glob("*.json")):
        try:
            data = json.loads(p.read_text(encoding="utf-8-sig"))
        except Exception as e:
            failed.append(f"{p.name}:{type(e).__name__}")
            continue
        meta = None
        if isinstance(data, dict) and "_via_img_metadata" in data:
            meta = next(iter(data["_via_img_metadata"].values()))
        elif isinstance(data, dict) and ("regions" in data or "filename" in data):
            meta = data
        elif isinstance(data, dict):
            inner = [v for v in data.values()
                     if isinstance(v, dict) and ("regions" in v or "filename" in v)]
            meta = inner[0] if inner else None
        if meta is None:
            failed.append(f"{p.name}:unrecognized")
            continue
        fn = meta.get("filename") or (p.stem + ".jpg")
        regs = meta.get("regions")
        if isinstance(regs, dict):
            regs = [regs] if regs else []
        elif regs is None:
            regs = []
        boxes = []
        for r in regs:
            sa = r.get("shape_attributes") or {}
            ra = r.get("region_attributes") or {}
            v = ra.get("CottonWeed")
            label = next(iter(v), None) if isinstance(v, dict) else (v if isinstance(v, str) else None)
            if label is None or label not in OFFICIAL_12:
                dropped_unknown[label or "<无标签>"] += 1
                continue
            boxes.append((OFFICIAL_12.index(label),
                          float(sa.get("x", 0)), float(sa.get("y", 0)),
                          float(sa.get("width", 0)), float(sa.get("height", 0))))
        entries[Path(fn).name] = boxes
    return entries, dropped_unknown, failed


def main():
    ap = argparse.ArgumentParser()
    here = Path(__file__).parent
    root = here.parent
    ap.add_argument("--src", default=str(root / "raw" / "extracted" / "CottonWeedDet12"))
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    src = Path(args.src)
    img_dir, via_dir, yolo_dir = src / "weedImages", src / "annotation_VGG_json", src / "annotation_YOLO_txt"
    for d in (img_dir, via_dir):
        if not d.is_dir():
            sys.exit(f"缺少目录: {d}")

    entries, dropped, failed = load_via_entries(via_dir)
    if failed:
        sys.exit(f"FAIL VIA 解析失败 {len(failed)} 份: {failed[:5]}")

    # 尺寸（PIL 头），全量登记
    from PIL import Image
    images_meta = {}
    all_imgs = sorted(p for p in img_dir.iterdir() if p.suffix.lower() in IMG_EXT)
    for i, p in enumerate(all_imgs):
        with Image.open(p) as im:
            images_meta[p.name] = (i + 1, im.size[0], im.size[1])

    # 强校验
    n_imgs, n_boxes = len(all_imgs), sum(len(v) for v in entries.values())
    print(f"图像 {n_imgs}（期望 {TARGET_IMGS}）  VIA 框 {n_boxes}（期望 {TARGET_BOXES}）  丢未知框 {sum(dropped.values())}")
    if n_imgs != TARGET_IMGS or n_boxes != TARGET_BOXES:
        sys.exit("FAIL 总数不符，终止（勿生成残缺数据集）")

    # YOLO txt 对照核查
    if yolo_dir.is_dir():
        yolo_total, diffs = 0, []
        for p in sorted(yolo_dir.glob("*.txt")):
            n = sum(1 for line in p.read_text(encoding="utf-8").splitlines() if line.strip())
            yolo_total += n
            via_n = len(entries.get(p.stem + ".jpg", []))
            if n != via_n:
                diffs.append(f"{p.stem}: yolo={n} via={via_n}")
        print(f"YOLO txt 合计 {yolo_total} 框 vs VIA {n_boxes}"
              f"（差异 {yolo_total - n_boxes}，逐文件不符 {len(diffs)} 份）")
        for d in diffs[:6]:
            print(f"  {d}")

    # COCO 组装（每图条目）
    def build_coco(names: list):
        images, annotations = [], []
        aid = 1
        for fn in names:
            iid, w, h = images_meta[fn]
            images.append({"id": iid, "file_name": fn, "width": w, "height": h})
            for cid, x, y, bw, bh in entries[fn]:
                annotations.append({"id": aid, "image_id": iid, "category_id": cid,
                                    "bbox": [x, y, bw, bh], "area": bw * bh, "iscrowd": 0})
                aid += 1
        return {"images": images, "annotations": annotations, "categories": CATS}

    # ---- 划分 A：随机划分（seed 0 纯随机 65/20/15；非官方划分文件，量级参照口径）----
    names = list(images_meta.keys())
    rng = random.Random(0)
    rng.shuffle(names)
    n_val = ceil(0.20 * len(names))
    n_test = ceil(0.15 * len(names))
    off_val, off_test = names[:n_val], names[n_val:n_val + n_test]
    off_train = names[n_val + n_test:]

    # ---- 划分 B：按最稀有类分层（主口径，保尾部类 val/test 覆盖）----
    class_count = Counter()
    for fn in names:
        for cid in {b[0] for b in entries[fn]}:
            class_count[cid] += 1
    groups = defaultdict(list)
    for fn in names:
        rarest = min((cid for cid in {b[0] for b in entries[fn]}), key=lambda c: class_count[c])
        groups[rarest].append(fn)
    strat_train, strat_val, strat_test = [], [], []
    for cid in sorted(groups):
        g = groups[cid][:]
        random.Random(args.seed + cid).shuffle(g)
        nv, nt = ceil(0.20 * len(g)), ceil(0.15 * len(g))
        strat_val += g[:nv]
        strat_test += g[nv:nv + nt]
        strat_train += g[nv + nt:]

    out_ann = root / "dataset" / "annotations"
    out_img = root / "dataset" / "images"
    out_img.mkdir(parents=True, exist_ok=True)
    out_ann.mkdir(parents=True, exist_ok=True)
    for name, split in [("train", strat_train), ("val", strat_val), ("test", strat_test),
                        ("train_official", off_train), ("val_official", off_val),
                        ("test_official", off_test)]:
        (out_ann / f"{name}.json").write_text(
            json.dumps(build_coco(split), ensure_ascii=False), encoding="utf-8")

    # images 硬链接（同卷零拷贝；失败退复制）
    linked, copied = 0, 0
    for p in all_imgs:
        dst = out_img / p.name
        if dst.exists():
            continue
        try:
            dst.hardlink_to(p)
            linked += 1
        except OSError:
            shutil.copy2(p, dst)
            copied += 1

    # 汇总
    def boxes_of(split):
        return Counter(OFFICIAL_12[b[0]] for fn in split for b in entries[fn])

    summary = {
        "classes": OFFICIAL_12,
        "categories": CATS,
        "splits": {k: {"images": len(v), "boxes": dict(boxes_of(v))} for k, v in
                   [("train", strat_train), ("val", strat_val), ("test", strat_test),
                    ("train_official", off_train), ("val_official", off_val),
                    ("test_official", off_test)]},
        "dropped_unknown": dict(dropped),
        "images_hardlinked": linked, "images_copied": copied,
    }
    (here / "dataset_config.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n划分汇总（图/框）:")
    for k, v in summary["splits"].items():
        print(f"  {k:<16} {v['images']:>5} 图  {sum(v['boxes'].values()):>5} 框")
    tail_ok = all(summary["splits"]["val"]["boxes"].get(c, 0) > 0 and
                  summary["splits"]["test"]["boxes"].get(c, 0) > 0 for c in OFFICIAL_12)
    print(f"尾部类覆盖: {'val/test 全 12 类有框 PASS' if tail_ok else 'FAIL（有类缺框）'}")
    print(f"images: 硬链接 {linked} / 复制 {copied}")
    print("-> dataset/ 与 scripts/dataset_config.json 就绪")


if __name__ == "__main__":
    main()
