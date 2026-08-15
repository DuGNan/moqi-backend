package com.dugnan.moqi.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 映射 Story Release 中冻结的章节正文 revision 集合。
 */
@Data
@TableName("story_release_chapters")
public class StoryReleaseChapterEntity extends BaseEntity {
    private Long releaseId;
    private Long workId;
    private Long chapterId;
    private Long proseRevisionId;
    private Integer chapterNo;
    private String contentHash;
}
