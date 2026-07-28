package com.dugnan.moqi.chapter.focus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
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
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.ReaderProgress;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusContentV1.StateChange;
import com.dugnan.moqi.chapter.consensus.ChapterConsensusValidator;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-07-28
 * @description 验证讨论对焦只解析当前最新草稿和同会话来源消息。
 */
@ExtendWith(MockitoExtension.class)
class ChapterDiscussionFocusResolverTest {

    @Mock
    private ChapterBriefMapper briefMapper;

    @Mock
    private ChapterConversationMessageMapper messageMapper;

    private ChapterDiscussionFocusResolver resolver;

    private ChapterConsensusCodec codec;

    /**
     * 初始化讨论对焦解析器。
     */
    @BeforeEach
    void setUp() {
        codec = new ChapterConsensusCodec(new ObjectMapper(), new ChapterConsensusValidator());
        resolver = new ChapterDiscussionFocusResolver(briefMapper, messageMapper, codec);
    }

    /**
     * 验证服务端按 ID 引用解析待决和来源正文。
     */
    @Test
    void resolvesLatestDraftFocusFromServerData() {
        ChapterBriefEntity brief = brief(21L, "draft");
        when(briefMapper.findByIdAndChapterId(21L, 2L)).thenReturn(brief);
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "draft")).thenReturn(brief);
        when(messageMapper.selectBatchIds(anyCollection())).thenReturn(List.of(message(11L, 2L, 8L)));

        var result = resolver.resolve(2L, 8L, 21L, "protagonist_choice");

        assertThat(result.decisionPrompt()).isEqualTo("选择救人还是追击");
        assertThat(result.sources()).singleElement()
                .satisfies(source -> {
                    assertThat(source.messageId()).isEqualTo(11L);
                    assertThat(source.content()).isEqualTo("我倾向先救人");
                });
    }

    /**
     * 验证旧草稿不能继续作为讨论对焦。
     */
    @Test
    void rejectsStaleDraftFocus() {
        when(briefMapper.findByIdAndChapterId(21L, 2L)).thenReturn(brief(21L, "draft"));
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "draft"))
                .thenReturn(brief(22L, "draft"));

        assertThatThrownBy(() -> resolver.resolve(2L, 8L, 21L, "protagonist_choice"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DISCUSSION_FOCUS_STALE);
    }

    /**
     * 验证来源消息不能跨会话进入对焦上下文。
     */
    @Test
    void rejectsSourceFromAnotherConversation() {
        ChapterBriefEntity brief = brief(21L, "draft");
        when(briefMapper.findByIdAndChapterId(21L, 2L)).thenReturn(brief);
        when(briefMapper.findLatestByChapterIdAndStatus(2L, "draft")).thenReturn(brief);
        when(messageMapper.selectBatchIds(anyCollection())).thenReturn(List.of(message(11L, 2L, 9L)));

        assertThatThrownBy(() -> resolver.resolve(2L, 8L, 21L, "protagonist_choice"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DISCUSSION_FOCUS_INVALID);
    }

    /**
     * 构造测试 Brief。
     *
     * @param id Brief ID
     * @param status Brief 状态
     * @return Brief 实体
     */
    private ChapterBriefEntity brief(Long id, String status) {
        ChapterBriefEntity brief = new ChapterBriefEntity();
        brief.setId(id);
        brief.setChapterId(2L);
        brief.setBriefStatus(status);
        brief.setBriefContent(codec.write(content()));
        brief.setVersion(0);
        brief.setDeleted(0);
        return brief;
    }

    /**
     * 构造测试共识。
     *
     * @return 共识内容
     */
    private ChapterConsensusContentV1 content() {
        return new ChapterConsensusContentV1(
                1,
                "推进主角选择",
                new StateChange("犹豫", "决断"),
                "主角承担代价",
                new ReaderProgress("得到阶段反馈", "谁泄露了情报"),
                List.of(),
                List.of(new Decision(
                        "protagonist_choice",
                        "主角选择",
                        "discussing",
                        true,
                        "选择救人还是追击",
                        "倾向先救人",
                        List.of(11L))));
    }

    /**
     * 构造测试消息。
     *
     * @param id 消息 ID
     * @param chapterId 章节 ID
     * @param conversationId 会话 ID
     * @return 消息实体
     */
    private ChapterConversationMessageEntity message(
            Long id,
            Long chapterId,
            Long conversationId) {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(id);
        message.setChapterId(chapterId);
        message.setConversationId(conversationId);
        message.setMessageRole("user");
        message.setContent("我倾向先救人");
        message.setDeleted(0);
        return message;
    }
}
