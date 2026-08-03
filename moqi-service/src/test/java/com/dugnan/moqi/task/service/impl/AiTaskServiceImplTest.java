package com.dugnan.moqi.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.entity.ChapterOutlineCandidateEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.mapper.ChapterOutlineCandidateMapper;
import com.dugnan.moqi.chapter.stream.ChapterReplyEvent;
import com.dugnan.moqi.chapter.stream.ConversationReplyTaskSubmittedEvent;
import com.dugnan.moqi.chapter.stream.OutlineCandidateEvent;
import com.dugnan.moqi.task.event.AiTaskCancellationSignal;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 验证 AI 任务查询、状态约束和幂等取消规则。
 */
@ExtendWith(MockitoExtension.class)
class AiTaskServiceImplTest {

    @Mock
    private AiTaskMapper taskMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ChapterOutlineCandidateMapper candidateMapper;
    @Mock
    private ChapterConversationMessageMapper messageMapper;

    private AiTaskServiceImpl service;

    /**
     * 初始化 AI 任务服务。
     */
    @BeforeEach
    void setUp() {
        service = new AiTaskServiceImpl(taskMapper, candidateMapper, null, messageMapper, eventPublisher);
    }

    /**
     * 验证查询返回任务当前状态和结果引用。
     */
    @Test
    void getsAiTask() {
        AiTaskEntity task = task(9001L, "running");
        task.setResultGenerationId(7001L);
        task.setResultOutlineCandidateId(7002L);
        when(taskMapper.selectById(9001L)).thenReturn(task);

        var result = service.getTask(9001L);

        assertThat(result.taskStatus()).isEqualTo("running");
        assertThat(result.resultGenerationId()).isEqualTo(7001L);
        assertThat(result.resultOutlineCandidateId()).isEqualTo(7002L);
    }

