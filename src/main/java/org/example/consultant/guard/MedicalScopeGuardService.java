package org.example.consultant.guard;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class MedicalScopeGuardService {

    private static final List<String> MEDICAL_KEYWORDS = List.of(
            "症状", "不舒服", "难受", "疼", "痛", "酸", "胀", "痒", "麻", "晕", "恶心", "呕吐", "腹泻", "便秘", "发烧", "发热", "低烧",
            "咳嗽", "咳痰", "流鼻涕", "鼻塞", "头痛", "头晕", "胸痛", "胸闷", "心慌", "呼吸困难", "肚子痛", "腹痛", "胃痛", "拉肚子",
            "高血压", "糖尿病", "感染", "炎", "肿瘤", "结节", "贫血", "过敏", "湿疹", "皮疹", "月经", "怀孕", "妊娠", "失眠",
            "抑郁", "焦虑", "疫苗", "体检", "检查", "化验", "药", "吃药", "用药", "副作用", "禁忌", "剂量", "挂号", "挂什么科",
            "门诊", "急诊", "住院", "康复", "复查", "指标", "血压", "血糖", "血脂", "心率", "b超", "ct", "核磁", "x光", "彩超",
            "牙疼", "喉咙", "嗓子", "头", "鼻子", "胃", "肚子", "胸口", "后背", "腰", "膝盖", "皮肤", "睡眠",
            "医生", "医院", "科室", "病", "疾病", "治疗", "手术", "护理", "预防", "医学", "医疗", "健康"
    );

    private static final List<String> NON_MEDICAL_KEYWORDS = List.of(
            "股票", "基金", "证券", "投资", "理财", "比特币", "行情", "k线",
            "代码", "编程", "java", "python", "bug", "前端", "后端", "算法", "sql", "服务器", "接口", "部署", "脚本",
            "论文", "降重", "ppt", "简历", "面试", "考研", "高数", "英语翻译", "作文", "文案", "营销", "运营", "作业", "答辩",
            "旅游", "酒店", "机票", "火车票", "外卖", "菜谱", "做饭", "美食", "电影", "电视剧", "小说", "游戏", "二次元",
            "星座", "塔罗", "算命", "风水", "法律", "合同", "起诉", "离婚", "房贷", "租房", "装修", "汽车", "购车",
            "手机", "电脑", "相机", "耳机", "淘宝", "拼多多", "京东", "优惠券", "直播", "短视频", "小红书",
            "天气", "下雨", "气温", "旅游攻略", "路线", "餐厅", "奶茶", "咖啡"
    );

    private static final List<String> SMALL_TALK_KEYWORDS = List.of(
            "你好", "您好", "hi", "hello", "在吗", "谢谢", "感谢", "早上好", "晚上好"
    );

    private static final List<Pattern> MEDICAL_INTENT_PATTERNS = List.of(
            Pattern.compile(".*(要不要去医院|需不需要去医院|该不该去医院).*"),
            Pattern.compile(".*(挂什么科|看什么科|去什么科).*"),
            Pattern.compile(".*(能不能吃|可以吃吗|怎么用药|药怎么吃|能一起吃吗|能不能一起吃|能同时吃吗|能同吃吗).*"),
            Pattern.compile(".*(检查结果|化验单|体检报告).*"),
            Pattern.compile(".*(怎么办|怎么处理|如何缓解).*")
    );

    private static final List<Pattern> GENERIC_NON_MEDICAL_PATTERNS = List.of(
            Pattern.compile(".*(帮我写|帮我做|帮我生成|帮我分析|帮我总结|帮我翻译).*"),
            Pattern.compile(".*(怎么安装|怎么配置|怎么部署|怎么开发|怎么学习).*"),
            Pattern.compile(".*(推荐一下|给我推荐|哪个好用|怎么赚钱).*"),
            Pattern.compile(".*(今天天气|明天天气|天气怎么样).*"),
            Pattern.compile(".*(写代码|改代码|修bug|做ppt|写论文).*"),
            Pattern.compile(".*(电影|游戏|旅游|美食|餐厅|酒店).*"),
            Pattern.compile(".*(股票|基金|投资|理财).*"),
            Pattern.compile(".*(法律|合同|起诉|离婚).*")
    );

    public ScopeDecision evaluate(String message) {
        String normalized = normalize(message);
        if (normalized.isBlank() || isSmallTalk(normalized)) {
            return ScopeDecision.inScope(Set.of(), Set.of());
        }

        Set<String> medicalHits = collectHits(normalized, MEDICAL_KEYWORDS);
        Set<String> nonMedicalHits = collectHits(normalized, NON_MEDICAL_KEYWORDS);
        boolean medicalIntent = matchesAny(normalized, MEDICAL_INTENT_PATTERNS);
        boolean genericNonMedicalIntent = matchesAny(normalized, GENERIC_NON_MEDICAL_PATTERNS);

        int medicalScore = medicalHits.size() + (medicalIntent ? 2 : 0);
        int nonMedicalScore = nonMedicalHits.size() + (genericNonMedicalIntent ? 2 : 0);

        boolean clearlyNonMedical = medicalScore == 0 && nonMedicalScore > 0;
        boolean likelyNonMedical = medicalScore == 0 && normalized.length() >= 6 && !looksLikeMedicalQuestion(normalized);

        if (clearlyNonMedical || likelyNonMedical) {
            return ScopeDecision.outOfScope(buildRefusalMessage(), medicalHits, nonMedicalHits);
        }
        return ScopeDecision.inScope(medicalHits, nonMedicalHits);
    }

    private boolean isSmallTalk(String normalized) {
        return SMALL_TALK_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean looksLikeMedicalQuestion(String normalized) {
        return normalized.contains("症状")
                || normalized.contains("身体")
                || normalized.contains("医院")
                || normalized.contains("医生")
                || normalized.contains("药")
                || normalized.contains("检查")
                || normalized.contains("治疗")
                || matchesAny(normalized, MEDICAL_INTENT_PATTERNS);
    }

    private boolean matchesAny(String normalized, List<Pattern> patterns) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(normalized).matches());
    }

    private String buildRefusalMessage() {
        return "这个问题看起来不属于医疗健康咨询范围，我这边先不展开回答。\n\n"
                + "如果你愿意，可以继续问我这些医疗相关问题：\n"
                + "1. 症状分析与风险判断\n"
                + "2. 是否需要去医院、该挂什么科\n"
                + "3. 检查报告或体检指标怎么理解\n"
                + "4. 常见用药注意事项\n\n"
                + "你也可以直接这样问：\"发烧三天要不要去医院\"、\"头痛伴恶心可能是什么原因\"。";
    }

    private Set<String> collectHits(String normalized, List<String> keywords) {
        Set<String> hits = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                hits.add(keyword);
            }
        }
        return hits;
    }

    private String normalize(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public record ScopeDecision(boolean inScope,
                                String refusalMessage,
                                Set<String> medicalHits,
                                Set<String> nonMedicalHits) {

        static ScopeDecision inScope(Set<String> medicalHits, Set<String> nonMedicalHits) {
            return new ScopeDecision(true, null, medicalHits, nonMedicalHits);
        }

        static ScopeDecision outOfScope(String refusalMessage,
                                        Set<String> medicalHits,
                                        Set<String> nonMedicalHits) {
            return new ScopeDecision(false, refusalMessage, medicalHits, nonMedicalHits);
        }
    }
}
