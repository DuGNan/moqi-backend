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

    @Test
    void sceneGenerationUsesOnlyFrozenBriefAndFrozenPreviousDrafts() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("运行时作品标题");
        work.setDeleted(0);
        ChapterEntity chapter = chapter(2L, 1L);
        chapter.setContent("运行时章节正文");
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        SceneGenerationContextFocus focus = new SceneGenerationContextFocus(
                "# 冻结的 Chapter Generation Brief",
                "brief-fingerprint",
                "chapter-generation-brief-v1",
                "scene-2",
                new SceneGenerationContextFocus.PreviousSceneDraft(31L, "scene-1", "冻结的上一场正文"),
                List.of());

        StoryContextSnapshot result = engine.build(new StoryContextBuildCommand(
                StoryContextProfile.SCENE_GENERATION,
                1L,
                2L,
                null,
                null,
                "生成当前场景正文",
                "生成当前场景",
                null,
                8192,
                2048,
                null,
                focus));

        assertThat(result.items()).extracting(StoryContextItem::sourceType)
                .contains(StoryContextSourceType.CHAPTER_GENERATION_BRIEF,
                        StoryContextSourceType.GENERATED_SCENE_DRAFT)
                .doesNotContain(StoryContextSourceType.WORK_METADATA,
                        StoryContextSourceType.CHAPTER_BRIEF,
                        StoryContextSourceType.CHAPTER_OUTLINE,
                        StoryContextSourceType.CHAPTER_CONTENT,
                        StoryContextSourceType.SETTING_ENTRY,
                        StoryContextSourceType.FORESHADOWING,
                        StoryContextSourceType.CHAPTER_SUMMARY,
                        StoryContextSourceType.CHAPTER_KEY_EVENT);
        assertThat(result.items()).extracting(StoryContextItem::content)
                .noneMatch(content -> content.contains("运行时作品标题") || content.contains("运行时章节正文"));
    }

    /**
     * 验证待决、当前共识和来源讨论以固定来源类型进入上下文。
     */
    @Test
    void includesDiscussionFocusBeforeCurrentUserInput() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("墨契");
        work.setDeleted(0);
        ChapterEntity chapter = chapter(2L, 1L);
        ChapterConversationEntity conversation = conversation(3L, 1L, 2L);
        ChapterConversationMessageEntity message = message(4L, 3L, 2L, "我倾向先救人");
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(messageMapper.selectById(4L)).thenReturn(message);
        StoryContextFocus focus = new StoryContextFocus(
                21L,
                0,
                "protagonist_choice",
                "待决：主角选择",
                """
                        {
                          "schemaVersion":1,
                          "chapterTask":"救人",
                          "decisions":[
                            {"key":"confirmed","title":"已确认","status":"confirmed","candidateSummary":"救人"},
                            {"key":"old_year","title":"旧纪年","status":"rejected",
                             "candidateSummary":"潮元 171 年"},
                            {"key":"other","title":"其他候选","status":"candidate",
                             "candidateSummary":"不相关候选"}
                          ]
                        }
                        """,
                List.of(new StoryContextFocus.StoryContextFocusSource(11L, "user", "先救人")));

        StoryContextSnapshot result = engine.build(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION,
                1L,
                2L,
                3L,
                4L,
                "围绕待决讨论",
                "我倾向先救人",
                null,
                4096,
                512,
                focus));

        assertThat(result.items()).extracting(StoryContextItem::sourceType)
                .containsSubsequence(
                        StoryContextSourceType.DECISION_FOCUS,
                        StoryContextSourceType.CHAPTER_CONSENSUS,
                        StoryContextSourceType.DECISION_SOURCE_MESSAGE)
                .endsWith(StoryContextSourceType.USER_INPUT);
        assertThat(result.items())
                .filteredOn(item -> item.sourceType() == StoryContextSourceType.DECISION_FOCUS)
                .extracting(StoryContextItem::authorityStatus)
                .containsOnly(StoryContextAuthorityStatus.CANDIDATE);
        assertThat(result.items())
                .filteredOn(item -> item.sourceType() == StoryContextSourceType.CHAPTER_CONSENSUS
                        && item.authorityStatus() == StoryContextAuthorityStatus.CONFIRMED)
                .extracting(StoryContextItem::authorityStatus)
                .containsOnly(StoryContextAuthorityStatus.CONFIRMED);
        assertThat(result.items())
                .filteredOn(item -> item.authorityStatus() == StoryContextAuthorityStatus.CONFIRMED)
                .extracting(StoryContextItem::content)
                .allMatch(content -> !content.contains("潮元 171 年") && !content.contains("不相关候选"));
        assertThat(result.items())
                .filteredOn(item -> item.authorityStatus() == StoryContextAuthorityStatus.REJECTED)
                .extracting(StoryContextItem::content)
                .anyMatch(content -> content.contains("旧纪年") && !content.contains("潮元 171 年"));
        assertThat(result.items())
                .filteredOn(item -> item.sourceType() == StoryContextSourceType.USER_INPUT)
                .extracting(StoryContextItem::authorityStatus)
                .containsOnly(StoryContextAuthorityStatus.EVIDENCE);
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
