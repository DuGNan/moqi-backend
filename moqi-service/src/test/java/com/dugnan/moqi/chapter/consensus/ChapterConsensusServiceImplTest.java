package com.dugnan.moqi.chapter.consensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.Decision;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.ReaderProgress;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.StateChange;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ConfirmBriefRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.CreateBriefDraftRequest;
import com.dugnan.moqi.chapter.dto.ChapterConsensusModels.ResolveDecisionRequest;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.chapter.service.impl.ChapterConsensusServiceImpl;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 验证章节共识草稿、确认和来源消息的业务规则。
 */
@ExtendWith(MockitoExtension.class)
class ChapterConsensusServiceImplTest {

    @Mock
    private WorkMapper workMapper;

    @Mock
    private ChapterMapper chapterMapper;

    @Mock
    private ChapterBriefMapper briefMapper;

    @Mock
    private ChapterConversationMessageMapper messageMapper;

    private ChapterConsensusServiceImpl service;

    /**
     * 初始化章节共识服务。
     */
    @BeforeEach
    void setUp() {
        ChapterConsensusValidator validator = new ChapterConsensusValidator();
        service = new ChapterConsensusServiceImpl(
                workMapper,
                chapterMapper,
                briefMapper,
                messageMapper,
                new ChapterConsensusCodec(new com.fasterxml.jackson.databind.ObjectMapper(), validator),
                validator);
    }

    /**
     * 验证创建草稿会追加新版本并保留来源引用。
     */
    @Test
    void createsNewDraftVersion() {
        prepareChapter();
        when(messageMapper.selectBatchIds(anyCollection())).thenReturn(List.of(message(11L, 2L)));
        when(briefMapper.insert(any(ChapterBriefEntity.class))).thenAnswer(invocation -> {
            ChapterBriefEntity entity = invocation.getArgument(0);
            entity.setId(21L);
            entity.setVersion(0);
            return 1;
        });

        var result = service.createDraft(
                2L,
                new CreateBriefDraftRequest(8L, null, content()));

        assertThat(result.id()).isEqualTo(21L);
        assertThat(result.briefStatus()).isEqualTo("draft");
        assertThat(result.contentFormat()).isEqualTo("structured_v1");
        assertThat(result.consensus().decisions().get(0).sourceMessageIds()).containsExactly(11L);
        verify(briefMapper).insert(any(ChapterBriefEntity.class));
    }

