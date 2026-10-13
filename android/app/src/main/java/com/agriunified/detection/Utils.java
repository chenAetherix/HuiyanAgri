package com.agriunified.detection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/** 图片加载 / 模型拷贝 / 检测结果绘制工具 */
public final class Utils {

    /** 相机原图可能非常大，统一限制最长边，防止 OOM（检测输入只有 640） */
    public static final int MAX_SIDE = 1280;

    /** assets 单文件拷贝到目标文件 */
    public static void copyAssetToFile(Context ctx, String assetPath, File dst) throws Exception {
        InputStream is = ctx.getAssets().open(assetPath);
        OutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = is.read(buf)) > 0) {
            os.write(buf, 0, n);
        }
        os.close();
        is.close();
    }

    /** 从 assets 解码并按最长边降采样 */
    public static Bitmap decodeAssetScaled(Context ctx, String assetPath) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            InputStream is = ctx.getAssets().open(assetPath);
            BitmapFactory.decodeStream(is, null, opts);
            is.close();
            opts.inSampleSize = calcSample(opts.outWidth, opts.outHeight);
            opts.inJustDecodeBounds = false;
            is = ctx.getAssets().open(assetPath);
            Bitmap bm = BitmapFactory.decodeStream(is, null, opts);
            is.close();
            return limitSide(bm);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 content uri 解码并按最长边降采样 */
    public static Bitmap decodeUriScaled(Context ctx, Uri uri) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            InputStream is = ctx.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(is, null, opts);
            if (is != null) is.close();
            opts.inSampleSize = calcSample(opts.outWidth, opts.outHeight);
            opts.inJustDecodeBounds = false;
            is = ctx.getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(is, null, opts);
            if (is != null) is.close();
            return limitSide(bm);
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 EXIF 旋转（ExifInterface(InputStream) 需 API 24，本工程 minSdk 21 故只提供文件路径版） */
    public static Bitmap rotateByExifFile(String path, Bitmap bm) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: degrees = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                default: return bm;
            }
            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (rotated != bm) bm.recycle();
            return rotated;
        } catch (Exception e) {
            return bm;
        }
    }

    private static int calcSample(int w, int h) {
        int sample = 1;
        int max = Math.max(w, h);
        while (max / (sample * 2) >= MAX_SIDE) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap limitSide(Bitmap bm) {
        if (bm == null) return null;
        int max = Math.max(bm.getWidth(), bm.getHeight());
        if (max <= MAX_SIDE) return bm;
        float scale = MAX_SIDE / (float) max;
        Bitmap scaled = Bitmap.createScaledBitmap(bm,
                Math.round(bm.getWidth() * scale),
                Math.round(bm.getHeight() * scale), true);
        if (scaled != bm) bm.recycle();
        return scaled;
    }

    /**
     * 在原图副本上绘制检测框与标签（框坐标是原图尺度，与 bm 同尺度直接用）。
     * 返回新 Bitmap（原图保持不动，供重复检测）。
     */
    public static Bitmap drawDetections(Bitmap src, List<Detector.Detection> detections, String mode) {
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        if (detections == null || detections.isEmpty()) return out;
        Canvas canvas = new Canvas(out);
        int side = Math.max(out.getWidth(), out.getHeight());
        float stroke = Math.max(3f, side / 320f);
        float textSize = Math.max(22f, side / 26f);
        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(stroke);
        Paint textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setFakeBoldText(true);
        float pad = textSize * 0.25f;

        for (Detector.Detection d : detections) {
            KnowledgeBase.PestInfo info = KnowledgeBase.get(
                    d.srcMode != null ? d.srcMode : mode, d.en);
            int color = info.color();
            // 框裁剪到图内（与官方 Pipeline.cc 的 & cv::Rect(0,0,w-1,h-1) 对齐，防坐标越界画到图外）
            RectF rect = new RectF(
                    Math.max(0, d.x1), Math.max(0, d.y1),
                    Math.min(out.getWidth() - 1, d.x2), Math.min(out.getHeight() - 1, d.y2));
            boxPaint.setColor(color);
            canvas.drawRect(rect, boxPaint);

            String label = String.format(Locale.CHINA, "%s %.2f", info.zh, d.score);
            float tw = textPaint.measureText(label);
            float baseY = rect.top - textSize - pad * 2;
            if (baseY < 0) baseY = rect.top;                 // 框外放不下时贴框内
            textBgPaint.setColor(color);
            canvas.drawRect(rect.left, baseY, rect.left + tw + pad * 2, baseY + textSize + pad * 2, textBgPaint);
            canvas.drawText(label, rect.left + pad, baseY + textSize + pad * 0.7f, textPaint);
        }
        return out;
    }

    private Utils() {
    }
}
