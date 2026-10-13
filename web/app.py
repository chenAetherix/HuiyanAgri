# -*- coding: utf-8 -*-
"""农田有害生物一体化检测系统（病虫 14 类 + 杂草 12 类，双引擎 + 农事问答）。

- 引擎 1 病虫害：PP-YOLOE-S-640（7.93M，val 部署口径 0.427/0.648），14 类
- 引擎 2 杂草：PP-PicoDet-L-640（5.80M，test mAP50 0.9646），12 类棉田杂草
- 农事问答 Tab：智谱 GLM（需联网+API key，见环境变量 ZHIPU_API_KEY），内置 26 类知识库+检测联动
两个模型独立训练、独立评测，本系统作为统一演示载体；检测全程离线，问答为在线增值服务。
启动：python app.py，浏览器开 http://127.0.0.1:7862
模型文件不入库：从 Releases 下载后放入 ./model_pest 与 ./model_weed（见 README）。
"""
import os
import sys
import threading
from pathlib import Path

import gradio as gr
import numpy as np
import requests
from PIL import Image, ImageDraw, ImageFont

_BASE = Path(__file__).resolve().parent
DEPLOY_PY = str(_BASE.parent / "PaddleDetection" / "deploy" / "python")
sys.path.insert(0, DEPLOY_PY)
sys.path.insert(0, str(_BASE))
from infer import Detector  # noqa: E402
from knowledge_pest import get_info as pest_info  # noqa: E402
from knowledge_weed import get_info as weed_info  # noqa: E402

DEVICE = "CPU"
MAX_SIDE = 1280
_INFER_LOCK = threading.Lock()

# ---------------- 农事问答（智谱 GLM）----------------
# key 留空时问答 Tab 显示配置提示，不影响三个检测 Tab；也可用环境变量 ZHIPU_API_KEY
ZHIPU_API_KEY = os.environ.get("ZHIPU_API_KEY", "")
ZHIPU_MODEL = "glm-4-flash"  # 免费档；不可用时可改为 glm-4-air / glm-4.6

# 最近一次检测结果摘要（["番茄晚疫病 0.75（病害）", ...]），供问答联动
LAST_DETECTION = []

# ---------------- 双引擎配置 ----------------
ENGINES = {
    "pest": {
        "dir": str(_BASE / "model_pest"),
        "info": pest_info,
        # kind → RGB：虫害红 / 地下害虫橙棕 / 病害蓝 / 健康绿（与安卓端 KnowledgeBase 同色值）
        "colors": {"虫害": (224, 93, 67), "地下": (194, 98, 43),
                   "病害": (59, 130, 196), "健康": (47, 158, 99)},
        "color_of": None,  # 下方统一填
        "title": "病虫害检测", "n_cls": 14,
        "legend": "虫害（红）· 地下害虫（橙棕）· 病害（蓝）· 健康（绿）",
    },
    "weed": {
        "dir": str(_BASE / "model_weed"),
        "info": weed_info,
        # 阔叶杂草橙红 / 禾本科杂草金棕
        "colors": {"阔叶杂草": (217, 104, 49), "禾本科杂草": (176, 137, 50)},
        "color_of": None,
        "title": "杂草检测", "n_cls": 12,
        "legend": "阔叶杂草（橙红）· 禾本科杂草（金棕）",
    },
}


def _color_of_pest(kind):
    if "地下" in kind:
        return ENGINES["pest"]["colors"]["地下"]
    if "虫" in kind:
        return ENGINES["pest"]["colors"]["虫害"]
    if "病" in kind:
        return ENGINES["pest"]["colors"]["病害"]
    return ENGINES["pest"]["colors"]["健康"]


def _color_of_weed(kind):
    return ENGINES["weed"]["colors"].get(kind, (71, 85, 105))


ENGINES["pest"]["color_of"] = _color_of_pest
ENGINES["weed"]["color_of"] = _color_of_weed

_load_lock = threading.Lock()


