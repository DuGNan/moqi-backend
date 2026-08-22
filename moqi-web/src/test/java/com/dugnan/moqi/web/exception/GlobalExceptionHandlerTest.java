package com.dugnan.moqi.web.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.dugnan.moqi.common.api.ApiResponse;
import com.dugnan.moqi.common.api.ErrorCode;
import com.dugnan.moqi.common.exception.BusinessException;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 验证 HTTP 异常边界的安全公共失败响应。
 */
class GlobalExceptionHandlerTest {

    @Test
    void exposesConsistentDiagnosticRefAndServiceUnavailableCategory() throws Exception {
        MockMvc mockMvc = mockMvc();

        mockMvc.perform(get("/model-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Diagnostic-Ref",
                        org.hamcrest.Matchers.matchesPattern("diag_[0-9a-f]{32}")))
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("模型服务暂时不可用"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.failure.category").value("service_unavailable"))
                .andExpect(jsonPath("$.failure.retryable").value(true))
                .andExpect(jsonPath("$.failure.diagnosticRef").value(
                        org.hamcrest.Matchers.matchesPattern("diag_[0-9a-f]{32}")))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                result.getResponse().getHeader("X-Diagnostic-Ref"))
                        .isEqualTo(com.jayway.jsonpath.JsonPath.read(
                                result.getResponse().getContentAsString(), "$.failure.diagnosticRef")));
    }

    @Test
    void unexpectedFailureDoesNotExposeExceptionOrSensitivePayload() throws Exception {
        mockMvc().perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("服务暂时无法完成请求"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.failure.category").value("internal"))
                .andExpect(jsonPath("$.failure.retryable").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("prompt-secret-provider"))));
    }

    @Test
    void preservesSafeVersionDataButFiltersSensitiveBusinessPayload() throws Exception {
        mockMvc().perform(get("/version-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("数据已发生变化，请刷新后重试"))
                .andExpect(jsonPath("$.data.version").value(7))
                .andExpect(jsonPath("$.data.prompt").doesNotExist())
                .andExpect(jsonPath("$.data.title").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("chapterId"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("provider-private-draft"))));
    }

    @Test
    void successfulResponseKeepsNullableFailureWithoutDiagnosticHeader() throws Exception {
        mockMvc().perform(get("/success"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Diagnostic-Ref"))
                .andExpect(jsonPath("$.failure").isEmpty());
    }

    @Test
    void doesNotWriteJsonWhenSseClientHasDisconnected() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DisconnectedSseController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/disconnected-sse"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 提供异常边界测试入口。
     */
    @RestController
    private static class FailureController {

        @GetMapping("/model-unavailable")
        ApiResponse<Void> modelUnavailable() {
            throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE, "provider=prompt-secret-provider");
        }

        @GetMapping("/unexpected")
        ApiResponse<Void> unexpected() {
            throw new IllegalStateException("prompt-secret-provider");
        }

        @GetMapping("/success")
        ApiResponse<String> success() {
            return ApiResponse.success("ok");
        }

        @GetMapping("/version-conflict")
        ApiResponse<Void> versionConflict() {
            throw new BusinessException(
                    ErrorCode.CHAPTER_VERSION_CONFLICT,
                    "chapterId must not be null",
                    Map.of("version", 7, "prompt", "secret", "title", "正文标题"));
        }
    }

    /**
     * 提供已断开 SSE 测试入口。
     */
    @RestController
    private static class DisconnectedSseController {

        @GetMapping(value = "/disconnected-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void disconnected() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("client disconnected");
        }
    }
}
