package com.dugnan.moqi.chapter.brief;

import java.util.List;

import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief.EntityExplanation;
import com.dugnan.moqi.chapter.brief.ChapterGenerationBrief.SourceRef;
import com.dugnan.moqi.chapter.outline.OutlineCandidateContent;
import com.dugnan.moqi.planning.PlanningModels.ScenePlanView;

/**
 * @author dgn
 * @date 2026-08-14
 * @description 承载经过作品归属、状态与版本校验的章节正文生成说明来源。
 */
public record ChapterGenerationBriefSource(
        Long workId,
        String workTitle,
        Long chapterId,
        Integer chapterNo,
        String chapterTitle,
        ConsensusSource consensus,
        OutlineCandidateContent outline,
        List<ScenePlanView> scenes,
        String previousChapterEnding,
        String previousChapterSummary,
        List<String> previousKeyEvents,
        List<EntityExplanation> entityExplanations,
        List<SourceRef> sourceRefs) {

    public ChapterGenerationBriefSource {
        scenes = scenes == null ? List.of() : List.copyOf(scenes);
        previousKeyEvents = previousKeyEvents == null ? List.of() : List.copyOf(previousKeyEvents);
        entityExplanations = entityExplanations == null ? List.of() : List.copyOf(entityExplanations);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }

    /** 只保留已确认结论的章节共识投影。 */
    public record ConsensusSource(
            String chapterTask,
            String openingState,
            String endingState,
            String keyPush,
            String readerPayoff,
            String openQuestion,
            List<String> writingBoundaries,
            List<String> confirmedDecisions) {

        public ConsensusSource {
            writingBoundaries = writingBoundaries == null ? List.of() : List.copyOf(writingBoundaries);
            confirmedDecisions = confirmedDecisions == null ? List.of() : List.copyOf(confirmedDecisions);
        }
    }
}