def _get_detector(key):
    eng = ENGINES[key]
    if eng.get("det") is None:
        with _load_lock:
            if eng.get("det") is None:
                print("正在加载 %s 模型：%s" % (key, eng["dir"]))
                eng["det"] = Detector(model_dir=eng["dir"], device=DEVICE,
                                      run_mode="paddle", threshold=0.05,
                                      output_dir="output", cpu_threads=4,
                                      enable_mkldnn=True)
                eng["labels"] = eng["det"].pred_config.labels
    return eng["det"], eng["labels"]


FONT = None
for _f in ("msyh.ttc", "msyh.ttf", "simhei.ttf"):
    _p = "C:/Windows/Fonts/" + _f
    if os.path.exists(_p):
        FONT = ImageFont.truetype(_p, 18)
        break
if FONT is None:
    FONT = ImageFont.load_default()

TAG_COLOR = {"物理": "#0e7490", "生物": "#15803d", "化学": "#b91c1c", "农业": "#a16207",
             "监测": "#6d28d9", "管理": "#475569", "环境": "#0369a1", "农事": "#713f12"}

PLACEHOLDER_HTML = '<div style="padding:28px;text-align:center;color:#94a3b8;">上传或选择示例图，点击「开始检测」</div>'


def _tip_html(tip):
    import re
    m = re.match(r"^【(.+?)】(.*)$", tip)
    if not m:
        return "<li>%s</li>" % tip
    tag, text = m.group(1), m.group(2)
    color = TAG_COLOR.get(tag, "#475569")
    return ('<li><span style="display:inline-block;background:%s1a;color:%s;'
            'border:1px solid %s55;border-radius:999px;padding:1px 9px;'
            'font-size:12px;font-weight:600;margin-right:7px;white-space:nowrap">'
            '%s</span>%s</li>' % (color, color, color, tag, text))


def detect(image, threshold, key):
    eng = ENGINES[key]
    det, labels = _get_detector(key)
    if image is None:
        gr.Warning("请先上传或从示例区选择一张图片")
        return None, PLACEHOLDER_HTML
    with _INFER_LOCK:
        boxes = det.predict_image([image], visual=False).get("boxes")
    hits = []
    if boxes is not None and len(boxes):
        for b in boxes:
            cls_id, score = int(b[0]), float(b[1])
            if score < threshold or cls_id >= len(labels):
                continue
            hits.append((cls_id, score, [float(v) for v in b[2:6]]))
    ann = Image.fromarray(image)
    scale = min(1.0, MAX_SIDE / max(ann.size))
    if scale < 1.0:
        ann = ann.resize((round(ann.width * scale), round(ann.height * scale)), Image.LANCZOS)
    return _render(ann, scale, hits, eng, threshold)


def _draw(ann, scale, hits, eng, labels):
    """把 hits 画到 ann（PIL Image）上，返回叠加标签层后的 ann。"""
    draw = ImageDraw.Draw(ann)
    overlay = Image.new("RGBA", ann.size, (0, 0, 0, 0))
    odraw = ImageDraw.Draw(overlay)
    for cls_id, score, (x1, y1, x2, y2) in hits:
        info = eng["info"](labels[cls_id])
        color = eng["color_of"](info.get("kind", ""))
        label = "%s %.2f" % (info["zh"], score)
        x1, y1, x2, y2 = (v * scale for v in (x1, y1, x2, y2))
        draw.rectangle([x1, y1, x2, y2], outline=color, width=4)
        tw = odraw.textlength(label, font=FONT)
        ly = y1 - 26 if y1 >= 26 else y1
        odraw.rectangle([x1, ly, x1 + tw + 10, ly + 24], fill=color + (185,))
        odraw.text((x1 + 5, ly + 1), label, fill=(255, 255, 255, 255), font=FONT)
    if hits:
        ann = Image.alpha_composite(ann.convert("RGBA"), overlay).convert("RGB")
    return ann


