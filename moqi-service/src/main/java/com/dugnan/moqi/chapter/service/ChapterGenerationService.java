package com.dugnan.moqi.chapter.service;

import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.CreateGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.AcceptGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.ChapterContent;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.ContentSaved;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationAccepted;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationCreated;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationDetail;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.GenerationRejected;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.LatestPreview;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.RegenerateRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.RejectGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.SaveContentRequest;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 定义章节生成、预览处理与正文读写能力。
 */
public interface ChapterGenerationService {

    /**
     * 基于章节正式大纲创建正文生成记录。
     *
     * @param chapterId 章节 ID
     * @param request 生成请求
     * @return 创建响应
     */
    GenerationCreated createGeneration(Long chapterId, CreateGenerationRequest request);

    /**
     * 查询生成资源详情。
     *
     * @param generationId 生成记录 ID
     * @return 生成详情
     */
    GenerationDetail getGeneration(Long generationId);

    /**
     * 查询章节最近的待处理预览。
     *
     * @param chapterId 章节 ID
     * @return 最近预览或显式空态
     */
    LatestPreview getLatestPreview(Long chapterId);

    /**
     * 将生成预览采纳到章节正文。
     *
     * @param generationId 生成记录 ID
     * @param request 采纳请求
     * @return 采纳结果
     */
    GenerationAccepted acceptGeneration(Long generationId, AcceptGenerationRequest request);

    /**
     * 拒绝生成预览。
     *
     * @param generationId 生成记录 ID
     * @param request 拒绝请求
     * @return 拒绝结果
     */
    GenerationRejected rejectGeneration(Long generationId, RejectGenerationRequest request);

    /**
     * 基于原生成依据和新增反馈创建新预览。
     *
     * @param generationId 原生成记录 ID
     * @param request 重新生成请求
     * @return 新生成记录
     */
    GenerationCreated regenerate(Long generationId, RegenerateRequest request);

    /**
     * 查询章节当前正文。
     *
     * @param chapterId 章节 ID
     * @return 章节正文
     */
    ChapterContent getContent(Long chapterId);

    /**
     * 按版本条件保存章节正文。
     *
     * @param chapterId 章节 ID
     * @param request 正文保存请求
     * @return 保存结果
     */
    ContentSaved saveContent(Long chapterId, SaveContentRequest request);
}
