package com.dugnan.moqi.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * @author dgn
 * @date 2026-08-22
 * @description 验证公共失败分类、重试事实和安全诊断引用。
 */
class PublicFailureFactoryTest {

    @Test
    void mapsStableCategoriesAndRetryFacts() {
        assertThat(PublicFailureFactory.from(ErrorCode.BAD_REQUEST, "diag_validation"))
                .isEqualTo(new PublicFailure("BAD_REQUEST", "validation", false, "diag_validation"));
        assertThat(PublicFailureFactory.from(ErrorCode.CHAPTER_NOT_FOUND, "diag_not_found").category())
                .isEqualTo("not_found");
        assertThat(PublicFailureFactory.from(ErrorCode.CHAPTER_VERSION_CONFLICT, "diag_conflict").category())
                .isEqualTo("conflict");
        assertThat(PublicFailureFactory.from(ErrorCode.MODEL_UNAVAILABLE, "diag_service"))
                .isEqualTo(new PublicFailure("MODEL_UNAVAILABLE", "service_unavailable", true, "diag_service"));
        assertThat(PublicFailureFactory.from("AGENT_EXECUTOR_REJECTED", "diag_task"))
                .isEqualTo(new PublicFailure("AGENT_EXECUTOR_REJECTED", "task_failure", true, "diag_task"));
        assertThat(PublicFailureFactory.from("SERVICE_UNAVAILABLE", "diag_retry"))
                .isEqualTo(new PublicFailure("SERVICE_UNAVAILABLE", "service_unavailable", true, "diag_retry"));
        assertThat(PublicFailureFactory.from("PROVIDER_RAW secret", "diag_internal"))
                .isEqualTo(new PublicFailure("INTERNAL_ERROR", "internal", false, "diag_internal"));
    }

    @Test
    void generatesOpaqueRandomReferences() {
        String first = PublicFailureFactory.newDiagnosticRef();
        String second = PublicFailureFactory.newDiagnosticRef();

        assertThat(first).matches("diag_[0-9a-f]{32}");
        assertThat(second).matches("diag_[0-9a-f]{32}").isNotEqualTo(first);
    }

    @Test
    void sanitizesMessagesAndPreservesOnlyCompatibleVersionFacts() {
        assertThat(PublicFailureFactory.safeMessage(
                ErrorCode.CHAPTER_VERSION_CONFLICT,
                "provider=private-prompt"))
                .isEqualTo("数据已发生变化，请刷新后重试");
        assertThat(PublicFailureFactory.safeMessage(
                ErrorCode.BAD_REQUEST,
                "chapterId must not be null"))
                .isEqualTo("请求内容不符合要求");
        assertThat(PublicFailureFactory.safeData(Map.of(
                "version", 3,
                "title", "正文标题",
                "prompt", "private")))
                .containsExactly(Map.entry("version", 3));
    }
}
