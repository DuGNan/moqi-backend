package com.dugnan.moqi.knowledge.service;

import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ChapterKeyEventList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ChapterSummaryDetail;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ConfirmSettingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ConfirmSettingResult;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.CreateForeshadowingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ForeshadowingDetail;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ForeshadowingList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.IgnoreSettingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.IgnoreSettingResult;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.SettingCandidateList;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.SettingList;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 定义第一版作品与章节知识层业务能力。
 */
public interface KnowledgeService {

    /**
     * 按条件查询作品设定候选。
     *
     * @param workId 作品 ID
     * @param chapterId 来源章节 ID
     * @param candidateStatus 候选状态
     * @param settingType 设定类型
     * @param keyword 关键字
     * @return 候选列表
     */
    default SettingCandidateList listSettingCandidates(
            Long workId,
            Long chapterId,
            String candidateStatus,
            String settingType,
            String keyword) {
        return listSettingCandidates(
                workId, chapterId, candidateStatus, settingType, keyword, null, null);
    }

    /**
     * 按条件分页查询作品设定候选。
     *
     * @param workId 作品 ID
     * @param chapterId 来源章节 ID
     * @param candidateStatus 候选状态
     * @param settingType 设定类型
     * @param keyword 关键字
     * @param page 页码
     * @param pageSize 每页数量
     * @return 候选列表
     */
    SettingCandidateList listSettingCandidates(
            Long workId,
            Long chapterId,
            String candidateStatus,
            String settingType,
            String keyword,
            Integer page,
            Integer pageSize);

    /**
     * 确认候选并创建或合并正式设定。
     *
     * @param candidateId 候选 ID
     * @param request 确认请求
     * @return 确认结果
     */
    ConfirmSettingResult confirmSettingCandidate(Long candidateId, ConfirmSettingRequest request);

    /**
     * 幂等忽略设定候选。
     *
     * @param candidateId 候选 ID
     * @param request 忽略请求
     * @return 忽略结果
     */
    IgnoreSettingResult ignoreSettingCandidate(Long candidateId, IgnoreSettingRequest request);

    /**
     * 按条件查询作品正式设定。
     *
     * @param workId 作品 ID
     * @param settingType 设定类型
     * @param entryStatus 设定状态
     * @param keyword 关键字
     * @return 正式设定列表
     */
    default SettingList listSettings(Long workId, String settingType, String entryStatus, String keyword) {
        return listSettings(workId, settingType, entryStatus, keyword, null, null);
    }

    /**
     * 按条件分页查询作品正式设定。
     *
     * @param workId 作品 ID
     * @param settingType 设定类型
     * @param entryStatus 设定状态
     * @param keyword 关键字
     * @param page 页码
     * @param pageSize 每页数量
     * @return 正式设定列表
     */
    SettingList listSettings(
            Long workId,
            String settingType,
            String entryStatus,
            String keyword,
            Integer page,
            Integer pageSize);

    /**
     * 按条件查询作品伏笔。
     *
     * @param workId 作品 ID
     * @param status 伏笔状态
     * @param sourceChapterId 来源章节 ID
     * @param payoffChapterId 回收章节 ID
     * @return 伏笔列表
     */
    default ForeshadowingList listForeshadowings(
            Long workId,
            String status,
            Long sourceChapterId,
            Long payoffChapterId) {
        return listForeshadowings(workId, status, sourceChapterId, payoffChapterId, null, null);
    }

    /**
     * 按条件分页查询作品伏笔。
     *
     * @param workId 作品 ID
     * @param status 伏笔状态
     * @param sourceChapterId 来源章节 ID
     * @param payoffChapterId 回收章节 ID
     * @param page 页码
     * @param pageSize 每页数量
     * @return 伏笔列表
     */
    ForeshadowingList listForeshadowings(
            Long workId,
            String status,
            Long sourceChapterId,
            Long payoffChapterId,
            Integer page,
            Integer pageSize);

    /**
     * 创建作品伏笔。
     *
     * @param workId 作品 ID
     * @param request 创建请求
     * @return 伏笔详情
     */
    ForeshadowingDetail createForeshadowing(Long workId, CreateForeshadowingRequest request);

    /**
     * 查询章节摘要。
     *
     * @param chapterId 章节 ID
     * @return 摘要详情，不存在摘要时返回 null
     */
    ChapterSummaryDetail getChapterSummary(Long chapterId);

    /**
     * 查询章节关键事件。
     *
     * @param chapterId 章节 ID
     * @return 关键事件列表
     */
    ChapterKeyEventList listChapterKeyEvents(Long chapterId);
}
