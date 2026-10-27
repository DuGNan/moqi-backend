package com.dugnan.moqi.chapter.selection;

import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 校验跳过和应用回报的会话绑定、幂等及竞争保护。
 */
class ProseExecutionStateServiceTest {
    private final ChapterSelectionAssistanceMapper mapper = mock(ChapterSelectionAssistanceMapper.class);
    private final ChapterConversationMapper conversations = mock(ChapterConversationMapper.class);
    private final ProseExecutionStateService service = new ProseExecutionStateService(mapper, conversations);

    @Test
    void applicationCannotCrossConversationOrObject() {
        fixture("edit", "not_applied");
        assertThatThrownBy(() -> service.action(1L,
                new SelectionAssistanceModels.ExecutionAction(99L, "formal:3", "applied"), false))
                .hasMessageContaining("不属于");
        assertThatThrownBy(() -> service.action(1L,
                new SelectionAssistanceModels.ExecutionAction(2L, "candidate:3", "applied"), false))
                .hasMessageContaining("不属于");
        verify(mapper, never()).update(isNull(), any());
    }

    @Test
    void duplicateApplicationIsIdempotentAndDoesNotWriteProse() {
        fixture("edit", "applied");
        service.action(1L, new SelectionAssistanceModels.ExecutionAction(2L, "formal:3", "applied"), false);
        verify(mapper, never()).update(isNull(), any());
    }

    @Test
    void concurrentUpdateCannotClaimApplicationSucceeded() {
        fixture("edit", "not_applied");
        when(mapper.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.action(1L,
                new SelectionAssistanceModels.ExecutionAction(2L, "formal:3", "applied"), false))
                .hasMessageContaining("状态已变化");
    }

    @Test
    void skipOnlyAcceptsClarificationAndCannotApplyAnEdit() {
        fixture("edit", "not_applied");
        assertThatThrownBy(() -> service.action(1L,
                new SelectionAssistanceModels.ExecutionAction(2L, "formal:3", null), true))
                .hasMessageContaining("不是待澄清");
        fixture("clarification", null);
        service.action(1L, new SelectionAssistanceModels.ExecutionAction(2L, "formal:3", null), true);
        assertThatThrownBy(() -> service.action(1L,
                new SelectionAssistanceModels.ExecutionAction(2L, "formal:3", "applied"), false))
                .hasMessageContaining("不支持");
    }

    private void fixture(String kind, String application) {
        ChapterSelectionAssistanceEntity entity = new ChapterSelectionAssistanceEntity();
        entity.setConversationId(2L);
        entity.setTargetObjectId("formal:3");
        entity.setResultKind(kind);
        entity.setApplicationStatus(application);
        entity.setClarificationStatus("pending");
        when(mapper.selectById(1L)).thenReturn(entity);
        ChapterConversationEntity conversation = new ChapterConversationEntity();
        conversation.setConversationStatus("active");
        when(conversations.selectById(2L)).thenReturn(conversation);
    }
}