_NO_HIT_HTML = ('<div style="padding:16px;text-align:center;color:#64748b;'
                'background:#f8fafc;border:1px dashed #cbd5e1;border-radius:10px">'
                '未检出目标（阈值 %.2f，可调低后重检）</div>')


def _cards(hits, eng, labels, threshold, heading=None):
    """生成建议卡 HTML；hits 为空返回未检出提示条。"""
    if not hits:
        return _NO_HIT_HTML % threshold
    by_cls = {}
    for cls_id, score, _ in hits:
        by_cls.setdefault(cls_id, []).append(score)
    parts = [heading or ('<div style="font-size:14px;color:#475569;margin-bottom:10px">共检出 '
                         f'<b>{len(hits)}</b> 个目标、<b>{len(by_cls)}</b> 类（阈值 {threshold}）</div>')]
    for cls_id, scores in by_cls.items():
        info = eng["info"](labels[cls_id])
        color = eng["color_of"](info.get("kind", ""))
        hexc = "#%02x%02x%02x" % color
        p = max(scores)
        badge = (f"✅ 高置信 {p * 100:.0f}%") if p >= 0.80 else (f"⚠️ {p * 100:.0f}%")
        bcolor = "#15803d" if p >= 0.80 else "#b45309"
        tips_html = "".join(_tip_html(t) for t in info["tips"])
        parts.append(
            f'<div style="border:1px solid #e2e8f0;border-left:5px solid {hexc};border-radius:10px;'
            f'padding:14px 16px;margin:10px 0;background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.04)">'
            f'<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">'
            f'<span style="font-size:17px;font-weight:700">{info["zh"]}</span>'
            f'<span style="font-size:12px;color:#64748b">{labels[cls_id]} · {info.get("kind", "")}</span>'
            f'<span style="font-size:12px;color:#64748b">检出 {len(scores)} 处</span>'
            f'<span style="margin-left:auto;background:{bcolor}1a;color:{bcolor};border:1px solid {bcolor}55;'
            f'border-radius:999px;padding:2px 10px;font-size:12px;font-weight:600">{badge}</span></div>'
            f'<div style="font-size:13px;color:#475569;margin:8px 0 6px"><b>为害特征：</b>{info["harm"]}</div>'
            f'<ul style="font-size:13px;color:#334155;margin:4px 0 0 18px;padding:0">{tips_html}</ul></div>')
    return "".join(parts)


def _infer(image, threshold, key):
    """单引擎推理，返回 hits。"""
    det, labels = _get_detector(key)
    with _INFER_LOCK:
        boxes = det.predict_image([image], visual=False).get("boxes")
    hits = []
    if boxes is not None and len(boxes):
        for b in boxes:
            cls_id, score = int(b[0]), float(b[1])
            if score < threshold or cls_id >= len(labels):
                continue
            hits.append((cls_id, score, [float(v) for v in b[2:6]]))
    return hits


def _record_hits(*hit_groups):
    """各引擎检出摘要写入全局 LAST_DETECTION（类别取最高分），供农事问答联动。"""
    global LAST_DETECTION
    out = []
    for eng_key, hits in hit_groups:
        if not hits:
            continue
        eng = ENGINES[eng_key]
        best = {}
        for cls_id, score, _ in hits:
            zh = eng["info"](eng["labels"][cls_id])["zh"]
            best[zh] = max(best.get(zh, 0.0), score)
        src = "病虫害引擎" if eng_key == "pest" else "杂草引擎"
        out.append(src + "：" + "、".join("%s %.2f" % kv for kv in best.items()))
    LAST_DETECTION = out or ["未检出目标"]


def detect(image, threshold, key):
    eng = ENGINES[key]
    if image is None:
        gr.Warning("请先上传或从示例区选择一张图片")
        return None, PLACEHOLDER_HTML
    hits = _infer(image, threshold, key)
    _record_hits((key, hits))
    ann = Image.fromarray(image)
    scale = min(1.0, MAX_SIDE / max(ann.size))
    if scale < 1.0:
        ann = ann.resize((round(ann.width * scale), round(ann.height * scale)), Image.LANCZOS)
    ann = _draw(ann, scale, hits, eng, ENGINES[key]["labels"])
    return np.array(ann), _cards(hits, eng, ENGINES[key]["labels"], threshold)


