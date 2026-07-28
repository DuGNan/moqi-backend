package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.Decision;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusValidator;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 在短事务中保存模型生成的共识草稿并完成任务。
 */
@Service
public class ChapterConsensusPersistenceService {

    private final AiTaskMapper taskMapper;

    private final ChapterBriefMapper briefMapper;

    private final ChapterConversationMessageMapper messageMapper;

    private final ChapterConsensusValidator validator;

    private final ChapterConsensusCodec codec;

    /**
     * 创建共识任务持久化服务。
     *
     * @param taskMapper AI 任务数据访问对象
     * @param briefMapper Brief 数据访问对象
     * @param messageMapper 消息数据访问对象
     * @param validator 共识校验器
     * @param codec 共识编解码器
     */
    public ChapterConsensusPersistenceService(
            AiTaskMapper taskMapper,
            ChapterBriefMapper briefMapper,
            ChapterConversationMessageMapper messageMapper,
            ChapterConsensusValidator validator,
            ChapterConsensusCodec codec) {
        this.taskMapper = taskMapper;
        this.briefMapper = briefMapper;
        this.messageMapper = messageMapper;
        this.validator = validator;
        this.codec = codec;
    }

    /**
     * 保存新 draft，并以任务版本条件绑定结果。
     *
     * @param task 正在运行的任务
     * @param conversationId 会话 ID
     * @param content 模型生成共识
     * @return 新 Brief ID
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public Long complete(
            AiTaskEntity task,
            Long conversationId,
            ChapterConsensusContentV1 content) {
        ChapterConsensusContentV1 normalized = validator.normalizeDraft(content);
        validateSources(task.getChapterId(), conversationId, normalized);

        ChapterBriefEntity brief = new ChapterBriefEntity();
        brief.setWorkId(task.getWorkId());
        brief.setChapterId(task.getChapterId());
        brief.setBriefStatus("draft");
        brief.setBriefContent(codec.write(normalized));
        brief.setDeleted(0);
        brief.setVersion(0);
        briefMapper.insert(brief);

        int version = task.getVersion() == null ? 0 : task.getVersion();
        int updated = taskMapper.update(null, new UpdateWrapper<AiTaskEntity>()
                .eq("id", task.getId())
                .eq("deleted", 0)
                .eq("version", version)
                .eq("task_status", "running")
                .isNull("result_brief_id")
                .set("task_status", "succeeded")
                .set("result_brief_id", brief.getId())
                .set("version", version + 1)
                .set("gmt_modified", LocalDateTime.now()));
        if (updated != 1) {
            throw new ChapterConsensusTaskCompletionException();
        }
        return brief.getId();
    }

    /**
     * 批量校验模型只能引用任务会话内的消息。
     *
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @param content 结构化共识
     */
    private void validateSources(
            Long chapterId,
            Long conversationId,
            ChapterConsensusContentV1 content) {
        Set<Long> sourceIds = new LinkedHashSet<>();
        for (Decision decision : content.decisions()) {
            sourceIds.addAll(decision.sourceMessageIds());
        }
        if (sourceIds.isEmpty()) {
            return;
        }
        List<ChapterConversationMessageEntity> messages = messageMapper.selectBatchIds(sourceIds);
        long validCount = messages.stream()
                .filter(message -> message != null
                        && chapterId.equals(message.getChapterId())
                        && conversationId.equals(message.getConversationId())
                        && !Integer.valueOf(1).equals(message.getDeleted()))
                .map(ChapterConversationMessageEntity::getId)
                .distinct()
                .count();
        if (validCount != sourceIds.size()) {
            throw new BusinessException(
                    ErrorCode.CHAPTER_CONSENSUS_INVALID,
                    "模型共识引用了任务会话之外的消息");
        }
    }
}
