package com.dugnan.moqi.health.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.health.service.HealthQueryService;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock

    private HealthQueryService healthQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        HealthController controller = new HealthController(healthQueryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldReturnUnifiedHealthResponse() throws Exception {
        given(healthQueryService.currentHealth()).willReturn(Map.of(
                "status", "UP",
                "details", Map.of("db", "UP")));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.details.db").value("UP"));
    }
}
