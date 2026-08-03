package com.dugnan.moqi.chapter.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.consensus.ChapterConsensusCodec;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.Decision;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.SourceQuote;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.ReaderProgress;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.StateChange;
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
 * @description 验证模型共识落库前的来源白名单和任务结果 CAS。
 */
@ExtendWith(MockitoExtension.class)
class ChapterConsensusPersistenceServiceTest {

    @Mock
    private AiTaskMapper taskMapper;

    @Mock
    private ChapterBriefMapper briefMapper;

    @Mock
    private ChapterConversationMessageMapper messageMapper;

    private ChapterConsensusPersistenceService service;

    /**
     * 初始化共识持久化服务。
     */
    @BeforeEach
    void setUp() {
        ChapterConsensusValidator validator = new ChapterConsensusValidator();
        service = new ChapterConsensusPersistenceService(
                taskMapper,
                briefMapper,
                messageMapper,
                validator,
                new ChapterConsensusCodec(new ObjectMapper(), validator));
    }

    /**
     * 验证合法来源会生成 draft 并绑定任务结果。
     */
    @Test
    void savesDraftAndCompletesTask() {
        when(messageMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(message(11L, 2L, 8L)));
        when(briefMapper.insert(any(ChapterBriefEntity.class))).thenAnswer(invocation -> {
            ChapterBriefEntity brief = invocation.getArgument(0);
            brief.setId(41L);
            return 1;
        });
        when(taskMapper.update(any(), any())).thenReturn(1);

        Long briefId = service.complete(task(), 8L, content());

        assertThat(briefId).isEqualTo(41L);
    }

    /**
     * 验证模型不能引用任务会话之外的消息。
     */
    @Test
    void rejectsSourceOutsideTaskConversation() {
        when(messageMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(message(11L, 2L, 9L)));

        assertThatThrownBy(() -> service.complete(task(), 8L, content()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONSENSUS_INVALID);
    }

    /** 验证摘录必须逐字命中所引用消息，不能借用其他消息或编造。 */
    @Test
    void rejectsQuoteThatDoesNotMatchSourceMessage() {
        ChapterConversationMessageEntity source = message(11L, 2L, 8L);
        source.setContent("这是另一段讨论内容");
        when(messageMapper.selectBatchIds(anyCollection())).thenReturn(List.of(source));

        assertThatThrownBy(() -> service.complete(task(), 8L, content()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONSENSUS_INVALID);
    }

    /** 验证引用比对忽略原消息中的 Markdown 强调标记。 */
    @Test
    void acceptsVisibleQuoteWithoutMarkdownDecoration() {
        ChapterConversationMessageEntity source = message(11L, 2L, 8L);
        source.setContent("**救人**还是**追击**");
        when(messageMapper.selectBatchIds(anyCollection())).thenReturn(List.of(source));
        when(briefMapper.insert(any(ChapterBriefEntity.class))).thenAnswer(invocation -> {
            ChapterBriefEntity brief = invocation.getArgument(0);
            brief.setId(42L);
            return 1;
        });
        when(taskMapper.update(any(), any())).thenReturn(1);

        Long briefId = service.complete(task(), 8L, content());

        assertThat(briefId).isEqualTo(42L);
    }

    /** 验证重新整理不能把用户已确认的决定重新写成候选。 */
    @Test
    void preservesResolvedDecisionFromBaseBrief() {
        when(messageMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(message(11L, 2L, 8L)));
        when(briefMapper.insert(any(ChapterBriefEntity.class))).thenAnswer(invocation -> {
            ChapterBriefEntity brief = invocation.getArgument(0);
            brief.setId(43L);
            return 1;
        });
        when(taskMapper.update(any(), any())).thenReturn(1);
        ChapterConsensusContentV1 base = new ChapterConsensusContentV1(
                1, "基础任务", new StateChange("起", "承"), "基础推进",
                new ReaderProgress("兑现", "问题"), List.of(), List.of(new Decision(
                        "protagonist_choice", "主角选择", "confirmed", true,
                        "救人还是追击", "先救人", List.of(11L),
                        List.of(new SourceQuote(11L, "救人还是追击")))));
        ChapterConsensusCodec codec = new ChapterConsensusCodec(new ObjectMapper(), new ChapterConsensusValidator());

        service.complete(task(), 8L, content(), codec.write(base));

        org.mockito.ArgumentCaptor<ChapterBriefEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ChapterBriefEntity.class);
        verify(briefMapper).insert(captor.capture());
        ChapterConsensusContentV1 saved = codec.read(captor.getValue().getBriefContent()).consensus();
        assertThat(saved.decisions()).extracting(Decision::status).containsExactly("confirmed");
    }

    private AiTaskEntity task() {
        AiTaskEntity task = new AiTaskEntity();
        task.setId(31L);
        task.setWorkId(1L);
        task.setChapterId(2L);
        task.setTaskStatus("running");
        task.setVersion(2);
        task.setDeleted(0);
        return task;
    }

    private ChapterConsensusContentV1 content() {
        return new ChapterConsensusContentV1(
                1,
                "推进选择",
                new StateChange("犹豫", "决断"),
                "承担代价",
                new ReaderProgress("兑现", "谁泄密"),
                List.of(),
                List.of(new Decision(
                        "protagonist_choice",
                        "主角选择",
                        "discussing",
                        true,
                        "救人还是追击",
                        "倾向救人",
                        List.of(11L),
                        List.of(new SourceQuote(11L, "救人还是追击")))));
    }

    private ChapterConversationMessageEntity message(
            Long id,
            Long chapterId,
            Long conversationId) {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(id);
        message.setChapterId(chapterId);
        message.setConversationId(conversationId);
        message.setContent("救人还是追击");
        message.setDeleted(0);
        return message;
    }
}
