package com.dugnan.moqi.release;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.chapter.dto.GenerationEvaluationModels.RetryEvaluationRequest;

/**
 * @author dgn
 * @date 2026-09-15
 * @description 验证修订质量接口完整传递作品、章节、修订、报告和并发参数。
 */
class RevisionQualityControllerTest {
    @Test
    void routesRevisionIdentityAndOptimisticParameters() throws Exception {
        RevisionQualityService service = mock(RevisionQualityService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RevisionQualityController(service)).build();
        String base = "/api/works/1/story-revisions/chapters/2/revisions/12/quality-evaluation";
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}"))
                .andExpect(status().isOk());
        verify(service).start(1L, 2L, 12L, 3);
        mvc.perform(get(base)).andExpect(status().isOk());
        verify(service).latest(1L, 2L, 12L);
        mvc.perform(post(base + "/31/retry").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedAttempt\":2}")).andExpect(status().isOk());
        verify(service).retry(1L, 2L, 12L, 31L, new RetryEvaluationRequest(2));
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content("malformed"))
                .andExpect(status().isBadRequest());
    }
}
