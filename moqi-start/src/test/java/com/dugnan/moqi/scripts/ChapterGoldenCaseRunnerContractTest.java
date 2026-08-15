package com.dugnan.moqi.scripts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ChapterGoldenCaseRunnerContractTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void runnerKeepsCandidatesAndEvidenceWithoutProviderSecrets() throws IOException {
        String script = Files.readString(ROOT.resolve("scripts/run-chapter-golden-case.ps1"));

        assertThat(script).contains("[ValidateRange(6, 100)] [int] $SampleCount = 6")
                .contains("Configuration drift detected")
                .contains("includeCurrentContent = $false")
                .contains("page=1&pageSize=100")
                .contains("humanScoringComplete = $false")
                .contains("userConfirmed = $false")
                .contains("needs_revision")
                .doesNotContain("/accept")
                .doesNotContain("apiKey")
                .doesNotContain("Authorization");
    }

    @Test
    void scorecardHasTenUnconfirmedDimensions() throws IOException {
        JsonNode scorecard = new ObjectMapper().readTree(
                ROOT.resolve("scripts/golden-case/scorecard-template.json").toFile());

        assertThat(scorecard.path("dimensions")).hasSize(10);
        assertThat(scorecard.path("codexDraftCompleted").asBoolean()).isFalse();
        assertThat(scorecard.path("userConfirmed").asBoolean()).isFalse();
        scorecard.path("dimensions").forEach(dimension -> {
            assertThat(dimension.path("codexDraftScore").isNull()).isTrue();
            assertThat(dimension.path("userScore").isNull()).isTrue();
        });
    }
}
