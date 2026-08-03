package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.CreateOutlineCandidateRequest;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateConfirmation;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateCreated;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.OutlineCandidateDetail;
import com.dugnan.moqi.chapter.dto.OutlineCandidateModels.UpdateOutlineCandidateRequest;

/**
 * @author dgn
 * @date 2026-07-30
 * @description 定义大纲调整候选的创建、查询、放弃和确认能力。
 */
public interface OutlineCandidateService {

    /**
     * 创建大纲调整候选和关联 AI 任务。
     *
     * @param chapterId 章节 ID
     * @param request 候选创建请求
     * @return 已创建候选和任务引用
     */
    OutlineCandidateCreated create(Long chapterId, CreateOutlineCandidateRequest request);

    /**
     * 查询章节最新候选。
     *
     * @param chapterId 章节 ID
     * @return 最新候选，不存在时返回 null
     */
    OutlineCandidateDetail getLatest(Long chapterId);

    /**
     * 查询指定候选详情。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @return 候选详情
     */
    OutlineCandidateDetail get(Long chapterId, Long candidateId);

    /**
     * 保存用户编辑后的已就绪候选。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @param request 候选内容和基础版本
     * @return 更新后的候选
     */
    OutlineCandidateDetail update(Long chapterId, Long candidateId, UpdateOutlineCandidateRequest request);

    /**
     * 放弃已就绪候选。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @return 放弃后的候选详情
     */
    OutlineCandidateDetail abandon(Long chapterId, Long candidateId);

    /**
     * 确认候选并通过条件更新写入正式大纲。
     *
     * @param chapterId 章节 ID
     * @param candidateId 候选 ID
     * @return 候选和正式大纲确认结果
     */
    OutlineCandidateConfirmation confirm(Long chapterId, Long candidateId);
}
