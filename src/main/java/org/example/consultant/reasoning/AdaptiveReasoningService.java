package org.example.consultant.reasoning;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdaptiveReasoningService {

    private static final Pattern BP_READING = Pattern.compile("(\\d{2,3})\\s*/\\s*(\\d{2,3})");

    public ReasoningPlan plan(String message) {
        String normalized = normalize(message);
        SignalProfile profile = extractSignals(message, normalized);

        if (profile.hasEmergencyGate()) {
            return buildPlan(
                    ReasoningMode.EMERGENCY_ASSESSMENT,
                    emergencySteps(),
                    profile.emergencyScore() + 2.0,
                    Math.max(profile.emergencyScore(), profile.medicationScore()),
                    profile.totalScore(),
                    emergencyReasons(profile),
                    profile
            );
        }

        LinkedHashMap<ReasoningMode, Double> scores = new LinkedHashMap<>();
        scores.put(ReasoningMode.MEDICATION_SAFETY, profile.medicationScore());
        scores.put(ReasoningMode.REPORT_INTERPRETATION, profile.reportScore());
        scores.put(ReasoningMode.DEPARTMENT_GUIDANCE, profile.departmentScore());
        scores.put(ReasoningMode.SYMPTOM_TRIAGE, profile.symptomScore());
        scores.put(ReasoningMode.GENERAL_HEALTH, profile.generalScore());

        ReasoningMode bestMode = ReasoningMode.GENERAL_HEALTH;
        double bestScore = -1;
        ReasoningMode secondMode = ReasoningMode.GENERAL_HEALTH;
        double secondScore = -1;

        for (Map.Entry<ReasoningMode, Double> entry : scores.entrySet()) {
            double score = entry.getValue();
            if (score > bestScore) {
                secondMode = bestMode;
                secondScore = bestScore;
                bestMode = entry.getKey();
                bestScore = score;
            } else if (score > secondScore) {
                secondMode = entry.getKey();
                secondScore = score;
            }
        }

        if (bestScore < 1.2) {
            bestMode = ReasoningMode.GENERAL_HEALTH;
        }

        return switch (bestMode) {
            case MEDICATION_SAFETY -> buildPlan(bestMode, medicationSteps(), bestScore, secondScore, profile.totalScore(), medicationReasons(profile), profile);
            case REPORT_INTERPRETATION -> buildPlan(bestMode, reportSteps(), bestScore, secondScore, profile.totalScore(), reportReasons(profile), profile);
            case DEPARTMENT_GUIDANCE -> buildPlan(bestMode, departmentSteps(), bestScore, secondScore, profile.totalScore(), departmentReasons(profile), profile);
            case SYMPTOM_TRIAGE -> buildPlan(bestMode, symptomSteps(), bestScore, secondScore, profile.totalScore(), symptomReasons(profile), profile);
            default -> buildPlan(ReasoningMode.GENERAL_HEALTH, generalSteps(), Math.max(bestScore, profile.generalScore()), secondScore, profile.totalScore(), generalReasons(profile), profile);
        };
    }

    private ReasoningPlan buildPlan(ReasoningMode mode,
                                    List<String> steps,
                                    double topScore,
                                    double secondScore,
                                    double totalScore,
                                    List<String> reasons,
                                    SignalProfile profile) {
        double ratio = topScore <= 0 ? 0 : topScore / Math.max(totalScore, topScore + 0.01);
        double gap = topScore <= 0 ? 0 : Math.max(0, topScore - Math.max(secondScore, 0)) / Math.max(topScore, 1.0);
        double confidence = clamp(0.45 + ratio * 0.28 + gap * 0.25 + (profile.hasEmergencyGate() ? 0.08 : 0), 0.45, 0.98);

        ReasoningMode secondaryMode = null;
        if (secondScore > 0 && topScore > 0 && secondScore / topScore >= 0.7 && secondScore >= 1.5) {
            secondaryMode = profile.modeFromScore(secondScore, mode);
        }

        return new ReasoningPlan(mode, labelOf(mode), steps, round2(topScore), round2(confidence), reasons, secondaryMode);
    }

    private SignalProfile extractSignals(String rawMessage, String normalized) {
        SignalProfile profile = new SignalProfile();
        String raw = rawMessage == null ? "" : rawMessage;

        profile.riskTerms.addAll(findMatches(normalized, "胸痛", "呼吸困难", "口唇发紫", "昏迷", "抽搐", "意识不清", "黑便", "呕血", "大出血", "严重过敏", "休克", "自杀", "轻生", "言语不清", "肢体无力"));
        profile.severityTerms.addAll(findMatches(normalized, "突然", "剧烈", "持续加重", "越来越重", "高热不退", "精神差", "无法缓解"));
        profile.medicationTerms.addAll(findMatches(normalized, "药", "药物", "抗生素", "布洛芬", "对乙酰氨基酚", "阿莫西林", "头孢", "剂量", "副作用", "不良反应", "退烧药", "降压药", "止痛药"));
        profile.medicationIntentTerms.addAll(findMatches(normalized, "能一起吃", "一起吃", "同吃", "联用", "冲突", "剂量", "怎么吃", "饭前吃", "饭后吃", "副作用", "禁忌"));
        profile.reportTerms.addAll(findMatches(normalized, "报告", "体检", "化验", "检查结果", "指标", "参考值", "偏高", "偏低", "阳性", "阴性", "血常规", "血压", "血糖", "片子", "b超", "ct", "核磁"));
        profile.departmentTerms.addAll(findMatches(normalized, "挂什么科", "看什么科", "去什么科", "挂号", "要不要去医院", "需不需要去医院", "去医院", "急诊", "门诊"));
        profile.symptomTerms.addAll(findMatches(normalized, "发热", "发烧", "咳嗽", "头痛", "腹痛", "恶心", "呕吐", "腹泻", "头晕", "喉咙痛", "鼻塞", "流鼻涕", "胸闷", "心慌", "乏力", "失眠", "皮疹"));
        profile.intentTerms.addAll(findMatches(normalized, "怎么办", "为什么", "什么原因", "怎么处理", "严重吗", "正常吗", "要紧吗", "如何缓解", "怎么回事"));
        profile.durationTerms.addAll(findMatches(normalized, "一天", "两天", "三天", "一周", "一个月", "最近", "反复", "持续"));
        profile.healthTerms.addAll(findMatches(normalized, "饮食", "运动", "睡眠", "预防", "康复", "调理", "护理"));

        Matcher bpMatcher = BP_READING.matcher(raw);
        if (bpMatcher.find()) {
            int systolic = Integer.parseInt(bpMatcher.group(1));
            int diastolic = Integer.parseInt(bpMatcher.group(2));
            profile.numericSignals.add("血压读数:" + systolic + "/" + diastolic);
            if (systolic > 180 || diastolic > 120) {
                profile.emergencyFlags.add("高血压危象阈值");
            } else if (systolic >= 130 || diastolic >= 80) {
                profile.reportNumericFlags.add("血压数值异常");
            }
        }

        if (raw.matches(".*\\d+(\\.\\d+)?\\s*℃.*")) {
            profile.numericSignals.add("体温数值");
            profile.reportNumericFlags.add("体温数值");
        }

        profile.emergencyScore = profile.riskTerms.size() * 3.0
                + profile.severityTerms.size() * 1.2
                + profile.emergencyFlags.size() * 4.0;

        profile.medicationScore = profile.medicationTerms.size() * 2.0
                + profile.medicationIntentTerms.size() * 1.8;

        profile.reportScore = profile.reportTerms.size() * 1.9
                + profile.reportNumericFlags.size() * 1.6
                + profile.numericSignals.size() * 0.8;

        profile.departmentScore = profile.departmentTerms.size() * 2.4
                + (normalized.contains("挂什么科") || normalized.contains("看什么科") ? 1.2 : 0)
                + (normalized.contains("去医院") ? 0.6 : 0);

        profile.symptomScore = profile.symptomTerms.size() * 1.5
                + profile.intentTerms.size() * 1.1
                + profile.durationTerms.size() * 0.6;

        profile.generalScore = 0.8 + profile.healthTerms.size() * 0.7;
        return profile;
    }

    private List<String> emergencySteps() {
        return List.of("先判断是否属于危险信号", "明确是否需要立即急诊", "只给简短、明确、可执行建议");
    }

    private List<String> medicationSteps() {
        return List.of("先判断药物是否对症", "说明能否自行使用或联用", "补充常见风险、禁忌和就医边界");
    }

    private List<String> reportSteps() {
        return List.of("先解释结果代表什么", "再说明危险程度", "最后给出复查或就医建议");
    }

    private List<String> departmentSteps() {
        return List.of("先判断是否需要线下就医", "再给出建议科室", "补充就诊前应观察或准备的信息");
    }

    private List<String> symptomSteps() {
        return List.of("先给可能情况的初步判断", "提示危险信号", "再给居家处理和就医建议");
    }

    private List<String> generalSteps() {
        return List.of("先判断用户问题属于哪类健康咨询", "不确定时明确说明信息不足", "给出保守、可执行建议");
    }

    private List<String> emergencyReasons(SignalProfile profile) {
        List<String> reasons = new ArrayList<>();
        if (!profile.riskTerms.isEmpty()) {
            reasons.add("命中高危信号: " + String.join("、", profile.riskTerms));
        }
        if (!profile.emergencyFlags.isEmpty()) {
            reasons.add("命中紧急阈值: " + String.join("、", profile.emergencyFlags));
        }
        if (!profile.severityTerms.isEmpty()) {
            reasons.add("出现加重/急性描述: " + String.join("、", profile.severityTerms));
        }
        return ensureReasons(reasons, "问题包含明显紧急就医风险");
    }

    private List<String> medicationReasons(SignalProfile profile) {
        List<String> reasons = new ArrayList<>();
        if (!profile.medicationTerms.isEmpty()) {
            reasons.add("命中药物相关词: " + String.join("、", profile.medicationTerms));
        }
        if (!profile.medicationIntentTerms.isEmpty()) {
            reasons.add("命中用药意图: " + String.join("、", profile.medicationIntentTerms));
        }
        return ensureReasons(reasons, "问题更偏向药物使用与安全性判断");
    }

    private List<String> reportReasons(SignalProfile profile) {
        List<String> reasons = new ArrayList<>();
        if (!profile.reportTerms.isEmpty()) {
            reasons.add("命中检查/指标词: " + String.join("、", profile.reportTerms));
        }
        if (!profile.numericSignals.isEmpty()) {
            reasons.add("检测到关键数值: " + String.join("、", profile.numericSignals));
        }
        return ensureReasons(reasons, "问题更偏向检查结果与指标解释");
    }

    private List<String> departmentReasons(SignalProfile profile) {
        List<String> reasons = new ArrayList<>();
        if (!profile.departmentTerms.isEmpty()) {
            reasons.add("命中就医分诊意图: " + String.join("、", profile.departmentTerms));
        }
        if (!profile.symptomTerms.isEmpty()) {
            reasons.add("伴随症状信息: " + String.join("、", profile.symptomTerms));
        }
        return ensureReasons(reasons, "问题更偏向是否就医和科室选择");
    }

    private List<String> symptomReasons(SignalProfile profile) {
        List<String> reasons = new ArrayList<>();
        if (!profile.symptomTerms.isEmpty()) {
            reasons.add("命中症状词: " + String.join("、", profile.symptomTerms));
        }
        if (!profile.intentTerms.isEmpty()) {
            reasons.add("命中处理/原因意图: " + String.join("、", profile.intentTerms));
        }
        return ensureReasons(reasons, "问题更偏向症状初筛与处置建议");
    }

    private List<String> generalReasons(SignalProfile profile) {
        List<String> reasons = new ArrayList<>();
        if (!profile.healthTerms.isEmpty()) {
            reasons.add("命中泛健康话题: " + String.join("、", profile.healthTerms));
        }
        reasons.add("未命中单一强特征类别，采用保守通用推理");
        return reasons;
    }

    private List<String> ensureReasons(List<String> reasons, String fallback) {
        if (reasons.isEmpty()) {
            reasons.add(fallback);
        }
        return reasons.stream().limit(3).toList();
    }

    private LinkedHashSet<String> findMatches(String text, String... keys) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String key : keys) {
            if (text.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    private String labelOf(ReasoningMode mode) {
        return switch (mode) {
            case EMERGENCY_ASSESSMENT -> "紧急风险评估";
            case MEDICATION_SAFETY -> "用药安全";
            case REPORT_INTERPRETATION -> "检查结果解读";
            case DEPARTMENT_GUIDANCE -> "就医分诊";
            case SYMPTOM_TRIAGE -> "症状分诊";
            case GENERAL_HEALTH -> "通用健康咨询";
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public enum ReasoningMode {
        EMERGENCY_ASSESSMENT,
        MEDICATION_SAFETY,
        REPORT_INTERPRETATION,
        DEPARTMENT_GUIDANCE,
        SYMPTOM_TRIAGE,
        GENERAL_HEALTH
    }

    public record ReasoningPlan(
            ReasoningMode mode,
            String label,
            List<String> steps,
            double score,
            double confidence,
            List<String> reasons,
            ReasoningMode secondaryMode
    ) {
        public String toPromptBlock() {
            StringBuilder builder = new StringBuilder("[自适应推理模式]\n");
            builder.append("- 模式: ").append(label).append(" (").append(mode.name()).append(")\n");
            builder.append("- 置信度: ").append(String.format(Locale.ROOT, "%.2f", confidence)).append("\n");
            if (!reasons.isEmpty()) {
                builder.append("- 判定依据:\n");
                int reasonIndex = 1;
                for (String reason : reasons) {
                    builder.append("  ").append(reasonIndex++).append(". ").append(reason).append("\n");
                }
            }
            if (secondaryMode != null) {
                builder.append("- 次级模式: ").append(secondaryMode.name()).append("\n");
            }
            builder.append("- 建议作答顺序:\n");
            int index = 1;
            for (String step : steps) {
                builder.append("  ").append(index++).append(". ").append(step).append("\n");
            }
            return builder.toString();
        }
    }

    private static class SignalProfile {
        private final LinkedHashSet<String> riskTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> severityTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> emergencyFlags = new LinkedHashSet<>();
        private final LinkedHashSet<String> medicationTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> medicationIntentTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> reportTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> reportNumericFlags = new LinkedHashSet<>();
        private final LinkedHashSet<String> departmentTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> symptomTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> intentTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> durationTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> healthTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> numericSignals = new LinkedHashSet<>();

        private double emergencyScore;
        private double medicationScore;
        private double reportScore;
        private double departmentScore;
        private double symptomScore;
        private double generalScore;

        boolean hasEmergencyGate() {
            return emergencyFlags.size() > 0 || riskTerms.size() > 0;
        }

        double totalScore() {
            return emergencyScore + medicationScore + reportScore + departmentScore + symptomScore + generalScore;
        }

        double emergencyScore() {
            return emergencyScore;
        }

        double medicationScore() {
            return medicationScore;
        }

        double reportScore() {
            return reportScore;
        }

        double departmentScore() {
            return departmentScore;
        }

        double symptomScore() {
            return symptomScore;
        }

        double generalScore() {
            return generalScore;
        }

        ReasoningMode modeFromScore(double score, ReasoningMode fallback) {
            if (Double.compare(score, medicationScore) == 0) return ReasoningMode.MEDICATION_SAFETY;
            if (Double.compare(score, reportScore) == 0) return ReasoningMode.REPORT_INTERPRETATION;
            if (Double.compare(score, departmentScore) == 0) return ReasoningMode.DEPARTMENT_GUIDANCE;
            if (Double.compare(score, symptomScore) == 0) return ReasoningMode.SYMPTOM_TRIAGE;
            if (Double.compare(score, generalScore) == 0) return ReasoningMode.GENERAL_HEALTH;
            return fallback;
        }
    }
}