def _section_heading(title, count, hexc):
    return (f'<div style="display:flex;align-items:center;gap:8px;margin:16px 0 6px">'
            f'<span style="width:10px;height:10px;border-radius:50%;background:{hexc};'
            f'display:inline-block"></span>'
            f'<span style="font-size:15px;font-weight:700;color:#334155">{title}</span>'
            f'<span style="font-size:12px;color:#94a3b8">（{count} 个检出）</span></div>')


def detect_unified(image, threshold):
    """一张图同时过病虫害与杂草两个引擎，结果叠加画框、建议卡分列。"""
    if image is None:
        gr.Warning("请先上传或从示例区选择一张图片")
        return None, PLACEHOLDER_HTML
    hits_pest = _infer(image, threshold, "pest")
    hits_weed = _infer(image, threshold, "weed")
    _record_hits(("pest", hits_pest), ("weed", hits_weed))
    ann = Image.fromarray(image)
    scale = min(1.0, MAX_SIDE / max(ann.size))
    if scale < 1.0:
        ann = ann.resize((round(ann.width * scale), round(ann.height * scale)), Image.LANCZOS)
    ann = _draw(ann, scale, hits_weed, ENGINES["weed"], ENGINES["weed"]["labels"])
    ann = _draw(ann, scale, hits_pest, ENGINES["pest"], ENGINES["pest"]["labels"])
    total = len(hits_pest) + len(hits_weed)
    html = (f'<div style="font-size:14px;color:#475569;margin-bottom:6px">双引擎共检出 '
            f'<b>{total}</b> 个目标（阈值 {threshold}）</div>')
    html += _section_heading("🐛 病虫害引擎", len(hits_pest), "#e05d43")
    html += _cards(hits_pest, ENGINES["pest"], ENGINES["pest"]["labels"], threshold,
                   heading=f'<div style="font-size:12.5px;color:#64748b">检出 {len(hits_pest)} 个</div>'
                   if hits_pest else None)
    html += _section_heading("🌿 杂草引擎", len(hits_weed), "#d96831")
    html += _cards(hits_weed, ENGINES["weed"], ENGINES["weed"]["labels"], threshold,
                   heading=f'<div style="font-size:12.5px;color:#64748b">检出 {len(hits_weed)} 个</div>'
                   if hits_weed else None)
    return np.array(ann), html


# ---------------- 农事问答 agent（智谱 GLM）----------------
from knowledge_pest import KNOWLEDGE as _KB_PEST  # noqa: E402
from knowledge_weed import KNOWLEDGE as _KB_WEED, KIND_OF as _WEED_KIND  # noqa: E402


def _kb_text():
    lines = ["【病虫害知识库（本系统可检测的 14 类）】"]
    for en, it in _KB_PEST.items():
        lines.append("- %s（%s）为害：%s 防治：%s"
                     % (it["zh"], it.get("kind", ""), it["harm"], "；".join(it["tips"])))
    lines.append("【棉田杂草知识库（本系统可检测的 12 类）】")
    for en, it in _KB_WEED.items():
        lines.append("- %s（%s）为害：%s 防除：%s"
                     % (it["zh"], _WEED_KIND.get(en, ""), it["harm"], "；".join(it["tips"])))
    return "\n".join(lines)


