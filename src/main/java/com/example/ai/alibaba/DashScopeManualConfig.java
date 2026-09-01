package com.example.ai.alibaba;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
// Only create DashScope bean when api-key is actually set (not dummy/empty)
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${spring.ai.dashscope.api-key:dummy}' != 'dummy'")
public class DashScopeManualConfig {

    @Bean
    public DashScopeChatModel dashScopeChatModel(
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}") String model,
            @Value("${spring.ai.dashscope.chat.options.temperature:0.7}") Double temperature) {

        DashScopeApi api = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();

        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .withModel(model)
                .withTemperature(temperature)
                .build();

        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(options)
                .build();
    }
}
