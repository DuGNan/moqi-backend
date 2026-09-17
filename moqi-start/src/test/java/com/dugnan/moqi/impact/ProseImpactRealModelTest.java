package com.dugnan.moqi.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.dugnan.moqi.impact.ProseImpactModels.CreateReportRequest;
import com.dugnan.moqi.llm.LlmProvider;
import com.dugnan.moqi.llm.LlmProviderFactory;
import com.dugnan.moqi.llm.LlmRequest;
import com.dugnan.moqi.llm.LlmResponse;

/**
 * @author dgn
 * @date 2026-09-17
 * @description 显式启用后使用隔离 QA 数据和真实模型验证影响分析；请求证据不含凭据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/moqi_issue_184_v56?useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password="
})
@EnabledIfEnvironmentVariable(named = "MOQI_185_REAL_MODEL_TEST", matches = "true")
class ProseImpactRealModelTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProseImpactService service;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private LlmProviderFactory providers;

    @Test
    void realModelReceivesChapterContextAndProducesValidatedReport() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("moqi_issue_184_v56");
        Long baseline = jdbc.queryForObject("SELECT current_story_release_id FROM works WHERE id=1", Long.class);
        Path evidence = Path.of(System.getenv("MOQI_185_QA_DIR")).toAbsolutePath().normalize();
        assertThat(evidence.toString().replace('\\', '/'))
                .endsWith("/WeWrite/.local/qa-artifacts/issue-185");
        Files.createDirectories(evidence);
        doAnswer(invocation -> {
            LlmProvider observed = spy((LlmProvider) invocation.callRealMethod());
            doAnswer(call -> {
                LlmRequest request = call.getArgument(0);
                Files.writeString(evidence.resolve("actual-provider-messages.json"),
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(request.messages()));
                LlmResponse response = (LlmResponse) call.callRealMethod();
                Files.writeString(evidence.resolve("actual-model-output.json"),
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(response.structuredContent()));
                return response;
            }).when(observed).generate(any());
            return observed;
        }).when(providers).createObserved(any(), any());

        String key = "qa-impact-185-" + UUID.randomUUID();
        var created = service.create(1L, 1L, 12L, new CreateReportRequest(6L, 10L, key));
        var report = created.report();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            report = service.detail(1L, 1L, 12L, report.id());
            if (!"queued".equals(report.reportStatus()) && !"running".equals(report.reportStatus())) {
                break;
            }
            Thread.sleep(250);
        }
        Files.writeString(evidence.resolve("real-model-report.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(report.reportStatus()).isEqualTo("ready");
        assertThat(report.changes()).isNotEmpty();
        assertThat(report.changes()).allSatisfy(change -> assertThat(change.affectedChapterIds()).contains(1L));
        assertThat(service.create(1L, 1L, 12L, new CreateReportRequest(6L, 10L, key)).report().id())
                .isEqualTo(report.id());
        assertThat(jdbc.queryForObject("SELECT current_story_release_id FROM works WHERE id=1", Long.class))
                .isEqualTo(baseline);
    }
}
