package com.dugnan.moqi.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.knowledge.entity.StoryKnowledgeExtractionBatchEntity;
import com.dugnan.moqi.knowledge.mapper.StoryKnowledgeExtractionBatchMapper;

/**
 * 在调用方事务回滚时仍持久化知识提取来源过期状态。
 *
 * @author dgn
 * @date 2026-08-05
 */
@Component
public class KnowledgeExtractionStaleMarker {

    private final StoryKnowledgeExtractionBatchMapper batchMapper;

    public KnowledgeExtractionStaleMarker(StoryKnowledgeExtractionBatchMapper batchMapper) {
        this.batchMapper = batchMapper;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = RuntimeException.class)
    public void mark(Long batchId) {
        batchMapper.update(null, new UpdateWrapper<StoryKnowledgeExtractionBatchEntity>()
                .eq("id", batchId)
                .in("batch_status", "queued", "running", "ready")
                .set("batch_status", "stale")
                .set("error_code", ErrorCode.KNOWLEDGE_EXTRACTION_STALE.name())
                .setSql("version = version + 1"));
    }
}
