package com.dugnan.moqi.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterConversationMessageEntity;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMessageMapper;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.context.entity.StoryContextSnapshotEntity;
import com.dugnan.moqi.context.mapper.StoryContextSnapshotMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

@ExtendWith(MockitoExtension.class)
class StoryContextEngineImplTest {

    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ChapterBriefMapper briefMapper;
    @Mock
    private ChapterOutlineQueryMapper outlineMapper;
    @Mock
    private SettingEntryMapper settingMapper;
    @Mock
    private ForeshadowingItemMapper foreshadowingMapper;
    @Mock
    private ChapterSummaryMapper summaryMapper;
    @Mock
    private ChapterKeyEventMapper eventMapper;
    @Mock
    private ChapterConversationMapper conversationMapper;
    @Mock
    private ChapterConversationMessageMapper messageMapper;
    @Mock
    private StoryContextSnapshotMapper snapshotMapper;

    private StoryContextEngineImpl engine;

    @BeforeEach
    void setUp() {
        engine = new StoryContextEngineImpl(
                workMapper, chapterMapper, briefMapper, outlineMapper, settingMapper,
                foreshadowingMapper, summaryMapper, eventMapper, conversationMapper,
                messageMapper, snapshotMapper, new ConservativeTokenEstimator(), new ObjectMapper());
        lenient().when(briefMapper.selectList(any())).thenReturn(List.of());
        lenient().when(outlineMapper.selectList(any())).thenReturn(List.of());
        lenient().when(settingMapper.selectList(any())).thenReturn(List.of());
        lenient().when(foreshadowingMapper.selectList(any())).thenReturn(List.of());
        lenient().when(summaryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(eventMapper.selectList(any())).thenReturn(List.of());
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of());
        lenient().when(snapshotMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        lenient().when(snapshotMapper.selectOne(any())).thenReturn(null);
        lenient().when(snapshotMapper.insert(any(StoryContextSnapshotEntity.class))).thenAnswer(invocation -> {
            StoryContextSnapshotEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            entity.setGmtCreate(LocalDateTime.now());
            return 1;
        });
    }

    @Test
    void buildsMinimalDeterministicSnapshot() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("墨契");
        work.setDeleted(0);
        ChapterEntity chapter = chapter(2L, 1L);
        ChapterConversationEntity conversation = conversation(3L, 1L, 2L);
        ChapterConversationMessageEntity message = message(4L, 3L, 2L, "当前问题");
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(messageMapper.selectById(4L)).thenReturn(message);

        StoryContextSnapshot result = engine.build(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION, 1L, 2L, 3L, 4L,
                "请给出章节共创建议", "当前问题", null, 4096, 512));

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.snapshotVersion()).isEqualTo(1L);
        assertThat(result.contentHash()).hasSize(64);
        assertThat(result.items()).extracting(StoryContextItem::messageRole)
                .containsExactly("SYSTEM", "SYSTEM", "SYSTEM", "USER");
        assertThat(result.toMessages()).hasSize(4);
    }

    @Test
    void rejectsChapterFromAnotherWork() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("墨契");
        work.setDeleted(0);
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 9L));

        assertThatThrownBy(() -> engine.build(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION, 1L, 2L, null, null,
                "任务", "输入", null, 4096, 512)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("章节不属于当前作品");
    }

    @Test
    void truncatesLongChapterContentAndRecordsReason() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("墨契");
        work.setDeleted(0);
        ChapterEntity chapter = chapter(2L, 1L);
        chapter.setContent("这是章节正文。".repeat(100));
        ChapterConversationEntity conversation = conversation(3L, 1L, 2L);
        ChapterConversationMessageEntity message = message(4L, 3L, 2L, "当前问题");
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(messageMapper.selectById(4L)).thenReturn(message);

        StoryContextSnapshot result = engine.build(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION, 1L, 2L, 3L, 4L,
                "任务", "当前问题", null, 256, 32));

        assertThat(result.items()).anyMatch(item -> item.sourceType() == StoryContextSourceType.CHAPTER_CONTENT
                && item.selectionReason().startsWith("TRUNCATED"));
    }

    private ChapterEntity chapter(Long id, Long workId) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(id);
        chapter.setWorkId(workId);
        chapter.setTitle("第一章");
        chapter.setDeleted(0);
        return chapter;
    }

    private ChapterConversationEntity conversation(Long id, Long workId, Long chapterId) {
        ChapterConversationEntity conversation = new ChapterConversationEntity();
        conversation.setId(id);
        conversation.setWorkId(workId);
        conversation.setChapterId(chapterId);
        conversation.setDeleted(0);
        return conversation;
    }

    private ChapterConversationMessageEntity message(Long id, Long conversationId, Long chapterId, String content) {
        ChapterConversationMessageEntity message = new ChapterConversationMessageEntity();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setChapterId(chapterId);
        message.setMessageRole("user");
        message.setContent(content);
        message.setDeleted(0);
        return message;
    }
}
