# -*- coding: utf-8 -*-
"""agriweed 终评脚本：训练完成后一键跑齐材料所需的全部指标。

  ① test 集官方 COCO 指标 + per-class AP50（COCOeval，demo 同款导出链路）
  ② CWFID 跨作物外部测试：甜菜田作物/杂草图上的检出统计（域外负样本/泛化观察）

用法（先导出模型，见 demo/README.md）:
    # Windows 两个必备环境坑（2026-10-04 实测踩到）：
    #   1) 配置含 UTF-8 中文注释，缺 PYTHONUTF8 会以 GBK 读 YAML 报 UnicodeDecodeError
    #   2) Paddle 构建 Conv2D 时即需要 cudnn64_8.dll，即使 use_gpu=false
    set PYTHONUTF8=1
    set PYTHONIOENCODING=utf-8
    python scripts/eval_final.py \
        --model_dir demo/model/picodet_l_640_agriweed \
        [--anno dataset640/annotations/test.json] [--thr 0.35] \
        [--out scripts/eval_final.md]

双口径对照（互不覆盖）:
    --anno dataset640/annotations/test.json          --out scripts/eval_final_stratified.md
    --anno dataset640/annotations/test_official.json --out scripts/eval_final_official.md
"""
import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, r"D:\AIC\PaddleDetection\deploy\python")
from infer import Detector  # noqa: E402

CWFID_DIR = ROOT / "raw" / "cwfid" / "images"


def run_detector(det, img_dir: Path, names, limit=None):
    """逐图推理，返回 {file_name: [(cls_id, score, x1,y1,x2,y2), ...]}（原图坐标系）。"""
    out = {}
    for i, fn in enumerate(names):
        if limit and i >= limit:
            break
        img = np.array(Image.open(img_dir / fn).convert("RGB"))
        res = det.predict_image([img], visual=False)
        boxes = res.get("boxes")
        out[fn] = [] if boxes is None else [
            (int(b[0]), float(b[1]), *[float(v) for v in b[2:6]]) for b in boxes]
    return out


def coco_eval(anno_path: Path, img_dir: Path, dets, cats):
    from pycocotools.coco import COCO
    from pycocotools.cocoeval import COCOeval
    coco = COCO(str(anno_path))
    # pycocotools 的 getImgIds() 只接受 imgIds/catIds，不支持 file_name 关键字
    # （原写法 coco.getImgIds(file_name=fn) 必抛 TypeError）——用 images 表自建映射。
    # 本数据集 categories.id = 0..11 且顺序与模型 label_list 完全一致，
    # 故检测框的类别索引可直接作为 category_id 使用。
    name2id = {im["file_name"]: im["id"] for im in coco.dataset["images"]}
    json_det = [{"image_id": name2id[fn], "category_id": c, "score": s,
                 "bbox": [x1, y1, x2 - x1, y2 - y1]}
                for fn, bs in dets.items() if fn in name2id
                for c, s, x1, y1, x2, y2 in bs]
    det_coco = coco.loadRes(json_det)
    ev = COCOeval(coco, det_coco, "bbox")
    ev.evaluate()
    ev.accumulate()
    ev.summarize()
    # per-class AP50 直接从已累计的 precision 取，等价于 COCOeval 的 stats[1]
    prec = ev.eval["precision"]          # [T, R, K, A, M]
    per_class = {}
    for k, cid in enumerate(ev.params.catIds):
        p = prec[0, :, k, 0, 2]
        p = p[p > -1]
        name = cats[cid] if 0 <= cid < len(cats) else f"cat{cid}"
        per_class[name] = float(p.mean()) if p.size else float("nan")
    return float(ev.stats[1]), float(ev.stats[0]), per_class  # mAP50, mAP.5:.95


