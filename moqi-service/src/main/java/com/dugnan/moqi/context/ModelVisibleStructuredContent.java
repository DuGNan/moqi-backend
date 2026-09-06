package com.dugnan.moqi.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

/**
 * @author dgn
 * @date 2026-09-04
 * @description 将持久化 JSON 资料转换为不暴露内部字段名和标识的模型可见中文。
 */
public final class ModelVisibleStructuredContent {

    private static final List<Map.Entry<String, String>> LABELS = List.of(
            Map.entry("chapterTask", "本章任务"),
            Map.entry("stateChange", "故事状态变化"),
            Map.entry("from", "此前状态"),
            Map.entry("to", "当前状态"),
            Map.entry("keyPush", "核心推进"),
            Map.entry("readerProgress", "读者体验"),
            Map.entry("payoff", "预期回报"),
            Map.entry("openQuestion", "保留问题"),
            Map.entry("writingBoundaries", "写作边界"),
            Map.entry("decisions", "作者已确认的决定"),
            Map.entry("title", "名称"),
            Map.entry("candidateSummary", "确认内容"),
            Map.entry("sourceQuotes", "作者原话"),
            Map.entry("quote", "作者原话"),
            Map.entry("prompt", "对应问题"),
            Map.entry("goal", "目标"),
            Map.entry("beats", "必须事件"),
            Map.entry("summary", "事件说明"),
            Map.entry("scenes", "场景安排"),
            Map.entry("endingHook", "结尾悬念"),
            Map.entry("constraints", "约束"),
            Map.entry("endingState", "结尾状态"),
            Map.entry("coreConflict", "核心冲突"),
            Map.entry("openingState", "开场状态"),
            Map.entry("turningPoint", "转折点"),
            Map.entry("chapterPurpose", "章节作用"),
            Map.entry("viewpointCharacter", "视角人物"),
            Map.entry("name", "名称"),
            Map.entry("timeAnchor", "时间"),
            Map.entry("location", "地点"),
            Map.entry("conflict", "冲突"),
            Map.entry("emotion", "情绪变化"),
            Map.entry("pacing", "节奏"),
            Map.entry("participants", "参与人物"),
            Map.entry("requiredSettings", "相关设定"),
            Map.entry("foreshadowingActions", "伏笔安排"),
            Map.entry("action", "处理方式"),
            Map.entry("description", "说明"),
            Map.entry("expectedOutcome", "预期结果"),
            Map.entry("readerMustKnow", "读者必须知道"),
            Map.entry("causalPreconditions", "因果前提"),
            Map.entry("locationTransition", "地点衔接"),
            Map.entry("stateChanges", "状态变化"),
            Map.entry("continuityConstraints", "连续性约束"),
            Map.entry("narrativeWeight", "叙事权重"),
            Map.entry("optionalExpression", "可自由表达"),
            Map.entry("doNotInvent", "不得编造"));
    private static final Set<String> INTERNAL_FIELDS = Set.of(
            "id", "key", "beatKey", "sceneKey", "sequence", "status", "required",
            "schemaVersion", "contentSchemaVersion", "sourceMessageIds", "messageId",
            "settingEntryId", "foreshadowingItemId", "outlineBeatKeys");

    private ModelVisibleStructuredContent() {
    }

    /**
     * JSON 内容转为带中文语义标签的纯文本；历史自由文本保持原样。
     *
     * @param objectMapper JSON 映射器
     * @param content 持久化资料
     * @return 模型可见资料
     */
    public static String render(ObjectMapper objectMapper, String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            if (!root.isContainerNode()) {
                return content;
            }
            List<String> lines = new ArrayList<>();
            collect(root, null, lines);
            return lines.isEmpty() ? "没有可用的自然语言资料。" : String.join("\n", lines);
        } catch (JsonProcessingException exception) {
            return content;
        }
    }

    private static void collect(JsonNode node, String label, List<String> lines) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isValueNode()) {
            String value = node.asText();
            if (StringUtils.hasText(value)) {
                lines.add("- " + (label == null ? "补充内容" : label) + "：" + value);
            }
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(child -> collect(child, label, lines));
            return;
        }
        node.fields().forEachRemaining(field -> {
            if (!internal(field.getKey())) {
                collect(field.getValue(), label(field.getKey()), lines);
            }
        });
    }

    private static boolean internal(String field) {
        return INTERNAL_FIELDS.contains(field) || field.endsWith("Id") || field.endsWith("Ids")
                || field.endsWith("Hash") || field.endsWith("Version");
    }

    private static String label(String field) {
        return LABELS.stream().filter(entry -> entry.getKey().equals(field))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
}
