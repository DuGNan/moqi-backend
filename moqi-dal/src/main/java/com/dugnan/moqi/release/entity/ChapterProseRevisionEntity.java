package com.dugnan.moqi.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 映射不可变章节正文 revision 及其来源和评价绑定。
 */
@Data
@TableName("chapter_prose_revisions")
public class ChapterProseRevisionEntity extends BaseEntity {
    private Long workId;
    private Long chapterId;
    private Long parentRevisionId;
    private Long sourceGenerationId;
    private Long sourceBoundedRevisionId;
    private Long sourceSnapshotId;
    private Long evaluationReportId;
    private Integer revisionNo;
    private String revisionOrigin;
    private String revisionStatus;
    private String content;
    private String contentHash;
    private String idempotencyKey;
    private String createdBy;
}
