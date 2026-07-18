package com.dugnan.moqi.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ConfirmSettingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.ConfirmSettingResult;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.CreateForeshadowingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.IgnoreSettingRequest;
import com.dugnan.moqi.knowledge.dto.KnowledgeModels.IgnoreSettingResult;
import com.dugnan.moqi.knowledge.entity.ChapterKeyEventEntity;
import com.dugnan.moqi.knowledge.entity.ChapterSummaryEntity;
import com.dugnan.moqi.knowledge.entity.ForeshadowingItemEntity;
import com.dugnan.moqi.knowledge.entity.SettingCandidateEntity;
import com.dugnan.moqi.knowledge.entity.SettingEntryEntity;
import com.dugnan.moqi.knowledge.mapper.ChapterKeyEventMapper;
import com.dugnan.moqi.knowledge.mapper.ChapterSummaryMapper;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingCandidateMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

/**
 * @author dgn
 * @date 2026-07-18
 * @description 验证知识层查询、候选状态流转和伏笔校验规则。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceImplTest {

    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private SettingCandidateMapper candidateMapper;
    @Mock
    private SettingEntryMapper settingMapper;
    @Mock
    private ForeshadowingItemMapper foreshadowingMapper;
    @Mock
    private ChapterSummaryMapper summaryMapper;
    @Mock
    private ChapterKeyEventMapper eventMapper;

    private KnowledgeServiceImpl service;

    /**
     * 初始化知识层服务。
     */
    @BeforeEach
    void setUp() {
        service = new KnowledgeServiceImpl(
                workMapper,
                chapterMapper,
                candidateMapper,
                settingMapper,
                foreshadowingMapper,
                summaryMapper,
                eventMapper,
                new ObjectMapper());
    }

    /**
     * 验证候选列表应用章节、状态、类型和关键字过滤，并保持空集合语义。
     */
    @Test
    void filtersSettingCandidatesAndReturnsEmptyList() {
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        when(candidateMapper.selectList(any())).thenReturn(List.of());

        var result = service.listSettingCandidates(1L, 12L, "pending", "character", "林风");

        assertThat(result.candidates()).isEmpty();
        verify(candidateMapper).selectList(any());
    }

    /**
     * 验证候选列表拒绝非法状态和设定类型过滤值。
     */
    @Test
    void rejectsInvalidSettingCandidateFilters() {
        when(workMapper.selectById(1L)).thenReturn(work(1L));

        assertThatThrownBy(() -> service.listSettingCandidates(1L, null, "unknown", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> service.listSettingCandidates(1L, null, "pending", "unknown", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    /**
     * 验证确认候选时创建正式设定并更新候选关联。
     */
    @Test
    void confirmsCandidateByCreatingSetting() {
        SettingCandidateEntity candidate = candidate(501L, 1L, "pending");
        when(candidateMapper.selectById(501L)).thenReturn(candidate);
        when(settingMapper.insert(any(SettingEntryEntity.class))).thenAnswer(invocation -> {
            SettingEntryEntity setting = invocation.getArgument(0);
            setting.setId(301L);
            return 1;
        });
        when(candidateMapper.update(isNull(), org.mockito.ArgumentMatchers.<Wrapper<SettingCandidateEntity>>any()))
                .thenReturn(1);

        var result = service.confirmSettingCandidate(
                501L,
                new ConfirmSettingRequest("character", "林风", "追问隐藏房间的人", null));

        assertThat(result.settingId()).isEqualTo(301L);
        assertThat(result.candidateStatus()).isEqualTo("confirmed");
        assertThat(candidate.getConfirmedSettingId()).isEqualTo(301L);
        verify(candidateMapper).update(
                isNull(),
                org.mockito.ArgumentMatchers.<Wrapper<SettingCandidateEntity>>any());
    }

    /**
     * 验证确认候选可合并到同作品正式设定。
     */
    @Test
    void confirmsCandidateByMergingExistingSetting() {
        SettingCandidateEntity candidate = candidate(501L, 1L, "pending");
        SettingEntryEntity setting = setting(301L, 1L);
        when(candidateMapper.selectById(501L)).thenReturn(candidate);
        when(settingMapper.selectById(301L)).thenReturn(setting);
        when(settingMapper.update(isNull(), org.mockito.ArgumentMatchers.<Wrapper<SettingEntryEntity>>any()))
                .thenReturn(1);
        when(candidateMapper.update(isNull(), org.mockito.ArgumentMatchers.<Wrapper<SettingCandidateEntity>>any()))
                .thenReturn(1);

        var result = service.confirmSettingCandidate(
                501L,
                new ConfirmSettingRequest("character", "林风", "合并后的正文", 301L));

        assertThat(result.settingId()).isEqualTo(301L);
        assertThat(setting.getContent()).isEqualTo("合并后的正文");
        verify(settingMapper).update(isNull(), org.mockito.ArgumentMatchers.<Wrapper<SettingEntryEntity>>any());
        verify(candidateMapper).update(
                isNull(),
                org.mockito.ArgumentMatchers.<Wrapper<SettingCandidateEntity>>any());
    }

    /**
     * 验证并发确认中只有条件更新成功的一方获胜，失败方必须抛错以回滚新建设定。
     */
    @Test
    void rollsBackCreatedSettingWhenConcurrentConfirmWinsCandidate() throws Exception {
        CyclicBarrier updateBarrier = new CyclicBarrier(2);
        AtomicBoolean isConfirmed = new AtomicBoolean();
        AtomicLong settingId = new AtomicLong(301L);
        when(candidateMapper.selectById(501L)).thenAnswer(invocation -> {
            SettingCandidateEntity candidate = candidate(501L, 1L, "pending");
            candidate.setVersion(3);
            return candidate;
        });
        when(settingMapper.insert(any(SettingEntryEntity.class))).thenAnswer(invocation -> {
            SettingEntryEntity setting = invocation.getArgument(0);
            setting.setId(settingId.getAndIncrement());
            return 1;
        });
        when(candidateMapper.update(
                isNull(),
                org.mockito.ArgumentMatchers.<Wrapper<SettingCandidateEntity>>any())).thenAnswer(invocation -> {
            Wrapper<SettingCandidateEntity> update = invocation.getArgument(1);
            assertThat(update.getSqlSegment()).contains("candidate_status", "version", "deleted");
            updateBarrier.await(5, TimeUnit.SECONDS);
            return isConfirmed.compareAndSet(false, true) ? 1 : 0;
        });

        List<Object> outcomes = runConcurrently(
                () -> service.confirmSettingCandidate(
                        501L,
                        new ConfirmSettingRequest("character", "林风", "并发确认正文一", null)),
                () -> service.confirmSettingCandidate(
                        501L,
                        new ConfirmSettingRequest("character", "林风", "并发确认正文二", null)));

        assertThat(outcomes).filteredOn(ConfirmSettingResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance).hasSize(1);
        verify(settingMapper, times(2)).insert(any(SettingEntryEntity.class));
    }

    /**
     * 验证确认合并正式设定时必须同时检查正式设定的版本和软删除条件。
     */
    @Test
    void rejectsMergeWhenSettingVersionRaceIsLost() {
        SettingCandidateEntity candidate = candidate(501L, 1L, "pending");
        candidate.setVersion(2);
        SettingEntryEntity setting = setting(301L, 1L);
        setting.setVersion(4);
        when(candidateMapper.selectById(501L)).thenReturn(candidate);
        when(settingMapper.selectById(301L)).thenReturn(setting);
        when(settingMapper.update(
                isNull(),
                org.mockito.ArgumentMatchers.<Wrapper<SettingEntryEntity>>any())).thenAnswer(invocation -> {
            Wrapper<SettingEntryEntity> update = invocation.getArgument(1);
            assertThat(update.getSqlSegment()).contains("version", "deleted");
            return 0;
        });

        assertThatThrownBy(() -> service.confirmSettingCandidate(
                501L,
                new ConfirmSettingRequest("character", "林风", "并发合并正文", 301L)))
                .isInstanceOf(BusinessException.class);
        verify(candidateMapper, never()).update(
                isNull(),
                org.mockito.ArgumentMatchers.<Wrapper<SettingCandidateEntity>>any());
    }

    /**
     * 验证确认与忽略基于同一 pending 版本竞争时只有一次条件更新可以成功。
     */
    @Test
    void allowsOnlyOneWinnerWhenConfirmAndIgnoreRace() throws Exception {
        CyclicBarrier updateBarrier = new CyclicBarrier(2);
        AtomicBoolean hasWinner = new AtomicBoolean();
        when(candidateMapper.selectById(501L)).thenAnswer(invocation -> {
            SettingCandidateEntity candidate = candidate(501L, 1L, "pending");
            candidate.setVersion(5);
            return candidate;
        });
        when(candidateMapper.update(
                isNull(),
                org.mockito.ArgumentMatchers.<Wrapper<SettingCandidateEntity>>any())).thenAnswer(invocation -> {
            updateBarrier.await(5, TimeUnit.SECONDS);
            return hasWinner.compareAndSet(false, true) ? 1 : 0;
        });
        when(settingMapper.insert(any(SettingEntryEntity.class))).thenAnswer(invocation -> {
            SettingEntryEntity setting = invocation.getArgument(0);
            setting.setId(303L);
            return 1;
        });

        List<Object> outcomes = runConcurrently(
                () -> service.ignoreSettingCandidate(501L, new IgnoreSettingRequest("并发忽略")),
                () -> service.confirmSettingCandidate(
                        501L,
                        new ConfirmSettingRequest("character", "林风", "并发确认正文", null)));

        assertThat(outcomes.stream()
                .filter(result -> result instanceof ConfirmSettingResult || result instanceof IgnoreSettingResult)
                .count()).isEqualTo(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance).hasSize(1);
        verify(candidateMapper, times(2)).update(
                isNull(),
                org.mockito.ArgumentMatchers.<Wrapper<SettingCandidateEntity>>any());
    }

    /**
     * 验证忽略操作幂等，已确认候选不能再忽略。
     */
    @Test
    void ignoresCandidateIdempotentlyAndRejectsConfirmedCandidate() {
        SettingCandidateEntity ignored = candidate(501L, 1L, "ignored");
        when(candidateMapper.selectById(501L)).thenReturn(ignored);

        var result = service.ignoreSettingCandidate(501L, new IgnoreSettingRequest("不保留"));

        assertThat(result.candidateStatus()).isEqualTo("ignored");
        verify(candidateMapper, never()).updateById(any(SettingCandidateEntity.class));

        when(candidateMapper.selectById(502L)).thenReturn(candidate(502L, 1L, "confirmed"));
        assertThatThrownBy(() -> service.ignoreSettingCandidate(502L, new IgnoreSettingRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    /**
     * 验证不存在候选返回明确业务错误。
     */
    @Test
    void rejectsMissingCandidate() {
        when(candidateMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.confirmSettingCandidate(
                999L,
                new ConfirmSettingRequest("character", "林风", "正文", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SETTING_CANDIDATE_NOT_FOUND);
    }

    /**
     * 验证伏笔来源章节必须属于路径作品且偏移范围有效。
     */
    @Test
    void validatesForeshadowingChapterAndOffsets() {
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, 2L));

        CreateForeshadowingRequest request = new CreateForeshadowingRequest(
                12L,
                "隐藏房间",
                "房间回应追问",
                "它在回答我",
                0,
                6,
                "planted",
                null);
        assertThatThrownBy(() -> service.createForeshadowing(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, 1L));
        CreateForeshadowingRequest invalidOffsets = new CreateForeshadowingRequest(
                12L,
                "隐藏房间",
                "房间回应追问",
                "短句",
                3,
                2,
                "planted",
                null);
        assertThatThrownBy(() -> service.createForeshadowing(1L, invalidOffsets))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    /**
     * 验证创建伏笔时返回主键和非空审计时间。
     */
    @Test
    void createsForeshadowingWithAuditTimes() {
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, 1L));
        when(foreshadowingMapper.insert(any(ForeshadowingItemEntity.class))).thenAnswer(invocation -> {
            ForeshadowingItemEntity entity = invocation.getArgument(0);
            entity.setId(801L);
            return 1;
        });

        var result = service.createForeshadowing(
                1L,
                new CreateForeshadowingRequest(
                        12L, "隐藏房间", "房间回应追问", null, null, null, "planted", null));

        assertThat(result.id()).isEqualTo(801L);
        assertThat(result.gmtCreate()).isNotNull();
        assertThat(result.gmtModified()).isNotNull();
    }

    /**
     * 验证伏笔来源原文保持原样，避免清理空白后偏移失真。
     */
    @Test
    void preservesForeshadowingSourceTextForOffsets() {
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, 1L));

        var result = service.createForeshadowing(
                1L,
                new CreateForeshadowingRequest(
                        12L, "隐藏房间", "房间回应追问", "  回应  ", 2, 4, "planted", null));

        assertThat(result.sourceText()).isEqualTo("  回应  ");
        assertThat(result.sourceStartOffset()).isEqualTo(2);
        assertThat(result.sourceEndOffset()).isEqualTo(4);
    }

    /**
     * 验证正式设定与伏笔列表返回结构化字段和空集合。
     */
    @Test
    void readsSettingsAndEmptyForeshadowings() {
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        SettingEntryEntity setting = setting(301L, 1L);
        setting.setAliasesJson("[\"小林\"]");
        setting.setAttributesJson("{\"role\":\"lead\"}");
        when(settingMapper.selectList(any())).thenReturn(List.of(setting));
        when(foreshadowingMapper.selectList(any())).thenReturn(List.of());

        var settings = service.listSettings(1L, "character", "active", "林");
        var foreshadowings = service.listForeshadowings(1L, null, null, null);

        assertThat(settings.settings()).hasSize(1);
        assertThat(settings.settings().get(0).aliases().get(0).asText()).isEqualTo("小林");
        assertThat(settings.settings().get(0).attributes().get("role").asText()).isEqualTo("lead");
        assertThat(foreshadowings.foreshadowings()).isEmpty();
    }

    /**
     * 验证章节摘要和关键事件返回结构化 JSON，事件空态返回空集合。
     */
    @Test
    void readsChapterSummaryAndKeyEvents() {
        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, 1L));
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        ChapterSummaryEntity summary = new ChapterSummaryEntity();
        summary.setId(1101L);
        summary.setWorkId(1L);
        summary.setChapterId(12L);
        summary.setSummary("怀表先给出反应");
        summary.setCharacterChangesJson("[{\"name\":\"姚宁\"}]");
        summary.setNewSettingsJson("[]");
        summary.setNewForeshadowingJson("[]");
        summary.setOpenQuestionsJson("[\"房间为何回应？\"]");
        summary.setSummaryStatus("confirmed");
        summary.setContentRevision(8);
        summary.setDeleted(0);
        when(summaryMapper.selectList(any())).thenReturn(List.of(summary));
        when(eventMapper.selectList(any())).thenReturn(List.of());

        var summaryResult = service.getChapterSummary(12L);
        var eventResult = service.listChapterKeyEvents(12L);

        assertThat(summaryResult.characterChanges().isArray()).isTrue();
        assertThat(summaryResult.openQuestions().get(0).asText()).isEqualTo("房间为何回应？");
        assertThat(eventResult.events()).isEmpty();
    }

    /**
     * 验证存在章节但没有摘要时返回空态，不把空态误判为章节不存在。
     */
    @Test
    void returnsNullWhenChapterHasNoSummary() {
        when(chapterMapper.selectById(12L)).thenReturn(chapter(12L, 1L));
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        when(summaryMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.getChapterSummary(12L)).isNull();
    }

    /**
     * 构造测试作品。
     *
     * @param id 作品 ID
     * @return 作品实体
     */
    private WorkEntity work(Long id) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setTitle("作品");
        work.setDeleted(0);
        return work;
    }

    /**
     * 构造测试章节。
     *
     * @param id 章节 ID
     * @param workId 作品 ID
     * @return 章节实体
     */
    private ChapterEntity chapter(Long id, Long workId) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(id);
        chapter.setWorkId(workId);
        chapter.setTitle("章节");
        chapter.setDeleted(0);
        return chapter;
    }

    /**
     * 构造测试候选设定。
     *
     * @param id 候选 ID
     * @param workId 作品 ID
     * @param status 候选状态
     * @return 候选实体
     */
    private SettingCandidateEntity candidate(Long id, Long workId, String status) {
        SettingCandidateEntity candidate = new SettingCandidateEntity();
        candidate.setId(id);
        candidate.setWorkId(workId);
        candidate.setChapterId(12L);
        candidate.setSettingType("character");
        candidate.setName("林风");
        candidate.setContent("候选正文");
        candidate.setCandidateStatus(status);
        candidate.setDeleted(0);
        candidate.setGmtModified(LocalDateTime.now());
        return candidate;
    }

    /**
     * 构造测试正式设定。
     *
     * @param id 设定 ID
     * @param workId 作品 ID
     * @return 正式设定实体
     */
    private SettingEntryEntity setting(Long id, Long workId) {
        SettingEntryEntity setting = new SettingEntryEntity();
        setting.setId(id);
        setting.setWorkId(workId);
        setting.setSettingType("character");
        setting.setName("林风");
        setting.setContent("旧正文");
        setting.setEntryStatus("active");
        setting.setDeleted(0);
        return setting;
    }

    /**
     * 并发执行两个候选操作并收集成功值或运行时异常。
     *
     * @param first 第一个操作
     * @param second 第二个操作
     * @return 两个操作结果
     * @throws Exception 等待并发任务失败
     */
    private List<Object> runConcurrently(Supplier<?> first, Supplier<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Object> firstResult = CompletableFuture.supplyAsync(() -> outcome(first), executor);
            CompletableFuture<Object> secondResult = CompletableFuture.supplyAsync(() -> outcome(second), executor);
            return List.of(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 将运行时异常转换为可断言的并发结果。
     *
     * @param action 待执行操作
     * @return 成功值或运行时异常
     */
    private Object outcome(Supplier<?> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            return exception;
        }
    }
}
