package com.agriunified.detection;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 农事问答对话历史持久化：filesDir/chat/chat_history.json
 * 只存成功完成的问答对（用户问 + 助手答，成对原子追加）；纯文本，200 条封顶。
 */
public class ChatStore {

    private static final int MAX_MSGS = 200;
    private final File jsonFile;

    public ChatStore(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "chat");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        jsonFile = new File(dir, "chat_history.json");
    }

    /** 全部历史消息 [{role, content}...]，时间正序；损坏返回空列表。 */
    public synchronized List<String[]> load() {
        List<String[]> out = new ArrayList<>();
        try {
            if (jsonFile.exists()) {
                JSONArray a = new JSONArray(readText());
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) {
                        out.add(new String[]{o.optString("role"), o.optString("content")});
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 成对追加一轮对话（后台线程调用）。 */
    public synchronized void append(String user, String assistant) {
        try {
            JSONArray a = jsonFile.exists() ? new JSONArray(readText()) : new JSONArray();
            a.put(new JSONObject().put("role", "user").put("content", user));
            a.put(new JSONObject().put("role", "assistant").put("content", assistant));
            while (a.length() > MAX_MSGS) {
                a.remove(0);
            }
            FileOutputStream fos = new FileOutputStream(jsonFile);
            fos.write(a.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    /** 清空全部对话。 */
    public synchronized void clear() {
        //noinspection ResultOfMethodCallIgnored
        jsonFile.delete();
    }

    private String readText() throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(jsonFile);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
