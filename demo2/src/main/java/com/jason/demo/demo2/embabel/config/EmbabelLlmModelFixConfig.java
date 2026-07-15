package com.jason.demo.demo2.embabel.config;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.ConfigurableModelProvider;
import com.embabel.common.ai.model.ConfigurableModelProviderProperties;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.ai.model.OptionsConverter;
import com.jason.demo.demo2.config.LoggingChatModel;
import org.springframework.ai.chat.model.ChatModel;
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
 * <p>
 * 同时用 {@link LoggingChatModel} 包装 ChatModel，记录 Embabel 侧 LLM 请求/响应
 *（主路径经 MessageSender 直调 ChatModel，不经 ChatClient Advisor）。
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
            llms.add(withLoggingAndModelInOptions((LlmService<?>) beanFactory.getBean(name)));
        }
        List<EmbeddingService> embeddingServices = Arrays.stream(
                        beanFactory.getBeanNamesForType(EmbeddingService.class))
                .map(name -> (EmbeddingService) beanFactory.getBean(name))
                .toList();
        return new ConfigurableModelProvider(llms, embeddingServices, properties);
    }

    private static LlmService<?> withLoggingAndModelInOptions(LlmService<?> llm) {
        if (!(llm instanceof SpringAiLlmService springAi)) {
            return llm;
        }
        ChatModel chatModel = springAi.getChatModel();
        if (!(chatModel instanceof LoggingChatModel)) {
            chatModel = new LoggingChatModel(chatModel, springAi.getName());
        }
        return springAi.copy(
                springAi.getName(),
                springAi.getProvider(),
                chatModel,
                new ModelIncludingOptionsConverter(springAi.getName()),
                springAi.getKnowledgeCutoffDate(),
                springAi.getPromptContributors(),
                springAi.getPricingModel(),
                springAi.getThinkingSupported(),
                springAi.getToolResponseContentAdapter(),
                springAi.getNativeStructuredOutputConfigurer(),
                springAi.getNativeSupport());
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
