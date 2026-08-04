package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.CandidateList;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.CandidateView;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveCandidateRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusScopeCandidateModels.ResolveScopeRequest;

/**
 * @author dgn
 * @date 2026-08-04
 * @description 定义共识作用域候选的查询和人工状态流转能力。
 */
public interface ChapterConsensusScopeCandidateService {
    CandidateList list(Long workId, Long chapterId, String status);
    CandidateView resolveUnknownScope(Long candidateId, ResolveScopeRequest request);
    CandidateView confirm(Long candidateId, ResolveCandidateRequest request);
    CandidateView reject(Long candidateId, ResolveCandidateRequest request);
}