    /**
     * 验证最新草稿和最新已确认版本分别查询。
     */
    @Test
    void readsDraftAndConfirmedSeparately() {
        prepareChapter();
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "draft"))
                .thenReturn(brief(21L, "draft", 0));
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed"))
                .thenReturn(brief(20L, "confirmed", 1));

        var result = service.getState(2L);

        assertThat(result.latestDraft().id()).isEqualTo(21L);
        assertThat(result.latestConfirmed().id()).isEqualTo(20L);
    }

    /** 验证确认版本之后不存在新草稿时，不会重新暴露历史草稿。 */
    @Test
    void hidesDraftThatPredatesLatestConfirmedBrief() {
        prepareChapter();
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "draft"))
                .thenReturn(brief(21L, "draft", 0));
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "confirmed"))
                .thenReturn(brief(22L, "confirmed", 1));

        var result = service.getState(2L);

        assertThat(result.latestDraft()).isNull();
        assertThat(result.latestConfirmed().id()).isEqualTo(22L);
    }

    /**
     * 验证确认使用预期版本并返回版本递增后的确认态。
     */
    @Test
    void confirmsDraftWithExpectedVersion() {
        prepareChapter();
        ChapterBriefEntity draft = brief(21L, "draft", 2);
        ChapterBriefEntity confirmed = brief(21L, "confirmed", 3);
        when(briefMapper.findByIdAndChapterId(21L, 2L))
                .thenReturn(draft)
                .thenReturn(confirmed);
        when(briefMapper.confirmDraft(21L, 2L, 2)).thenReturn(1);

        var result = service.confirm(2L, 21L, new ConfirmBriefRequest(2));

        assertThat(result.briefStatus()).isEqualTo("confirmed");
        assertThat(result.version()).isEqualTo(3);
    }

    /**
     * 验证并发更新导致确认条件失效时返回稳定冲突错误。
     */
    @Test
    void rejectsStaleBriefVersion() {
        prepareChapter();
        when(briefMapper.findByIdAndChapterId(21L, 2L)).thenReturn(brief(21L, "draft", 2));
        when(briefMapper.confirmDraft(21L, 2L, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(2L, 21L, new ConfirmBriefRequest(1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_BRIEF_VERSION_CONFLICT);
    }

    /**
     * 验证来源消息必须属于当前章节。
     */
    @Test
    void rejectsSourceMessageFromAnotherChapter() {
        prepareChapter();
        when(messageMapper.selectBatchIds(anyCollection())).thenReturn(List.of(message(11L, 3L)));

        assertThatThrownBy(() -> service.createDraft(
                        2L,
                        new CreateBriefDraftRequest(8L, null, content())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONSENSUS_INVALID);
    }

    /** 验证 adopt、reject 和 discuss 均追加新草稿，不覆盖当前 Brief。 */
    @Test
    void resolvesCandidateByAppendingDraft() {
        prepareChapter();
        ChapterBriefEntity draft = briefWithDecision(21L, 2, "candidates", true);
        when(briefMapper.findByIdAndChapterId(21L, 2L)).thenReturn(draft);
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "draft")).thenReturn(draft);
        AtomicLong ids = new AtomicLong(22L);
        when(briefMapper.insert(any(ChapterBriefEntity.class))).thenAnswer(invocation -> {
            invocation.<ChapterBriefEntity>getArgument(0).setId(ids.getAndIncrement());
            return 1;
        });

        service.resolveDecision(2L, 21L, "protagonist_choice", new ResolveDecisionRequest(2, "adopt"));
        service.resolveDecision(2L, 21L, "protagonist_choice", new ResolveDecisionRequest(2, "reject"));
        var result = service.resolveDecision(2L, 21L, "protagonist_choice", new ResolveDecisionRequest(2, "discuss"));

        ArgumentCaptor<ChapterBriefEntity> captor = ArgumentCaptor.forClass(ChapterBriefEntity.class);
        verify(briefMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        ChapterConsensusCodec codec = new ChapterConsensusCodec(
                new com.fasterxml.jackson.databind.ObjectMapper(), new ChapterConsensusValidator());
        assertThat(captor.getAllValues()).extracting(entity -> codec.read(entity.getBriefContent())
                .consensus().decisions().get(0).status()).containsExactly("confirmed", "rejected", "discussing");
        assertThat(result.id()).isEqualTo(24L);
        assertThat(draft.getBriefStatus()).isEqualTo("draft");
    }

    /** 验证过期草稿和版本均映射为稳定冲突。 */
    @Test
    void rejectsStaleDraftOrVersionWhenResolvingCandidate() {
        prepareChapter();
        ChapterBriefEntity draft = briefWithDecision(21L, 2, "candidates", true);
        when(briefMapper.findByIdAndChapterId(21L, 2L)).thenReturn(draft);
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "draft"))
                .thenReturn(briefWithDecision(22L, 0, "candidates", true));

        assertThatThrownBy(() -> service.resolveDecision(
                        2L, 21L, "protagonist_choice", new ResolveDecisionRequest(2, "reject")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_BRIEF_VERSION_CONFLICT);
    }

    /** 验证相同动作对已处理的当前草稿幂等返回，且不追加版本。 */
    @Test
    void returnsCurrentDraftForIdempotentDecisionAction() {
        prepareChapter();
        ChapterBriefEntity draft = briefWithDecision(21L, 2, "rejected", true);
        when(briefMapper.findByIdAndChapterId(21L, 2L)).thenReturn(draft);
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "draft")).thenReturn(draft);

        var result = service.resolveDecision(2L, 21L, "protagonist_choice", new ResolveDecisionRequest(2, "reject"));

        assertThat(result.id()).isEqualTo(21L);
        verify(briefMapper, never()).insert(any(ChapterBriefEntity.class));
    }

    /** 验证必要 rejected 仍阻塞确认，而可选 rejected 不阻塞。 */
    @Test
    void blocksRequiredRejectedDecisionButAllowsOptionalRejectedDecision() {
        prepareChapter();
        ChapterBriefEntity required = briefWithDecision(21L, 0, "rejected", true);
        when(briefMapper.findByIdAndChapterId(21L, 2L)).thenReturn(required);
        assertThatThrownBy(() -> service.confirm(2L, 21L, new ConfirmBriefRequest(0)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_BRIEF_CONFIRMATION_BLOCKED);

        ChapterBriefEntity optional = briefWithDecision(22L, 0, "rejected", false);
        ChapterBriefEntity confirmed = briefWithDecision(22L, 1, "rejected", false);
        confirmed.setBriefStatus("confirmed");
        when(briefMapper.findByIdAndChapterId(22L, 2L)).thenReturn(optional).thenReturn(confirmed);
        when(briefMapper.confirmDraft(22L, 2L, 0)).thenReturn(1);
        assertThat(service.confirm(2L, 22L, new ConfirmBriefRequest(0)).briefStatus()).isEqualTo("confirmed");
    }

    /**
     * 准备作品与章节归属。
     */
    private void prepareChapter() {
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

    /**
     * 构造测试共识。
     *
     * @return 结构化共识
     */
    private ChapterConsensusContentV1 content() {
        return new ChapterConsensusContentV1(
                1,
                "推进主角选择",
                new StateChange("犹豫", "决断"),
                "主角承担代价",
                new ReaderProgress("得到阶段反馈", "谁泄露了情报"),
                List.of("不改变时间线"),
                List.of(new Decision(
                        "protagonist_choice",
                        "主角选择",
                        "confirmed",
                        true,
                        "选择救人还是追击",
                        "先救人",
                        List.of(11L))));
    }

    /**
     * 构造测试 Brief。
     *
     * @param id Brief ID
     * @param status Brief 状态
     * @param version 乐观锁版本
     * @return Brief 实体
     */
    private ChapterBriefEntity brief(Long id, String status, Integer version) {
        ChapterBriefEntity brief = new ChapterBriefEntity();
        brief.setId(id);
        brief.setWorkId(1L);
        brief.setChapterId(2L);
        brief.setBriefStatus(status);
        brief.setBriefContent(new ChapterConsensusCodec(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ChapterConsensusValidator()).write(content()));
        brief.setDeleted(0);
        brief.setVersion(version);
        return brief;
    }

    private ChapterBriefEntity briefWithDecision(Long id, Integer version, String decisionStatus, boolean required) {
        ChapterBriefEntity brief = brief(id, "draft", version);
        ChapterConsensusContentV1 consensus = new ChapterConsensusContentV1(
                1, "推进主角选择", new StateChange("犹豫", "决断"), "主角承担代价",
                new ReaderProgress("得到阶段反馈", "谁泄露了情报"), List.of(), List.of(new Decision(
                        "protagonist_choice", "主角选择", decisionStatus, required, "选择救人还是追击", "先救人", List.of())));
        brief.setBriefContent(new ChapterConsensusCodec(
                new com.fasterxml.jackson.databind.ObjectMapper(), new ChapterConsensusValidator()).write(consensus));
        return brief;
    }

    /**
     * 构造测试消息。
     *
     * @param id 消息 ID
     * @param chapterId 章节 ID
     * @return 消息实体
     */
    private ChapterConversationMessageEntity message(Long id, Long chapterId) {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(id);
        message.setConversationId(8L);
        message.setChapterId(chapterId);
        message.setMessageRole("user");
        message.setContent("讨论内容");
        message.setDeleted(0);
        return message;
    }
}
