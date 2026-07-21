package com.dugnan.moqi.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author dgn
 * @date 2026-07-21
 * @description 验证 Spring AI DeepSeek 依赖契约与默认禁用边界。
 */
class DeepSeekDependencyBaselineTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeepSeekChatAutoConfiguration.class));

    @Test
    void shouldExposeDeepSeekAsSpringAiChatModel() {
        assertThat(ChatModel.class).isAssignableFrom(DeepSeekChatModel.class);
    }

    @Test
    void shouldNotCreateChatModelWhenChatModelIsDisabled() {
        contextRunner.withPropertyValues("spring.ai.model.chat=none")
                .run(context -> assertThat(context).doesNotHaveBean(ChatModel.class));
    }
}
