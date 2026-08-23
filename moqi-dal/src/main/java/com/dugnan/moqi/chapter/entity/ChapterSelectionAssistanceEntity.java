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
    private String targetKind;
    private Integer requestContractVersion;
    private String targetObjectId;
    private Long targetCandidateId;
    private Integer targetContentVersion;
    private String targetContentHash;
    private String referenceScope;
    private Integer baseChapterVersion;
    private String baseContentHash;
    private Integer selectionStart;
    private Integer selectionEnd;
    private String selectedText;
    private String referenceTextHash;
    private Integer referenceSentenceCount;
    private String referenceSnapshot;
    private Long createdCandidateId;
    private String proposalStatus;
    private Long conversationId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String planningContextJson;
    private Integer appliedCandidateVersion;
    private String appliedCandidateHash;
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
