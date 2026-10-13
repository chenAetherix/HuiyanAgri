package com.agriunified.detection;

import android.graphics.Color;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一体化知识库：14 类农作物病虫害 + 12 类棉田杂草（与 PC 端 knowledge_pest/weed.py 同源）。
 * key 与模型输出 class_id 按 labels_pest/weed.txt 顺序一一对应。
 */
public final class KnowledgeBase {

    public static class PestInfo {
        public final String en;
        public final String zh;
        public final String kind;      // 病虫：虫害/病害/健康；杂草：阔叶杂草/禾本科杂草
        public final String harm;      // 为害特征
        public final String[] tips;    // 防治/防除建议（含【物理】【生物】【化学】等前缀）

        PestInfo(String en, String zh, String kind, String harm, String[] tips) {
            this.en = en;
            this.zh = zh;
            this.kind = kind;
            this.harm = harm;
            this.tips = tips;
        }

        /** 大类配色：与 PC 端统一系统一致（病虫：虫红/地下橙棕/病蓝/健康绿；杂草：阔叶橙红/禾本金棕） */
        public int color() {
            if (kind.startsWith("阔叶杂草")) return Color.parseColor("#d96831");
            if (kind.startsWith("禾本科")) return Color.parseColor("#b08932");
            if (kind.startsWith("虫害（地下")) return Color.parseColor("#c2622b");
            if (kind.startsWith("虫害")) return Color.parseColor("#e05d43");
            if (kind.startsWith("病害")) return Color.parseColor("#3b82c4");
            return Color.parseColor("#2f9e63");
        }
    }

    public static final Map<String, PestInfo> KNOWLEDGE = new LinkedHashMap<>();
    public static final Map<String, PestInfo> WEED = new LinkedHashMap<>();