    @Test
    void retriesFailedConversationReplyWithoutCreatingAnotherTask() {
        AiTaskEntity task = task(9001L, "failed");
        task.setTaskInputJson("""
                {
                  "schemaVersion":1,
                  "messageId":11,
                  "conversationId":8,
                  "replyMode":"compare",
                  "replyDepth":"brief",
                  "replyScope":{
                    "primaryIntent":"compare_candidates",
                    "targetType":"current_discussion",
                    "targetReference":null,
                    "allowedChanges":"candidate_summaries",
                    "maxCandidates":3,
                    "allowNewTerms":false
                  },
                  "controlSource":"message",
                  "policyVersion":"chapter-reply-policy-v1",
                  "contextAuthorityVersion":"story-context-authority-v2",
                  "convergenceApplied":false
                }
                """);
        task.setResultMessageId(7001L);
        task.setErrorCode("PROVIDER_UNAVAILABLE");
        task.setErrorMessage("Provider unavailable");
        when(taskMapper.selectById(9001L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(messageMapper.selectList(any())).thenReturn(List.of(new ChapterConversationMessageEntity()));

        var result = service.retryTask(9001L);

        assertThat(result.id()).isEqualTo(9001L);
        assertThat(result.taskStatus()).isEqualTo("queued");
        assertThat(result.effectiveReplyPolicy().replyMode()).isEqualTo("compare");
        assertThat(result.effectiveReplyPolicy().replyDepth()).isEqualTo("brief");
        assertThat(result.effectiveReplyPolicy().replyScope().maxCandidates()).isEqualTo(3);
        verify(eventPublisher).publishEvent(new ConversationReplyTaskSubmittedEvent(9001L));
    }

    @Test
    void rejectsRetryWhenOriginalUserMessageIsMissing() {
        AiTaskEntity task = task(9001L, "failed");
        when(taskMapper.selectById(9001L)).thenReturn(task);
        when(messageMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.retryTask(9001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可重试的用户消息")
                .extracting(exception -> ((BusinessException) exception).getErrorCode().name())
                .isEqualTo("AI_TASK_STATE_CONFLICT");
    }

    /**
     * 验证排队和运行中的任务可以取消。
     */
    @Test
    void cancelsNonTerminalTask() {
        AiTaskEntity task = task(9001L, "queued");
        when(taskMapper.selectById(9001L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);

        var result = service.cancelTask(9001L);

        assertThat(result.taskStatus()).isEqualTo("canceled");
        verify(taskMapper).update(any(), any());
        verify(eventPublisher).publishEvent(ChapterReplyEvent.canceled(12L, 9001L));
    }

    /**
     * 验证运行中任务取消会发布独立的 Provider 取消信号。
     */
    @Test
    void cancelsRunningProviderCall() {
        AiTaskEntity task = task(9001L, "running");
        when(taskMapper.selectById(9001L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);

        service.cancelTask(9001L);

        verify(eventPublisher).publishEvent(new AiTaskCancellationSignal(9001L));
        verify(eventPublisher).publishEvent(ChapterReplyEvent.canceled(12L, 9001L));
    }

    /**
     * 验证首次取消竞争失败后，会读取 running 最新版本并再次取消。
     */
    @Test
    void retriesCancellationWithLatestRunningVersion() {
        AiTaskEntity queued = task(9001L, "queued");
        queued.setVersion(1);
        AiTaskEntity running = task(9001L, "running");
        running.setVersion(2);
        when(taskMapper.selectById(9001L)).thenReturn(queued, running);
        when(taskMapper.update(any(), any())).thenAnswer(invocation -> {
            UpdateWrapper<?> update = invocation.getArgument(1);
            return update.getParamNameValuePairs().containsValue(3) ? 1 : 0;
        });

        var result = service.cancelTask(9001L);

        assertThat(result.taskStatus()).isEqualTo("canceled");
        verify(taskMapper, times(2)).selectById(9001L);
        verify(taskMapper, times(2)).update(any(), any());
    }

    /**
     * 验证持续版本竞争时取消只做有限次数尝试。
     */
    @Test
    void stopsCancellationAfterBoundedRetries() {
        AiTaskEntity first = task(9001L, "queued");
        AiTaskEntity second = task(9001L, "running");
        AiTaskEntity third = task(9001L, "running");
        AiTaskEntity latest = task(9001L, "running");
        first.setVersion(1);
        second.setVersion(2);
        third.setVersion(3);
        latest.setVersion(4);
        when(taskMapper.selectById(9001L)).thenReturn(first, second, third, latest);
        when(taskMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.cancelTask(9001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重试")
                .extracting(exception -> ((BusinessException) exception).getErrorCode().name())
                .isEqualTo("AI_TASK_STATE_CONFLICT");
        verify(taskMapper, times(3)).update(any(), any());
        verify(taskMapper, times(4)).selectById(9001L);
    }

    /**
     * 验证终态任务取消保持幂等且不再写库。
     */
    @Test
    void keepsTerminalTaskCancellationIdempotent() {
        when(taskMapper.selectById(9001L)).thenReturn(task(9001L, "succeeded"));

        var result = service.cancelTask(9001L);

        assertThat(result.taskStatus()).isEqualTo("succeeded");
        verify(taskMapper, never()).update(any(), any());
    }

    /**
     * 验证不存在或软删除任务返回明确错误。
     */
    @Test
    void rejectsMissingTask() {
        AiTaskEntity deleted = task(9002L, "queued");
        deleted.setDeleted(1);
        when(taskMapper.selectById(9001L)).thenReturn(null);
        when(taskMapper.selectById(9002L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.getTask(9001L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_TASK_NOT_FOUND);
        assertThatThrownBy(() -> service.getTask(9002L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_TASK_NOT_FOUND);
    }

    /**
     * 验证数据库中的任务状态必须属于受支持状态集合。
     */
    @Test
    void rejectsUnsupportedTaskStatus() {
        when(taskMapper.selectById(9001L)).thenReturn(task(9001L, "paused"));

        assertThatThrownBy(() -> service.getTask(9001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paused");
    }

    /**
     * 验证取消候选任务会在同一事务内同步候选状态，避免迟到结果覆盖取消。
     */
    @Test
    void cancelsLinkedOutlineCandidate() {
        AiTaskEntity task = task(9001L, "running");
        task.setTaskType("outline_adjustment_candidate");
        ChapterOutlineCandidateEntity candidate = new ChapterOutlineCandidateEntity();
        candidate.setId(7002L);
        candidate.setAiTaskId(9001L);
        candidate.setChapterId(12L);
        candidate.setBaseOutlineId(33L);
        candidate.setBaseOutlineRevision(4);
        candidate.setCandidateStatus("running");
        candidate.setVersion(0);
        candidate.setDeleted(0);
        when(taskMapper.selectById(9001L)).thenReturn(task);
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(candidateMapper.findByTaskId(9001L)).thenReturn(candidate);
        when(candidateMapper.update(any(), any())).thenReturn(1);

        service.cancelTask(9001L);

        verify(candidateMapper).update(any(), any());
        verify(eventPublisher).publishEvent(OutlineCandidateEvent.updated(
                12L, 9001L, 7002L, "canceled", "canceled", 33L, 4));
    }

    /**
     * 构造测试 AI 任务。
     *
     * @param id 任务 ID
     * @param status 任务状态
     * @return AI 任务实体
     */
    private AiTaskEntity task(Long id, String status) {
        AiTaskEntity task = new AiTaskEntity();
        task.setId(id);
        task.setTaskType("conversation_reply");
        task.setTaskStatus(status);
        task.setWorkId(1L);
        task.setChapterId(12L);
        task.setDeleted(0);
        task.setVersion(0);
        return task;
    }
}
