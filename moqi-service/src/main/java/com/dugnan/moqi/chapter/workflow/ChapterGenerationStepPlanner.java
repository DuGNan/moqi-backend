package com.dugnan.moqi.chapter.workflow;

import org.springframework.stereotype.Component;

import com.dugnan.moqi.chapter.entity.ChapterGenerationSceneEntity;

/**
 * @author dgn
 * @date 2026-08-09
 * @description 根据场景顺序确定章节正文生成的下一执行步骤。
 */
@Component
public class ChapterGenerationStepPlanner {

    public static final String GENERATE_PREFIX = "generate_scene:";
    public static final String GENERATE_CHAPTER = "generate_chapter";
    public static final String COHERE = "cohere_chapter";
    public static final String FINALIZE = "finalize_generation";

    private final ChapterGenerationStateStore stateStore;

    public ChapterGenerationStepPlanner(ChapterGenerationStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public String nextStep(Long generationId, int sequenceNo) {
        if (ChapterGenerationStateStore.ASSEMBLY_WHOLE_CHAPTER_ONCE.equals(
                stateStore.requireGeneration(generationId).getContentAssemblyMode())) {
            return GENERATE_CHAPTER;
        }
        ChapterGenerationSceneEntity next = stateStore.nextScene(generationId, sequenceNo);
        if (next != null) {
            return GENERATE_PREFIX + next.getSceneKey();
        }
        return COHERE;
    }
}