def _agent_system_prompt():
    detect_note = ""
    if LAST_DETECTION:
        detect_note = ("\n用户最近一次检测结果（用户问\"这个怎么治\"时即指这些目标）："
                       + "；".join(LAST_DETECTION))
    return (
        "你是\"农田小助手\"，一位服务农户的农事问答助手，内置于农作物病虫害/杂草检测系统中。\n"
        "回答要求：\n"
        "1. 语言通俗，像乡镇农技员那样讲给农户听，少用术语，多给可操作的做法（什么时间、怎么做、注意什么）；\n"
        "2. 涉及农药/除草剂时，提醒按推荐剂量使用、注意安全间隔期、交替用药防止抗性；\n"
        "3. 用户问到本系统可检测的病虫害或杂草时，优先依据下面的内置知识库回答；"
        "知识库之外的农事问题（施肥、轮作、播种、天气影响等）凭农业专业知识简明回答；\n"
        "4. 回答控制在 300 字以内，分点列出；拿不准时如实说明并建议咨询当地农技部门，不要编造药名。\n"
        "\n" + _kb_text() + detect_note
    )


def _agent_reply(message, history):
    if not message or not message.strip():
        return "请输入你的问题，例如：番茄晚疫病怎么防治？"
    if not ZHIPU_API_KEY:
        return ("⚠️ 尚未配置智谱 API key：设环境变量 ZHIPU_API_KEY 后重启即可使用农事问答。"
                "三个检测 Tab 不受影响。")
    msgs = [{"role": "system", "content": _agent_system_prompt()}]
    for m in list(history)[-10:]:
        if isinstance(m, dict) and m.get("role") in ("user", "assistant") and m.get("content"):
            msgs.append({"role": m["role"], "content": str(m["content"])})
    msgs.append({"role": "user", "content": message})
    try:
        r = requests.post(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            headers={"Authorization": "Bearer " + ZHIPU_API_KEY,
                     "Content-Type": "application/json"},
            json={"model": ZHIPU_MODEL, "messages": msgs,
                  "temperature": 0.6, "max_tokens": 1024},
            timeout=60)
        r.raise_for_status()
        content = r.json()["choices"][0]["message"]["content"]
        if isinstance(content, list):  # 智谱分段格式 [{"type":"text","text":"…"}]
            content = "".join(seg.get("text", "") for seg in content
                              if isinstance(seg, dict))
        return str(content).strip()
    except Exception as e:  # noqa: BLE001
        return ("⚠️ 问答服务暂时不可用（%s），请稍后再试；检测功能不受影响。"
                % type(e).__name__)


def _chat_ask(message, history):
    """Gradio 6 手动 Chatbot（messages 格式）：自行追加 user/assistant 并返回完整列表。"""
    reply = _agent_reply(message, history)
    new_history = list(history or []) + [
        {"role": "user", "content": message},
        {"role": "assistant", "content": reply}]
    return "", new_history


CSS = """
.gradio-container{font-family:"Microsoft YaHei","Segoe UI Emoji","Segoe UI Symbol",sans-serif!important}
#hero{background:linear-gradient(135deg,#15803d 0%,#166534 100%);color:#fff;
padding:38px 24px;text-align:center;border-radius:14px;margin-bottom:14px}
#hero h1{font-size:38px;margin:0 0 10px;color:#fff}
#hero p{font-size:15px;opacity:.92;margin:0}
.panel-card{border:1px solid #e2e8f0;border-radius:12px;padding:16px;background:#fff}
.panel-title{font-size:16px;font-weight:700;color:#166534;margin-bottom:10px;
display:flex;align-items:center;gap:8px}
.stat-box{background:#f0fdf4;border:1px solid #bbf7d0;border-radius:12px;padding:16px;
text-align:center}
.stat-num{font-size:28px;font-weight:800;color:#16a34a}
.stat-label{font-size:12.5px;color:#475569;margin-top:4px}
.footer-note{font-size:12px;color:#94a3b8;text-align:center;margin-top:10px}
"""

