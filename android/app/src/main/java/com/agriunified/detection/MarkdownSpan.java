package com.agriunified.detection;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown → Spannable 渲染（零依赖，保住离线构建）。
 * 覆盖农事问答 GLM 回复的常见语法：标题 / **粗体** / `行内代码` / 代码块 /
 * 无序与有序列表 / 链接 / 分隔线；表格按原样文本输出（可读的降级）。
 */
public final class MarkdownSpan {

    /** 行内元素：粗体 / 行内代码 / [文字](链接)，一遍扫描防嵌套错位 */
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|`([^`\\n]+)`|\\[([^\\]\\n]+)]\\((https?://[^)\\s]+)\\)");

    private static final int CODE_BG = Color.parseColor("#EEF2F4");
    private static final int HEAD_COLOR = Color.parseColor("#289b55");
    private static final int LINK_COLOR = Color.parseColor("#2563eb");

    public static CharSequence render(String md) {
        if (md == null) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        boolean inCode = false;
        List<String> codeBuf = new ArrayList<>();
        boolean firstLine = true;

        for (String rawLine : md.split("\n", -1)) {
            String line = rawLine.replace("\r", "");
            String trim = line.trim();

            if (trim.startsWith("```")) {
                if (inCode) {
                    appendCodeBlock(sb, codeBuf);
                    codeBuf.clear();
                    inCode = false;
                } else {
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                codeBuf.add(line);
                continue;
            }

            if (!firstLine && sb.length() > 0) {
                sb.append('\n');
            }
            firstLine = false;

            if (trim.isEmpty()) {
                sb.append('\n');   // 空行→段落间距
                continue;
            }
            if (trim.matches("^([-*_])\\1{2,}$")) {
                sb.append("────────────");
                continue;
            }
            Matcher h = Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(trim);
            if (h.find()) {
                int level = h.group(1).length();
                String text = h.group(2).replace("**", "");
                int start = sb.length();
                sb.append(text);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(level <= 1 ? 1.25f : level == 2 ? 1.15f : 1.08f),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(HEAD_COLOR),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (trim.matches("^[-*•]\\s+.*")) {
                sb.append("•  ");
                appendInline(sb, trim.replaceFirst("^[-*•]\\s+", ""));
                continue;
            }
            if (trim.matches("^\\d{1,2}[.、)]\\s+.*")) {
                appendInline(sb, trim);   // 有序编号原样保留
                continue;
            }
            if (trim.startsWith("|")) {
                sb.append(trim);          // 表格行原样（等宽降级为可读文本）
                continue;
            }
            appendInline(sb, trim);
        }
        if (inCode && !codeBuf.isEmpty()) {
            appendCodeBlock(sb, codeBuf);   // 未闭合代码块兜底
        }
        return sb;
    }

    private static void appendCodeBlock(SpannableStringBuilder sb, List<String> lines) {
        if (sb.length() > 0) sb.append('\n');
        int start = sb.length();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        int end = sb.length();
        sb.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new BackgroundColorSpan(CODE_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendInline(SpannableStringBuilder sb, String text) {
        Matcher m = INLINE.matcher(text);
        int copied = 0;
        while (m.find()) {
            if (m.start() > copied) sb.append(text, copied, m.start());
            int start = sb.length();
            if (m.group(1) != null) {                       // **粗体**
                sb.append(m.group(1));
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null) {                // `行内代码`
                sb.append(m.group(2));
                sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new BackgroundColorSpan(CODE_BG), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {                                        // [文字](链接)
                sb.append(m.group(3));
                sb.setSpan(new URLSpan(m.group(4)), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(LINK_COLOR), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            copied = m.end();
        }
        if (copied < text.length()) sb.append(text, copied, text.length());
    }

    private MarkdownSpan() {
    }
}
