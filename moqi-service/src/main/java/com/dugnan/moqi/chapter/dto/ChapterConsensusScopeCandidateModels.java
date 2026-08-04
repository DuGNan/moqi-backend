package com.dugnan.moqi.chapter.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 定义章节共识作用域候选的稳定接口模型。
 */
public final class ChapterConsensusScopeCandidateModels {
    private ChapterConsensusScopeCandidateModels() { }
    public record CandidateView(Long id, Long workId, Long chapterId, String scope, String candidateStatus,
            String candidateContentJson, BigDecimal confidence, Integer version, LocalDateTime gmtModified) { }
    public record CandidateList(Long workId, List<CandidateView> candidates) { }
    public record ResolveScopeRequest(Integer baseVersion, String scope) { }
    public record ResolveCandidateRequest(Integer baseVersion) { }
}
