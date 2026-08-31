package com.dugnan.moqi.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.dugnan.moqi.chapter.policy.ConversationReplyPromptCompiler;
import com.dugnan.moqi.chapter.policy.ConversationReplyTaskInputV1;
import com.dugnan.moqi.chapter.policy.DefaultReplyPolicyResolver;
import com.dugnan.moqi.chapter.policy.ReplyDepth;
import com.dugnan.moqi.chapter.policy.ReplyMode;
import com.dugnan.moqi.chapter.policy.ReplyScope;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.context.entity.StoryContextSnapshotEntity;
import com.dugnan.moqi.context.mapper.StoryContextSnapshotMapper;
import com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;
import com.dugnan.moqi.llm.LlmRole;
import com.dugnan.moqi.llm.LlmOptions;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponseFormat;

/**
 * @author dgn
 * @date 2026-08-03
 * @description 验证故事上下文按预算、权威边界和消息角色生成不可变快照。
 */
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
        assertThat(result.toMessages()).extracting(llmMessage -> llmMessage.content())
                .containsExactly(
                        "你是墨契，作者的小说共创搭档。",
                        "请给出章节共创建议",
                        "【当前作品】作品：墨契",
                        "当前问题");
        assertThat(result.items())
                .filteredOn(item -> item.sourceType() == StoryContextSourceType.WORK_METADATA)
                .allSatisfy(item -> {
                    assertThat(item.authorityStatus()).isEqualTo(StoryContextAuthorityStatus.EVIDENCE);
                    assertThat(item.content()).startsWith("【当前作品】作品：墨契");
                    assertThat(item.content()).doesNotContain("作者已经明确确定");
                });
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
    void labelsFixedStoryContextBySourceInsteadOfAsAuthorConfirmation() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("长篇作品");
        work.setDeleted(0);
        ChapterEntity chapter = chapter(2L, 1L);
        chapter.setContent("当前章节正文内容");
        ChapterSummaryEntity summary = new ChapterSummaryEntity();
        summary.setId(5L);
        summary.setWorkId(1L);
        summary.setChapterId(1L);
        summary.setSummary("前章已经抵达车站");
        summary.setSummaryStatus("confirmed");
        summary.setContentRevision(1);
        summary.setVersion(0);
        summary.setDeleted(0);
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(summaryMapper.selectList(any())).thenReturn(List.of(summary));

        StoryContextSnapshot result = engine.build(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION, 1L, 2L, null, null,
                "讨论当前章节", "继续讨论", null, 4096, 512));

        assertThat(result.items()).extracting(StoryContextItem::content)
                .contains("【当前作品】作品：长篇作品")
                .anyMatch(content -> content.startsWith("【当前章节正文】当前章节正文内容"))
                .anyMatch(content -> content.startsWith("【相关章节资料】章节摘要：前章已经抵达车站"))
                .noneMatch(content -> content.startsWith("【作者已经明确确定，必须遵守】作品：")
                        || content.startsWith("【作者已经明确确定，必须遵守】当前章节正文内容")
                        || content.startsWith("【作者已经明确确定，必须遵守】章节摘要："));
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
                .allSatisfy(item -> {
                    assertThat(item.authorityStatus()).isEqualTo(StoryContextAuthorityStatus.CONFIRMED);
                    assertThat(item.content()).startsWith("【作者已经明确确定，必须遵守】");
                });
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

    @Test
    void keepsConversationHistoryAsCompleteUserAssistantTurns() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("墨契");
        work.setDeleted(0);
        ChapterEntity chapter = chapter(2L, 1L);
        ChapterConversationEntity conversation = conversation(3L, 1L, 2L);
        ChapterConversationMessageEntity current = message(9L, 3L, 2L, "请总结当前讨论");
        List<ChapterConversationMessageEntity> history = new ArrayList<>();
        history.add(message(5L, 3L, 2L, "作者第一轮"));
        history.add(assistantMessage(6L, 3L, 2L, "助手第一轮候选"));
        history.add(message(7L, 3L, 2L, "作者第二轮"));
        history.add(assistantMessage(8L, 3L, 2L, "助手第二轮候选"));
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(messageMapper.selectById(9L)).thenReturn(current);
        when(messageMapper.selectList(any())).thenReturn(history);

        StoryContextSnapshot result = engine.build(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION, 1L, 2L, 3L, 9L,
                "请整理已经讨论的内容", "请总结当前讨论", null, 4096, 512));

        assertThat(result.toMessages()).extracting(message -> message.role())
                .containsSubsequence(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER);
        assertThat(result.toMessages()).extracting(message -> message.content())
                .noneMatch(content -> content.contains("权威状态")
                        || content.contains("confirmed")
                        || content.contains("evidence"));
    }

    @Test
    void neverKeepsHalfConversationTurnWhenHistoryBudgetIsTight() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("墨契");
        work.setDeleted(0);
        ChapterEntity chapter = chapter(2L, 1L);
        ChapterConversationEntity conversation = conversation(3L, 1L, 2L);
        ChapterConversationMessageEntity current = message(11L, 3L, 2L, "继续");
        List<ChapterConversationMessageEntity> history = List.of(
                message(5L, 3L, 2L, "作者长消息".repeat(24)),
                assistantMessage(6L, 3L, 2L, "助手长回复".repeat(24)),
                message(7L, 3L, 2L, "作者较短消息"),
                assistantMessage(8L, 3L, 2L, "助手较短回复"));
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(messageMapper.selectById(11L)).thenReturn(current);
        when(messageMapper.selectList(any())).thenReturn(history);

        StoryContextSnapshot result = engine.build(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION, 1L, 2L, 3L, 11L,
                "继续当前讨论", "继续", null, 320, 64));

        List<LlmRole> historyRoles = result.toMessages().stream()
                .filter(message -> message.content().contains("作者长消息")
                        || message.content().contains("助手长回复")
                        || message.content().contains("作者较短消息")
                        || message.content().contains("助手较短回复"))
                .map(message -> message.role())
                .toList();
        assertThat(historyRoles.size()).isEven();
        for (int index = 0; index < historyRoles.size(); index += 2) {
            assertThat(historyRoles.get(index)).isEqualTo(LlmRole.USER);
            assertThat(historyRoles.get(index + 1)).isEqualTo(LlmRole.ASSISTANT);
        }
    }

    @Test
    void keepsAssistantHistoryAsPlainConversationWithoutCandidateStatusMarkers() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("墨契");
        work.setDeleted(0);
        ChapterEntity chapter = chapter(2L, 1L);
        ChapterConversationEntity conversation = conversation(3L, 1L, 2L);
        ChapterConversationMessageEntity current = message(9L, 3L, 2L, "继续讨论");
        List<ChapterConversationMessageEntity> history = List.of(
                message(5L, 3L, 2L, "加入超自然现象"),
                assistantMessage(6L, 3L, 2L,
                        "【尚未确认的候选，仅供讨论】【尚未确认的候选，仅供讨论】可以让车站出现空间错位。"));
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(messageMapper.selectById(9L)).thenReturn(current);
        when(messageMapper.selectList(any())).thenReturn(history);

        StoryContextSnapshot result = engine.build(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION, 1L, 2L, 3L, 9L,
                "用自然直接的中文继续讨论", "继续讨论", null, 4096, 512));

        assertThat(result.toMessages())
                .anyMatch(message -> message.role() == LlmRole.ASSISTANT
                        && message.content().equals("可以让车站出现空间错位。"));
        assertThat(result.toMessages()).extracting(message -> message.content())
                .noneMatch(content -> content.contains("【尚未确认的候选，仅供讨论】"));
        assertThat(result.toMessages()).extracting(message -> message.content())
                .noneMatch(content -> content.contains("这段是助手先前的建议")
                        || content.contains("以上只是助手先前的建议"));
    }

    @Test
    void suppressesUnconfirmedAssistantDetailsForConvergenceWhileKeepingCompleteTurns() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("墨契");
        work.setDeleted(0);
        ChapterEntity chapter = chapter(2L, 1L);
        ChapterConversationEntity conversation = conversation(3L, 1L, 2L);
        ChapterConversationMessageEntity current = message(9L, 3L, 2L, "深入总结已经确认的内容");
        List<ChapterConversationMessageEntity> history = List.of(
                message(5L, 3L, 2L, "暴雨车站发生空间错位，主角无力救下妹妹"),
                assistantMessage(6L, 3L, 2L, "妹妹手里拿着一张不存在的车票，站牌会变色。"));
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(messageMapper.selectById(9L)).thenReturn(current);
        when(messageMapper.selectList(any())).thenReturn(history);

        StoryContextSnapshot result = engine.build(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION, 1L, 2L, 3L, 9L,
                "深入总结作者已经确认的内容", current.getContent(), null, 4096, 512));

        assertThat(result.toMessages()).extracting(message -> message.role())
                .containsSubsequence(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER);
        assertThat(result.toMessages()).extracting(message -> message.content())
                .contains("暴雨车站发生空间错位，主角无力救下妹妹")
                .contains("妹妹手里拿着一张不存在的车票，站牌会变色。");
    }

    @Test
    void buildsReadableSyntheticLongContextWithoutSplittingHistoryTurns() throws Exception {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("Issue #144 合成长上下文裁剪夹具");
        work.setDeleted(0);
        ChapterEntity chapter = chapter(2L, 1L);
        ChapterConversationEntity conversation = conversation(3L, 1L, 2L);
        ChapterConversationMessageEntity current = message(
                101L,
                3L,
                2L,
                "请只沿已经选定的雨夜车站方向，分析人物自责、亲友关系和调查选择的长期影响。");
        List<FixtureTurn> fixtureTurns = List.of(
                new FixtureTurn(
                        "我确定故事发生在停运多年的北站，时间是连续暴雨后的第三夜。",
                        "北站和第三夜可以作为当前讨论的时空基础；停运原因仍未确定。"),
                new FixtureTurn(
                        "妹妹不是死亡，而是在站台灯熄灭后失踪，这两者不要混写。",
                        "可以把失踪保留为未知状态，不用死亡替代作者的表述。"),
                new FixtureTurn(
                        "我否定监控直接拍到真相，录像只能出现缺帧，不能替人物完成调查。",
                        "录像缺帧可以制造证据断裂，但它仍只是辅助线索。"),
                new FixtureTurn(
                        "主角当晚没有超能力，他只能依靠记忆、车票和目击者口供追查。",
                        "现阶段调查手段限定为普通人的观察与核对。"),
                new FixtureTurn(
                        "那张旧车票可以保留，但日期是否来自未来我还没决定。",
                        "车票已经进入当前方向，日期来源继续保持待定。"),
                new FixtureTurn(
                        "目击者先说看见妹妹上车，后来又否认；我不确定他是在撒谎还是记忆被改写。",
                        "这段矛盾口供可以同时支撑人为隐瞒和异常影响，两种解释都尚未确认。"),
                new FixtureTurn(
                        "母亲只知道兄妹吵过架，不知道北站的事，也不要让她突然提供关键线索。",
                        "母亲目前只承担家庭压力，不越界成为调查答案。"),
                new FixtureTurn(
                        "主角最强烈的是自责，但他并不确定妹妹失踪是否真由那次争吵造成。",
                        "自责属于人物感受，因果仍保持不确定，二者不能合并成事实。"),
                new FixtureTurn(
                        "朋友愿意陪他查一次，却明确拒绝再次夜闯站台，这是关系边界。",
                        "朋友提供有限支持，同时保留对风险的拒绝。"),
                new FixtureTurn(
                        "我撤回站长参与阴谋的设定，站长现在只是不愿谈起旧事故。",
                        "站长不再是已定反派，他的沉默只能作为待解释行为。"),
                new FixtureTurn(
                        "章末只能确认站台空间有异常，不能确认异常是谁制造，也不能找回妹妹。",
                        "章末结果限定为确认异常存在，其来源和妹妹去向继续未知。"),
                new FixtureTurn(
                        "最后保留一个指代：主角说‘那个人也记得’，但‘那个人’具体是谁留到下一轮决定。",
                        "这个指代目前无法恢复，不应替作者指定为目击者、朋友或站长。"));
        List<ChapterConversationMessageEntity> history = new ArrayList<>();
        for (int index = 0; index < fixtureTurns.size(); index++) {
            long turn = index + 1L;
            FixtureTurn fixtureTurn = fixtureTurns.get(index);
            history.add(message(turn * 2 - 1, 3L, 2L,
                    "作者第" + turn + "轮：" + fixtureTurn.user()));
            history.add(assistantMessage(turn * 2, 3L, 2L,
                    "助手第" + turn + "轮：" + fixtureTurn.assistant()));
        }
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(conversationMapper.selectById(3L)).thenReturn(conversation);
        when(messageMapper.selectById(101L)).thenReturn(current);
        when(messageMapper.selectList(any())).thenReturn(history);

        String taskInstruction = new ConversationReplyPromptCompiler().compile(
                new ConversationReplyTaskInputV1(
                        ConversationReplyTaskInputV1.SCHEMA_VERSION,
                        101L,
                        3L,
                        ReplyMode.EXPLORE,
                        ReplyDepth.DEEP,
                        new ReplyScope(
                                "explore_direction", "current_focus", "雨夜车站的空间错位",
                                "changes_only", 1, false),
                        "message",
                        DefaultReplyPolicyResolver.POLICY_VERSION,
                        ConversationReplyTaskInputV1.AUTHORITY_VERSION,
                        false,
                        null,
                        null,
                        false,
                        false,
                        null));
        StoryContextSnapshot snapshot = engine.build(new StoryContextBuildCommand(
                StoryContextProfile.CHAPTER_DISCUSSION, 1L, 2L, 3L, 101L,
                taskInstruction,
                current.getContent(), null, 1536, 512));
        LlmRequest request = new LlmRequest(
                snapshot.toMessages(), new LlmOptions(512, null, List.of(), LlmResponseFormat.TEXT));

        assertThat(snapshot.estimatedInputTokens()).isLessThanOrEqualTo(snapshot.inputBudgetTokens());
        List<StoryContextItem> selectedHistory = snapshot.items().stream()
                .filter(item -> item.sourceType() == StoryContextSourceType.CONVERSATION_TURN)
                .toList();
        assertThat(selectedHistory).isNotEmpty();
        assertThat(selectedHistory.size()).isLessThan(history.size());
        assertThat(selectedHistory.size()).isEven();
        for (int index = 0; index < selectedHistory.size(); index += 2) {
            assertThat(selectedHistory.get(index).messageRole()).isEqualTo("USER");
            assertThat(selectedHistory.get(index + 1).messageRole()).isEqualTo("ASSISTANT");
            assertThat(selectedHistory.get(index).sourceId().replace(":user", ""))
                    .isEqualTo(selectedHistory.get(index + 1).sourceId().replace(":assistant", ""));
        }
        assertThat(selectedHistory).extracting(StoryContextItem::sourceId)
                .contains("23:24:user", "23:24:assistant")
                .doesNotContain("1:2:user", "1:2:assistant");
        assertThat(request.messages().get(request.messages().size() - 1).role()).isEqualTo(LlmRole.USER);
        assertThat(request.messages().get(request.messages().size() - 1).content())
                .isEqualTo(current.getContent());
        assertThat(request.messages()).extracting(message -> message.content())
                .noneMatch(content -> content.contains("权威状态")
                        || content.contains("primaryIntent")
                        || content.contains("allowedChanges")
                        || content.contains("maxCandidates")
                        || content.contains("confirmed")
                        || content.contains("evidence")
                        || content.contains("回复仅用于讨论，不表示 Brief")
                        || content.contains("【作者已经明确确定，必须遵守】作品："));
        assertThat(request.messages()).extracting(message -> message.content())
                .anyMatch(content -> content.equals("【当前作品】作品：Issue #144 合成长上下文裁剪夹具"));
        assertThat(request.messages()).extracting(message -> message.content())
                .anyMatch(content -> content.contains("本轮深入推演当前问题"))
                .noneMatch(content -> content.contains("本轮简洁回答")
                        || content.contains("本轮平衡回答"));
        assertThat(request.options().maxOutputTokens()).isEqualTo(512);

        String snapshotPath = System.getProperty("moqi.qa.providerSnapshotPath");
        if (snapshotPath != null && !snapshotPath.isBlank()) {
            Path output = Path.of(snapshotPath).toAbsolutePath().normalize();
            Files.createDirectories(output.getParent());
            new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                    "fixtureType", "synthetic-long-context-trimming",
                    "constructedTurns", fixtureTurns.size(),
                    "profile", StoryContextProfile.CHAPTER_DISCUSSION,
                    "estimatedInputTokens", snapshot.estimatedInputTokens(),
                    "inputBudgetTokens", snapshot.inputBudgetTokens(),
                    "snapshot", snapshot,
                    "providerRequest", request));
        }
    }

    private record FixtureTurn(String user, String assistant) {
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

    private ChapterConversationMessageEntity assistantMessage(
            Long id,
            Long conversationId,
            Long chapterId,
            String content) {
        ChapterConversationMessageEntity message = message(id, conversationId, chapterId, content);
        message.setMessageRole("assistant");
        return message;
    }
}
