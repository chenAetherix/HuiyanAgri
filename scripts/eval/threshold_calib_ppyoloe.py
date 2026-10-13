# -*- coding: utf-8 -*-
"""PP-YOLOE-S 证据链重算（val，基于 _val_ppyoloe_s_dets.json，无推理）：
逐类阈值(F1/R90) + ECE 分箱(修正版中心) + 阈值-precision/recall + 分层逐框精确率。
对照 PicoDet 版口径（per_class_threshold.py / plot_figures.py 同逻辑）。

注意：脚本内为本机绝对路径，复用时请改为你的数据路径。
"""
import json

from pycocotools.coco import COCO

DET = r"D:\AIC\agripest\scripts\_tmp_eval\_val_ppyoloe_s_dets.json"
VAL = r"D:\AIC\agripest\dataset\annotations\val.json"

coco = COCO(VAL)
cats = {c["id"]: c["name"] for c in coco.loadCats(coco.getCatIds())}
dets = json.load(open(DET))


def iou_xywh(a, b):
    ix = max(0.0, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
    inter = ix * iy
    return inter / (a[2] * a[3] + b[2] * b[3] - inter + 1e-9)


img_src = {}
for im in coco.loadImgs(coco.getImgIds()):
    img_src[im["id"]] = ("ip" if im["file_name"].startswith("ip102__")
                         else "pd" if im["file_name"].startswith("plantdoc__") else "ot")

by_img_cat = {}
for d in dets:
    by_img_cat.setdefault((d["image_id"], d["category_id"]), []).append(d)
n_gt = {cid: 0 for cid in cats}
for iid, anns in coco.imgToAnns.items():
    for g in anns:
        n_gt[g["category_id"]] = n_gt.get(g["category_id"], 0) + 1
pairs = {cid: [] for cid in cats}
pairs_src = {"ip": [], "pd": []}
bin_tp = [0] * 10
bin_n = [0] * 10
for (iid, cid), group in by_img_cat.items():
    gts = [g for g in coco.imgToAnns.get(iid, []) if g["category_id"] == cid]
    used = set()
    group.sort(key=lambda d: -d["score"])
    for d in group:
        best, bi = 0.0, -1
        for gi, g in enumerate(gts):
            if gi in used:
                continue
            v = iou_xywh(d["bbox"], g["bbox"])
            if v > best:
                best, bi = v, gi
        if best >= 0.5:
            used.add(bi)
        tp01 = 1 if best >= 0.5 else 0
        pairs[cid].append((d["score"], tp01))
        if img_src[iid] in pairs_src:
            pairs_src[img_src[iid]].append((d["score"], tp01))
        b = min(int(d["score"] * 10), 9)
        bin_n[b] += 1
        bin_tp[b] += tp01

print("== 逐类阈值（F1 最小阈值 / recall>=0.9 最小阈值）==")
print("%-28s %8s %10s %10s %8s" % ("class", "F1_thr", "R90_thr", "F1", "n_gt"))
for cid, name in sorted(cats.items(), key=lambda kv: kv[1]):
    ps = sorted(pairs[cid], key=lambda p: -p[0])
    P = n_gt[cid]
    tp = seen = 0
    f1_best, thr_f1 = 0.0, 0.05
    thr_r90 = None
    for sc, is_tp in ps:
        seen += 1
        tp += is_tp
        prec, rec = tp / seen, tp / max(P, 1)
        f1 = 2 * prec * rec / (prec + rec + 1e-9)
        if f1 > f1_best:
            f1_best, thr_f1 = f1, sc
        if rec >= 0.90 and thr_r90 is None:
            thr_r90 = sc
    if thr_r90 is None:
        thr_r90 = round(ps[-1][0], 2) if ps else 0.30
    print("%-28s %8.2f %10.2f %10.3f %8d" % (name, thr_f1, thr_r90, f1_best, P))

centers = [(b + 0.5) / 10 for b in range(10)]
acc = [bin_tp[b] / bin_n[b] if bin_n[b] else 0 for b in range(10)]
ece = sum(bin_n[b] * abs(acc[b] - centers[b]) for b in range(10) if bin_n[b]) / max(sum(bin_n), 1)
mce = max((abs(acc[b] - centers[b]) for b in range(10) if bin_n[b]), default=0)
print("\n== ECE 分箱（N=%d）==" % sum(bin_n))
for b in range(10):
    if bin_n[b]:
        print("  conf=%.2f  n=%6d  acc=%.4f  |dev|=%.4f" % (centers[b], bin_n[b], acc[b], abs(acc[b] - centers[b])))
print("ECE=%.4f  MCE=%.4f" % (ece, mce))

allp = sorted([p for ps in pairs.values() for p in ps], key=lambda p: -p[0])
n_gt_total = sum(len(v) for v in coco.imgToAnns.values())


def pr_at(pairs_sorted, t, n_gt_ref):
    tp = n = 0
    for sc, is_tp in pairs_sorted:
        if sc < t:
            break
        n += 1
        tp += is_tp
    return (tp / n if n else 1.0), tp / n_gt_ref


print("\n== 阈值-precision/recall ==")
for t in (0.20, 0.30, 0.35, 0.50, 0.80, 0.90):
    p, r = pr_at(allp, t, n_gt_total)
    print("  @%.2f  precision=%.4f  recall=%.4f" % (t, p, r))

print("\n== 分层逐框精确率 ==")
for src, name in (("ip", "ip102(虫)"), ("pd", "plantdoc(病)")):
    ps = sorted(pairs_src[src], key=lambda p: -p[0])
    n_gt_src = sum(len(a) for iid, a in coco.imgToAnns.items() if img_src[iid] == src)
    for t in (0.35, 0.80):
        p, r = pr_at(ps, t, n_gt_src)
        print("  %s @%.2f  precision=%.4f  recall=%.4f  (n_gt=%d)" % (name, t, p, r, n_gt_src))
