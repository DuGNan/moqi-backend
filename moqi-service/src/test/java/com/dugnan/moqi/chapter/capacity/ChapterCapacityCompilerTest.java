package com.dugnan.moqi.chapter.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanContent;
import com.dugnan.moqi.planning.PlanningModels.ChapterPlanView;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证章节容量基线的四类边界、稳定指纹与不可省略因果提取。
 */
class ChapterCapacityCompilerTest {

    private final ChapterCapacityCompiler compiler = new ChapterCapacityCompiler(new ObjectMapper());

    @Test
    void classifiesDenseFittingAndThinTargetsWithoutDividingBySceneCount() {
        var dense = compiler.compile(plan(), brief("brief-a"), 700);
        var fitting = compiler.compile(plan(), brief("brief-a"), 1500);
        var thin = compiler.compile(plan(), brief("brief-a"), 5000);

        assertThat(dense.fallback().status()).isEqualTo("too_dense");
        assertThat(dense.fallback().availableActions()).contains("continue_long_chapter", "split_chapter");
        assertThat(fitting.fallback().status()).isEqualTo("fits");
        assertThat(thin.fallback().status()).isEqualTo("too_thin");
        assertThat(dense.fallback().eventWeights())
                .extracting(item -> item.eventKey() + ":" + item.weight())
                .containsExactly("scene-1:high", "scene-2:medium", "scene-3:low");
        assertThat(dense.fallback().suggestedMinimumWordCount()).isEqualTo(1160);
        assertThat(dense.fallback().suggestedMaximumWordCount()).isEqualTo(1566);
        assertThat(dense.fallback().nonCompressibleCausalNodes())
                .contains("scene-1：必须先找到钥匙", "scene-2：主角受伤");
    }

    @Test
    void keepsFingerprintStableButChangesItWithAnyFrozenInput() {
        var first = compiler.compile(plan(), brief("brief-a"), 1500);
        var repeated = compiler.compile(plan(), brief("brief-a"), 1500);
        var changedBrief = compiler.compile(plan(), brief("brief-b"), 1500);
        var changedTarget = compiler.compile(plan(), brief("brief-a"), 1600);

        assertThat(repeated.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(changedBrief.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(changedTarget.fingerprint()).isNotEqualTo(first.fingerprint());
    }

    private ChapterPlanView plan() {
        ScenePlanContent first = new ScenePlanContent("scene-1", 1, "潜入", null, "夜", null,
                "找到钥匙", "守卫巡逻", "紧张", "快", List.of(), List.of(), List.of(), "取得钥匙",
                "planned", List.of(), List.of(), List.of("必须先找到钥匙"), "", List.of(), List.of(),
                "core", List.of(), List.of());
        ScenePlanContent second = new ScenePlanContent("scene-2", 2, "逃离", null, "夜", null,
                "离开仓库", "出口封锁", "急迫", "快", List.of(), List.of(), List.of(), "成功逃离",
                "planned", List.of(), List.of(), List.of(), "", List.of("主角受伤"), List.of(),
                "supporting", List.of("环境描写"), List.of());
        ScenePlanContent third = new ScenePlanContent("scene-3", 3, "转场", null, "清晨", null,
                "抵达安全屋", "时间紧迫", "缓和", "慢", List.of(), List.of(), List.of(), "准备下一步",
                "planned", List.of(), List.of(), List.of(), "", List.of(), List.of(),
                "transition", List.of("环境描写"), List.of());
        return new ChapterPlanView(31L, 12L, 4, "published", null, null, 21L, 3,
                null, null, new ChapterPlanContent("", "", ""),
                List.of(new ScenePlanView(41L, "scene-1", 1, 2, first),
                        new ScenePlanView(42L, "scene-2", 2, 2, second),
                        new ScenePlanView(43L, "scene-3", 3, 2, third)),
                2, "not_required", null, null, List.of(), "current", List.of(),
                null, null, 5, null, null);
    }

    private ChapterGenerationBrief brief(String fingerprint) {
        return new ChapterGenerationBrief(1, "chapter-generation-brief-v1", 2L, "作品", 12L, 1,
                "章节", "任务", "目标", "冲突", List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), fingerprint,
                LocalDateTime.of(2026, 8, 15, 0, 0), "# Chapter Generation Brief");
    }
}
