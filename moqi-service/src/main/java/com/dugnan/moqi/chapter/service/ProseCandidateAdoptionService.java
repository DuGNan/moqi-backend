package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.AdoptProseCandidateRequest;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.AdoptionReadiness;
import com.dugnan.moqi.chapter.dto.ProseWorkspaceModels.ProseCandidateAdoptionView;
import com.dugnan.moqi.chapter.entity.ChapterProseCandidateEntity;

/**
 * @author dgn
 * @date 2026-08-23
 * @description 执行正文候选唯一采纳门禁并恢复已发布章节的影响分析启动。
 */
public interface ProseCandidateAdoptionService {
    /**
     * 计算候选当前的采纳方式、质量报告和公开阻塞原因。
     *
     * @param candidate 正文候选
     * @return 当前采纳门禁摘要
     */
    AdoptionReadiness readiness(ChapterProseCandidateEntity candidate);

    /**
     * 通过唯一入口执行显式候选采纳。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param request 显式采纳请求
     * @return 冻结的采纳结果
     */
    ProseCandidateAdoptionView adopt(
            Long chapterId,
            Long candidateId,
            AdoptProseCandidateRequest request);

    /** 恢复已提交但未成功启动影响分析的采纳记录。 */
    void resumePendingImpacts();
}
