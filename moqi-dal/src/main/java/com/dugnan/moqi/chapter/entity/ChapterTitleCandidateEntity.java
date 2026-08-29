package com.dugnan.moqi.chapter.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-29
 * @description 保存 AI 章节标题候选及其显式采用结果。
 */
@Data
@TableName("chapter_title_candidates")
public class ChapterTitleCandidateEntity extends BaseEntity {

    private Long batchId;
    private Integer candidateOrder;
    private String title;
    private String adoptedTitle;
    private String adoptionIdempotencyKey;
    private Integer adoptedChapterVersion;
    private LocalDateTime adoptedAt;
}
