package com.dugnan.moqi.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.dugnan.moqi.common.entity.BaseEntity;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 持久化独立正文会话的有界摘要、覆盖位置及来源指纹。
 */
@Data
@TableName("prose_conversation_memories")
public class ProseConversationMemoryEntity extends BaseEntity {
    private Long conversationId;
    private Long throughMessageId;
    private String summaryJson;
    private String sourceFingerprint;
    private String sourceSnapshotJson;
}
