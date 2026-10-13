package com.agriunified.detection;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    private static final int REQ_GALLERY = 1002;
    private static final int REQ_HISTORY = 1003;

    /** HistoryActivity 选中的待回填记录（跨页传递，进程内有效）。 */
    static volatile RecordItem pendingRestore = null;
    /** 待回填记录（切 Tab 等模型就绪后执行，优先于自动示例图）。 */
    private RecordItem restoreQueue = null;
    private static final String[] SAMPLES_PEST = {
            "examples/ip102__IP015000027.jpg",  // 蝼蛄（实测 0.75）
            "examples/022.jpg",                 // 玉米大斑病（实测 0.94）
            "examples/119B_Tom_Mold.jpg",       // 文件名带 Mold 但实测检出为番茄晚疫病 0.75（近缘混淆）
            "examples/05-069f12.jpg",           // 番茄壳针孢叶斑病（实测 0.93）
    };
    private static final String[] SAMPLES_WEED = {
            "examples/weed_1.jpg",              // 粟米草（实测 0.98）
            "examples/weed_2.jpg",              // 镰荚决明（实测 0.94）
            "examples/weed_3.jpg",              // 长芒苋（实测 0.97，另混生粟米草 0.37）
    };
    private static final String[] SAMPLES_BOTH;
    static {
        String[] both = new String[SAMPLES_PEST.length + SAMPLES_WEED.length];
        System.arraycopy(SAMPLES_PEST, 0, both, 0, SAMPLES_PEST.length);
        System.arraycopy(SAMPLES_WEED, 0, both, SAMPLES_PEST.length, SAMPLES_WEED.length);
        SAMPLES_BOTH = both;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 最近一次检测摘要（类别+最高分），供 ChatActivity 农事问答联动。 */
    public static volatile String lastDetectionNote = "";

    private String mode = Detector.MODE_PEST;
    private TabLayout tabMode;
    private int lastTabPos = 0;          // 检测中拒绝切 Tab 时回拨用
    private final Detector detPest = new Detector();
    private final Detector detWeed = new Detector();
    /** 当前活动引擎（综合模式下为主引擎=病虫，配合 detWeed 双跑）。 */
    private Detector detector = detPest;

    private ImageView imageView;
    private TextView imgPlaceholder;     // 预览卡无图占位文字（有图时隐藏）
    private TextView statusText;
    private TextView thresholdText;
    private SeekBar thresholdBar;
    private LinearLayout cardsContainer;
    private Button btnDetect;
    private HistoryStore historyStore;
    private LinearLayout historyRow;
    private TextView historyEmpty;

    private float threshold = 0.35f;
    private Bitmap currentBitmap;      // 原图（未画框），供调阈值后重检
    private Bitmap annotatedBitmap;    // 当前展示的画框图
    private Uri pendingCameraUri;
    private String pendingCameraPath;   // EXIF 旋转需文件路径版 API（minSdk 21 无 stream 构造）
    private int sampleIdx = 0;
    private volatile boolean detecting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle(R.string.app_name);

        imageView = findViewById(R.id.imageView);
        imgPlaceholder = findViewById(R.id.imgPlaceholder);
        statusText = findViewById(R.id.statusText);
        thresholdText = findViewById(R.id.thresholdText);
        thresholdBar = findViewById(R.id.thresholdBar);
        cardsContainer = findViewById(R.id.cardsContainer);
        btnDetect = findViewById(R.id.btnDetect);
        btnDetect.setEnabled(false);      // 置灰由 btn_detect_bg selector 呈现
        btnDetect.setOnClickListener(v -> runDetection());

        // AppBar 右侧头像 = 使用说明入口（引导长文收进弹窗，不占首页）
        findViewById(R.id.ibAvatar).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.help_title)
                        .setMessage(R.string.help_body)
                        .setPositiveButton("知道了", null)
                        .show());

        // 历史记录入口：AppBar 时钟图标 + 历史卡「查看更多」（跳完整列表页）
        historyStore = new HistoryStore(this);
        historyRow = findViewById(R.id.historyRow);
        historyEmpty = findViewById(R.id.historyEmpty);
        android.view.View.OnClickListener goHistory = v ->
                startActivityForResult(new Intent(this, HistoryActivity.class), REQ_HISTORY);
        findViewById(R.id.ibHistory).setOnClickListener(goHistory);
        findViewById(R.id.tvHistoryMore).setOnClickListener(goHistory);

        Button btnCamera = findViewById(R.id.btnCamera);
        Button btnGallery = findViewById(R.id.btnGallery);
        Button btnSample = findViewById(R.id.btnSample);
        btnCamera.setOnClickListener(v -> takePhoto());
        btnGallery.setOnClickListener(v -> pickFromGallery());
        btnSample.setOnClickListener(v -> loadSample());

        tabMode = findViewById(R.id.tabMode);
        tabMode.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switchMode(modeOfTab(tab.getPosition()));
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        findViewById(R.id.btnChat).setOnClickListener(
                v -> startActivity(new Intent(this, ChatActivity.class)));

        thresholdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // progress 0~12 -> 阈值 0.20~0.80，步进 0.05（与网页端演示口径一致）
                threshold = 0.20f + progress * 0.05f;
                thresholdText.setText(String.format(java.util.Locale.US, "%.2f", threshold));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 阈值变化不自动重检（避免长推理场景误触发），提示用户手动点「重新检测」
                if (currentBitmap != null && !detecting) {
                    statusText.setText(getString(R.string.status_threshold_changed, threshold));
                }
            }
        });

        // 模型初始化放后台（nb 23~30MB，拷贝+加载需数秒）
        worker.execute(() -> {
            boolean ok = ensureEngines();
            main.post(() -> {
                if (ok) {
                    statusText.setText(R.string.status_idle);
                    loadSample();   // 首次进入自动跑一张示例
                } else {
                    statusText.setText(R.string.status_no_model);
                    Toast.makeText(this, "模型加载失败，请查看日志", Toast.LENGTH_LONG).show();
                }
                refreshHistoryRow();   // 进入时展示已有历史
            });
        });
    }

    /** 初始化当前模式所需的引擎实例（后台线程调用）；所需引擎全部就绪返回 true。 */
    private boolean ensureEngines() {
        if (Detector.MODE_WEED.equals(mode)) {
            detector = detWeed;
            return detWeed.init(getApplicationContext(), Detector.MODE_WEED);
        }
        detector = detPest;
        boolean ok = detPest.init(getApplicationContext(), Detector.MODE_PEST);
        if (Detector.MODE_BOTH.equals(mode)) {
            ok = detWeed.init(getApplicationContext(), Detector.MODE_WEED) && ok;
        }
        return ok;
    }

    /** 当前模式所需引擎是否全部就绪。 */
    private boolean enginesReady() {
        if (Detector.MODE_BOTH.equals(mode)) {
            return detPest.isLoaded() && detWeed.isLoaded();
        }
        return detector.isLoaded();
    }

    /** Tab 位置 -> 检测模式常量 */
    private String modeOfTab(int pos) {
        if (pos == 1) return Detector.MODE_WEED;
        if (pos == 2) return Detector.MODE_BOTH;
        return Detector.MODE_PEST;
    }

    /** 模式常量 -> Tab 位置 */
    private int tabOfMode(String m) {
        if (Detector.MODE_WEED.equals(m)) return 1;
        if (Detector.MODE_BOTH.equals(m)) return 2;
        return 0;
    }

    /** 切换检测模式（病虫害 / 杂草 / 综合）：后台加载对应模型并重置界面状态。 */
    private void switchMode(String newMode) {
        if (detecting) {
            Toast.makeText(this, "检测进行中，请稍候再切换模式", Toast.LENGTH_SHORT).show();
            int back = lastTabPos;   // 视觉回拨到切换前的 Tab（selectTab 会再触发 listener，equals 保护直接返回）
            tabMode.getTabAt(back).select();
            return;
        }
        if (newMode.equals(mode)) {
            return;
        }
        mode = newMode;
        lastTabPos = tabOfMode(mode);
        sampleIdx = 0;
        currentBitmap = null;
        annotatedBitmap = null;
        imageView.setImageDrawable(null);
        imgPlaceholder.setVisibility(View.VISIBLE);   // 清图后恢复占位文字
        cardsContainer.removeAllViews();
        btnDetect.setEnabled(false);
        btnDetect.setText(R.string.btn_detect);
        String loading;
        if (Detector.MODE_WEED.equals(mode)) {
            loading = "正在加载杂草检测模型…";
        } else if (Detector.MODE_BOTH.equals(mode)) {
            loading = "正在加载双引擎模型（病虫害+杂草）…";
        } else {
            loading = "正在加载病虫害检测模型…";
        }
        statusText.setText(loading);
        worker.execute(() -> {
            boolean ok = ensureEngines();
            main.post(() -> {
                if (ok) {
                    statusText.setText(R.string.status_idle);
                    if (restoreQueue != null) {
                        doRestore(restoreQueue);   // 历史回填优先于自动示例图
                    } else {
                        loadSample();
                    }
                } else {
                    statusText.setText(R.string.status_no_model);
                    Toast.makeText(this, "模型加载失败，请查看日志", Toast.LENGTH_LONG).show();
                }
            });
        });
    }


    private void takePhoto() {
        File dir = new File(getCacheDir(), "camera");
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "无法创建缓存目录", Toast.LENGTH_SHORT).show();
            return;
        }
        File photoFile;
        try {
            photoFile = File.createTempFile("capture_", ".jpg", dir);
        } catch (IOException e) {
            Toast.makeText(this, "创建照片文件失败", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
        pendingCameraUri = uri;
        pendingCameraPath = photoFile.getAbsolutePath();
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        intent.setClipData(android.content.ClipData.newRawUri("photo", uri));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "未找到相机应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickFromGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择图片"), REQ_GALLERY);
        } catch (Exception e) {
            Toast.makeText(this, "未找到相册应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSample() {
        String[] samples;
        if (Detector.MODE_WEED.equals(mode)) {
            samples = SAMPLES_WEED;
        } else if (Detector.MODE_BOTH.equals(mode)) {
            samples = SAMPLES_BOTH;
        } else {
            samples = SAMPLES_PEST;
        }
        String path = samples[sampleIdx % samples.length];
        sampleIdx++;
        worker.execute(() -> {
            Bitmap bm = Utils.decodeAssetScaled(getApplicationContext(), path);
            if (bm == null) {
                main.post(() -> Toast.makeText(this, "示例图读取失败", Toast.LENGTH_SHORT).show());
                return;
            }
            main.post(() -> setCurrentBitmap(bm));
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HISTORY) {
            // 历史页选中了记录 → 回填（切 Tab + 恢复阈值 + 载图）
            if (resultCode == RESULT_OK && pendingRestore != null) {
                RecordItem r = pendingRestore;
                pendingRestore = null;
                restoreQueue = r;
                // 先恢复阈值（onProgressChanged 更新 threshold 显示）
                thresholdBar.setProgress(Math.round((r.threshold - 0.20f) / 0.05f));
                if (!r.mode.equals(mode)) {
                    switchMode(r.mode);   // 就绪后 restoreQueue 优先于示例图
                } else {
                    doRestore(r);
                }
            }
            refreshHistoryRow();
            return;
        }
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_CAMERA && pendingCameraUri != null) {
            final Uri uri = pendingCameraUri;
            final String path = pendingCameraPath;
            worker.execute(() -> {
                Bitmap bm = Utils.decodeUriScaled(this, uri);
                if (bm == null) {
                    main.post(() -> Toast.makeText(this, "照片读取失败", Toast.LENGTH_SHORT).show());
                    return;
                }
                Bitmap rotated = Utils.rotateByExifFile(path, bm);
                main.post(() -> setCurrentBitmap(rotated));
            });
        } else if (requestCode == REQ_GALLERY && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            worker.execute(() -> {
                // 相册图 EXIF 转正：先把原始字节落到临时文件（EXIF orientation 随字节保留；
                // 不能先解码再压缩——Bitmap.compress 写出的 JPEG 不带 orientation 标签），
                // 再按路径解码 + rotateByExifFile（框架 ExifInterface 无 stream 构造，走文件路径）。
                File tmp = null;
                try {
                    tmp = File.createTempFile("gal_", ".img", getCacheDir());
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                    Bitmap bm = Utils.decodeUriScaled(this, Uri.fromFile(tmp));
                    if (bm == null) {
                        main.post(() -> Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    Bitmap rotated = Utils.rotateByExifFile(tmp.getAbsolutePath(), bm);
                    if (rotated != bm) {
                        bm.recycle();
                    }
                    final Bitmap outBm = rotated;
                    main.post(() -> setCurrentBitmap(outBm));
                } catch (Exception e) {
                    main.post(() -> Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show());
                } finally {
                    if (tmp != null) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }
            });
        }
    }

    private void setCurrentBitmap(Bitmap bm) {
        if (bm == null) return;
        if (currentBitmap != null) currentBitmap.recycle();
        currentBitmap = bm;
        imageView.setImageBitmap(bm);
        imgPlaceholder.setVisibility(View.GONE);      // 有图隐藏占位
        cardsContainer.removeAllViews();
        // 图片就绪后不自动检测，等用户点「开始检测」
        btnDetect.setEnabled(true);
        btnDetect.setText(R.string.btn_detect);
        statusText.setText(R.string.status_ready);
    }

    private void runDetection() {
        // init 未就绪时优先走重试（即使还没加载图片，也能先修好模型）
        if (!enginesReady()) {
            // 首次 init 失败时，「开始检测」兼作重试入口（无需杀进程重开）
            btnDetect.setEnabled(false);
            statusText.setText(R.string.status_detecting);
            worker.execute(() -> {
                boolean ok = ensureEngines();
                main.post(() -> {
                    btnDetect.setEnabled(true);
                    if (ok) {
                        runDetection();
                    } else {
                        statusText.setText(R.string.status_no_model);
                        Toast.makeText(this, "模型加载失败，请查看日志", Toast.LENGTH_LONG).show();
                    }
                });
            });
            return;
        }
        final Bitmap bm = currentBitmap;
        if (bm == null || detecting) return;
        detecting = true;
        btnDetect.setEnabled(false);
        btnDetect.setText(R.string.btn_detecting);
        statusText.setText(R.string.status_detecting);
        final float thr = threshold;
        worker.execute(() -> {
            final Detector.Result result;
            if (Detector.MODE_BOTH.equals(mode)) {
                // 综合模式：两引擎先后推理，检出合并（srcMode 标记来源，卡片按引擎配色/知识库）
                Detector.Result r1 = detPest.detect(bm, thr);
                Detector.Result r2 = detWeed.detect(bm, thr);
                for (Detector.Detection d : r1.detections) d.srcMode = Detector.MODE_PEST;
                for (Detector.Detection d : r2.detections) d.srcMode = Detector.MODE_WEED;
                r1.detections.addAll(r2.detections);
                r1.inferenceMs += r2.inferenceMs;
                result = r1;
            } else {
                result = detector.detect(bm, thr);
            }
            final Bitmap annotated = Utils.drawDetections(bm, result.detections, mode);
            // 成功完成一次识别即存历史（原图 720 缩略 + 检出结构化数据，回看可复算）
            historyStore.add(mode, thr, bm, result.detections);
            main.post(() -> {
                detecting = false;
                btnDetect.setEnabled(true);
                btnDetect.setText(R.string.btn_redetect);
                if (annotatedBitmap != null) annotatedBitmap.recycle();
                annotatedBitmap = annotated;
                imageView.setImageBitmap(annotated);
                renderResult(result);
                refreshHistoryRow();
            });
        });
    }

    private void renderResult(Detector.Result result) {
        List<Detector.Detection> dets = result.detections;
        if (dets == null || dets.isEmpty()) {
            statusText.setText(R.string.status_none);
            cardsContainer.removeAllViews();
            lastDetectionNote = "未检出目标";
            return;
        }
        String time = String.format(java.util.Locale.US,
                "预处理 %.0f ms · 推理 %.0f ms · 后处理 %.0f ms · 阈值 %.2f",
                result.preprocessMs, result.inferenceMs, result.postprocessMs, threshold);
        statusText.setText("检出 " + dets.size() + " 个目标\n" + time);

        // 按类别去重（保留最高分）并统计每类检出数，生成建议卡（PC 端同款 ×N 口径）
        Map<String, Detector.Detection> best = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Detector.Detection d : dets) {
            Detector.Detection old = best.get(d.en);
            counts.put(d.en, (counts.containsKey(d.en) ? counts.get(d.en) : 0) + 1);
            if (old == null || d.score > old.score) best.put(d.en, d);
        }
        cardsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Detector.Detection d : best.values()) {
            addCard(inflater, d, counts.get(d.en));
        }

        // 检测摘要（类别取最高分）供农事问答联动（"这个怎么治"指这些目标）
        StringBuilder note = new StringBuilder();
        for (Detector.Detection d : best.values()) {
            KnowledgeBase.PestInfo info = KnowledgeBase.get(d.srcMode != null ? d.srcMode : mode, d.en);
            if (note.length() > 0) {
                note.append("、");
            }
            note.append(info.zh).append(" ")
                    .append(String.format(java.util.Locale.US, "%.2f", d.score));
        }
        lastDetectionNote = "检出：" + note;
    }

    @SuppressLint("InflateParams")
    private void addCard(LayoutInflater inflater, Detector.Detection d, int count) {
        KnowledgeBase.PestInfo info = KnowledgeBase.get(d.srcMode != null ? d.srcMode : mode, d.en);
        View card = inflater.inflate(R.layout.item_card, cardsContainer, false);

        View dot = card.findViewById(R.id.colorDot);
        GradientDrawable dotBg = (GradientDrawable) dot.getBackground();
        dotBg.setColor(info.color());

        TextView title = card.findViewById(R.id.cardTitle);
        title.setText(count > 1 ? String.format("%s ×%d", info.zh, count) : info.zh);

        TextView kind = card.findViewById(R.id.cardKind);
        kind.setText(info.kind);
        GradientDrawable pillBg = (GradientDrawable) kind.getBackground();
        pillBg.setColor(info.color());

        TextView score = card.findViewById(R.id.cardScore);
        // 置信徽章与网站端同口径：≥0.80 高置信 ✅（绿），以下 ⚠️（琥珀）——文字同步上色
        boolean hi = d.score >= 0.80f;
        String badge = hi ? "✅ 高置信 %.0f%%" : "⚠️ %.0f%%";
        score.setText(String.format(java.util.Locale.US, badge, d.score * 100));
        score.setTextColor(android.graphics.Color.parseColor(hi ? "#15803d" : "#b45309"));

        TextView harm = card.findViewById(R.id.cardHarm);
        harm.setText(String.format("为害特征：%s", info.harm));

        LinearLayout tipsContainer = card.findViewById(R.id.tipsContainer);
        for (String tip : info.tips) {
            TextView tv = new TextView(this);
            tv.setText(tip);
            tv.setTextSize(14);
            tv.setTextColor(0xFF1F2933);
            tv.setLineSpacing(3, 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 6;
            tipsContainer.addView(tv, lp);
        }
        cardsContainer.addView(card);
    }

    // ---------- 历史识别记录 ----------

    /** 后台读最近记录并刷新主页历史卡（最近 3 条横滑）。 */
    private void refreshHistoryRow() {
        worker.execute(() -> {
            final java.util.List<RecordItem> all = historyStore.list();
            final int limit = Math.min(all.size(), 3);
            final android.graphics.Bitmap[] thumbs = new android.graphics.Bitmap[limit];
            for (int i = 0; i < limit; i++) {
                File f = historyStore.imageFile(all.get(i));
                if (f.exists()) {
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory
                            .decodeFile(f.getAbsolutePath());
                    if (bm != null) {
                        thumbs[i] = android.graphics.Bitmap.createScaledBitmap(bm, 176, 176, true);
                        if (thumbs[i] != bm) bm.recycle();
                    }
                }
            }
            final java.util.List<RecordItem> top = new java.util.ArrayList<>(all.subList(0, limit));
            main.post(() -> renderHistoryRow(top, thumbs));
        });
    }

    private void renderHistoryRow(java.util.List<RecordItem> top, android.graphics.Bitmap[] thumbs) {
        historyRow.removeAllViews();
        boolean empty = top.isEmpty();
        historyEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA);
        for (int i = 0; i < top.size(); i++) {
            final RecordItem r = top.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(6), dp(6), dp(6), dp(6));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(android.graphics.Color.WHITE);
            bg.setCornerRadius(dp(8));
            bg.setStroke(1, android.graphics.Color.parseColor("#DDDDDD"));
            item.setBackground(bg);

            ImageView iv = new ImageView(this);
            if (thumbs[i] != null) iv.setImageBitmap(thumbs[i]);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(88), dp(88));
            item.addView(iv, ivLp);

            TextView label = new TextView(this);
            label.setText(fmt.format(new java.util.Date(r.id)) + " " + r.modeZh());
            label.setTextSize(10);
            label.setTextColor(android.graphics.Color.parseColor("#666666"));
            label.setMaxLines(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            item.addView(label, lp);

            item.setOnClickListener(v -> {
                restoreQueue = r;
                thresholdBar.setProgress(Math.round((r.threshold - 0.20f) / 0.05f));
                if (!r.mode.equals(mode)) {
                    switchMode(r.mode);
                } else {
                    doRestore(r);
                }
            });
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.rightMargin = dp(8);
            historyRow.addView(item, cardLp);
        }
    }

    /** 回填一条历史记录：还原当时的画框图与建议卡（结果已存档，无需重跑推理），
     *  阈值已先行恢复；想复算点「重新检测」即可。 */
    private void doRestore(RecordItem r) {
        restoreQueue = null;
        statusText.setText(R.string.status_detecting);
        worker.execute(() -> {
            File f = historyStore.imageFile(r);
            android.graphics.Bitmap bm = f.exists()
                    ? android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
            if (bm == null) {
                main.post(() -> {
                    statusText.setText("历史图片已丢失，无法回看");
                    loadSample();
                });
                return;
            }
            // 用存档检出重画当时的框，并构造 Result 走统一渲染（建议卡/统计/问答联动同款）
            final android.graphics.Bitmap annotated =
                    Utils.drawDetections(bm, r.toDetections(), r.mode);
            final Detector.Result result = new Detector.Result();
            result.detections.addAll(r.toDetections());
            result.preprocessMs = 0;
            result.inferenceMs = 0;
            result.postprocessMs = 0;
            main.post(() -> {
                setCurrentBitmap(bm);
                if (annotatedBitmap != null) annotatedBitmap.recycle();
                annotatedBitmap = annotated;
                imageView.setImageBitmap(annotated);
                renderResult(result);
                btnDetect.setText(R.string.btn_redetect);
                statusText.setText(new java.text.SimpleDateFormat("MM-dd HH:mm",
                        java.util.Locale.CHINA).format(new java.util.Date(r.id))
                        + " 的历史结果已还原（阈值 " + String.format(java.util.Locale.US, "%.2f", r.threshold)
                        + "），点「重新检测」可按当前阈值复算");
            });
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        worker.execute(() -> {
            detPest.release();
            detWeed.release();
        });
        worker.shutdown();
        if (currentBitmap != null) currentBitmap.recycle();
        if (annotatedBitmap != null) annotatedBitmap.recycle();
        super.onDestroy();
    }
}
