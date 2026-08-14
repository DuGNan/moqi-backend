package com.dugnan.moqi.chapter.brief;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief.EntityExplanation;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief.SourceRef;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBriefSource.ConsensusSource;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Beat;
import com.dugnan.moqi.planning.PlanningModels.PlanReference;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 验证章节正文生成说明的确定性、语义完整性与自然语言输出。
 */
class ChapterGenerationBriefCompilerTest {

    private final ChapterGenerationBriefCompiler compiler = new ChapterGenerationBriefCompiler(
            new ChapterGenerationBriefFingerprint(new ObjectMapper()), new ChapterGenerationBriefRenderer());

    @Test
    void keepsContentAndFingerprintStableWhenOnlyCompileTimeChanges() {
        ChapterGenerationBriefSource source = source("阻止舱门关闭");

        ChapterGenerationBrief first = compiler.compile(source, LocalDateTime.of(2026, 8, 14, 10, 0));
        ChapterGenerationBrief second = compiler.compile(source, LocalDateTime.of(2026, 8, 14, 11, 0));

        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.content()).isEqualTo(second.content())
                .contains("P0｜章节身份与任务", "读者必须知道", "人物目标与认知边界", "禁止发明")
                .contains("林风（人物）：舰桥值班员，只知道备用电源失效")
                .doesNotContain("\"sceneKey\"", "{");
        assertThat(first.compiledAt()).isNotEqualTo(second.compiledAt());
    }

    @Test
    void changesFingerprintWhenAConfirmedSourceChanges() {
        ChapterGenerationBrief first = compiler.compile(source("阻止舱门关闭"));
        ChapterGenerationBrief changed = compiler.compile(source("打开舱门撤离"));

        assertThat(first.fingerprint()).isNotEqualTo(changed.fingerprint());
        assertThat(changed.content()).contains("打开舱门撤离");
    }

    private ChapterGenerationBriefSource source(String goal) {
        ConsensusSource consensus = new ConsensusSource(
                "揭示敌方已潜入", "警报刚刚响起", "主角取得控制权", "确认备用电源被破坏",
                "确认内鬼存在", "内鬼身份仍未知", List.of("不得新增援军"), List.of("先救伤员"));
        OutlineCandidateContent outline = new OutlineCandidateContent(
                2, "迫使主角改变判断", "舰桥断电", goal, "舱门锁死", List.of(new Beat("beat-1", "夺回控制权")),
                "发现破坏痕迹", "主角取得控制权", "内鬼留下假线索", List.of("不揭示内鬼身份"));
        ScenePlanContent scene = new ScenePlanContent(
                "alarm", 1, "警报", new PlanReference(101L, "林风"), "深夜",
                new PlanReference(102L, "舰桥"), goal, "舱门锁死", "紧张", "快速",
                List.of(new PlanReference(101L, "林风")), List.of(), List.of(), "取得局部控制权", "planned",
                List.of("beat-1"), List.of("敌方已经潜入"), List.of("备用电源失效"), "从舰桥进入机舱",
                List.of("主角取得控制权"), List.of("林风不知道内鬼身份"), "core", List.of("动作细节"),
                List.of("不得新增援军"));
        return new ChapterGenerationBriefSource(
                1L, "长夜号", 2L, 2, "失控舰桥", consensus, outline,
                List.of(new ScenePlanView(301L, "alarm", 1, 2, scene)), "舱门在身后锁死。", "警报响起",
                List.of("备用电源被切断"),
                List.of(new EntityExplanation(101L, "人物", "林风", "舰桥值班员，只知道备用电源失效")),
                List.of(new SourceRef("CHAPTER_OUTLINE", "201", "3:1")));
    }
}