with gr.Blocks(css=CSS, title="农田有害生物一体化检测系统") as demo:
    gr.HTML('<div id="hero"><h1>🌾 农田有害生物一体化检测系统</h1>'
            '<p>病虫害（14 类：5 虫 + 8 病 + 1 健康）× 棉田杂草（12 类）· 双引擎离线检测 · '
            '联动防治/防除知识库 · 全程本地运行</p></div>')

    with gr.Row():
        gr.HTML('<div class="stat-box"><div class="stat-num">26</div>'
                '<div class="stat-label">检测目标类别</div></div>')
        gr.HTML('<div class="stat-box"><div class="stat-num">2</div>'
                '<div class="stat-label">检测引擎（病虫 / 杂草）</div></div>')
        gr.HTML('<div class="stat-box"><div class="stat-num">0.648</div>'
                '<div class="stat-label">病虫害引擎 mAP50（val 部署口径）</div></div>')
        gr.HTML('<div class="stat-box"><div class="stat-num">0.965</div>'
                '<div class="stat-label">杂草引擎 mAP50（test）</div></div>')

    with gr.Tabs():
        with gr.Tab("🔍 综合检测（一张图·双引擎）"):
            with gr.Row():
                with gr.Column(scale=4, elem_classes="panel-card"):
                    gr.HTML('<div class="panel-title">🔍 上传田间图片</div>')
                    inp0 = gr.Image(type="numpy", label="", height=300,
                                    sources=["upload", "clipboard", "webcam"])
                    thr0 = gr.Slider(0.20, 0.80, value=0.35, step=0.05,
                                     label="置信度阈值（两引擎共用）", info="过高漏检 / 过低误检")
                    btn0 = gr.Button("🔍 双引擎检测", variant="primary")
                with gr.Column(scale=5, elem_classes="panel-card"):
                    gr.HTML('<div class="panel-title">🖼️ 综合检测结果</div>')
                    out0 = gr.Image(type="numpy", label="", height=300)
                    gr.HTML('<div class="footer-note">病虫（虫红/病蓝/健康绿）与杂草（阔叶橙红/禾本金棕）'
                            '检出框叠加展示，建议卡按引擎分列</div>')
            out0h = gr.HTML(PLACEHOLDER_HTML)
            gr.Examples(examples=[[str(p)] for p in
                sorted((_BASE / "examples_pest").glob("*.jpg")) +
                sorted((_BASE / "examples_weed").glob("*.jpg"))], inputs=inp0,
                label="示例图片（前几张病虫害、后几张杂草）")
            btn0.click(detect_unified, [inp0, thr0], [out0, out0h])
            thr0.release(detect_unified, [inp0, thr0], [out0, out0h])
        with gr.Tab("🐛 病虫害检测（14 类）"):
            with gr.Row():
                with gr.Column(scale=4, elem_classes="panel-card"):
                    gr.HTML('<div class="panel-title">🔍 上传作物图片</div>')
                    inp1 = gr.Image(type="numpy", label="", height=300,
                                    sources=["upload", "clipboard", "webcam"])
                    thr1 = gr.Slider(0.20, 0.80, value=0.35, step=0.05,
                                     label="置信度阈值", info="过高漏检 / 过低误检")
                    btn1 = gr.Button("🔍 开始检测", variant="primary")
                with gr.Column(scale=5, elem_classes="panel-card"):
                    gr.HTML('<div class="panel-title">🖼️ 检测结果 · 病虫害</div>')
                    out1 = gr.Image(type="numpy", label="", height=300)
                    gr.HTML('<div class="footer-note">%s</div>' % ENGINES["pest"]["legend"])
            out1h = gr.HTML(PLACEHOLDER_HTML)
            gr.Examples(examples=[[str(p)] for p in sorted(
                (_BASE / "examples_pest").glob("*.jpg"))], inputs=inp1,
                label="示例图片（按文件名排序）")
        with gr.Tab("🌿 杂草检测（12 类）"):
            with gr.Row():
                with gr.Column(scale=4, elem_classes="panel-card"):
                    gr.HTML('<div class="panel-title">🔍 上传棉田图片</div>')
                    inp2 = gr.Image(type="numpy", label="", height=300,
                                    sources=["upload", "clipboard", "webcam"])
                    thr2 = gr.Slider(0.20, 0.90, value=0.35, step=0.05,
                                     label="置信度阈值", info="过高漏检 / 过低误检")
                    btn2 = gr.Button("🔍 开始检测", variant="primary")
                with gr.Column(scale=5, elem_classes="panel-card"):
                    gr.HTML('<div class="panel-title">🖼️ 检测结果 · 杂草</div>')
                    out2 = gr.Image(type="numpy", label="", height=300)
                    gr.HTML('<div class="footer-note">%s</div>' % ENGINES["weed"]["legend"])
            out2h = gr.HTML(PLACEHOLDER_HTML)
            gr.Examples(examples=[[str(p)] for p in sorted(
                (_BASE / "examples_weed").glob("*.jpg"))], inputs=inp2,
                label="示例图片（棉田杂草实测样例）")
        with gr.Tab("💬 农事问答（智谱 GLM）"):
            with gr.Row():
                with gr.Column(scale=6, elem_classes="panel-card"):
                    gr.HTML('<div class="panel-title">💬 问农事 · 病虫害 / 杂草 / 种植管理</div>')
                    chat = gr.Chatbot(
                        height=420, label="",
                        value=[{"role": "assistant",
                                "content": "你好，我是农田小助手 🌾\n可咨询本系统 26 类病虫害/杂草的防治方法，"
                                           "也可问施肥、轮作等农事问题。\n检测过的图片再提问（如\"这个怎么治\"），"
                                           "我会结合刚才的检测结果回答。"}])
                    msg = gr.Textbox(placeholder="例如：番茄晚疫病怎么治？/ 棉田阔叶杂草苗后用什么除草剂？",
                                     label="你的问题", lines=2)
                    with gr.Row():
                        send = gr.Button("📨 发送", variant="primary")
                        clear = gr.Button("🧹 清空对话")
                    gr.Examples(examples=["番茄晚疫病怎么防治？",
                                           "棉田禾本科杂草怎么除？",
                                           "刚才检出的这个怎么治？",
                                           "玉米什么时期追肥效果好？"],
                                inputs=msg, label="试试这些问题")
                with gr.Column(scale=4, elem_classes="panel-card"):
                    gr.HTML('<div class="panel-title">🧭 使用说明</div>')
                    gr.HTML(
                        '<ul style="font-size:13.5px;color:#475569;line-height:2;padding-left:18px">'
                        '<li>由智谱 GLM 大模型驱动，内置 26 类检测目标知识库，回答结合本地检测结果</li>'
                        '<li>先在前三个 Tab 检测图片，再来提问，可直接问"刚才检出的怎么治"</li>'
                        '<li>防治与用药建议仅供学习参考，实际生产请遵循当地植保部门指导</li>'
                        '<li>本 Tab 需联网并配置 API key；三个检测 Tab 仍完全离线运行</li></ul>')

    btn1.click(detect, [inp1, thr1, gr.State("pest")], [out1, out1h])
    thr1.release(detect, [inp1, thr1, gr.State("pest")], [out1, out1h])
    btn2.click(detect, [inp2, thr2, gr.State("weed")], [out2, out2h])
    thr2.release(detect, [inp2, thr2, gr.State("weed")], [out2, out2h])
    msg.submit(_chat_ask, [msg, chat], [msg, chat], queue=True)
    send.click(_chat_ask, [msg, chat], [msg, chat], queue=True)
    clear.click(lambda: None, None, chat, queue=False)

    gr.HTML('<div class="footer-note">两个引擎分别对应两项独立训练与评测的检测模型，'
            '本系统作为统一演示载体 · 防治与用药建议仅供学习参考，'
            '实际生产请遵循当地植保部门指导 · 检测全程离线运行；'
            '农事问答由智谱 GLM 驱动，需联网</div>')

if __name__ == "__main__":
    demo.launch(server_name="127.0.0.1", server_port=7862, inbrowser=True,
                show_error=True)
