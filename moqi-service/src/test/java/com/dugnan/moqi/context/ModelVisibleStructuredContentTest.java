package com.dugnan.moqi.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * @author dgn
 * @date 2026-09-04
 * @description 验证模型可见结构化资料只保留自然语言语义。
 */
class ModelVisibleStructuredContentTest {

    @Test
    void removesInternalFieldsWhileKeepingNarrativeMeaning() {
        String content = """
                {"schemaVersion":2,"chapterPurpose":"完成主角转变","beats":[
                  {"beatKey":"beat-001","summary":"主角回身救人"}],
                 "decisions":[{"key":"choice","status":"confirmed","required":true,
                  "title":"留下救人","candidateSummary":"主角主动承担责任",
                  "sourceMessageIds":[60],"sourceQuotes":[{"messageId":60,"quote":"让他留下"}]}],
                 "contentHash":"hidden"}
                """;

        String rendered = ModelVisibleStructuredContent.render(new ObjectMapper(), content);

        assertThat(rendered)
                .contains("章节作用：完成主角转变", "事件说明：主角回身救人")
                .contains("名称：留下救人", "确认内容：主角主动承担责任", "作者原话：让他留下")
                .doesNotContain("schemaVersion", "chapterPurpose", "beatKey", "candidateSummary")
                .doesNotContain("sourceMessageIds", "messageId", "contentHash", "confirmed", "beat-001", "60");
    }

    @Test
    void keepsHistoricalPlainTextUnchanged() {
        String content = "作者确认主角此时只能使用普通人的判断。";

        assertThat(ModelVisibleStructuredContent.render(new ObjectMapper(), content)).isEqualTo(content);
    }
}
