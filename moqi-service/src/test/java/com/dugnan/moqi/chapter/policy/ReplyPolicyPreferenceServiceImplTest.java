package com.dugnan.moqi.chapter.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ReplyPolicyPreferenceEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ReplyPolicyPreferenceMapper;
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
 * @description 验证回复偏好的作用域、继承优先级和乐观锁语义。
 */
@ExtendWith(MockitoExtension.class)
class ReplyPolicyPreferenceServiceImplTest {

    @Mock
    private ReplyPolicyPreferenceMapper preferenceMapper;
    @Mock
    private ChapterConversationMapper conversationMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private WorkMapper workMapper;

    /**
     * user 作用域允许省略 scopeId，并统一持久化为 0。
     */
    @Test
    void normalizesUserScopeWhenCreatingPreference() {
        when(preferenceMapper.selectOne(any())).thenReturn(null);
        when(preferenceMapper.insert(any(ReplyPolicyPreferenceEntity.class))).thenAnswer(invocation -> {
            ReplyPolicyPreferenceEntity entity = invocation.getArgument(0);
            entity.setId(9L);
            return 1;
        });

        ReplyPolicyPreferenceModels.PreferenceDetail detail = service().save(
                new PreferenceRequest(" USER ", null, " DEEP ", 0));

        ArgumentCaptor<ReplyPolicyPreferenceEntity> entityCaptor =
                ArgumentCaptor.forClass(ReplyPolicyPreferenceEntity.class);
        org.mockito.Mockito.verify(preferenceMapper).insert(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getUserId()).isEqualTo("local-user");
        assertThat(entityCaptor.getValue().getScopeType()).isEqualTo("user");
        assertThat(entityCaptor.getValue().getScopeId()).isZero();
        assertThat(detail.scopeType()).isEqualTo("user");
        assertThat(detail.scopeId()).isZero();
        assertThat(detail.replyDepth()).isEqualTo("deep");
        assertThat(detail.version()).isZero();
    }

    /**
     * 会话偏好优先于章节、作品和用户偏好。
     */
    @Test
    void resolvesInheritedDepthsFromMostSpecificToLeastSpecific() {
        stubValidConversationHierarchy();
        when(preferenceMapper.selectList(any())).thenReturn(List.of(
                preference("user", 0L, "brief"),
                preference("work", 1L, "balanced"),
                preference("chapter", 2L, "brief"),
                preference("conversation", 8L, "deep")));

        ReplyPolicyPreferenceServiceImpl service = service();
        Map<String, ReplyDepth> inherited = service.inheritedDepths(8L);
        ResolvedReplyPolicy resolved = service.resolve(8L, "继续讨论", null);

        assertThat(inherited.keySet()).containsExactly("conversation", "chapter", "work", "user");
        assertThat(inherited).containsEntry("conversation", ReplyDepth.DEEP)
                .containsEntry("chapter", ReplyDepth.BRIEF)
                .containsEntry("work", ReplyDepth.BALANCED)
                .containsEntry("user", ReplyDepth.BRIEF);
        assertThat(resolved.depth()).isEqualTo(ReplyDepth.DEEP);
        assertThat(resolved.controlSource()).isEqualTo("conversation");
    }

    /**
     * 非法作用域和不存在的资源必须在访问数据前后分别拒绝。
     */
    @Test
    void rejectsInvalidScopeAndMissingResource() {
        ReplyPolicyPreferenceServiceImpl service = service();

        assertThatThrownBy(() -> service.get("project", 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        when(workMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.get("work", 99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WORK_NOT_FOUND));
    }

    /**
     * 已有偏好必须按 baseVersion 更新，更新失败返回状态冲突。
     */
    @Test
    void updatesWithBaseVersionAndRejectsOptimisticLockConflict() {
        ReplyPolicyPreferenceEntity existing = preference("user", 0L, "brief");
        existing.setId(9L);
        existing.setVersion(3);
        when(preferenceMapper.selectOne(any())).thenReturn(existing);
        when(preferenceMapper.update(any(), any())).thenReturn(1, 0);

        ReplyPolicyPreferenceModels.PreferenceDetail updated = service().save(
                new PreferenceRequest("user", 0L, "balanced", 3));

        assertThat(updated.replyDepth()).isEqualTo("balanced");
        assertThat(updated.version()).isEqualTo(4);
        assertThatThrownBy(() -> service().save(
                new PreferenceRequest("user", 0L, "deep", 3)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AI_TASK_STATE_CONFLICT));
    }

    private ReplyPolicyPreferenceServiceImpl service() {
        return new ReplyPolicyPreferenceServiceImpl(
                preferenceMapper,
                conversationMapper,
                chapterMapper,
                workMapper,
                new DefaultReplyPolicyResolver());
    }

    private void stubValidConversationHierarchy() {
        ChapterConversationEntity conversation = new ChapterConversationEntity();
        conversation.setId(8L);
        conversation.setChapterId(2L);
        conversation.setWorkId(1L);
        conversation.setDeleted(0);
        when(conversationMapper.selectById(8L)).thenReturn(conversation);

        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(2L);
        chapter.setWorkId(1L);
        chapter.setDeleted(0);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);

        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setDeleted(0);
        when(workMapper.selectById(1L)).thenReturn(work);
    }

    private ReplyPolicyPreferenceEntity preference(String scopeType, Long scopeId, String depth) {
        ReplyPolicyPreferenceEntity entity = new ReplyPolicyPreferenceEntity();
        entity.setUserId("local-user");
        entity.setScopeType(scopeType);
        entity.setScopeId(scopeId);
        entity.setReplyDepth(depth);
        entity.setVersion(0);
        entity.setDeleted(0);
        return entity;
    }
}
