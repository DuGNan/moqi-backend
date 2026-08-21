package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-21
 * @description 保存章节正文工作区最后一次明确选择的稳定编辑对象。
 */
@Data
@TableName("chapter_prose_workspace_selections")
public class ChapterProseWorkspaceSelectionEntity extends BaseEntity {

    private Long workId;
    private Long chapterId;
    private String selectedObjectKind;
    private String selectedObjectId;
}
