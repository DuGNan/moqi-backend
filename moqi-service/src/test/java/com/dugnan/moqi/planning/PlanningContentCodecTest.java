package com.dugnan.moqi.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.planning.PlanningModels.ForeshadowingAction;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;

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

    private ScenePlanContent scene(String key, int sequence, String action, Long itemId) {
        return new ScenePlanContent(key, sequence, "相遇", null, "当夜", null, "试探", "身份隐瞒", "紧张", "中速",
                List.of(), List.of(), List.of(new ForeshadowingAction(action, itemId, "留下线索")), "达成试探", "planned");
    }
}
