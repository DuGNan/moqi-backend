package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 映射章节选区讨论与局部改写候选的冻结输入、运行状态和采纳结果。
 */
@Data
@TableName("chapter_selection_assistance")
public class ChapterSelectionAssistanceEntity extends BaseEntity {

    private Long workId;
    private Long chapterId;
    private Long parentId;
    private Long aiTaskId;
    private Long agentRunId;
    private String idempotencyKey;
    private String operationType;
    private String requestStatus;
    private Integer baseChapterVersion;
    private String baseContentHash;
    private Integer selectionStart;
    private Integer selectionEnd;
    private String selectedText;
    private String adjacentBefore;
    private String adjacentAfter;
    private String userInstruction;
    private String briefTemplateVersion;
    private String briefFingerprint;
    private String briefContent;
    private String inputFingerprint;
    private String resultContent;
    private String diffJson;
    private String factRiskStatus;
    private String factRiskReasonsJson;
    private String modelCallRef;
    private String errorCode;
    private String errorMessage;
    private Integer acceptedChapterVersion;
}
