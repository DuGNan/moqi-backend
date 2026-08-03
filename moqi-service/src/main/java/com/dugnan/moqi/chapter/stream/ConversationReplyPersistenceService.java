package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.workflow.ChapterConsensusMaturityStarter;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 在同一短事务中保存助手消息并完成讨论回复任务。
 */
@Service
public class ConversationReplyPersistenceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationReplyPersistenceService.class);

    private final AiTaskMapper taskMapper;
    private final ChapterConversationMessageMapper messageMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ChapterBriefMapper briefMapper;
    private final ChapterConsensusMaturityStarter maturityStarter;

    /**
     * 创建回复结果持久化服务。
     *
     * @param taskMapper AI 任务数据访问对象
     * @param messageMapper 会话消息数据访问对象
     */
    @Autowired
    public ConversationReplyPersistenceService(
            AiTaskMapper taskMapper,
            ChapterConversationMessageMapper messageMapper,
            ApplicationEventPublisher eventPublisher,
            ChapterBriefMapper briefMapper,
            ChapterConsensusMaturityStarter maturityStarter) {
        this.taskMapper = taskMapper;
        this.messageMapper = messageMapper;
        this.eventPublisher = eventPublisher;
        this.briefMapper = briefMapper;
        this.maturityStarter = maturityStarter;
    }

    /** 保留既有单元测试使用的无事件构造入口。 */
    public ConversationReplyPersistenceService(
            AiTaskMapper taskMapper,
            ChapterConversationMessageMapper messageMapper) {
        this(taskMapper, messageMapper, event -> { }, null, null);
    }

    /**
     * 保存助手回复并以任务版本条件完成任务。
     *
     * @param task 当前运行任务
     * @param input 触发回复的用户消息
     * @param content 完整助手回复
     * @return 已持久化助手消息 ID
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public Long complete(
            AiTaskEntity task,
            ChapterConversationMessageEntity input,
            String content) {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setConversationId(input.getConversationId());
        message.setChapterId(task.getChapterId());
        message.setMessageRole("assistant");
        message.setContent(content);
        message.setAiTaskId(task.getId());
        message.setDeleted(0);
        messageMapper.insert(message);
        int version = task.getVersion() == null ? 0 : task.getVersion();
        try {
            startMaturityRun(task, input, message);
        } catch (RuntimeException exception) {
            LOGGER.warn("章节共识成熟度 Run 创建失败，不影响讨论回复，taskId={}, chapterId={}", task.getId(),
                    task.getChapterId(), exception);
        }
        if (taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", "running")
                .set("task_status", "succeeded")
                .set("result_message_id", message.getId())
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now())) != 1) {
            throw new ConversationReplyTaskCanceledException();
        }
        return message.getId();
    }

    private void startMaturityRun(AiTaskEntity task, ChapterConversationMessageEntity input,
            ChapterConversationMessageEntity assistantMessage) {
        if (maturityStarter == null) {
            return;
        }
        ChapterBriefEntity latestBrief = briefMapper.findLatestByChapterId(task.getChapterId());
        maturityStarter.start(task.getWorkId(), task.getChapterId(), input.getConversationId(), input.getId(),
                assistantMessage.getId(), latestBrief == null ? null : latestBrief.getId(), task.getId());
    }
}
