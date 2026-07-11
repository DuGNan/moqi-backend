package com.dugnan.moqi.work.service;

import com.dugnan.moqi.work.dto.CreateChapterCommand;
import com.dugnan.moqi.work.dto.CreateWorkCommand;
import com.dugnan.moqi.work.dto.WorkChapterModels.*;

public interface WorkChapterService {
    WorkList listWorks(String status, String keyword, Integer limit);
    WorkSummary createWork(CreateWorkCommand command);
    WorkDetail getWork(Long workId);
    ChapterList listChapters(Long workId, String chapterType, String workflowStatus, String keyword);
    ChapterCreated createChapter(Long workId, CreateChapterCommand command);
    ChapterDetail getChapter(Long chapterId);
    ChapterOpen openChapter(Long chapterId);
}
