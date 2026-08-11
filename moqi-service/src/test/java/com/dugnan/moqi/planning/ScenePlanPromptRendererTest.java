package com.dugnan.moqi.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;

/**
 * @author dgn
 * @date 2026-08-11
 * @description 验证场景规划以自然语言而非原始 JSON 进入正文模型。
 */
class ScenePlanPromptRendererTest {
    private final ScenePlanPromptRenderer renderer = new ScenePlanPromptRenderer();

    @Test
    void rendersV2SemanticsWithoutRawJson() {
        ScenePlanContent scene = new ScenePlanContent(
                "alarm", 1, "警报响起", null, "深夜", null, "关闭反应堆", "敌方切断供能", "紧张", "快速",
                List.of(), List.of(), List.of(), "反应堆恢复稳定", "planned", List.of("beat-1"),
                List.of("敌方已经潜入"), List.of("备用电源失效"), "从舰桥冲向机舱",
                List.of("主角取得控制权"), List.of("左臂仍有伤"), "core",
                List.of("可自行设计警报声"), List.of("不得新增援军"));

        String rendered = renderer.render(scene);

        assertThat(rendered).contains("场景 1｜警报响起", "叙事权重：核心", "因果前置：备用电源失效",
                        "读者必须知道：敌方已经潜入", "禁止发明：不得新增援军")
                .doesNotContain("{", "\"sceneKey\"");
    }

    @Test
    void marksMissingV1SemanticsWithoutInventingFacts() {
        ScenePlanContent legacy = new ScenePlanContent(
                "legacy", 1, "旧场景", null, "当夜", null, "前进", "受阻", "紧张", "中速",
                List.of(), List.of(), List.of(), "抵达", "planned", List.of());

        assertThat(renderer.render(legacy)).contains("叙事权重：未标注", "状态变化：未标注", "禁止发明：未标注");
    }
}
