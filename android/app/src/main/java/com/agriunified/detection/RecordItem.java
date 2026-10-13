package com.agriunified.detection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条历史识别记录（JSON 序列化，org.json 手写转换）。
 * 图片单独存文件 history/&lt;id&gt;.jpg（原图 720px 缩略，未画框）；
 * 检出结果存结构化数据，回看时重新画框，也可换阈值复算。
 */
public class RecordItem {

    public long id;                 // 时间戳即 id（升序唯一）
    public String mode;             // Detector.MODE_PEST / WEED / BOTH
    public float threshold;         // 当时使用的置信度阈值
    public String image;            // 图片文件名（history/ 下）
    public final List<Det> dets = new ArrayList<>();

    /** 单个检出（回看画框 + 摘要所需的最小字段集） */
    public static class Det {
        public String en;
        public String srcMode;      // 综合模式下区分引擎；单模式为 null
        public float score;
        public float x1, y1, x2, y2;
    }

    public String modeZh() {
        if (Detector.MODE_WEED.equals(mode)) return "杂草";
        if (Detector.MODE_BOTH.equals(mode)) return "综合";
        return "病虫害";
    }

    /** 最高分检出的中文名（结果摘要用） */
    public String topZh() {
        Det best = null;
        for (Det d : dets) {
            if (best == null || d.score > best.score) best = d;
        }
        if (best == null) return "未检出";
        return KnowledgeBase.get(best.srcMode != null ? best.srcMode : mode, best.en).zh;
    }

    public float topScore() {
        Det best = null;
        for (Det d : dets) {
            if (best == null || d.score > best.score) best = d;
        }
        return best == null ? 0f : best.score;
    }

    /** 重建为 Detector.Detection 列表（历史回看渲染画框/建议卡用） */
    public List<Detector.Detection> toDetections() {
        List<Detector.Detection> out = new ArrayList<>();
        for (Det d : dets) {
            Detector.Detection x = new Detector.Detection();
            x.en = d.en;
            x.srcMode = d.srcMode;
            x.score = d.score;
            x.x1 = d.x1;
            x.y1 = d.y1;
            x.x2 = d.x2;
            x.y2 = d.y2;
            out.add(x);
        }
        return out;
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id).put("mode", mode).put("threshold", threshold).put("image", image);
            JSONArray a = new JSONArray();
            for (Det d : dets) {
                a.put(new JSONObject()
                        .put("en", d.en)
                        .put("src", d.srcMode == null ? "" : d.srcMode)
                        .put("s", d.score)
                        .put("x1", d.x1).put("y1", d.y1).put("x2", d.x2).put("y2", d.y2));
            }
            o.put("dets", a);
            return o;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static RecordItem fromJson(JSONObject o) {
        RecordItem r = new RecordItem();
        r.id = o.optLong("id");
        r.mode = o.optString("mode", Detector.MODE_PEST);
        r.threshold = (float) o.optDouble("threshold", 0.35);
        r.image = o.optString("image", "");
        JSONArray a = o.optJSONArray("dets");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject d = a.optJSONObject(i);
                if (d == null) continue;
                Det det = new Det();
                det.en = d.optString("en");
                String src = d.optString("src", "");
                det.srcMode = src.isEmpty() ? null : src;
                det.score = (float) d.optDouble("s", 0);
                det.x1 = (float) d.optDouble("x1");
                det.y1 = (float) d.optDouble("y1");
                det.x2 = (float) d.optDouble("x2");
                det.y2 = (float) d.optDouble("y2");
                r.dets.add(det);
            }
        }
        return r;
    }
}
