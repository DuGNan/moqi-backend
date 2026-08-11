package com.dugnan.moqi.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.PlanningModels.ForeshadowingAction;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证场景规划结构契约和伏笔引用约束。
 */
class PlanningContentCodecTest {
    private final PlanningContentCodec codec = new PlanningContentCodec();

    @Test
    void acceptsOrderedScenePlan() {
        assertThat(codec.scenes(List.of(scene("scene-1", 1, "seed", null)))).hasSize(1);
    }

    @Test
    void rejectsAdvanceWithoutExistingForeshadowing() {
        assertThatThrownBy(() -> codec.scenes(List.of(scene("scene-1", 1, "advance", null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsNonContinuousSceneSequences() {
        assertThatThrownBy(() -> codec.scenes(List.of(
                scene("scene-1", 1, "seed", null),
                scene("scene-3", 3, "seed", null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("连续");
    }

    @Test
    void rejectsUnspecifiedNarrativeWeightForV2Writes() {
        ScenePlanContent legacy = new ScenePlanContent(
                "scene-1", 1, "相遇", null, "当夜", null, "试探", "身份隐瞒", "紧张", "中速",
                List.of(), List.of(), List.of(), "达成试探", "planned", List.of("beat-1"));

        assertThatThrownBy(() -> codec.scenes(List.of(legacy)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("narrativeWeight");
    }

    @Test
    void rejectsSemanticListsOverTwentyItems() {
        ScenePlanContent scene = scene("scene-1", 1, "seed", null);
        List<String> tooMany = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(index -> "状态" + index).toList();
        ScenePlanContent invalid = new ScenePlanContent(
                scene.sceneKey(), scene.sequence(), scene.title(), scene.viewpointCharacter(), scene.timeAnchor(),
                scene.location(), scene.goal(), scene.conflict(), scene.emotion(), scene.pacing(), scene.participants(),
                scene.requiredSettings(), scene.foreshadowingActions(), scene.expectedOutcome(), scene.status(),
                scene.outlineBeatKeys(), scene.readerMustKnow(), scene.causalPreconditions(),
                scene.locationTransition(),
                tooMany, scene.continuityConstraints(), scene.narrativeWeight(), scene.optionalExpression(),
                scene.doNotInvent());

        assertThatThrownBy(() -> codec.scenes(List.of(invalid)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("stateChanges");
    }

    @Test
    void readsV1JsonWithExplicitCompatibilityDefaults() throws Exception {
        ScenePlanContent legacy = new ObjectMapper().readValue("""
                {"sceneKey":"legacy","sequence":1,"title":"旧场景","timeAnchor":"当夜",
                "goal":"前进","conflict":"受阻","emotion":"紧张","pacing":"中速",
                "participants":[],"requiredSettings":[],"foreshadowingActions":[],
                "expectedOutcome":"抵达","status":"planned","outlineBeatKeys":[]}
                """, ScenePlanContent.class);

        assertThat(legacy.readerMustKnow()).isEmpty();
        assertThat(legacy.locationTransition()).isEmpty();
        assertThat(legacy.narrativeWeight()).isEqualTo("unspecified");
        assertThat(legacy.doNotInvent()).isEmpty();
    }

    @Test
    void exposesContentSchemaVersionInSceneView() throws Exception {
        ScenePlanContent content = scene("scene-1", 1, "seed", null);

        String json = new ObjectMapper().writeValueAsString(new ScenePlanView(11L, "scene-1", 1, 2, content));

        assertThat(json).contains("\"contentSchemaVersion\":2", "\"narrativeWeight\":\"supporting\"");
    }

    private ScenePlanContent scene(String key, int sequence, String action, Long itemId) {
        return new ScenePlanContent(key, sequence, "相遇", null, "当夜", null, "试探", "身份隐瞒", "紧张", "中速",
                List.of(), List.of(), List.of(new ForeshadowingAction(action, itemId, "留下线索")), "达成试探", "planned",
                List.of("beat-1"), List.of(), List.of(), "", List.of(), List.of(), "supporting", List.of(), List.of());
    }
}
