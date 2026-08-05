package com.dugnan.moqi.chapter.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParseException;
import org.springframework.context.ApplicationEventPublisher;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.CreateGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.AcceptGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.RegenerateRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.RejectGenerationRequest;
import com.dugnan.moqi.chapter.dto.ChapterGenerationModels.SaveContentRequest;
import com.dugnan.moqi.chapter.entity.AiTaskEntity;
import com.dugnan.moqi.chapter.entity.ChapterBriefEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.generator.ChapterContentGenerator;
import com.dugnan.moqi.chapter.mapper.AiTaskMapper;
import com.dugnan.moqi.chapter.mapper.ChapterBriefMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.ChapterOutlineEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 验证章节生成服务的创建与状态流转规则。
 */
@ExtendWith(MockitoExtension.class)
class ChapterGenerationServiceImplTest {

    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ChapterOutlineQueryMapper outlineMapper;
    @Mock
    private ChapterBriefMapper briefMapper;
    @Mock
    private ChapterGenerationMapper generationMapper;
    @Mock
    private AiTaskMapper aiTaskMapper;
    @Mock
    private ChapterContentGenerator contentGenerator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ChapterGenerationServiceImpl service;

    /**
     * 初始化章节生成服务测试依赖。
     */
    @BeforeEach
    void setUp() {
        service = new ChapterGenerationServiceImpl(
                workMapper,
                chapterMapper,
                outlineMapper,
                briefMapper,
                generationMapper,
                aiTaskMapper,
                contentGenerator,
                eventPublisher);
    }

    /**
     * 验证创建响应保留 draft，同时生成资源可完成为预览。
     */
    @Test
    void createsDraftGenerationResponse() {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(12L);
        chapter.setWorkId(1L);
        chapter.setTitle("房间\t终于回答了");
        chapter.setDeleted(0);
        when(chapterMapper.selectById(12L)).thenReturn(chapter);

        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("玻璃钟表馆");
        work.setDeleted(0);
        when(workMapper.selectById(1L)).thenReturn(work);

        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setId(1201L);
        outline.setWorkId(1L);
        outline.setChapterId(12L);
        outline.setRevision(4);
        outline.setOutlineContent("{\"goal\":\"隐藏房间回应林风\"}");
        outline.setDeleted(0);
        when(outlineMapper.findLatest(12L)).thenReturn(outline);

        ChapterBriefEntity brief = new ChapterBriefEntity();
        brief.setId(501L);
        brief.setChapterId(12L);
        brief.setBriefStatus("confirmed");
        brief.setBriefContent("姚宁第一次改写自己的判断");
        brief.setVersion(3);
        brief.setDeleted(0);
        when(briefMapper.findLatestByChapterIdAndStatus(12L, "confirmed")).thenReturn(brief);

        when(aiTaskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            AiTaskEntity task = invocation.getArgument(0);
            task.setId(9003L);
            return 1;
        });
        when(generationMapper.insert(any(ChapterGenerationEntity.class))).thenAnswer(invocation -> {
            ChapterGenerationEntity generation = invocation.getArgument(0);
            assertThat(generation.getOutlineId()).isEqualTo(1201L);
            assertThat(generation.getOutlineRevision()).isEqualTo(4);
            assertThat(generation.getGenerationMode()).isEqualTo("full_draft");
            assertThat(generation.getLengthPreset()).isEqualTo("about_3000");
            assertThat(generation.getBasisSnapshotJson()).contains("隐藏房间回应林风");
            assertThat(generation.getBasisSnapshotJson()).contains("姚宁第一次改写自己的判断");
            assertThat(generation.getBasisSnapshotJson()).contains("\"confirmedBriefId\":501");
            assertThat(generation.getBasisSnapshotJson()).contains("\"confirmedBriefVersion\":3");
            assertThat(generation.getBasisSnapshotJson()).contains("房间\\t终于回答了");
            assertThat(generation.getGenerationStatus()).isEqualTo("draft");
            assertThat(generation.getAiTaskId()).isEqualTo(9003L);
            generation.setId(7001L);
            return 1;
        });
        when(contentGenerator.generate(any())).thenReturn("怀表冷下去以后，楼里的风声反而更清楚了。");
        ChapterGenerationEntity persisted = generation(7001L, "preview", "怀表冷下去以后，楼里的风声反而更清楚了。");
        persisted.setGmtCreate(LocalDateTime.of(2026, 7, 18, 20, 0));
        when(generationMapper.selectById(7001L)).thenReturn(persisted);

