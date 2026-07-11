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

public interface WorkChapterService {
    WorkList listWorks(String status, String keyword, Integer limit);

    WorkSummary createWork(CreateWorkCommand command);

    WorkDetail getWork(Long workId);

    ChapterList listChapters(Long workId, String chapterType, String workflowStatus, String keyword);

    ChapterCreated createChapter(Long workId, CreateChapterCommand command);

    ChapterDetail getChapter(Long chapterId);

    ChapterOpen openChapter(Long chapterId);
}
