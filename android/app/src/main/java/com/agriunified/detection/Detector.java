package com.agriunified.detection;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.baidu.paddle.lite.MobileConfig;
import com.baidu.paddle.lite.PaddlePredictor;
import com.baidu.paddle.lite.PowerMode;
import com.baidu.paddle.lite.Tensor;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * PicoDet-L-640 农业病虫害检测器（Paddle-Lite Java API）。
 *
 * 模型（由 PaddleDetection 导出 + Paddle-Lite opt 转 nb）：
 *  - 输入 image        [1,3,640,640]，预处理=直接拉伸 resize（keep_ratio=false）
 *    + ImageNet 归一化（mean 0.485/0.456/0.406，std 0.229/0.224/0.225，RGB 顺序）
 *  - 输入 scale_factor [1,2] = [640/origH, 640/origW]（"缩放后/原图"；模型图内
 *    pred_bboxes /= scale_factor 把坐标还原到原图尺度）
 *  - 输出 [N,6] = [class_id, score, x1, y1, x2, y2]（原图坐标） + [N]（bbox_num）
 *  - 图内 multiclass_nms3 实际属性（从导出模型读取）：score_threshold=0.025、nms_threshold=0.6、
 *    keep_top_k=100——注意与 infer_cfg.yml 里的 NMS 段（0.3/0.5）并不一致，UI 阈值过滤才是最终闸门
 *
 * 输入索引按实测固定绑定：image=0、scale_factor=1（未 resize 前 shape() 返回空数组，
 * 无法自适应识别；jar 的 getTensor(String name) 是按名取的备选方案）。
 */
public class Detector {

    private static final String TAG = "AgriPestDetector";

    public static class Detection {
        public int clsId;
        public String en;
        public float score;
        public float x1, y1, x2, y2;   // 原图像素坐标
        public String srcMode;          // 综合模式下标记来源引擎（pest/weed），单模式为 null
    }

    public static class Result {
        public List<Detection> detections = new ArrayList<>();
        public float preprocessMs;
        public float inferenceMs;
        public float postprocessMs;
    }

    public static final String MODE_PEST = "pest";
    public static final String MODE_WEED = "weed";
    public static final String MODE_BOTH = "both";
    private static final String MODEL_ASSET_DIR = "models";
    private static final String[] MODEL_FILES = {"pest_640.nb", "weed_640.nb"};
    private static final String[] LABEL_ASSETS = {"labels/labels_pest.txt", "labels/labels_weed.txt"};
    private static final int INPUT_SIZE = 640;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private PaddlePredictor predictor = null;
    private String curMode = null;
    private final List<String> labelEn = new ArrayList<>();
    private int imageInputIdx = -1;
    private int sfInputIdx = -1;

    public String getMode() {
        return curMode;
    }

    public boolean isLoaded() {
        return predictor != null;
    }

    /** 初始化指定模式的模型；模式变化时自动重载。 */
    public synchronized boolean init(Context appCtx, String mode) {
        if (predictor != null && mode.equals(curMode)) return true;
        predictor = null;
        curMode = mode;
        int idx = MODE_WEED.equals(mode) ? 1 : 0;
        String modelFile = MODEL_FILES[idx];
        String labelAsset = LABEL_ASSETS[idx];
        try {
            // assets -> cacheDir（Paddle-Lite 需从文件路径加载）
            File modelDir = new File(appCtx.getCacheDir(), MODEL_ASSET_DIR);
            if (!modelDir.exists() && !modelDir.mkdirs()) {
                Log.e(TAG, "mkdir failed: " + modelDir);
                return false;
            }
            File mf = new File(modelDir, modelFile);
            // 缓存与 assets 长度不一致时重新拷贝（升级模型 adb install -r 不清数据，防旧 nb 残留）
            boolean needCopy = !mf.exists();
            long assetLen = -1;
            if (!needCopy) {
                InputStream probe = appCtx.getAssets().open(MODEL_ASSET_DIR + "/" + modelFile);
                assetLen = probe.available();
                probe.close();
                if (assetLen > 0) {
                    needCopy = mf.length() != assetLen;
                }
            }
            if (needCopy) {
                Utils.copyAssetToFile(appCtx, MODEL_ASSET_DIR + "/" + modelFile, mf);
                if (assetLen > 0 && mf.length() != assetLen) {
                    Log.e(TAG, "model copy incomplete: " + mf.length() + " != " + assetLen);
                    return false;
                }
            }

            MobileConfig config = new MobileConfig();
            config.setModelFromFile(mf.getAbsolutePath());
            config.setThreads(4);
            config.setPowerMode(PowerMode.LITE_POWER_HIGH);
            predictor = PaddlePredictor.createPaddlePredictor(config);

            loadLabels(appCtx, labelAsset);
            bindInputIdx();
            Log.i(TAG, "model loaded [" + mode + "]: " + mf.getAbsolutePath()
                    + ", labels=" + labelEn.size());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
            predictor = null;
            return false;
        }
    }