    static {
        put(new PestInfo("aphid", "蚜虫", "虫害",
                "群集嫩叶嫩茎刺吸汁液，致叶片卷缩发黄，并传播多种植物病毒病。",
                new String[]{
                        "【物理】田间悬挂黄色粘虫板诱杀有翅蚜，每亩 20~30 块；铺设银灰色地膜避蚜。",
                        "【生物】保护瓢虫、草蛉、食蚜蝇等天敌；蚜霉菌等微生物制剂高湿天气使用。",
                        "【化学】发生初期喷施 10% 吡虫啉可湿性粉剂或 3% 啶虫脒，注意交替用药防抗性。",
                        "【农业】及时清除田间杂草与残株，控制氮肥防止旺长招蚜。"}));
        put(new PestInfo("beet_army_worm", "甜菜夜蛾", "虫害",
                "幼虫啃食叶片成孔洞缺刻，大龄幼虫暴食，还可蛀食果实与生长点。",
                new String[]{
                        "【物理】成虫期布设频振式杀虫灯与甜菜夜蛾性诱剂诱捕器，压低虫口基数。",
                        "【生物】卵孵化盛期喷施苏云金杆菌（Bt）或核多角体病毒制剂，保护叉角厉蝽等天敌。",
                        "【化学】低龄幼虫期选用甲维盐、氯虫苯甲酰胺或茚虫威，傍晚施药效果好。",
                        "【农业】及时中耕除草消灭土中蛹，收获后深翻晒垡。"}));
        put(new PestInfo("corn_borer", "玉米螟", "虫害",
                "幼虫蛀食茎秆和雌穗，造成折茎、秃尖与籽粒破损，严重影响产量。",
                new String[]{
                        "【生物】赤眼蜂卵期放蜂（每亩 1~2 万头）；心叶期丢施白僵菌颗粒剂。",
                        "【物理】成虫期架设杀虫灯，处理秸秆消灭越冬幼虫。",
                        "【化学】大喇叭口期用氯虫苯甲酰胺灌心或丢颗粒剂，一代防治最关键。",
                        "【农业】秸秆粉碎还田或沤肥，降低越冬虫源。"}));
        put(new PestInfo("corn_leaf_blight", "玉米大斑病", "病害",
                "叶片出现灰褐色长梭形大斑，湿度大时生黑霉，病斑连片致叶片枯死。",
                new String[]{
                        "【农业】选用抗病品种，合理密植保持通风透光；收获后清除病残体并深翻。",
                        "【化学】发病初期喷施苯醚甲环唑、丙环唑或嘧菌酯，隔 7~10 天连防 2~3 次。",
                        "【环境】雨季注意排水，控制氮肥增施磷钾肥提高抗性。"}));
        put(new PestInfo("leafhopper", "叶蝉", "虫害",
                "刺吸叶片汁液造成失绿白斑，传播矮缩病等植原体/病毒病害。",
                new String[]{
                        "【物理】黄板诱杀 + 防虫网阻隔，灯光诱杀成虫。",
                        "【生物】保护蜘蛛、缨小蜂等天敌。",
                        "【化学】若虫盛期喷施噻虫嗪、吡虫啉或呋虫胺，重点喷叶背。",
                        "【农业】清除田边杂草，切断中间寄主。"}));
        put(new PestInfo("mole_cricket", "蝼蛄", "虫害（地下害虫）",
                "在土中开隧道并咬食种子、幼根与嫩茎，造成缺苗断垄。",
                new String[]{
                        "【物理】蝼蛄有强趋光性，苗期设频振灯诱杀；堆鲜马粪诱集人工捕杀。",
                        "【化学】用辛硫磷或毒死蜱拌炒香饵料（麦麸、豆饼）制成毒饵傍晚撒施。",
                        "【农业】深耕晒垡破坏虫巢，水旱轮作可显著降低虫口密度。"}));
        put(new PestInfo("potato_early_blight", "马铃薯早疫病", "病害",
                "叶片生褐色圆形病斑，具明显同心轮纹，严重时叶片提前枯死。",
                new String[]{
                        "【农业】与非茄科作物轮作 2 年以上；增施钾肥，及时排除田间积水。",
                        "【化学】发病初期喷施代森锰锌、嘧菌酯或苯醚甲环唑，间隔 7~10 天。",
                        "【农业】收获后彻底清除病残体，种薯播前消毒。"}));
        put(new PestInfo("potato_late_blight", "马铃薯晚疫病", "病害",
                "高湿低温下流行极快，叶面现暗绿水浸状斑，叶背生白霉，可致全田毁灭。",
                new String[]{
                        "【监测】中心病株出现即全田预警，现蕾封垄期是防控关键窗口。",
                        "【化学】预防用代森锰锌，治疗用氟菌·霜霉威、霜脲氰·锰锌或氰霜唑，雨后补喷。",
                        "【农业】选用抗病品种与脱毒种薯，起垄培土减少块茎感染。",
                        "【农业】发病中心及时拔除深埋，收获前割秧晒地。"}));
        put(new PestInfo("tomato_bacterial_spot", "番茄细菌性斑点病", "病害（细菌性）",
                "叶、茎、果生深褐色至黑褐色小斑点，边缘黄色晕圈，高湿时溢菌脓。",
                new String[]{
                        "【化学】发病初喷施氢氧化铜、王铜或噻唑锌等铜制剂，7~10 天一次。",
                        "【农业】种子温汤浸种或次氯酸钠消毒；与非茄科轮作 2~3 年。",
                        "【农事】避免带露雨天整枝打杈，减少伤口传染；用具定期消毒。"}));
        put(new PestInfo("tomato_early_blight", "番茄早疫病", "病害",
                "叶片现具同心轮纹的褐色病斑，从下部老叶向上蔓延，果蒂部也可受害。",
                new String[]{
                        "【农业】摘除下部病叶带出田外；加强通风降湿，增施磷钾肥。",
                        "【化学】发病初喷代森锰锌或苯醚甲环唑，与嘧菌酯交替使用。",
                        "【农业】合理密植、雨后及时排水，收获后清园。"}));
        put(new PestInfo("tomato_late_blight", "番茄晚疫病", "病害",
                "叶缘生暗绿色水浸状病斑，湿度大时叶背白色霉层，果实感后硬块状腐烂。",
                new String[]{
                        "【环境】大棚加强通风换气控湿（叶面避免结露）是防控核心。",
                        "【化学】发现中心病株立即摘除，全田喷氟菌·霜霉威、氰霜唑或烯酰吗啉。",
                        "【农业】与非茄科作物轮作，选用抗病品种，避免过密种植。"}));
        put(new PestInfo("tomato_mold_leaf", "番茄叶霉病", "病害",
                "叶面生淡黄褪绿斑，叶背出现灰紫色至橄榄色霉层，叶片卷曲枯死。",
                new String[]{
                        "【环境】棚内控温控湿（湿度 <85%），加强通风透光。",
                        "【化学】发病初喷施腈菌唑、苯醚甲环唑或春雷·王铜，重点喷叶背。",
                        "【农业】选用抗病品种，合理密植；发病后及时摘除病叶并清园。"}));
        put(new PestInfo("tomato_septoria_leaf_spot", "番茄壳针孢叶斑病", "病害",
                "叶片散生圆形小病斑，中央灰白、边缘深褐，其上生黑色小粒点，由下叶向上蔓延。",
                new String[]{
                        "【农业】及时摘除下部病叶，收获后彻底清除病残体；实行轮作。",
                        "【化学】发病初喷施苯醚甲环唑、嘧菌酯或百菌清，隔 7~10 天连防 2~3 次。",
                        "【环境】避免喷灌溅水传病，雨季注意排水降湿。"}));
        put(new PestInfo("tomato_leaf_healthy", "番茄健康叶片", "健康",
                "未见病虫害症状，叶片状态正常。",
                new String[]{
                        "【管理】无需防治；保持常规水肥管理，注意通风与光照。",
                        "【监测】建议定期巡检，重点关注叶背与下部老叶的早期症状。"}));

        // ---- 12 类棉田杂草（与 agriweed/demo/knowledge.py 同源） ----
        putWeed(new PestInfo("Waterhemp", "长芒苋", "阔叶杂草",
                "C4 高光效植物，生长极快，单株可产籽数十万粒；对草甘膦、ALS 类等多种除草剂已普遍产生抗性，重度发生时严重压制棉花产量。",
                new String[]{
                        "【农业】与禾谷类作物轮作并配合密植，压缩其生态位；收获后深翻压埋种子库。",
                        "【化学】坚持苗前封闭（如二甲戊灵）+ 苗后早期（5 叶前）处理的组合，轮换作用机理不同的药剂延缓抗性。",
                        "【物理】零星抗性株在结籽前人工拔除并带出田外销毁，切勿让其留种。",
                        "【监测】本种抗性多发，防除失败时取样做抗性检测，次年调整方案。"}));
        putWeed(new PestInfo("MorningGlory", "牵牛花属", "阔叶杂草",
                "一年生缠绕藤本，攀附棉花后遮光并引起倒伏；根系发达入土深，残茬易再生，种子在土中可存活多年。",
                new String[]{
                        "【农业】适期早播促棉花封行，减少藤蔓上架机会；秋耕深翻扰动其深根系。",
                        "【化学】苗前封闭结合苗后幼株期（2~4 叶）定向处理，成株藤蔓化后防效显著下降。",
                        "【物理】中耕切断幼苗；少量藤蔓人工摘除，注意连根带出。"}));
        putWeed(new PestInfo("Purslane", "马齿苋", "阔叶杂草",
                "肉质贴地生长，茎段落地即可再生，耐旱耐践踏；单株结籽量大，田间一旦建立便难以清除。",
                new String[]{
                        "【物理】中耕或人工拔除后及时清出田外，避免断茎再生成活。",
                        "【化学】幼苗期用药效果最好，成株蜡质层厚需加大覆盖度；行间可定向喷雾。",
                        "【农业】地膜覆盖与秸秆覆盖能显著抑制其出苗。"}));
        putWeed(new PestInfo("SpottedSpurge", "斑地锦", "阔叶杂草",
                "匍匐低矮成垫状，贴地生长竞争浅层水肥；折断处流白色乳汁对皮肤有刺激性，密度大时影响田间作业。",
                new String[]{
                        "【农业】提高作物覆盖度是关键——其竞争力弱，封行后自然受抑。",
                        "【化学】苗前封闭对其小粒种子效果好，苗后需在垫状群落形成前处理。",
                        "【物理】花期前人工铲除；作业时戴手套避免乳汁接触皮肤。"}));
        putWeed(new PestInfo("Carpetweed", "粟米草", "阔叶杂草",
                "匍匐茎交织成地毯状快速覆盖地面，与棉花争水争肥；单株目标小、易漏检漏治。",
                new String[]{
                        "【化学】苗前土壤封闭为主，出苗后趁群落未铺展时早处理。",
                        "【物理】早期中耕即可有效铲除；覆盖栽培抑制出苗。",
                        "【监测】目标细小易被漏检，巡查时应贴近地面观察。"}));
        putWeed(new PestInfo("Ragweed", "豚草", "阔叶杂草",
                "竞争性强、植株高大；花粉是重要致敏原，影响田间作业人员健康；单株产籽数以万计。",
                new String[]{
                        "【农业】轮作与秋深翻消耗种子库；提倡在其花期前完成防除。",
                        "【物理】开花前人工拔除或机械铲除，重点清除田边地头源头。",
                        "【监测】花粉期作业做好个人防护；近田居民区周边优先防除。"}));
        putWeed(new PestInfo("Eclipta", "鳢肠", "阔叶杂草",
                "喜湿环境一年生阔叶草，茎节接触土壤即可生根，刈割后再生能力强；在低洼棉田常成片发生。",
                new String[]{
                        "【农业】完善田间排水，降低湿度削弱其优势；水旱轮作可大幅压低基数。",
                        "【化学】苗后早期处理，避免刈割后仅除地上部。",
                        "【物理】拔除时带出田外，防止茎节二次生根。"}));
        putWeed(new PestInfo("PricklySida", "刺黄花稔", "阔叶杂草",
                "直根系亚灌木型阔叶草，木质化后除草剂传导差、防除窗口短；成熟植株影响机械采收。",
                new String[]{
                        "【化学】抓住幼苗期（6 叶前）用药，木质化后防效急剧下降。",
                        "【农业】适当密植提早封行；秋翻切断直根再生。",
                        "【物理】零星植株人工拔除，注意连主根拔起。"}));
        putWeed(new PestInfo("PalmerAmaranth", "长序苋", "阔叶杂草",
                "国际公认的\"最具威胁杂草\"之一：日生长可达 2~3 厘米，产籽量巨大且对多类除草剂产生抗性；漏防一株，来年成片。",
                new String[]{
                        "【监测】重点巡查田边沟渠，发现即标记立即处理。",
                        "【化学】多机理药剂轮换+苗前封闭组合，杜绝单一药剂连年使用。",
                        "【物理】结籽前连根拔除并焚毁，严禁留田。",
                        "【农业】短周期轮作+覆盖作物压制其种子库。"}));
        putWeed(new PestInfo("Sicklepod", "镰荚决明", "阔叶杂草",
                "植株高大、分枝多，强烈遮光竞争；荚果种子量大且硬实，土壤种子库可存活多年。",
                new String[]{
                        "【化学】苗后早期定向处理，植株封垄后防效有限。",
                        "【农业】与密植作物轮作；及早中耕。",
                        "【物理】花荚期前割除并带出田，防止落荚补充种子库。"}));
        putWeed(new PestInfo("Goosegrass", "牛筋草", "禾本科杂草",
                "禾本科一年生，根系纤维发达成盘，极耐旱耐践踏，人工拔除困难；对草甘膦抗性在全球多地已有报道。",
                new String[]{
                        "【化学】选用对禾本科高效的苗后药剂（如芳氧苯氧丙酸类），抗性种群可换用其他机理药剂。",
                        "【物理】苗期浅中耕结合人工带根拔除；成株拔除需工具。",
                        "【农业】避免连作；覆盖栽培减少出苗。"}));
        putWeed(new PestInfo("CutleafGroundcherry", "裂叶酸浆", "阔叶杂草",
                "茄科一年生，浆果内种子量大，经动物取食传播扩散；植株侵占空间并易藏匿害虫，样本最稀有的类群之一。",
                new String[]{
                        "【农业】及早中耕铲除苗期植株，减少结果传播。",
                        "【物理】结果前拔除带出田外；清沟理墒清除田缘寄主。",
                        "【监测】样本稀少的稀有类，模型检出后建议人工复核确认。"}));
    }

    private static void put(PestInfo info) {
        KNOWLEDGE.put(info.en, info);
    }

    private static void putWeed(PestInfo info) {
        WEED.put(info.en, info);
    }

    /** 按英文类别名取知识条目；未知类别返回占位 */
    public static PestInfo get(String en) {
        PestInfo info = KNOWLEDGE.get(en);
        if (info != null) return info;
        return new PestInfo(en, en, "未知", "暂无资料。", new String[0]);
    }

    /** 按模式分派：weed 模式查杂草库，其余查病虫库。 */
    public static PestInfo get(String mode, String en) {
        if (Detector.MODE_WEED.equals(mode)) {
            PestInfo info = WEED.get(en);
            if (info != null) return info;
            return new PestInfo(en, en, "未知", "暂无资料。", new String[0]);
        }
        return get(en);
    }

    private KnowledgeBase() {
    }
}
