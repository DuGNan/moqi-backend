package com.dugnan.moqi.planning;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.planning.PlanningModels.ForeshadowingAction;
import com.dugnan.moqi.planning.PlanningModels.PlanReference;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;

/**
 * @author dgn
 * @date 2026-08-11
 * @description 将版本化场景规划渲染为正文模型可读的自然语言约束。
 */
@Component
public class ScenePlanPromptRenderer {

    public String render(ScenePlanView scene) {
        return render(scene.content());
    }

    public String render(ScenePlanContent scene) {
        StringBuilder text = new StringBuilder();
        text.append("场景 ").append(scene.sequence()).append("｜").append(scene.title())
                .append("（").append(scene.sceneKey()).append("）\n");
        append(text, "状态", scene.status());
        append(text, "叙事权重", narrativeWeight(scene.narrativeWeight()));
        append(text, "时间锚点", scene.timeAnchor());
        append(text, "地点", reference(scene.location()));
        append(text, "地点承接", marked(scene.locationTransition()));
        append(text, "视角人物", reference(scene.viewpointCharacter()));
        appendList(text, "参与人物", scene.participants(), this::reference, "无");
        append(text, "场景目标", scene.goal());
        append(text, "冲突", scene.conflict());
        append(text, "预期结果", scene.expectedOutcome());
        append(text, "情绪", scene.emotion());
        append(text, "节奏", scene.pacing());
        appendList(text, "因果前置", scene.causalPreconditions(), Function.identity(), "未标注");
        appendList(text, "读者必须知道", scene.readerMustKnow(), Function.identity(), "未标注");
        appendList(text, "状态变化", scene.stateChanges(), Function.identity(), "未标注");
        appendList(text, "连续性约束", scene.continuityConstraints(), Function.identity(), "未标注");
        appendList(text, "可自由发挥", scene.optionalExpression(), Function.identity(), "未提供");
        appendList(text, "禁止发明", scene.doNotInvent(), Function.identity(), "未标注");
        appendList(text, "所需设定", scene.requiredSettings(), this::reference, "无");
        appendList(text, "伏笔动作", scene.foreshadowingActions(), this::foreshadowing, "无");
        appendList(text, "章纲节拍", scene.outlineBeatKeys(), Function.identity(), "未标注");
        return text.toString().stripTrailing();
    }

    public String renderRoute(List<ScenePlanView> scenes) {
        return scenes.stream().map(this::render).collect(Collectors.joining("\n\n"));
    }

    private void append(StringBuilder text, String label, String value) {
        text.append("- ").append(label).append("：").append(marked(value)).append('\n');
    }

    private <T> void appendList(StringBuilder text, String label, List<T> values,
            Function<T, String> formatter, String emptyText) {
        String rendered = values == null || values.isEmpty()
                ? emptyText
                : values.stream().map(formatter).collect(Collectors.joining("；"));
        append(text, label, rendered);
    }

    private String reference(PlanReference reference) {
        if (reference == null) {
            return "未标注";
        }
        String name = StringUtils.hasText(reference.name()) ? reference.name().trim() : "未命名设定";
        return reference.settingEntryId() == null ? name : name + "（设定#" + reference.settingEntryId() + "）";
    }

    private String foreshadowing(ForeshadowingAction action) {
        String reference = action.foreshadowingItemId() == null
                ? "新伏笔候选" : "伏笔#" + action.foreshadowingItemId();
        return action.action() + " " + reference + "：" + action.description();
    }

    private String narrativeWeight(String value) {
        return switch (value == null ? "unspecified" : value) {
            case "core" -> "核心";
            case "supporting" -> "支撑";
            case "transition" -> "过渡";
            default -> "未标注";
        };
    }

    private String marked(String value) {
        return StringUtils.hasText(value) ? value.trim() : "未标注";
    }
}
