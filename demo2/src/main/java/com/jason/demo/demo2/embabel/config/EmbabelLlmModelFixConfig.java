package com.jason.demo.demo2.embabel.config;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.ConfigurableModelProvider;
import com.embabel.common.ai.model.ConfigurableModelProviderProperties;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.ai.model.OptionsConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Embabel openai-custom 使用 StandardOpenAiOptionsConverter，不会在 ChatOptions 中写入 model。
 * Spring AI 2.0 的 OpenAiChatModel 在 prompt 已有 options 时不会合并 defaultOptions，
 * 导致实际请求落到 OpenAI SDK 默认模型 gpt-5-mini。
 */
@Configuration
public class EmbabelLlmModelFixConfig {

    @Bean
    @Primary
    @DependsOn("openAiCustomModelsInitializer")
    ModelProvider fixedModelProvider(
            ConfigurableListableBeanFactory beanFactory,
            ConfigurableModelProviderProperties properties) {
        List<LlmService<?>> llms = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(LlmService.class)) {
            llms.add(withModelInOptions((LlmService<?>) beanFactory.getBean(name)));
        }
        List<EmbeddingService> embeddingServices = Arrays.stream(
                        beanFactory.getBeanNamesForType(EmbeddingService.class))
                .map(name -> (EmbeddingService) beanFactory.getBean(name))
                .toList();
        return new ConfigurableModelProvider(llms, embeddingServices, properties);
    }

    private static LlmService<?> withModelInOptions(LlmService<?> llm) {
        if (llm instanceof SpringAiLlmService springAi) {
            return springAi.withOptionsConverter(new ModelIncludingOptionsConverter(springAi.getName()));
        }
        return llm;
    }

    private static final class ModelIncludingOptionsConverter implements OptionsConverter<OpenAiChatOptions> {

        private final String modelName;

        private ModelIncludingOptionsConverter(String modelName) {
            this.modelName = modelName;
        }

        @Override
        public OpenAiChatOptions convertOptions(LlmOptions options) {
            return OpenAiChatOptions.builder()
                    .model(modelName)
                    .temperature(options.getTemperature())
                    .topP(options.getTopP())
                    .maxTokens(options.getMaxTokens())
                    .presencePenalty(options.getPresencePenalty())
                    .frequencyPenalty(options.getFrequencyPenalty())
                    .build();
        }
    }
}