def main():
    ap = argparse.ArgumentParser()
    here = Path(__file__).parent
    ap.add_argument("--model_dir", default=str(ROOT / "demo" / "model" / "picodet_l_640_agriweed"))
    ap.add_argument("--anno", default=str(ROOT / "dataset640" / "annotations" / "test.json"))
    ap.add_argument("--img_dir", default=str(ROOT / "dataset640" / "images"))
    ap.add_argument("--thr", type=float, default=0.35)
    ap.add_argument("--skip-cwfid", action="store_true")
    ap.add_argument("--out", default=str(here / "eval_final.md"),
                    help="结果 md 路径；双口径对照时分别指定，避免互相覆盖")
    args = ap.parse_args()

    det = Detector(model_dir=args.model_dir, device="CPU", run_mode="paddle",
                   threshold=0.05, output_dir="output", cpu_threads=4, enable_mkldnn=True)
    labels = det.pred_config.labels

    # ① test 集官方指标
    anno = Path(args.anno)
    gt = json.loads(anno.read_text(encoding="utf-8"))
    names = [im["file_name"] for im in gt["images"]]
    print(f"[1/2] test 集推理 {len(names)} 图 ...")
    raw = run_detector(det, Path(args.img_dir), names)
    dets = {fn: [b for b in bs if b[1] >= args.thr] for fn, bs in raw.items()}
    mAP50, mAP_all, per_class = coco_eval(anno, Path(args.img_dir), dets, labels)
    n_det = sum(len(v) for v in dets.values())
    print(f"阈值 {args.thr}：检出 {n_det} 框；mAP50={mAP50:.4f} mAP[.5:.95]={mAP_all:.4f}")

    caliber = "随机划分口径" if "official" in anno.name else "分层划分口径"
    lines = [f"# agriweed 终评（阈值 {args.thr}，anno={anno.name}）\n",
             f"- {caliber}：**mAP50 = {mAP50:.4f}**，mAP[.5:.95] = {mAP_all:.4f}（demo 导出链路，COCOeval）\n",
             f"- 检出统计：test 集共 {n_det} 框（阈值 {args.thr}）\n",
             "\n## per-class AP50\n",
             "| 类别 | 中文名 | AP50 |", "|---|---|---|"]

    def _fmt(v):
        return "n/a（该类无 GT 或全漏检）" if v != v else f"{v:.3f}"

    for c, ap_v in sorted(per_class.items(),
                          key=lambda kv: (kv[1] != kv[1], -(kv[1] if kv[1] == kv[1] else 0))):
        lines.append(f"| {c} | {_fmt(ap_v)} |")

    # ② CWFID 域外观察
    if not args.skip_cwfid and CWFID_DIR.is_dir():
        names_c = sorted(p.name for p in CWFID_DIR.glob("*.png"))[:60]
        print(f"[2/2] CWFID 域外测试 {len(names_c)} 图 ...")
        raw_c = run_detector(det, CWFID_DIR, names_c)
        dets_c = {fn: [b for b in bs if b[1] >= args.thr] for fn, bs in raw_c.items()}
        n_c = sum(len(v) for v in dets_c.values())
        zero = sum(1 for v in dets_c.values() if not v)
        by_cls = defaultdict(int)
        for bs in dets_c.values():
            for b in bs:
                by_cls[labels[b[0]]] += 1
        lines += ["\n## CWFID 域外观察（甜菜田，非棉田训练域）\n",
                  f"- {len(names_c)} 图、阈值 {args.thr}：共 {n_c} 检出"
                  f"（{n_c / max(len(names_c), 1):.2f}/图），零检出图 {zero}/{len(names_c)}\n",
                  "- 检出类别分布：" + ", ".join(f"{k}={v}" for k, v in
                                                   sorted(by_cls.items(), key=lambda kv: -kv[1]))]
        print(f"CWFID: {n_c / max(len(names_c), 1):.2f} 检出/图，零检出 {zero}/{len(names_c)}")

    dets_name = f"_eval_dets_{anno.stem}.json"   # 带口径后缀，双口径互不覆盖
    (here / dets_name).write_text(
        json.dumps({fn: [list(b) for b in bs] for fn, bs in dets.items()}), encoding="utf-8")
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"-> {out_path}")


if __name__ == "__main__":
    main()
