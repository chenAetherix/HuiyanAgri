package com.agriunified.detection;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 历史识别记录本地存储：records.json（元数据）+ history/&lt;id&gt;.jpg（720px 原图缩略）。
 * 零第三方依赖（不引 Room：support 工程引 androidx 依赖链风险大，数据量小 JSON 足够）。
 * 所有方法应在后台线程调用（文件 IO）。
 */
public final class HistoryStore {

    private static final int MAX_KEEP = 200;      // 上限，超出丢最旧
    private static final int IMAGE_SIDE = 720;    // 原图缩略最长边（回看/复算可用）

    private final File dir;
    private final File jsonFile;

    public HistoryStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "history");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        jsonFile = new File(dir, "records.json");
    }

    /** 新增一条记录（图片为检测原图；detections 为本次全部检出）。返回新记录。 */
    public synchronized RecordItem add(String mode, float threshold, Bitmap origin,
                                       List<Detector.Detection> dets) {
        RecordItem r = new RecordItem();
        r.id = System.currentTimeMillis();
        r.mode = mode;
        r.threshold = threshold;
        r.image = r.id + ".jpg";
        for (Detector.Detection d : dets) {
            RecordItem.Det x = new RecordItem.Det();
            x.en = d.en;
            x.srcMode = d.srcMode;
            x.score = d.score;
            x.x1 = d.x1;
            x.y1 = d.y1;
            x.x2 = d.x2;
            x.y2 = d.y2;
            r.dets.add(x);
        }
        saveImage(new File(dir, r.image), origin);
        List<RecordItem> all = list();
        all.add(0, r);
        while (all.size() > MAX_KEEP) {
            RecordItem old = all.remove(all.size() - 1);
            //noinspection ResultOfMethodCallIgnored
            new File(dir, old.image).delete();
        }
        saveAll(all);
        return r;
    }

    /** 全部记录（按时间降序，最新在前）。文件缺失/损坏时返回空列表不抛异常。 */
    public synchronized List<RecordItem> list() {
        List<RecordItem> out = new ArrayList<>();
        try {
            if (jsonFile.exists()) {
                JSONArray a = new JSONArray(readText(jsonFile));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) out.add(RecordItem.fromJson(o));
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (a, b) -> Long.compare(b.id, a.id));
        return out;
    }

    /** 删除单条（含图片）。 */
    public synchronized void remove(long id) {
        List<RecordItem> all = list();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == id) {
                //noinspection ResultOfMethodCallIgnored
                new File(dir, all.get(i).image).delete();
                all.remove(i);
                break;
            }
        }
        saveAll(all);
    }

    /** 清空全部（含图片）。 */
    public synchronized void clear() {
        List<RecordItem> all = list();
        for (RecordItem r : all) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, r.image).delete();
        }
        //noinspection ResultOfMethodCallIgnored
        jsonFile.delete();
    }

    public File imageFile(RecordItem r) {
        return new File(dir, r.image);
    }

    private void saveImage(File target, Bitmap src) {
        try {
            Bitmap out = src;
            int max = Math.max(src.getWidth(), src.getHeight());
            if (max > IMAGE_SIDE) {
                float s = IMAGE_SIDE / (float) max;
                out = Bitmap.createScaledBitmap(src,
                        Math.round(src.getWidth() * s), Math.round(src.getHeight() * s), true);
            }
            FileOutputStream fos = new FileOutputStream(target);
            out.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            if (out != src) out.recycle();
        } catch (Exception ignored) {
        }
    }

    private void saveAll(List<RecordItem> all) {
        try {
            JSONArray a = new JSONArray();
            for (RecordItem r : all) a.put(r.toJson());
            FileOutputStream fos = new FileOutputStream(jsonFile);
            fos.write(a.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private static String readText(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
