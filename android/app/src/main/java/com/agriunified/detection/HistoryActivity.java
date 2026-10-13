package com.agriunified.detection;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 历史识别记录（完整列表页）：
 * - 竖向卡片 = 缩略图 + 类型/结果/时间置信度 + 已完成标签 + 删除
 * - 按模式筛选（全部/病虫害/杂草/综合）；长按卡片保存画框结果图到相册；
 * - 点卡片回填到主页（pendingRestore 机制）；清空需二次确认。
 */
public class HistoryActivity extends AppCompatActivity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private HistoryStore store;
    private LinearLayout hisList;
    private LinearLayout hisFilterRow;
    private TextView hisEmpty;
    private ScrollView hisScroll;
    /** null = 全部；否则按 Detector.MODE_* 过滤 */
    private String filterMode = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        store = new HistoryStore(this);
        hisList = findViewById(R.id.hisList);
        hisFilterRow = findViewById(R.id.hisFilterRow);
        hisEmpty = findViewById(R.id.hisEmpty);
        hisScroll = (ScrollView) hisList.getParent();
        findViewById(R.id.hisBack).setOnClickListener(v -> finish());
        findViewById(R.id.hisClear).setOnClickListener(v -> confirmClear());
        buildFilterChips();
        reload();
    }

    /** 筛选 chips：全部 / 病虫害 / 杂草 / 综合（选中绿底白字，未选白底绿边）。 */
    private void buildFilterChips() {
        String[] names = {"全部", "病虫害", "杂草", "综合"};
        String[] modes = {null, Detector.MODE_PEST, Detector.MODE_WEED, Detector.MODE_BOTH};
        for (int i = 0; i < names.length; i++) {
            final String m = modes[i];
            TextView chip = new TextView(this);
            chip.setText(names[i]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), dp(6), dp(16), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            styleChip(chip, m == null ? filterMode == null : m.equals(filterMode));
            chip.setOnClickListener(v -> {
                filterMode = m;
                restyleChips();
                reload();
            });
            chip.setTag(m == null ? "ALL" : m);
            hisFilterRow.addView(chip);
        }
    }

    private void restyleChips() {
        for (int i = 0; i < hisFilterRow.getChildCount(); i++) {
            View c = hisFilterRow.getChildAt(i);
            Object tag = c.getTag();
            boolean sel = "ALL".equals(tag) ? filterMode == null : tag.equals(filterMode);
            styleChip((TextView) c, sel);
        }
    }

    private void styleChip(TextView chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (selected) {
            bg.setColor(Color.parseColor("#289b55"));
            chip.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.WHITE);
            bg.setStroke(1, Color.parseColor("#289b55"));
            chip.setTextColor(Color.parseColor("#289b55"));
        }
        chip.setBackground(bg);
    }

    private void reload() {
        worker.execute(() -> {
            List<RecordItem> all = store.list();
            if (filterMode != null) {
                List<RecordItem> f = new ArrayList<>();
                for (RecordItem r : all) {
                    if (filterMode.equals(r.mode)) f.add(r);
                }
                all = f;
            }
            final int limit = Math.min(all.size(), 50);
            final Bitmap[] thumbs = new Bitmap[limit];
            for (int i = 0; i < limit; i++) {
                File f = store.imageFile(all.get(i));
                if (f.exists()) {
                    Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (bm != null) {
                        thumbs[i] = Bitmap.createScaledBitmap(bm, 220, 220, true);
                        if (thumbs[i] != bm) bm.recycle();
                    }
                }
            }
            final List<RecordItem> top = new ArrayList<>(all.subList(0, limit));
            runOnUiThread(() -> render(top, thumbs));
        });
    }

    @SuppressLint("InflateParams")
    private void render(List<RecordItem> all, Bitmap[] thumbs) {
        hisList.removeAllViews();
        boolean empty = all.isEmpty();
        hisEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        hisScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;

        SimpleDateFormat dayFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (int i = 0; i < all.size(); i++) {
            final RecordItem r = all.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(12));
            card.setBackground(bg);
            card.setPadding(dp(12), dp(10), dp(6), dp(10));

            ImageView iv = new ImageView(this);
            Bitmap t = thumbs[i];
            if (t != null) iv.setImageBitmap(t);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(72), dp(72));
            card.addView(iv, ivLp);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoLp =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            infoLp.leftMargin = dp(12);
            card.addView(info, infoLp);

            // 第一行：模式 + 已完成标签（绿色小胶囊）
            LinearLayout line1 = new LinearLayout(this);
            line1.setOrientation(LinearLayout.HORIZONTAL);
            line1.setGravity(Gravity.CENTER_VERTICAL);
            TextView modeTv = new TextView(this);
            modeTv.setText(r.modeZh());
            modeTv.setTextSize(14);
            modeTv.setTextColor(Color.parseColor("#333333"));
            modeTv.setTypeface(null, android.graphics.Typeface.BOLD);
            line1.addView(modeTv);
            TextView doneTag = new TextView(this);
            doneTag.setText("已完成");
            doneTag.setTextSize(10);
            doneTag.setTextColor(Color.parseColor("#15803d"));
            GradientDrawable tagBg = new GradientDrawable();
            tagBg.setColor(Color.parseColor("#E8F5EE"));
            tagBg.setCornerRadius(dp(9));
            doneTag.setBackground(tagBg);
            doneTag.setPadding(dp(8), dp(2), dp(8), dp(2));
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tagLp.leftMargin = dp(8);
            line1.addView(doneTag, tagLp);
            info.addView(line1);

            String result = r.dets.isEmpty() ? "未检出目标"
                    : r.topZh() + String.format(Locale.CHINA, " %.0f%%", r.topScore() * 100)
                        + (r.dets.size() > 1 ? " 等" + r.dets.size() + " 处" : "");
            addLine(info, result, 13, Color.parseColor("#289b55"));
            addLine(info, dayFmt.format(new Date(r.id))
                    + String.format(Locale.CHINA, " · 阈值 %.2f", r.threshold), 12,
                    Color.parseColor("#666666"));

            TextView del = new TextView(this);
            del.setText("✕");
            del.setTextSize(16);
            del.setTextColor(Color.parseColor("#999999"));
            del.setPadding(dp(10), dp(10), dp(10), dp(10));
            del.setOnClickListener(v -> confirmDelete(r));
            card.addView(del);

            card.setOnClickListener(v -> {
                MainActivity.pendingRestore = r;
                setResult(RESULT_OK);
                finish();
            });
            // 长按：保存画框结果图到相册（MediaStore，API29+ 免权限；低版本兜底 app 目录）
            card.setOnLongClickListener(v -> {
                saveAnnotated(r);
                return true;
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            hisList.addView(card, lp);
        }
    }

    private void addLine(LinearLayout parent, String text, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        parent.addView(tv);
    }

    /** 后台重画当时结果（原图缩略 + 记录的检出框），存入系统相册。 */
    private void saveAnnotated(RecordItem r) {
        Toast.makeText(this, "正在生成结果图…", Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            File f = store.imageFile(r);
            Bitmap bm = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
            String where;
            if (bm == null) {
                where = null;
            } else {
                List<Detector.Detection> dets = new ArrayList<>();
                for (RecordItem.Det d : r.dets) {
                    Detector.Detection x = new Detector.Detection();
                    x.en = d.en;
                    x.srcMode = d.srcMode;
                    x.score = d.score;
                    x.x1 = d.x1;
                    x.y1 = d.y1;
                    x.x2 = d.x2;
                    x.y2 = d.y2;
                    dets.add(x);
                }
                Bitmap annotated = Utils.drawDetections(bm, dets, r.mode);
                where = saveToGallery(annotated, r.id);
                if (annotated != bm) annotated.recycle();
                bm.recycle();
            }
            final String path = where;
            runOnUiThread(() -> {
                if (path != null) {
                    Toast.makeText(this, "已保存结果图：" + path, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "保存失败：图片文件已丢失", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private String saveToGallery(Bitmap bmp, long id) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "慧眼识农_" + new SimpleDateFormat("MMdd_HHmm", Locale.CHINA).format(new Date(id)) + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HuiYanShiNong");
            }
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os != null) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
                    os.close();
                    return "相册/Pictures/HuiYanShiNong";
                }
            }
        } catch (Exception ignored) {
        }
        // 兜底：app 专属图片目录（免权限，可通过文件管理器访问）
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File out = new File(dir, "慧眼识农_" + id + ".jpg");
            FileOutputStream fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.close();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void confirmDelete(RecordItem r) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.history_delete_confirm)
                .setPositiveButton("删除", (d, w) -> worker.execute(() -> {
                    store.remove(r.id);
                    runOnUiThread(this::reload);
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.history_clear_confirm)
                .setPositiveButton("清空", (d, w) -> worker.execute(() -> {
                    store.clear();
                    runOnUiThread(this::reload);
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
