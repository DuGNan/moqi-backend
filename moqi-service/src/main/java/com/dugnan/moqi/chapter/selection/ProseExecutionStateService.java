package com.dugnan.moqi.chapter.selection;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dugnan.moqi.chapter.entity.ChapterSelectionAssistanceEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterSelectionAssistanceMapper;

/**
 * @author dgn
 * @date 2026-10-25
 * @description 管理正文执行状态及绑定会话的幂等操作，不保存作者正文。
 */
@Service
public class ProseExecutionStateService {
    private static final String RUNNING = "running";
    private static final String ACTIVE = "active";
    private static final String CLARIFICATION = "clarification";
    private static final String PENDING = "pending";
    private static final String SKIPPED = "skipped";
    private static final String EDIT = "edit";
    private static final String NOT_APPLIED = "not_applied";
    private static final String APPLIED = "applied";
    private static final String UNDONE = "undone";
    private static final String STALE = "stale";
    private static final String SAVED = "saved";
    private final ChapterSelectionAssistanceMapper mapper;
    private final ChapterConversationMapper conversations;

    public ProseExecutionStateService(ChapterSelectionAssistanceMapper mapper,
            ChapterConversationMapper conversations) {
        this.mapper = mapper;
        this.conversations = conversations;
    }

    public static SelectionAssistanceModels.ExecutionDetails details(ChapterSelectionAssistanceEntity entity) {
        if (entity.getExecutionStage() == null) {
            return null;
        }
        JsonNode plan = ProseConversationProtocol.reference(entity.getExecutionPlanJson());
        List<String> options = plan == null || !plan.path("options").isArray() ? List.of()
                : java.util.stream.StreamSupport.stream(plan.path("options").spliterator(), false)
                        .map(JsonNode::asText).toList();
        return new SelectionAssistanceModels.ExecutionDetails(plan == null ? null : plan.path("task").asText(),
                entity.getExecutionStage(), entity.getResultKind(), entity.getClarificationStatus(),
                entity.getApplicationStatus(), options);
    }

    public void checkRunning(Long requestId) {
        ChapterSelectionAssistanceEntity entity = mapper.selectById(requestId);
        if (entity == null || !RUNNING.equals(entity.getRequestStatus())) {
            throw new IllegalStateException("正文任务已停止");
        }
        var conversation = conversations.selectById(entity.getConversationId());
        if (conversation == null || !ACTIVE.equals(conversation.getConversationStatus())) {
            throw new IllegalStateException("正文会话已切换");
        }
    }

    public void stage(Long id, String stage) {
        checkRunning(id);
        mapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>().eq("id", id)
                .eq("request_status", RUNNING).set("execution_stage", stage));
    }

    public void plan(Long id, JsonNode plan) {
        checkRunning(id);
        mapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>().eq("id", id)
                .eq("request_status", RUNNING).set("execution_plan_json", plan.toString()));
    }

    public void result(Long id, String kind) {
        checkRunning(id);
        mapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>().eq("id", id)
                .eq("request_status", RUNNING).set("result_kind", kind)
                .set("clarification_status", CLARIFICATION.equals(kind) ? PENDING : null)
                .set("application_status", EDIT.equals(kind) ? NOT_APPLIED : null)
                .set("execution_stage", "completed"));
    }

    @Transactional(rollbackFor = RuntimeException.class)
    public void action(Long id, SelectionAssistanceModels.ExecutionAction action, boolean skip) {
        ChapterSelectionAssistanceEntity entity = mapper.selectById(id);
        if (entity == null || action == null
                || !Objects.equals(entity.getConversationId(), action.conversationId())
                || !Objects.equals(entity.getTargetObjectId(), action.objectId())) {
            throw new IllegalArgumentException("操作不属于当前正文会话");
        }
        var conversation = conversations.selectById(action.conversationId());
        if (conversation == null || !ACTIVE.equals(conversation.getConversationStatus())) {
            throw new IllegalArgumentException("当前会话已变化");
        }
        if (skip) {
            boolean legacy = entity.getResultKind() == null && entity.getResultContent() != null
                    && entity.getResultContent().startsWith("【需要澄清】");
            if (!CLARIFICATION.equals(entity.getResultKind()) && !legacy) {
                throw new IllegalArgumentException("当前结果不是待澄清问题");
            }
            UpdateWrapper<ChapterSelectionAssistanceEntity> update = new UpdateWrapper<ChapterSelectionAssistanceEntity>()
                    .eq("id", id).set("clarification_status", SKIPPED);
            if (legacy) {
                update.isNull("result_kind").set("result_kind", CLARIFICATION).set("execution_stage", "completed");
            } else {
                update.eq("clarification_status", PENDING);
            }
            mapper.update(null, update);
            return;
        }
        if (!EDIT.equals(entity.getResultKind()) || action.status() == null
                || !Set.of(APPLIED, UNDONE, STALE).contains(action.status())) {
            throw new IllegalArgumentException("不支持的正文应用状态");
        }
        String before = entity.getApplicationStatus();
        if (Objects.equals(before, action.status())) {
            return;
        }
        if (SAVED.equals(before) && APPLIED.equals(action.status())) {
            return;
        }
        boolean allowed = switch (String.valueOf(before)) {
            case NOT_APPLIED -> Set.of(APPLIED, STALE).contains(action.status());
            case APPLIED -> Set.of(UNDONE, STALE).contains(action.status());
            case SAVED -> UNDONE.equals(action.status());
            default -> false;
        };
        if (!allowed) {
            throw new IllegalArgumentException("正文应用状态已变化");
        }
        int updated = mapper.update(null, new UpdateWrapper<ChapterSelectionAssistanceEntity>().eq("id", id)
                .eq("application_status", before).set("application_status", action.status()));
        if (updated != 1) {
            throw new IllegalArgumentException("正文应用状态已变化，请刷新后重试");
        }
    }
}
