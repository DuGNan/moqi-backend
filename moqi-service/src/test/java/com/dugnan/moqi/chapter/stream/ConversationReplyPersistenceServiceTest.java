package com.dugnan.moqi.chapter.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.workflow.ChapterConsensusMaturityStarter;

class ConversationReplyPersistenceServiceTest {

    @Test
    void createsMaturityRunWhileSourceTaskIsStillRunningBeforeCompletingTask() {
        AiTaskMapper taskMapper = mock(AiTaskMapper.class);
        ChapterConversationMessageMapper messageMapper = mock(ChapterConversationMessageMapper.class);
        ChapterBriefMapper briefMapper = mock(ChapterBriefMapper.class);
        ChapterConsensusMaturityStarter maturityStarter = mock(ChapterConsensusMaturityStarter.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        doAnswer(invocation -> {
            ChapterConversationMessageEntity message = invocation.getArgument(0);
            message.setId(301L);
            return 1;
        }).when(messageMapper).insert(any(ChapterConversationMessageEntity.class));
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        ConversationReplyPersistenceService service = new ConversationReplyPersistenceService(
                taskMapper, messageMapper, eventPublisher, briefMapper, maturityStarter);
        AiTaskEntity task = new AiTaskEntity();
        task.setId(201L);
        task.setWorkId(11L);
        task.setChapterId(21L);
        task.setTaskStatus("running");
        task.setVersion(3);
        ChapterConversationMessageEntity input = new ChapterConversationMessageEntity();
        input.setId(101L);
        input.setConversationId(31L);

        Long messageId = service.complete(task, input, "完整回复");

        assertThat(messageId).isEqualTo(301L);
        InOrder order = inOrder(messageMapper, maturityStarter, taskMapper);
        order.verify(messageMapper).insert(any(ChapterConversationMessageEntity.class));
        order.verify(maturityStarter).start(11L, 21L, 31L, 101L, 301L, null, 201L);
        order.verify(taskMapper).update(any(), any(Wrapper.class));
    }
}
