# -*- coding: utf-8 -*-
"""PP-YOLOE-S 导出链路复评（val 1324 图，deploy Detector，CPU）：验证 0.421/0.639 在部署链路成立。

注意：脚本内为本机绝对路径，复用时请改为你的模型/数据路径。
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, r"D:\AIC\PaddleDetection\deploy\python")
from infer import Detector  # noqa: E402
from pycocotools.coco import COCO  # noqa: E402
from pycocotools.cocoeval import COCOeval  # noqa: E402

VAL_JSON = r"D:\AIC\agripest\dataset\annotations\val.json"
VAL_IMG = Path(r"D:\AIC\agripest\dataset\images\val")
MODEL_DIR = r"D:\AIC\agripest\from_server_ppyoloe\ppyoloe_plus_crn_s_100e_agripest"
OUT_MD = r"D:\AIC\agripest\scripts\_tmp_eval\ap_ppyoloe_s.md"
DET_JSON = r"D:\AIC\agripest\scripts\_tmp_eval\_val_ppyoloe_s_dets.json"

coco = COCO(VAL_JSON)
name2cid = {c["name"]: c["id"] for c in coco.loadCats(coco.getCatIds())}

det = Detector(model_dir=MODEL_DIR, device="CPU", run_mode="paddle",
               threshold=0.05, output_dir="output", cpu_threads=4,
               enable_mkldnn=True)
labels = det.pred_config.labels
cls2cid = [name2cid[l] for l in labels]

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

results = []
t0 = time.time()
imgs = coco.loadImgs(coco.getImgIds())
for i, im in enumerate(imgs):
    path = VAL_IMG / im["file_name"]
    img = np.array(Image.open(path).convert("RGB"))
    res = det.predict_image([img], visual=False)
    boxes = res.get("boxes")
    if boxes is not None and len(boxes):
        for b in boxes:
            cls_id, score = int(b[0]), float(b[1])
            if cls_id >= len(labels):
                continue
            x1, y1, x2, y2 = (float(v) for v in b[2:6])
            results.append({"image_id": im["id"], "category_id": cls2cid[cls_id],
                            "bbox": [x1, y1, x2 - x1, y2 - y1], "score": score})
    if (i + 1) % 200 == 0:
        print("[%d/%d] %.1fs" % (i + 1, len(imgs), time.time() - t0), flush=True)

print("inference done: %d imgs, %d dets, %.1fs" % (len(imgs), len(results), time.time() - t0))
Path(DET_JSON).parent.mkdir(parents=True, exist_ok=True)
Path(DET_JSON).write_text(json.dumps(results))
cocoDt = coco.loadRes(DET_JSON)
E = COCOeval(coco, cocoDt, "bbox")
E.evaluate(); E.accumulate(); E.summarize()

prec = E.eval["precision"]
cat_names = {v: k for k, v in name2cid.items()}
lines = ["# PP-YOLOE-S 导出链路复评（val）", "",
         "- mAP@[.5:.95] = %.4f（训练链路 Best 0.421）" % E.stats[0],
         "- mAP@0.50      = %.4f（训练链路 Best 0.639）" % E.stats[1],
         "- AP small/medium/large = %.4f / %.4f / %.4f" % (E.stats[3], E.stats[4], E.stats[5]),
         "- PicoDet-L 导出链路基线：0.3774 / 0.6164", "",
         "| 类别 | AP50 |", "|---|---|"]
for cid in sorted(name2cid.values()):
    k = cid - 1
    p50 = prec[0, :, k, 0, 2]
    p50 = p50[p50 > -1]
    ap = p50.mean() if p50.size else float("nan")
    lines.append("| %s | %.3f |" % (cat_names[cid], ap))
Path(OUT_MD).write_text("\n".join(lines), encoding="utf-8")
print("saved:", OUT_MD)
