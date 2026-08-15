package com.dugnan.moqi.release;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.release.StoryReleaseModels.ReleaseView;
import com.dugnan.moqi.release.StoryReleaseModels.RevisionView;

/**
 * @author dgn
 * @date 2026-08-15
 * @description 验证 Story Release API 保留 revision 候选和用户显式确认参数。
 */
class StoryReleaseControllerTest {
    private StoryReleaseService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StoryReleaseService.class);
        mvc = MockMvcBuilders.standaloneSetup(new StoryReleaseController(service)).build();
    }

    @Test
    void createsRevisionDraftWithoutPublishingChapter() throws Exception {
        when(service.createRevision(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(2L), any())).thenReturn(revision());

        mvc.perform(post("/api/works/1/story-revisions/chapters/2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentRevisionId\":5,\"content\":\"新正文\",\"idempotencyKey\":\"r-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revisionStatus").value("draft"));
    }

    @Test
    void publishEndpointCarriesExplicitConfirmation() throws Exception {
        when(service.publishWorkspace(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L), any())).thenReturn(release());

        mvc.perform(post("/api/works/1/story-revisions/workspaces/10/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"idempotencyKey\":\"p-1\",\"userConfirmed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.releaseStatus").value("current"));
        verify(service).publishWorkspace(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L), any());
    }

    @Test
    void rollbackCreatesAuditedReleaseInsteadOfMutatingHistoricalRelease() throws Exception {
        when(service.rollback(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(release());

        mvc.perform(post("/api/works/1/story-revisions/releases/7/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCurrentReleaseId\":8,\"expectedWorkVersion\":4,"
                                + "\"idempotencyKey\":\"rb-1\",\"userConfirmed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9));
    }

    private RevisionView revision() {
        return new RevisionView(6L, 1L, 2L, 5L, null, null, null, null, 2,
                "manual", "draft", "新正文", "hash", 0, LocalDateTime.now(), LocalDateTime.now());
    }

    private ReleaseView release() {
        return new ReleaseView(9L, 1L, 8L, 7L, 3, "current", "hash", List.of(), 1,
                LocalDateTime.now(), LocalDateTime.now());
    }
}
