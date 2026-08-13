package com.dugnan.moqi.planning.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.planning.StoryPlanningService;
import com.dugnan.moqi.planning.ScenePlanConsistencyService;
import com.dugnan.moqi.web.exception.GlobalExceptionHandler;

/**
 * @author dgn
 * @date 2026-08-01
 * @description 验证已移除的作品规划 HTTP 入口不再对外暴露。
 */
class StoryPlanningControllerTest {

    private StoryPlanningService planningService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        planningService = Mockito.mock(StoryPlanningService.class);
        mvc = MockMvcBuilders.standaloneSetup(new StoryPlanningController(planningService,
                Mockito.mock(ScenePlanConsistencyService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void doesNotExposeNarrativePlanRoutes() throws Exception {
        mvc.perform(get("/api/works/2/narrative-plans/latest-draft"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"));
    }
}
