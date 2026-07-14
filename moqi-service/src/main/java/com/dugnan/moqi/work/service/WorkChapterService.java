package com.dugnan.moqi.work.service;

import com.dugnan.moqi.work.dto.CreateChapterCommand;
import com.dugnan.moqi.work.dto.CreateWorkCommand;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterCreated;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterDetail;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterList;
import com.dugnan.moqi.work.dto.WorkChapterModels.ChapterOpen;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkDetail;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkList;
import com.dugnan.moqi.work.dto.WorkChapterModels.WorkSummary;

/**
 * @author dgn
 * @date:2026-07-13
 * @description:定义作品与章节的基础业务能力。
 */
public interface WorkChapterService {
    /**
     * 查询作品列表。
     *
     * @param status 作品状态
     * @param keyword 作品标题关键字
     * @param limit 返回数量上限
     * @return 作品列表
     */
    WorkList listWorks(String status, String keyword, Integer limit);

    /**
     * 创建作品。
     *
     * @param command 创建作品命令
     * @return 创建后的作品摘要
     */
    WorkSummary createWork(CreateWorkCommand command);

    /**
     * 查询作品详情。
     *
     * @param workId 作品 ID
     * @return 作品详情
     */
    WorkDetail getWork(Long workId);

    /**
     * 查询作品下的章节列表。
     *
     * @param workId 作品 ID
     * @param chapterType 章节类型
     * @param workflowStatus 工作流状态
     * @param keyword 章节标题关键字
     * @return 章节列表
     */
    ChapterList listChapters(Long workId, String chapterType, String workflowStatus, String keyword);

    /**
     * 创建章节。
     *
     * @param workId 作品 ID
     * @param command 创建章节命令
     * @return 创建后的章节信息
     */
    ChapterCreated createChapter(Long workId, CreateChapterCommand command);

    /**
     * 查询章节详情。
     *
     * @param chapterId 章节 ID
     * @return 章节详情
     */
    ChapterDetail getChapter(Long chapterId);

    /**
     * 获取章节打开时的默认工作区建议。
     *
     * @param chapterId 章节 ID
     * @return 默认工作区及相关上下文
     */
    ChapterOpen openChapter(Long chapterId);
}
