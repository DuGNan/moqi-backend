package com.dugnan.moqi.chapter.stream;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

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
        return complete(task, conversationId, content, null);
    }

    /**
     * 保存新的 draft，并保留基础 Brief 中已经由用户处理的决定。
     *
     * @param task 正在运行的任务
     * @param conversationId 会话 ID
     * @param content 模型生成共识
     * @param baseBriefContent 任务创建时的基础 Brief 内容
     * @return 新 Brief ID
     */
    @Transactional(rollbackFor = RuntimeException.class)
    public Long complete(
            AiTaskEntity task,
            Long conversationId,
            ChapterConsensusContentV1 content,
            String baseBriefContent) {
        ChapterConsensusContentV1 normalized = preserveUserResolvedDecisions(
                validator.normalizeGeneratedDraft(content), baseBriefContent);
        Map<Long, ChapterConversationMessageEntity> messages =
                validateSources(task.getChapterId(), conversationId, normalized);
        validateQuotes(normalized, messages);

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
     * 将基础 Brief 中已由用户操作确定的决定原样带入新的模型草稿。
     *
     * @param generated 模型生成的规范化草稿
     * @param baseBriefContent 基础 Brief 内容
     * @return 不会降级用户决定的草稿
     */
    private ChapterConsensusContentV1 preserveUserResolvedDecisions(
            ChapterConsensusContentV1 generated,
            String baseBriefContent) {
        if (baseBriefContent == null || baseBriefContent.isBlank()) {
            return generated;
        }
        ChapterConsensusContentV1 base = codec.read(baseBriefContent).consensus();
        if (base == null) {
            return generated;
        }
        List<Decision> merged = new ArrayList<>(generated.decisions());
        for (Decision baseDecision : base.decisions()) {
            if (!isUserResolved(baseDecision)) {
                continue;
            }
            int index = decisionIndex(merged, baseDecision.key());
            if (index >= 0) {
                merged.set(index, baseDecision);
            } else {
                merged.add(baseDecision);
            }
        }
        return new ChapterConsensusContentV1(
                generated.schemaVersion(),
                generated.chapterTask(),
                generated.stateChange(),
                generated.keyPush(),
                generated.readerProgress(),
                generated.writingBoundaries(),
                merged);
    }

    private boolean isUserResolved(Decision decision) {
        return "confirmed".equals(decision.status())
                || "rejected".equals(decision.status())
                || "discussing".equals(decision.status());
    }

    private int decisionIndex(List<Decision> decisions, String decisionKey) {
        for (int index = 0; index < decisions.size(); index++) {
            if (decisions.get(index).key().equals(decisionKey)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 批量校验模型只能引用任务会话内的消息。
     *
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @param content 结构化共识
     */
    private Map<Long, ChapterConversationMessageEntity> validateSources(
            Long chapterId,
            Long conversationId,
            ChapterConsensusContentV1 content) {
        Set<Long> sourceIds = new LinkedHashSet<>();
        for (Decision decision : content.decisions()) {
            sourceIds.addAll(decision.sourceMessageIds());
        }
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        List<ChapterConversationMessageEntity> messages = messageMapper.selectBatchIds(sourceIds);
        Map<Long, ChapterConversationMessageEntity> indexed = new LinkedHashMap<>();
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
        for (ChapterConversationMessageEntity message : messages) {
            if (message != null) {
                indexed.put(message.getId(), message);
            }
        }
        return indexed;
    }

    private void validateQuotes(
            ChapterConsensusContentV1 content,
            Map<Long, ChapterConversationMessageEntity> messages) {
        for (Decision decision : content.decisions()) {
            for (ChapterConsensusContentV1.SourceQuote sourceQuote : decision.sourceQuotes()) {
                ChapterConversationMessageEntity message = messages.get(sourceQuote.messageId());
                String source = normalizeVisibleText(message == null ? null : message.getContent());
                String quote = normalizeVisibleText(sourceQuote.quote());
                if (!source.contains(quote)) {
                    throw new BusinessException(ErrorCode.CHAPTER_CONSENSUS_INVALID, "模型共识引用的摘录未命中原消息");
                }
            }
        }
    }

    private String normalizeVisibleText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("**", "")
                .replace("__", "")
                .replace("~~", "")
                .replace("`", "")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s{0,3}>\\s?", "")
                .replaceAll("\\s+", "");
    }
}
