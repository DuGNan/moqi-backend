package com.dugnan.moqi.chapter.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dugnan.moqi.chapter.dto.ChapterCollaborationModels.ReplyControlRequest;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ReplyPolicyPreferenceEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ReplyPolicyPreferenceMapper;
import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceModels.PreferenceDetail;
import com.dugnan.moqi.chapter.policy.ReplyPolicyPreferenceModels.PreferenceRequest;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 校验本地用户作用域并按固定优先级解析回复偏好。
 */
@Service
public class ReplyPolicyPreferenceServiceImpl implements ReplyPolicyPreferenceService {

    private static final String LOCAL_USER = "local-user";
    private static final String SCOPE_USER = "user";
    private static final String SCOPE_WORK = "work";
    private static final String SCOPE_CHAPTER = "chapter";
    private static final String SCOPE_CONVERSATION = "conversation";
    private static final Set<String> SCOPE_TYPES =
            Set.of(SCOPE_USER, SCOPE_WORK, SCOPE_CHAPTER, SCOPE_CONVERSATION);

    private final ReplyPolicyPreferenceMapper preferenceMapper;
    private final ChapterConversationMapper conversationMapper;
    private final ChapterMapper chapterMapper;
    private final WorkMapper workMapper;
    private final ReplyPolicyResolver resolver;

    /**
     * 创建回复偏好服务。
     *
     * @param preferenceMapper 偏好数据访问对象
     * @param conversationMapper 会话数据访问对象
     * @param chapterMapper 章节数据访问对象
     * @param workMapper 作品数据访问对象
     * @param resolver 回复策略解析器
     */
    public ReplyPolicyPreferenceServiceImpl(
            ReplyPolicyPreferenceMapper preferenceMapper,
            ChapterConversationMapper conversationMapper,
            ChapterMapper chapterMapper,
            WorkMapper workMapper,
            ReplyPolicyResolver resolver) {
        this.preferenceMapper = preferenceMapper;
        this.conversationMapper = conversationMapper;
        this.chapterMapper = chapterMapper;
        this.workMapper = workMapper;
        this.resolver = resolver;
    }