        var result = service.createGeneration(
                12L,
                new CreateGenerationRequest(1201L, 4, "full_draft", "about_3000", null));

        assertThat(result.generationId()).isEqualTo(7001L);
        assertThat(result.aiTaskId()).isEqualTo(9003L);
        assertThat(result.generationStatus()).isEqualTo("draft");
        assertThat(result.gmtCreate()).isEqualTo(LocalDateTime.of(2026, 7, 18, 20, 0));
        verify(generationMapper).updateById(org.mockito.ArgumentMatchers.<ChapterGenerationEntity>argThat(generation ->
                "preview".equals(generation.getGenerationStatus())
                        && "怀表冷下去以后，楼里的风声反而更清楚了。".equals(generation.getGeneratedContent())
                        && generation.getWordCount() > 0));
        verify(aiTaskMapper).updateById(org.mockito.ArgumentMatchers.<AiTaskEntity>argThat(task ->
                "succeeded".equals(task.getTaskStatus())
                        && Long.valueOf(7001L).equals(task.getResultGenerationId())));
        verify(briefMapper).findLatestByChapterIdAndStatus(12L, "confirmed");
    }

    /**
     * 验证创建生成时会拒绝不存在的章节。
     */
    @Test
    void rejectsMissingChapterWhenCreatingGeneration() {
        when(chapterMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.createGeneration(
                99L,
                new CreateGenerationRequest(1201L, 4, "full_draft", "about_3000", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_NOT_FOUND);
    }

    /**
     * 验证创建生成时区分缺少大纲与大纲 revision 冲突。
     */
    @Test
    void rejectsMissingOutlineAndStaleRevision() {
        ChapterEntity chapter = chapter(12L, "旧正文", 3);
        when(chapterMapper.selectById(12L)).thenReturn(chapter);
        when(workMapper.selectById(1L)).thenReturn(work());
        when(outlineMapper.findLatest(12L)).thenReturn(null);

        assertThatThrownBy(() -> service.createGeneration(
                12L,
                new CreateGenerationRequest(1201L, 4, "full_draft", "about_3000", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OUTLINE_NOT_FOUND);

        when(outlineMapper.findLatest(12L)).thenReturn(outline());
        assertThatThrownBy(() -> service.createGeneration(
                12L,
                new CreateGenerationRequest(1201L, 3, "full_draft", "about_3000", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OUTLINE_REVISION_CONFLICT);
    }

    /**
     * 验证没有已确认 Brief 时不允许创建正文生成。
     */
    @Test
    void requiresConfirmedBriefWhenCreatingGeneration() {
        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, "", 3));
        when(workMapper.selectById(1L)).thenReturn(work());
        when(outlineMapper.findLatest(12L)).thenReturn(outline());
        when(briefMapper.findLatestByChapterIdAndStatus(12L, "confirmed")).thenReturn(null);

        assertThatThrownBy(() -> service.createGeneration(
                12L,
                new CreateGenerationRequest(1201L, 4, "full_draft", "about_3000", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED);
    }

    /**
     * 验证显式选择 draft Brief 时拒绝生成。
     */
    @Test
    void rejectsExplicitDraftBriefWhenCreatingGeneration() {
        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, "", 3));
        when(workMapper.selectById(1L)).thenReturn(work());
        when(outlineMapper.findLatest(12L)).thenReturn(outline());
        ChapterBriefEntity draft = new ChapterBriefEntity();
        draft.setId(502L);
        draft.setChapterId(12L);
        draft.setBriefStatus("draft");
        draft.setDeleted(0);
        when(briefMapper.findByIdAndChapterId(502L, 12L)).thenReturn(draft);

        assertThatThrownBy(() -> service.createGeneration(
                12L,
                new CreateGenerationRequest(
                        1201L,
                        4,
                        "full_draft",
                        "about_3000",
                        null,
                        502L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED);
    }

    /**
     * 验证生成使用的已确认 Brief 必须与大纲绑定版本一致。
     */
    @Test
    void rejectsConfirmedBriefDifferentFromOutlineBinding() {
        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, "", 3));
        when(workMapper.selectById(1L)).thenReturn(work());
        ChapterOutlineEntity outline = outline();
        outline.setConfirmedBriefId(501L);
        when(outlineMapper.findLatest(12L)).thenReturn(outline);
        ChapterBriefEntity anotherConfirmed = new ChapterBriefEntity();
        anotherConfirmed.setId(503L);
        anotherConfirmed.setChapterId(12L);
        anotherConfirmed.setBriefStatus("confirmed");
        anotherConfirmed.setDeleted(0);
        when(briefMapper.findByIdAndChapterId(503L, 12L)).thenReturn(anotherConfirmed);

        assertThatThrownBy(() -> service.createGeneration(
                        12L,
                        new CreateGenerationRequest(
                                1201L,
                                4,
                                "full_draft",
                                "about_3000",
                                null,
                                503L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CHAPTER_CONFIRMED_BRIEF_REQUIRED);
    }

    /**
     * 验证生成详情返回完整持久化字段，缺失资源返回明确错误。
     */
    @Test
    void getsGenerationDetailAndRejectsMissingGeneration() {
        ChapterGenerationEntity generation = generation(7001L, "preview", "预览正文");
        when(generationMapper.selectById(7001L)).thenReturn(generation);

        var detail = service.getGeneration(7001L);

        assertThat(detail.outlineId()).isEqualTo(1201L);
        assertThat(detail.outlineRevision()).isEqualTo(4);
        assertThat(detail.generationMode()).isEqualTo("full_draft");
        assertThat(detail.basisSnapshot()).containsEntry("chapterTitle", "房间终于回答了");
        assertThat(detail.generatedContent()).isEqualTo("预览正文");
        assertThat(detail.aiTaskId()).isEqualTo(9003L);

        when(generationMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.getGeneration(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GENERATION_NOT_FOUND);
    }

    /**
     * 验证损坏的生成依据不会泄露创作内容，并保留原始 cause 和单条脱敏日志。
     */
    @Test
    void protectsInvalidGenerationBasisContent() {
        String sensitiveBasis = "{\"chapterTitle\":\"未闭合的完整创作内容：林风推开门";
        ChapterGenerationEntity generation = generation(7001L, "preview", "预览正文");
        generation.setBasisSnapshotJson(sensitiveBasis);
        when(generationMapper.selectById(7001L)).thenReturn(generation);
        Logger logger = (Logger) LoggerFactory.getLogger(ChapterGenerationServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> service.getGeneration(7001L))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                        assertThat(exception.getData()).isEmpty();
                        assertThat(exception.getCause()).isInstanceOf(JsonParseException.class);
                    });
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("generationId=7001");
                assertThat(event.getFormattedMessage()).doesNotContain(sensitiveBasis, "林风推开门");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * 验证最新已确认 Brief 在数据库侧按状态排序限一。
     *
     * @throws NoSuchMethodException Mapper 查询方法不存在
     */
    @Test
    void latestBriefQuerySortsAndLimitsAtDatabase() throws NoSuchMethodException {
        Select select = ChapterBriefMapper.class
                .getMethod("findLatestByChapterIdAndStatus", Long.class, String.class)
                .getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("brief_status")
                .contains("brief_status = #{briefStatus}")
                .doesNotContain("brief_type")
                .contains("ORDER BY gmt_modified DESC, id DESC")
                .contains("LIMIT 1");
    }

    /**
     * 验证章节没有待处理预览时返回显式空态。
     */
    @Test
    void returnsEmptyLatestPreview() {
        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, "", 3));
        when(generationMapper.findLatestPreview(12L)).thenReturn(null);

        var preview = service.getLatestPreview(12L);

        assertThat(preview.generationId()).isNull();
        assertThat(preview.chapterId()).isEqualTo(12L);
        assertThat(preview.generationStatus()).isNull();
        verify(generationMapper).findLatestPreview(12L);
    }

    /**
     * 验证最近预览在数据库侧排序限一，并避免读取正文长字段。
     *
     * @throws NoSuchMethodException Mapper 查询方法不存在
     */
    @Test
    void latestPreviewQuerySortsAndLimitsAtDatabase() throws NoSuchMethodException {
        Select select = ChapterGenerationMapper.class
                .getMethod("findLatestPreview", Long.class)
                .getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("ORDER BY gmt_create DESC, id DESC")
                .contains("LIMIT 1")
                .doesNotContain("generated_content", "basis_snapshot_json");
    }

    /**
     * 验证采纳生成稿支持替换正文。
     */
    @Test
    void acceptsPreviewByReplacingContent() {
        ChapterEntity current = chapter(12L, "旧正文", 3);
        ChapterEntity saved = chapter(12L, "预览正文", 4);
        when(generationMapper.selectById(7001L)).thenReturn(generation(7001L, "preview", "预览正文"));
        when(chapterMapper.selectById(12L)).thenReturn(current, saved);
        when(generationMapper.updateStatusIfCurrent(7001L, "preview", "accepted")).thenReturn(1);
        when(chapterMapper.updateContentIfVersion(12L, "预览正文", 3)).thenReturn(1);

        var result = service.acceptGeneration(7001L, new AcceptGenerationRequest("replace", 3));

        assertThat(result.generationStatus()).isEqualTo("accepted");
        assertThat(result.version()).isEqualTo(4);
        verify(chapterMapper).updateContentIfVersion(12L, "预览正文", 3);
        verify(eventPublisher).publishEvent(
                new com.dugnan.moqi.chapter.event.ChapterGenerationAcceptedEvent(12L, 7001L));
    }

    /**
     * 验证采纳生成稿支持追加正文。
     */
    @Test
    void acceptsPreviewByAppendingContent() {
        ChapterEntity current = chapter(12L, "旧正文", 3);
        ChapterEntity saved = chapter(12L, "旧正文\n\n预览正文", 4);
        when(generationMapper.selectById(7001L)).thenReturn(generation(7001L, "preview", "预览正文"));
        when(chapterMapper.selectById(12L)).thenReturn(current, saved);
        when(generationMapper.updateStatusIfCurrent(7001L, "preview", "accepted")).thenReturn(1);
        when(chapterMapper.updateContentIfVersion(12L, "旧正文\n\n预览正文", 3)).thenReturn(1);

        service.acceptGeneration(7001L, new AcceptGenerationRequest("append", 3));

        verify(chapterMapper).updateContentIfVersion(12L, "旧正文\n\n预览正文", 3);
    }

    /**
     * 验证重复采纳不会再次写入章节正文。
     */
    @Test
    void repeatedAcceptDoesNotWriteContentTwice() {
        when(generationMapper.selectById(7001L)).thenReturn(generation(7001L, "accepted", "预览正文"));
        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, "预览正文", 4));

        var result = service.acceptGeneration(7001L, new AcceptGenerationRequest("replace", 3));

        assertThat(result.version()).isEqualTo(4);
        verify(chapterMapper, never()).updateContentIfVersion(any(), any(), any());
    }

    /**
     * 验证采纳版本冲突返回服务端正文、版本和保存时间。
     */
    @Test
    void returnsServerStateWhenAcceptVersionConflicts() {
        ChapterEntity current = chapter(12L, "旧正文", 3);
        ChapterEntity server = chapter(12L, "服务端正文", 4);
        server.setGmtModified(LocalDateTime.of(2026, 7, 18, 20, 30));
        when(generationMapper.selectById(7001L)).thenReturn(generation(7001L, "preview", "预览正文"));
        when(chapterMapper.selectById(12L)).thenReturn(current, server);
        when(generationMapper.updateStatusIfCurrent(7001L, "preview", "accepted")).thenReturn(1);
        when(chapterMapper.updateContentIfVersion(12L, "预览正文", 3)).thenReturn(0);

        assertThatThrownBy(() -> service.acceptGeneration(
                7001L,
                new AcceptGenerationRequest("replace", 3)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAPTER_VERSION_CONFLICT);
                    assertThat(exception.getData())
                            .containsEntry("serverContent", "服务端正文")
                            .containsEntry("version", 4)
                            .containsEntry("serverSavedAt", server.getGmtModified());
                });
    }

    /**
     * 验证只有 preview 状态可以首次采纳或拒绝。
     */
    @Test
    void rejectsAcceptAndRejectFromIllegalStatus() {
        when(generationMapper.selectById(7001L)).thenReturn(generation(7001L, "draft", null));

        assertThatThrownBy(() -> service.acceptGeneration(
                7001L,
                new AcceptGenerationRequest("replace", 3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GENERATION_STATUS_CONFLICT);
        assertThatThrownBy(() -> service.rejectGeneration(
                7001L,
                new RejectGenerationRequest("继续讨论")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GENERATION_STATUS_CONFLICT);
    }

    /**
     * 验证 preview 可以被拒绝。
     */
    @Test
    void rejectsPreviewGeneration() {
        ChapterGenerationEntity preview = generation(7001L, "preview", "预览正文");
        ChapterGenerationEntity rejected = generation(7001L, "rejected", "预览正文");
        when(generationMapper.selectById(7001L)).thenReturn(preview, rejected);
        when(generationMapper.updateStatusIfCurrent(7001L, "preview", "rejected")).thenReturn(1);

        var result = service.rejectGeneration(7001L, new RejectGenerationRequest("继续讨论"));

        assertThat(result.generationStatus()).isEqualTo("rejected");
    }

    /**
     * 验证重新生成会保留原依据并合并反馈。
     */
    @Test
    void regeneratesFromOriginalBasisWithFeedback() {
        ChapterGenerationEntity original = generation(7001L, "preview", "预览正文");
        ChapterEntity renamedChapter = chapter(12L, "", 3);
        renamedChapter.setTitle("改名后的章节");
        when(chapterMapper.selectById(12L)).thenReturn(renamedChapter);
        when(workMapper.selectById(1L)).thenReturn(work());
        when(aiTaskMapper.insert(any(AiTaskEntity.class))).thenAnswer(invocation -> {
            AiTaskEntity task = invocation.getArgument(0);
            task.setId(9006L);
            return 1;
        });
        when(generationMapper.insert(any(ChapterGenerationEntity.class))).thenAnswer(invocation -> {
            ChapterGenerationEntity generation = invocation.getArgument(0);
            generation.setId(7002L);
            return 1;
        });
        when(contentGenerator.generate(any())).thenReturn("更克制的重写稿");
        ChapterGenerationEntity persisted = generation(7002L, "preview", "更克制的重写稿");
        persisted.setGmtCreate(LocalDateTime.of(2026, 7, 18, 21, 0));
        when(generationMapper.selectById(7001L)).thenReturn(original);
        when(generationMapper.selectById(7002L)).thenReturn(persisted);

        var result = service.regenerate(
                7001L,
                new RegenerateRequest("让姚宁的反应更克制", "full_draft", "about_3000", null));

        assertThat(result.generationId()).isEqualTo(7002L);
        assertThat(result.generationStatus()).isEqualTo("draft");
        assertThat(result.gmtCreate()).isEqualTo(LocalDateTime.of(2026, 7, 18, 21, 0));
        verify(contentGenerator).generate(org.mockito.ArgumentMatchers.argThat(input ->
                "让姚宁的反应更克制".equals(input.feedback())
                        && "房间终于回答了".equals(input.chapterTitle())
                        && input.outlineContent().contains("隐藏房间回应林风")));
        verify(generationMapper).insert(org.mockito.ArgumentMatchers.<ChapterGenerationEntity>argThat(generation ->
                generation.getOutlineId().equals(1201L)
                        && generation.getOutlineRevision().equals(4)
                        && generation.getBasisSnapshotJson().contains("房间终于回答了")
                        && !generation.getBasisSnapshotJson().contains("改名后的章节")
                        && generation.getBasisSnapshotJson().contains("让姚宁的反应更克制")));
    }

    /**
     * 验证正文读取与条件保存成功路径。
     */
    @Test
    void getsAndSavesChapterContent() {
        ChapterEntity current = chapter(12L, "旧正文", 3);
        current.setGmtModified(LocalDateTime.of(2026, 7, 18, 20, 0));
        ChapterEntity saved = chapter(12L, "新正文", 4);
        saved.setGmtModified(LocalDateTime.of(2026, 7, 18, 20, 1));
        when(chapterMapper.selectById(12L)).thenReturn(current, current, saved);
        when(chapterMapper.updateContentIfVersion(12L, "新正文", 3)).thenReturn(1);

        var content = service.getContent(12L);
        var result = service.saveContent(12L, new SaveContentRequest("新正文", 3, "manual"));

        assertThat(content.content()).isEqualTo("旧正文");
        assertThat(content.version()).isEqualTo(3);
        assertThat(result.saved()).isTrue();
        assertThat(result.version()).isEqualTo(4);
        verify(chapterMapper).updateContentIfVersion(12L, "新正文", 3);
    }

    /**
     * 验证正文保存版本冲突返回服务端状态。
     */
    @Test
    void returnsServerStateWhenContentSaveConflicts() {
        ChapterEntity current = chapter(12L, "旧正文", 3);
        ChapterEntity server = chapter(12L, "服务端正文", 4);
        server.setGmtModified(LocalDateTime.of(2026, 7, 18, 20, 30));
        when(chapterMapper.selectById(12L)).thenReturn(current, server);
        when(chapterMapper.updateContentIfVersion(12L, "新正文", 3)).thenReturn(0);

        assertThatThrownBy(() -> service.saveContent(
                12L,
                new SaveContentRequest("新正文", 3, "auto_save")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHAPTER_VERSION_CONFLICT);
                    assertThat(exception.getData())
                            .containsEntry("serverContent", "服务端正文")
                            .containsEntry("version", 4)
                            .containsEntry("serverSavedAt", server.getGmtModified());
                });
    }

    private WorkEntity work() {
        WorkEntity work = new WorkEntity();
        work.setId(1L);
        work.setTitle("玻璃钟表馆");
        work.setDeleted(0);
        return work;
    }

    private ChapterEntity chapter(Long id, String content, int version) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(id);
        chapter.setWorkId(1L);
        chapter.setTitle("房间终于回答了");
        chapter.setContent(content);
        chapter.setWorkflowStatus("co_creation");
        chapter.setVersion(version);
        chapter.setDeleted(0);
        return chapter;
    }

    private ChapterOutlineEntity outline() {
        ChapterOutlineEntity outline = new ChapterOutlineEntity();
        outline.setId(1201L);
        outline.setWorkId(1L);
        outline.setChapterId(12L);
        outline.setRevision(4);
        outline.setOutlineContent("{\"goal\":\"隐藏房间回应林风\"}");
        outline.setDeleted(0);
        return outline;
    }

    private ChapterGenerationEntity generation(Long id, String status, String content) {
        ChapterGenerationEntity generation = new ChapterGenerationEntity();
        generation.setId(id);
        generation.setWorkId(1L);
        generation.setChapterId(12L);
        generation.setOutlineId(1201L);
        generation.setOutlineRevision(4);
        generation.setGenerationStatus(status);
        generation.setGenerationMode("full_draft");
        generation.setLengthPreset("about_3000");
        generation.setBasisSnapshotJson(
                "{\"outlineId\":1201,\"outlineRevision\":4,"
                        + "\"chapterTitle\":\"房间终于回答了\","
                        + "\"briefContent\":\"姚宁第一次改写自己的判断\","
                        + "\"outlineContent\":\"隐藏房间回应林风\"}");
        generation.setGeneratedContent(content);
        generation.setWordCount(content == null ? 0 : content.length());
        generation.setAiTaskId(9003L);
        generation.setDeleted(0);
        return generation;
    }
}
