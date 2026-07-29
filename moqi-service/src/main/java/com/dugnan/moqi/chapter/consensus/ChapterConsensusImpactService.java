package com.dugnan.moqi.chapter.consensus;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ConsensusImpact;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.DimensionImpact;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 使用保守确定性规则判断大纲是否显式承接核心章节共识。
 */
@Service
public class ChapterConsensusImpactService {

    private static final String STATUS_PRESERVED = "preserved";

    private static final String STATUS_POSSIBLY_CHANGED = "possibly_changed";

    private final ChapterConsensusCodec codec;

    /**
     * 创建共识影响判断服务。
     *
     * @param codec 共识编解码器
     */
    public ChapterConsensusImpactService(ChapterConsensusCodec codec) {
        this.codec = codec;
    }

    /**
     * 判断大纲对绑定 Brief 的承接情况。
     *
     * @param briefContent Brief 内容
     * @param outlineContent 大纲内容
     * @return 三个核心维度的保守判断
     */
    public ConsensusImpact assess(String briefContent, String outlineContent) {
        ChapterConsensusDocument document = codec.read(briefContent);
        if (document.consensus() == null) {
            DimensionImpact unknown = changed("历史文本 Brief 无法进行结构化比对，需人工复核");
            return new ConsensusImpact(unknown, unknown, unknown);
        }
        ChapterConsensusContentV1 consensus = document.consensus();
        DimensionImpact chapterTask = contains(outlineContent, consensus.chapterTask())
                ? preserved()
                : changed("大纲未显式承接本章任务，需复核");
        boolean preservesStateChange = contains(outlineContent, consensus.stateChange().from())
                && contains(outlineContent, consensus.stateChange().to());
        DimensionImpact stateChange = preservesStateChange
                ? preserved()
                : changed("大纲未同时体现章节前后状态，需复核");
        boolean preservesReaderProgress = contains(outlineContent, consensus.readerProgress().payoff())
                && contains(outlineContent, consensus.readerProgress().openQuestion());
        DimensionImpact readerProgress = preservesReaderProgress
                ? preserved()
                : changed("大纲未同时体现阅读回报与开放问题，需复核");
        return new ConsensusImpact(chapterTask, stateChange, readerProgress);
    }

    /**
     * 判断大纲是否显式包含非空共识文本。
     *
     * @param outlineContent 大纲内容
     * @param consensusText 待匹配的共识文本
     * @return 是否完整包含共识文本
     */
    private boolean contains(String outlineContent, String consensusText) {
        return StringUtils.hasText(outlineContent)
                && StringUtils.hasText(consensusText)
                && outlineContent.contains(consensusText.trim());
    }

    /**
     * 创建已承接结果。
     *
     * @return 已承接的维度判断
     */
    private DimensionImpact preserved() {
        return new DimensionImpact(STATUS_PRESERVED, "");
    }

    /**
     * 创建可能变化结果。
     *
     * @param reason 判断依据
     * @return 可能变化的维度判断
     */
    private DimensionImpact changed(String reason) {
        return new DimensionImpact(STATUS_POSSIBLY_CHANGED, reason);
    }
}