    @Override
    public PreferenceDetail get(String scopeType, Long scopeId) {
        Scope scope = requireScope(scopeType, scopeId);
        ReplyPolicyPreferenceEntity entity = find(scope.type(), scope.id());
        return entity == null ? null : detail(entity);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public PreferenceDetail save(PreferenceRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "回复偏好请求不能为空");
        }
        Scope scope = requireScope(request.scopeType(), request.scopeId());
        ReplyDepth depth = requireDepth(request.replyDepth());
        ReplyPolicyPreferenceEntity existing = find(scope.type(), scope.id());
        if (existing == null) {
            if (request.baseVersion() != null && request.baseVersion() != 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "新建偏好的 baseVersion 必须为空或为 0");
            }
            ReplyPolicyPreferenceEntity entity = new ReplyPolicyPreferenceEntity();
            entity.setUserId(LOCAL_USER);
            entity.setScopeType(scope.type());
            entity.setScopeId(scope.id());
            entity.setReplyDepth(depth.name().toLowerCase(Locale.ROOT));
            entity.setDeleted(0);
            entity.setVersion(0);
            preferenceMapper.insert(entity);
            return detail(entity);
        }
        int expectedVersion = request.baseVersion() == null ? -1 : request.baseVersion();
        if (expectedVersion < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "更新偏好必须提交 baseVersion");
        }
        int changed = preferenceMapper.update(null, new UpdateWrapper<ReplyPolicyPreferenceEntity>()
                .eq("id", existing.getId())
                .eq("user_id", LOCAL_USER)
                .eq("deleted", 0)
                .eq("version", expectedVersion)
                .set("reply_depth", depth.name().toLowerCase(Locale.ROOT))
                .set("version", expectedVersion + 1));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.AI_TASK_STATE_CONFLICT, "回复偏好已变化，请刷新后重试");
        }
        existing.setReplyDepth(depth.name().toLowerCase(Locale.ROOT));
        existing.setVersion(expectedVersion + 1);
        return detail(existing);
    }

    @Override
    public ResolvedReplyPolicy resolve(Long conversationId, String content, ReplyControlRequest control) {
        return resolver.resolve(content, control, inheritedDepths(conversationId));
    }

    @Override
    public Map<String, ReplyDepth> inheritedDepths(Long conversationId) {
        ChapterConversationEntity conversation = requireConversation(conversationId);
        Map<String, ReplyDepth> result = new LinkedHashMap<>();
        List<ReplyPolicyPreferenceEntity> preferences = preferenceMapper.selectList(
                new LambdaQueryWrapper<ReplyPolicyPreferenceEntity>()
                        .eq(ReplyPolicyPreferenceEntity::getUserId, LOCAL_USER)
                        .eq(ReplyPolicyPreferenceEntity::getDeleted, 0));
        addDepth(result, preferences, "conversation", conversation.getId());
        addDepth(result, preferences, "chapter", conversation.getChapterId());
        addDepth(result, preferences, "work", conversation.getWorkId());
        addDepth(result, preferences, "user", 0L);
        return result;
    }

    private void addDepth(
            Map<String, ReplyDepth> result,
            List<ReplyPolicyPreferenceEntity> preferences,
            String scopeType,
            Long scopeId) {
        preferences.stream()
                .filter(item -> scopeType.equals(item.getScopeType()) && scopeId.equals(item.getScopeId()))
                .findFirst()
                .ifPresent(item -> result.put(scopeType, requireDepth(item.getReplyDepth())));
    }

    private Scope requireScope(String scopeType, Long scopeId) {
        String normalized = StringUtils.hasText(scopeType)
                ? scopeType.trim().toLowerCase(Locale.ROOT) : "";
        if (!SCOPE_TYPES.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "scopeType 仅支持 user、work、chapter、conversation");
        }
        long normalizedId = normalizedScopeId(normalized, scopeId);
        if (normalizedId < 0 || isInvalidUserScope(normalized, normalizedId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "回复偏好 scopeId 不合法");
        }
        validateResource(normalized, normalizedId);
        return new Scope(normalized, normalizedId);
    }

    private void validateResource(String scopeType, Long scopeId) {
        if (SCOPE_USER.equals(scopeType)) {
            return;
        }
        if (SCOPE_WORK.equals(scopeType)) {
            requireWork(scopeId);
            return;
        }
        if (SCOPE_CHAPTER.equals(scopeType)) {
            ChapterEntity chapter = chapterMapper.selectById(scopeId);
            if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())) {
                throw new BusinessException(ErrorCode.CHAPTER_NOT_FOUND, "章节不存在");
            }
            requireWork(chapter.getWorkId());
            return;
        }
        requireConversation(scopeId);
    }

    private long normalizedScopeId(String scopeType, Long scopeId) {
        if (SCOPE_USER.equals(scopeType) && scopeId == null) {
            return 0L;
        }
        return scopeId == null ? -1L : scopeId;
    }

    private boolean isInvalidUserScope(String scopeType, long scopeId) {
        return SCOPE_USER.equals(scopeType) && scopeId != 0L;
    }

    private ChapterConversationEntity requireConversation(Long conversationId) {
        ChapterConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || Integer.valueOf(1).equals(conversation.getDeleted())) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        ChapterEntity chapter = chapterMapper.selectById(conversation.getChapterId());
        if (chapter == null || Integer.valueOf(1).equals(chapter.getDeleted())
                || !conversation.getWorkId().equals(chapter.getWorkId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话不属于有效的当前章节和作品");
        }
        requireWork(conversation.getWorkId());
        return conversation;
    }

    private WorkEntity requireWork(Long workId) {
        WorkEntity work = workMapper.selectById(workId);
        if (work == null || Integer.valueOf(1).equals(work.getDeleted())) {
            throw new BusinessException(ErrorCode.WORK_NOT_FOUND, "作品不存在");
        }
        return work;
    }

    private ReplyPolicyPreferenceEntity find(String scopeType, Long scopeId) {
        return preferenceMapper.selectOne(new LambdaQueryWrapper<ReplyPolicyPreferenceEntity>()
                .eq(ReplyPolicyPreferenceEntity::getUserId, LOCAL_USER)
                .eq(ReplyPolicyPreferenceEntity::getScopeType, scopeType)
                .eq(ReplyPolicyPreferenceEntity::getScopeId, scopeId)
                .eq(ReplyPolicyPreferenceEntity::getDeleted, 0));
    }

    private ReplyDepth requireDepth(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "replyDepth 不能为空");
        }
        try {
            return ReplyDepth.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "replyDepth 仅支持 brief、balanced、deep");
        }
    }

    private PreferenceDetail detail(ReplyPolicyPreferenceEntity entity) {
        return new PreferenceDetail(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getReplyDepth(),
                entity.getVersion());
    }

    private record Scope(String type, Long id) {
    }
}
