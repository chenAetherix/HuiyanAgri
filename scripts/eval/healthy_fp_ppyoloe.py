# -*- coding: utf-8 -*-
"""PP-YOLOE-S 健康图误报：val 中仅含 tomato_leaf_healthy 真值的图，
统计非健康类检出（阈值 0.35 / 0.20）。基于 _val_ppyoloe_s_dets.json，无推理。
对照 PicoDet：1.8 个/图（0.35 档）。

注意：脚本内为本机绝对路径，复用时请改为你的数据路径。
"""
import json

from pycocotools.coco import COCO

coco = COCO(r"D:\AIC\agripest\dataset\annotations\val.json")
dets = json.load(open(r"D:\AIC\agripest\scripts\_tmp_eval\_val_ppyoloe_s_dets.json"))
hid = [c["id"] for c in coco.loadCats(coco.getCatIds()) if c["name"] == "tomato_leaf_healthy"][0]

only_h = [iid for iid, anns in coco.imgToAnns.items()
          if anns and all(a["category_id"] == hid for a in anns)]
fp35 = fp20 = 0
for d in dets:
    if d["image_id"] in only_h and d["category_id"] != hid:
        if d["score"] >= 0.35:
            fp35 += 1
        if d["score"] >= 0.20:
            fp20 += 1
print("仅含健康真值的图: %d 张" % len(only_h))
print("非健康类检出 @0.35: %d 个（%.1f/图）| @0.20: %d 个（%.1f/图）"
      % (fp35, fp35 / len(only_h), fp20, fp20 / len(only_h)))
