# -*- coding: utf-8 -*-
"""PP-YOLOE-S 分层评测：按来源（ip102/plantdoc）评 val mAP50。对照 PicoDet 0.905/0.459。

注意：脚本内为本机绝对路径，复用时请改为你的数据路径。
"""
from pycocotools.coco import COCO
from pycocotools.cocoeval import COCOeval

DET = r"D:\AIC\agripest\scripts\_tmp_eval\_val_ppyoloe_s_dets.json"
VAL = r"D:\AIC\agripest\dataset\annotations\val.json"

coco = COCO(VAL)
dt = coco.loadRes(DET)


def eval_on(img_ids, title):
    E = COCOeval(coco, dt, "bbox")
    E.params.imgIds = img_ids
    E.evaluate(); E.accumulate(); E.summarize()
    print("== %s: n_img=%d mAP50=%.4f mAP[.5:.95]=%.4f"
          % (title, len(img_ids), E.stats[1], E.stats[0]), flush=True)


src_ip = [im["id"] for im in coco.loadImgs(coco.getImgIds())
          if im["file_name"].startswith("ip102__")]
src_pd = [im["id"] for im in coco.loadImgs(coco.getImgIds())
          if im["file_name"].startswith("plantdoc__")]
eval_on(src_ip, "ip102 子集（害虫主场，PicoDet 基线 0.905）")
eval_on(src_pd, "plantdoc 子集（病害主场，PicoDet 基线 0.459）")
