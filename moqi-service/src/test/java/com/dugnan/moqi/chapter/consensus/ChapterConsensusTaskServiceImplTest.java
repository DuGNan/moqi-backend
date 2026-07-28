package com.dugnan.moqi.chapter.consensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConsensusTaskRequest;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.service.impl.ChapterConsensusTaskServiceImpl;
import com.dugnan.moqi.chapter.stream.ChapterConsensusTaskSubmittedEvent;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 验证共识任务只持久化已校验的 ID 引用并在事务中发布提交事件。
 */
@ExtendWith(MockitoExtension.class)
class ChapterConsensusTaskServiceImplTest {

    @Mock
    private WorkMapper workMapper;

    @Mock
    private ChapterMapper chapterMapper;

    @Mock
    private ChapterConversationMapper conversationMapper;

    @Mock
    private ChapterBriefMapper briefMapper;

    @Mock
    private ChapterConversationMessageMapper messageMapper;

    @Mock
    private AiTaskMapper taskMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ChapterConsensusTaskServiceImpl service;

    /**
     * 初始化共识任务服务。
     */
    @BeforeEach
    void setUp() {
        service = new ChapterConsensusTaskServiceImpl(
                workMapper,
                chapterMapper,
                conversationMapper,
                briefMapper,
                messageMapper,
                taskMapper,
                new ObjectMapper(),
                eventPublisher);
    }

    /**
     * 验证任务输入只包含会话与基础 Brief ID。
     */
    @Test
    void createsRecoverableConsensusTaskWithIdReferencesOnly() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter());
        when(workMapper.selectById(1L)).thenReturn(work());
        when(conversationMapper.selectById(8L)).thenReturn(conversation());
        when(briefMapper.findByIdAndChapterId(21L, 2L)).thenReturn(brief());
        when(messageMapper.selectList(any())).thenReturn(List.of(message()));
        when(taskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            AiTaskEntity task = invocation.getArgument(0);
            task.setId(31L);
            return 1;
        });

        var result = service.createTask(2L, new ConsensusTaskRequest(8L, 21L));

        assertThat(result.taskId()).isEqualTo(31L);
        ArgumentCaptor<AiTaskEntity> captor = ArgumentCaptor.forClass(AiTaskEntity.class);
        verify(taskMapper).insert(captor.capture());
        assertThat(captor.getValue().getTaskInputJson())
                .isEqualTo("{\"conversationId\":8,\"baseBriefId\":21,\"currentMessageId\":11}")
                .doesNotContain("prompt", "content");
        verify(eventPublisher).publishEvent(new ChapterConsensusTaskSubmittedEvent(31L));
    }

    private ChapterEntity chapter() {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(2L);
        chapter.setWorkId(1L);
        chapter.setDeleted(0);
        return chapter;
    }

    private WorkEntity work() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setDeleted(0);
        return work;
    }

    private ChapterConversationEntity conversation() {
        ChapterConversationEntity conversation = new ChapterConversationEntity();
        conversation.setId(8L);
        conversation.setWorkId(1L);
        conversation.setChapterId(2L);
        conversation.setDeleted(0);
        return conversation;
    }

    private ChapterBriefEntity brief() {
        ChapterBriefEntity brief = new ChapterBriefEntity();
        brief.setId(21L);
        brief.setChapterId(2L);
        brief.setDeleted(0);
        return brief;
    }

    private ChapterConversationMessageEntity message() {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(11L);
        message.setChapterId(2L);
        message.setConversationId(8L);
        message.setMessageRole("user");
        message.setDeleted(0);
        return message;
    }
}
