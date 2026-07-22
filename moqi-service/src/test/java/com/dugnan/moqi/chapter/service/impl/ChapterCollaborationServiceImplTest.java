package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.MessageCreated;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.OutlineRequest;
import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.SendMessageRequest;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.stream.ConversationReplyTaskSubmittedEvent;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-16
 * @description 验证章节共创服务的会话、消息和大纲规则。
 */
@ExtendWith(MockitoExtension.class)
class ChapterCollaborationServiceImplTest {

    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ChapterConversationMapper conversationMapper;
    @Mock
    private ChapterConversationMessageMapper messageMapper;
    @Mock
    private ChapterBriefMapper briefMapper;
    @Mock
    private ChapterOutlineQueryMapper outlineMapper;
    @Mock
    private AiTaskMapper aiTaskMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ChapterCollaborationServiceImpl service;

    /**
     * 初始化章节共创服务。
     */
    @BeforeEach
    void setUp() {
        service = new ChapterCollaborationServiceImpl(
                workMapper,
                chapterMapper,
                conversationMapper,
                messageMapper,
                briefMapper,
                outlineMapper,
                aiTaskMapper,
                eventPublisher);
    }

    /**
     * 验证创建会话时复用同一章节下的活动会话。
     */
    @Test
    void reusesActiveConversationForChapter() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        ChapterConversationEntity conversation = conversation(8L, 1L, 2L);
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));

        var result = service.createOrGetConversation(2L);

        assertThat(result.id()).isEqualTo(8L);
        assertThat(result.conversationStatus()).isEqualTo("active");
    }

    /**
     * 验证发送消息时可创建 AI 任务并回填消息任务 ID。
     */
    @Test
    void createsAiTaskWhenMessageRequestsIt() {
        when(conversationMapper.selectById(8L)).thenReturn(conversation(8L, 1L, 2L));
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        when(messageMapper.insert(any(ChapterConversationMessageEntity.class))).thenAnswer(invocation -> {
            ChapterConversationMessageEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            entity.setGmtCreate(LocalDateTime.now());
            entity.setGmtModified(entity.getGmtCreate());
            return 1;
        });
        when(aiTaskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            AiTaskEntity entity = invocation.getArgument(0);
            entity.setId(12L);
            return 1;
        });

        MessageCreated result = service.sendMessage(
                8L,
                new SendMessageRequest("user", "讨论本章目标", true));

        assertThat(result.id()).isEqualTo(11L);
        assertThat(result.aiTaskId()).isEqualTo(12L);
        verify(messageMapper).updateById(any(ChapterConversationMessageEntity.class));
        verify(eventPublisher).publishEvent(new ConversationReplyTaskSubmittedEvent(12L));
        verify(aiTaskMapper).insert(org.mockito.ArgumentMatchers.<AiTaskEntity>argThat(task ->
                task.getResultMessageId() == null));
    }

    /**
     * 验证大纲保存会拒绝过期 revision。
     */
    @Test
    void rejectsStaleOutlineRevision() {
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 1L));
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setId(5L);
        outline.setWorkId(1L);
        outline.setChapterId(2L);
        outline.setOutlineStatus("draft");
        outline.setOutlineContent("{\"title\":\"旧大纲\"}");
        outline.setRevision(3);
        outline.setDeleted(0);
        when(outlineMapper.findLatest(2L)).thenReturn(outline);

        assertThatThrownBy(() -> service.saveOutline(
                2L,
                new OutlineRequest("{\"title\":\"新大纲\"}", "draft", 2)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OUTLINE_REVISION_CONFLICT);
    }

    /**
     * 构造测试用作品。
     *
     * @param id 作品 ID
     * @return 作品实体
     */
    private WorkEntity work(Long id) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setTitle("作品");
        work.setDeleted(0);
        return work;
    }

    /**
     * 构造测试用章节。
     *
     * @param id 章节 ID
     * @param workId 作品 ID
     * @return 章节实体
     */
    private ChapterEntity chapter(Long id, Long workId) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(id);
        chapter.setWorkId(workId);
        chapter.setTitle("章节");
        chapter.setDeleted(0);
        return chapter;
    }

    /**
     * 构造测试用会话。
     *
     * @param id 会话 ID
     * @param workId 作品 ID
     * @param chapterId 章节 ID
     * @return 会话实体
     */
    private ChapterConversationEntity conversation(Long id, Long workId, Long chapterId) {
        ChapterConversationEntity conversation = new ChapterConversationEntity();
        conversation.setId(id);
        conversation.setWorkId(workId);
        conversation.setChapterId(chapterId);
        conversation.setConversationType("chapter_co_creation");
        conversation.setConversationStatus("active");
        conversation.setDeleted(0);
        return conversation;
    }
}
