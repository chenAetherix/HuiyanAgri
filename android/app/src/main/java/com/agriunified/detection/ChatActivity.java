package com.agriunified.detection;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 农事问答聊天页：ScrollView + 动态 TextView 气泡（零新增依赖，保住 --offline 构建）。
 * 对话历史存内存（JSONArray），最多携带最近 10 条请求模型（ChatAgent 内截断）。
 */
public class ChatActivity extends AppCompatActivity {

    private final JSONArray history = new JSONArray();
    private ChatStore store;   // onCreate 里初始化（构造期 getFilesDir() 为 null）
    private LinearLayout chatList;
    private ScrollView chatScroll;
    private EditText input;
    private View sendBtn;
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        chatList = findViewById(R.id.chatList);
        chatScroll = findViewById(R.id.chatScroll);
        input = findViewById(R.id.chatInput);
        sendBtn = findViewById(R.id.btnSend);
        findViewById(R.id.chatBack).setOnClickListener(v -> finish());
        store = new ChatStore(this);
        findViewById(R.id.chatClear).setOnClickListener(v -> confirmClear());
        restoreHistory();
        sendBtn.setOnClickListener(v -> send());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });
        addBubble(false, "你好，我是**农田小助手** 🌾\n\n可以问我：\n\n"
                + "- **防治方法**：本系统 26 类病虫害 / 杂草怎么治\n"
                + "- **农事问题**：施肥、轮作、用药时机等\n"
                + "- **检测联动**：刚检测过的图片直接问\"这个怎么治\"，我会结合结果回答\n");
    }

    /** 恢复上次对话（气泡渲染 + 模型上下文连续，隔天可接着聊）。 */
    private void restoreHistory() {
        for (String[] m : store.load()) {
            boolean user = "user".equals(m[0]);
            addBubble(user, m[1]);
            history.put(msg(m[0], m[1]));
        }
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 清空对话（二次确认）：存档、上下文、气泡全部重置。 */
    private void confirmClear() {
        new android.support.v7.app.AlertDialog.Builder(this)
                .setMessage("确定清空所有对话记录？此操作不可恢复")
                .setPositiveButton("清空", (d, w) -> {
                    store.clear();
                    while (history.length() > 0) {
                        history.remove(0);
                    }
                    chatList.removeAllViews();
                    addBubble(false, "你好，我是**农田小助手** 🌾\n\n可以问我：\n\n"
                            + "- **防治方法**：本系统 26 类病虫害 / 杂草怎么治\n"
                            + "- **农事问题**：施肥、轮作、用药时机等\n"
                            + "- **检测联动**：刚检测过的图片直接问\"这个怎么治\"，我会结合结果回答\n");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || busy) {
            return;
        }
        busy = true;
        sendBtn.setEnabled(false);
        input.setText("");
        addBubble(true, text);
        history.put(msg("user", text));
        new Thread(() -> {
            String reply;
            boolean ok = true;
            try {
                reply = ChatAgent.ask(history);
            } catch (Exception e) {
                ok = false;
                reply = "⚠️ 问答服务暂时不可用（" + e.getClass().getSimpleName()
                        + "），请稍后再试；检测功能不受影响。";
            }
            final String r = reply;
            history.put(msg("assistant", r));
            if (ok) {
                store.append(text, r);   // 只落盘成功完成的问答对
            }
            runOnUiThread(() -> {
                addBubble(false, r);
                busy = false;
                sendBtn.setEnabled(true);
            });
        }).start();
    }

    private static JSONObject msg(String role, String content) {
        try {
            return new JSONObject().put("role", role).put("content", content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 追加气泡：用户右侧绿底白字（纯文本），助手左侧白底深字（Markdown 渲染）。 */
    private void addBubble(boolean user, String text) {
        TextView tv = new TextView(this);
        // 助手回复按 Markdown 渲染（标题/粗体/列表/代码），用户输入按纯文本
        tv.setText(user ? text : MarkdownSpan.render(text));
        tv.setTextSize(14.5f);
        tv.setLineSpacing(dp(3), 1f);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        int padH = dp(14), padV = dp(10);
        if (user) {
            tv.setTextColor(Color.WHITE);
            bg.setColor(Color.parseColor("#15803d"));
            bg.setStroke(1, Color.parseColor("#166534"));
        } else {
            tv.setTextColor(Color.parseColor("#1F2933"));
            bg.setColor(Color.WHITE);
            bg.setStroke(1, Color.parseColor("#E2E8F0"));
        }
        tv.setBackground(bg);
        tv.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.gravity = user ? Gravity.END : Gravity.START;
        int max = chatList.getWidth() - dp(24);
        if (max > 0) {
            tv.setMaxWidth(max * 4 / 5);
        }
        chatList.addView(tv, lp);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
