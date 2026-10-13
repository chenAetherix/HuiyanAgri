package com.agriunified.detection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 农事问答 agent：调用智谱 GLM chat/completions（问答需联网；检测功能完全不依赖本类）。
 * 系统提示词与 PC 端 unified_demo/app.py 同构：26 类知识库 + 农户助手人设 + 最近检测结果。
 */
public final class ChatAgent {

    private ChatAgent() {}

    public static String systemPrompt() {
        StringBuilder kb = new StringBuilder();
        kb.append("【病虫害知识库（本系统可检测的 14 类）】\n");
        for (KnowledgeBase.PestInfo p : KnowledgeBase.KNOWLEDGE.values()) {
            kb.append("- ").append(p.zh).append("（").append(p.kind).append("）为害：")
                    .append(p.harm).append(" 防治：").append(joinTips(p.tips)).append('\n');
        }
        kb.append("【棉田杂草知识库（本系统可检测的 12 类）】\n");
        for (KnowledgeBase.PestInfo p : KnowledgeBase.WEED.values()) {
            kb.append("- ").append(p.zh).append("（").append(p.kind).append("）为害：")
                    .append(p.harm).append(" 防除：").append(joinTips(p.tips)).append('\n');
        }
        StringBuilder sb = new StringBuilder(
                "你是\"农田小助手\"，一位服务农户的农事问答助手，内置于农作物病虫害/杂草检测 App。\n"
                        + "回答要求：\n"
                        + "1. 语言通俗，像乡镇农技员那样讲给农户听，少用术语，多给可操作的做法"
                        + "（什么时间、怎么做、注意什么）；\n"
                        + "2. 涉及农药/除草剂时，提醒按推荐剂量使用、注意安全间隔期、交替用药防止抗性；\n"
                        + "3. 用户问到本系统可检测的病虫害或杂草时，优先依据下面的内置知识库回答；"
                        + "知识库之外的农事问题（施肥、轮作、播种等）凭农业专业知识简明回答；\n"
                        + "4. 回答控制在 300 字以内，分点列出；拿不准时如实说明并建议咨询当地农技部门，"
                        + "不要编造药名。\n\n");
        sb.append(kb);
        String note = MainActivity.lastDetectionNote;
        if (note != null && !note.isEmpty()) {
            sb.append("\n用户最近一次检测结果（用户问\"这个怎么治\"时即指这些目标）：").append(note);
        }
        return sb.toString();
    }

    private static String joinTips(String[] tips) {
        StringBuilder sb = new StringBuilder();
        for (String t : tips) {
            if (sb.length() > 0) sb.append('；');
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * 同步调用（必须在子线程）。messages 为 user/assistant 对话历史（不含 system，
     * 每次调用现拼以带上最新检测结果）；只携带最近 10 条防 token 超限。
     */
    public static String ask(JSONArray messages) throws Exception {
        if (ApiConfig.ZHIPU_API_KEY == null || ApiConfig.ZHIPU_API_KEY.trim().isEmpty()) {
            return "⚠️ 尚未配置智谱 API key（见 ApiConfig.ZHIPU_API_KEY），问答暂不可用；"
                    + "拍照检测与本地防治建议不受影响。";
        }
        JSONArray full = new JSONArray();
        full.put(new JSONObject().put("role", "system").put("content", systemPrompt()));
        for (int i = Math.max(0, messages.length() - 10); i < messages.length(); i++) {
            full.put(messages.get(i));
        }
        JSONObject body = new JSONObject();
        body.put("model", ApiConfig.ZHIPU_MODEL);
        body.put("messages", full);
        body.put("temperature", 0.6);
        body.put("max_tokens", 1024);
        body.put("stream", false);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ApiConfig.API_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + ApiConfig.ZHIPU_API_KEY);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder resp = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                resp.append(line);
            }
            reader.close();
            if (code < 200 || code >= 300) {
                String snippet = resp.substring(0, Math.min(200, resp.length()));
                throw new RuntimeException("HTTP " + code + " " + snippet);
            }
            // 智谱 content 有两种格式：纯字符串，或分段数组 [{"type":"text","text":"…"}]
            Object content = new JSONObject(resp.toString())
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").get("content");
            if (content instanceof String) {
                return ((String) content).trim();
            }
            if (content instanceof JSONArray) {
                StringBuilder sb = new StringBuilder();
                JSONArray arr = (JSONArray) content;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject seg = arr.optJSONObject(i);
                    if (seg != null) {
                        sb.append(seg.optString("text", ""));
                    }
                }
                return sb.toString().trim();
            }
            throw new RuntimeException("unexpected content type: " + content.getClass().getSimpleName());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
