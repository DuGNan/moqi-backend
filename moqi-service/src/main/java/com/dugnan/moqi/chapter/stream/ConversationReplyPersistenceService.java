package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;

/**
 * @author dgn
 * @date 2026-07-22
 * @description 在同一短事务中保存助手消息并完成讨论回复任务。
 */
@Service
public class ConversationReplyPersistenceService {

    private final AiTaskMapper taskMapper;
    private final ChapterConversationMessageMapper messageMapper;

    /**
     * 创建回复结果持久化服务。
     *
     * @param taskMapper AI 任务数据访问对象
     * @param messageMapper 会话消息数据访问对象
     */
    public ConversationReplyPersistenceService(
            AiTaskMapper taskMapper,
            ChapterConversationMessageMapper messageMapper) {
        this.taskMapper = taskMapper;
        this.messageMapper = messageMapper;
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
}