    private void loadLabels(Context appCtx, String labelAsset) throws Exception {
        labelEn.clear();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                appCtx.getAssets().open(labelAsset), "utf-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int sp = line.indexOf(' ');
            // 行格式 "en_name 中文名"，取英文 key（与 KnowledgeBase 对齐）
            labelEn.add(sp > 0 ? line.substring(0, sp) : line);
        }
        reader.close();
    }

    /**
     * 按 shape 识别 image / scale_factor 输入索引。
     * 注意：本模型（PicoDet 导出+opt 转换）固定 2 个输入，上限即 2——
     * Paddle-Lite 的 getInput() 越界会在 native 层 SIGABRT（Java 无法捕获），绝不能试探越界索引。
     */
    private void bindInputIdx() {
        imageInputIdx = -1;
        sfInputIdx = -1;
        for (int i = 0; i < 2; i++) {
            Tensor t;
            try {
                t = predictor.getInput(i);
            } catch (Throwable e) {
                break;
            }
            if (t == null) break;
            long[] shape;
            try {
                shape = t.shape();
            } catch (Throwable e) {
                continue;
            }
            long len = 1;
            for (long s : shape) len *= s;
            Log.i(TAG, "probe input[" + i + "] shape=" + java.util.Arrays.toString(shape));
            if (shape.length == 4 && shape[1] == 3) {
                imageInputIdx = i;
            } else if (shape.length == 2 && len == 2) {
                sfInputIdx = i;
            }
            if (imageInputIdx >= 0 && sfInputIdx >= 0) break;
        }
        // shape 未定时（实测 shape() 在未 resize 前返回空数组）按固定顺序 fallback。
        // 顺序由实测确定（喂反会在 run() 时 CheckShape abort）：image=0、scale_factor=1
        if (imageInputIdx < 0 || sfInputIdx < 0) {
            Log.i(TAG, "shape probe undetermined, fallback: image=0 scale_factor=1");
            imageInputIdx = 0;
            sfInputIdx = 1;
        }
        Log.i(TAG, "input tensors: image=" + imageInputIdx + " scale_factor=" + sfInputIdx);
    }

    /**
     * 检测一张图（bitmap 尺寸任意；调用方负责回收临时 bitmap）。
     * 返回过滤阈值后的检出列表与耗时统计。
     */
    public synchronized Result detect(Bitmap bitmap, float threshold) {
        Result result = new Result();
        if (predictor == null || bitmap == null) return result;

        final int origW = bitmap.getWidth();
        final int origH = bitmap.getHeight();
        long t0 = System.currentTimeMillis();

        // ---- 预处理：直接拉伸到 640x640（与训练导出配置 keep_ratio=false 一致）----
        Bitmap scaled;
        if (origW == INPUT_SIZE && origH == INPUT_SIZE) {
            scaled = bitmap;
        } else {
            scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        }
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        final int plane = INPUT_SIZE * INPUT_SIZE;
        float[] inputData = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xff) / 255.0f;
            float g = ((p >> 8) & 0xff) / 255.0f;
            float b = (p & 0xff) / 255.0f;
            inputData[i] = (r - MEAN[0]) / STD[0];
            inputData[plane + i] = (g - MEAN[1]) / STD[1];
            inputData[2 * plane + i] = (b - MEAN[2]) / STD[2];
        }
        if (scaled != bitmap) scaled.recycle();

        try {
            // ---- 喂输入 ----
            Tensor imageTensor = predictor.getInput(Math.max(imageInputIdx, 0));
            imageTensor.resize(new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});
            imageTensor.setData(inputData);
            if (sfInputIdx >= 0) {
                Tensor sfTensor = predictor.getInput(sfInputIdx);
                sfTensor.resize(new long[]{1, 2});
                // 模型图内 pred_bboxes /= scale_factor（pico_head.py），故必须喂"缩放后/原图"：
                // [640/origH, 640/origW]，与官方 Pipeline.cc（inputHeight/rows）一致
                sfTensor.setData(new float[]{INPUT_SIZE / (float) origH, INPUT_SIZE / (float) origW});
            }
        } catch (Throwable t) {
            Log.e(TAG, "set input failed", t);
            return result;
        }
        long t1 = System.currentTimeMillis();
        result.preprocessMs = t1 - t0;

        // ---- 推理 ----
        try {
            predictor.run();
        } catch (Throwable t) {
            Log.e(TAG, "run failed", t);
            return result;
        }
        long t2 = System.currentTimeMillis();
        result.inferenceMs = t2 - t1;

        // ---- 后处理：解析 [N,6] 输出（坐标已是原图尺度；模型固定 2 个输出，越界会 native abort，不探测） ----
        try {
            float[] boxes = null;
            for (int i = 0; i < 2; i++) {
                Tensor out;
                try {
                    out = predictor.getOutput(i);
                } catch (Throwable e) {
                    break;
                }
                if (out == null) break;
                float[] data = out.getFloatData();
                if (data == null || data.length <= 1 || data.length % 6 != 0) continue; // bbox_num 等
                if (boxes == null || data.length > boxes.length) boxes = data;
            }
            if (boxes != null) {
                for (int i = 0; i + 5 < boxes.length; i += 6) {
                    int clsId = (int) boxes[i];
                    float score = boxes[i + 1];
                    if (score < threshold) continue;
                    Detection d = new Detection();
                    d.clsId = clsId;
                    d.en = (clsId >= 0 && clsId < labelEn.size()) ? labelEn.get(clsId) : String.valueOf(clsId);
                    d.score = score;
                    d.x1 = boxes[i + 2];
                    d.y1 = boxes[i + 3];
                    d.x2 = boxes[i + 4];
                    d.y2 = boxes[i + 5];
                    result.detections.add(d);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "postprocess failed", t);
        }
        long t3 = System.currentTimeMillis();
        result.postprocessMs = t3 - t2;
        return result;
    }

    public synchronized void release() {
        predictor = null;
        labelEn.clear();
    }
}
