package com.dugnan.moqi.work.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

import com.dugnan.moqi.chapter.entity.ChapterConversationEntity;
import com.dugnan.moqi.chapter.entity.ChapterGenerationEntity;
import com.dugnan.moqi.chapter.mapper.ChapterConversationMapper;
import com.dugnan.moqi.chapter.mapper.ChapterGenerationMapper;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;
import com.dugnan.moqi.knowledge.mapper.ForeshadowingItemMapper;
import com.dugnan.moqi.knowledge.mapper.SettingCandidateMapper;
import com.dugnan.moqi.knowledge.mapper.SettingEntryMapper;
import com.dugnan.moqi.work.dto.CreateChapterCommand;
import com.dugnan.moqi.work.dto.CreateWorkCommand;
import com.dugnan.moqi.work.entity.ChapterEntity;
import com.dugnan.moqi.work.entity.WorkEntity;
import com.dugnan.moqi.work.mapper.ChapterMapper;
import com.dugnan.moqi.work.mapper.ChapterOutlineQueryMapper;
import com.dugnan.moqi.work.mapper.WorkMapper;

@ExtendWith(MockitoExtension.class)
/**
 * @author dgn
 * @date:2026-07-13
 * @description:验证作品与章节服务的查询、创建和打开规则。
 */
class WorkChapterServiceImplTest {

    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ChapterConversationMapper conversationMapper;
    @Mock
    private ChapterGenerationMapper generationMapper;
    @Mock
    private ChapterOutlineQueryMapper outlineMapper;
    @Mock
    private SettingCandidateMapper settingCandidateMapper;
    @Mock
    private SettingEntryMapper settingEntryMapper;
    @Mock
    private ForeshadowingItemMapper foreshadowingMapper;

    private WorkChapterServiceImpl service;

    /**
     * 初始化作品章节服务测试依赖。
     */
    @BeforeEach
    void setUp() {
        service = new WorkChapterServiceImpl(workMapper, chapterMapper, conversationMapper,
                generationMapper, outlineMapper, settingCandidateMapper, settingEntryMapper,
                foreshadowingMapper);
    }

    /**
     * 验证创建作品时会清理标题并使用草稿状态。
     */
    @Test
    void createsTrimmedDraftWork() {
        when(workMapper.insert(any(WorkEntity.class))).thenAnswer(invocation -> {
            WorkEntity entity = invocation.getArgument(0);
            entity.setId(7L);
            entity.setGmtCreate(LocalDateTime.now());
            entity.setGmtModified(entity.getGmtCreate());
            return 1;
        });
        var result = service.createWork(new CreateWorkCommand("  玻璃钟表馆  "));
        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.title()).isEqualTo("玻璃钟表馆");
        assertThat(result.status()).isEqualTo("draft");
        assertThat(result.chapterCount()).isZero();
    }

    /**
     * 验证查询不存在作品时返回业务错误。
     */
    @Test
    void rejectsMissingWork() {
        when(workMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.getWork(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WORK_NOT_FOUND);
    }

    /**
     * 验证创建章节时会按当前最大编号递增并进入共创状态。
     */
    @Test
    void createsNextChapterInCoCreation() {
        WorkEntity work = work(1L);
        when(workMapper.selectById(1L)).thenReturn(work);
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(3L, 1L, 2, null)));
        when(chapterMapper.insert(any(ChapterEntity.class))).thenAnswer(invocation -> {
            ChapterEntity entity = invocation.getArgument(0);
            entity.setId(4L);
            entity.setVersion(0);
            entity.setGmtCreate(LocalDateTime.now());
            entity.setGmtModified(entity.getGmtCreate());
            return 1;
        });
        var result = service.createChapter(1L, new CreateChapterCommand(" 第三章 ", null));
        assertThat(result.chapterNo()).isEqualTo(3);
        assertThat(result.chapterType()).isEqualTo("chapter");
        assertThat(result.workflowStatus()).isEqualTo("co_creation");
        assertThat(result.defaultWorkspace()).isEqualTo("co_creation");
    }

    /**
     * 验证章节字数统计会排除 Unicode 空白字符。
     */
    @Test
    void countsUnicodeNonWhitespaceCharacters() {
        WorkEntity work = work(1L);
        ChapterEntity chapter = chapter(2L, 1L, 1, "龙 族\nA😀");
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(workMapper.selectById(1L)).thenReturn(work);
        assertThat(service.getChapter(2L).wordCount()).isEqualTo(4);
    }

    /**
     * 验证打开章节时按预览、编辑器、共创顺序选择工作区。
     */
    @Test
    void openPrefersEditorThenPreviewThenCoCreation() {
        WorkEntity work = work(1L);
        ChapterEntity chapter = chapter(2L, 1L, 1, "正文");
        when(chapterMapper.selectById(2L)).thenReturn(chapter);
        when(workMapper.selectById(1L)).thenReturn(work);
        ChapterGenerationEntity preview = new ChapterGenerationEntity();
        preview.setId(9L);
        preview.setGenerationStatus("preview");
        when(generationMapper.selectList(any())).thenReturn(List.of(preview));
        when(conversationMapper.selectList(any())).thenReturn(List.of());
        when(outlineMapper.findLatest(2L)).thenReturn(null);
        when(settingCandidateMapper.selectCount(any())).thenReturn(0L);
        assertThat(service.openChapter(2L).defaultWorkspace()).isEqualTo("editor");
        assertThat(service.openChapter(2L).latestPreviewGenerationId()).isEqualTo(9L);

        chapter.setContent("  \n");
        assertThat(service.openChapter(2L).defaultWorkspace()).isEqualTo("generation_preview");
        when(generationMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.openChapter(2L).defaultWorkspace()).isEqualTo("co_creation");
    }

    /**
     * 验证非法数量和章节类型参数会被拒绝。
     */
    @Test
    void rejectsInvalidLimitAndChapterType() {
        assertThatThrownBy(() -> service.listWorks(null, null, 101))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        when(workMapper.selectById(1L)).thenReturn(work(1L));
        assertThatThrownBy(() -> service.createChapter(1L, new CreateChapterCommand("章节", "invalid")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
    }

    /**
     * 构造测试用作品实体。
     *
     * @param id 作品 ID
     * @return 测试作品实体
     */
    private WorkEntity work(Long id) {
        WorkEntity work = new WorkEntity();
        work.setId(id);
        work.setTitle("作品");
        work.setStatus("draft");
        work.setDeleted(0);
        return work;
    }

    /**
     * 构造测试用章节实体。
     *
     * @param id 章节 ID
     * @param workId 作品 ID
     * @param chapterNo 章节编号
     * @param content 章节正文
     * @return 测试章节实体
     */
    private ChapterEntity chapter(Long id, Long workId, int chapterNo, String content) {
        ChapterEntity chapter = new ChapterEntity();
        chapter.setId(id);
        chapter.setWorkId(workId);
        chapter.setTitle("章节");
        chapter.setChapterNo(chapterNo);
        chapter.setChapterType("chapter");
        chapter.setWorkflowStatus("co_creation");
        chapter.setContent(content);
        chapter.setDeleted(0);
        chapter.setVersion(0);
        return chapter;
    }
}
