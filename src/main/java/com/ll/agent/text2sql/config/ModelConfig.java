package com.ll.agent.text2sql.config;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 大模型 Bean：智能体与入口守卫共用同一个模型实例。 */
@Configuration
public class ModelConfig {

    @Bean
    public Model chatModel(T2SqlProperties properties) {
        return OpenAIChatModel.builder()
                .baseUrl(properties.getLlm().getBaseUrl())
                .apiKey(properties.getLlm().effectiveApiKey())
                .modelName(properties.getLlm().getModel())
                .build();
    }
}
