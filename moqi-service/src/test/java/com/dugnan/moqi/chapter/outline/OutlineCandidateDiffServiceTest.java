package com.dugnan.moqi.chapter.outline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dugnan.moqi.chapter.outline.OutlineCandidateContent.Scene;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 验证大纲候选按场景稳定 ID 计算增删改移差异。
 */
class OutlineCandidateDiffServiceTest {

    /**
     * 验证目标、冲突、约束及场景增删改移可同时表达。
     */
    @Test
    void reportsAddedRemovedModifiedAndMovedScenes() {
        OutlineCandidateContent base = new OutlineCandidateContent("旧目标", "旧冲突", List.of(
                scene("a", "甲", "内容甲"), scene("b", "乙", "内容乙"), scene("c", "丙", "内容丙")), List.of("旧约束"));
        OutlineCandidateContent candidate = new OutlineCandidateContent("新目标", "新冲突", List.of(
                scene("b", "乙", "内容乙已改"), scene("a", "甲", "内容甲"), scene("d", "丁", "内容丁")), List.of("新约束"));

        var result = new OutlineCandidateDiffService().diff(base, candidate);

        assertThat(result.goal().changed()).isTrue();
        assertThat(result.coreConflict().changed()).isTrue();
        assertThat(result.constraints().changed()).isTrue();
        assertThat(result.scenes()).extracting(diff -> diff.sceneId() + ":" + diff.changeType())
                .containsExactly("a:moved", "b:modified", "c:removed", "d:added");
        assertThat(result.scenes().get(1).changedFields()).containsExactly("content");
    }

    private Scene scene(String id, String title, String content) {
        return new Scene(id, title, content, List.of("标签"));
    }
}
