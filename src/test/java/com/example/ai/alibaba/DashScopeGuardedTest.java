package com.example.ai.alibaba;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.ai.config.GuardedOutputChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The output guardrail claims to wrap EVERY {@code ChatModel} bean, so the
 * cloud model must be covered too — the whole point of the model seam is that
 * a caller cannot forget it. With a (fake) API key the DashScope auto-config
 * creates a real {@code DashScopeChatModel} bean; this asserts it comes out of
 * the context already decorated, with no network call.
 *
 * <p>This is the gap {@code @MockitoBean} cannot cover: a mocked
 * {@code DashScopeChatModel} bypasses the bean post-processor, so the existing
 * {@code AlibabaEnabledTest} proves the endpoint works but not that the cloud
 * model is guarded. It also guards the injection contract: the guardrail swaps
 * the bean's concrete type, so the controller must select it by name as a
 * {@code ChatModel} or DashScope breaks at runtime.
 * </p>
 */
@SpringBootTest(properties = "spring.ai.dashscope.api-key=sk-test-dummy-key-for-ci")
class DashScopeGuardedTest {

    @Autowired
    @Qualifier("dashScopeChatModel")
    private ObjectProvider<ChatModel> dashScopeProvider;

    @Test
    void dashScopeModelIsWrappedByTheOutputGuardrail() {
        ChatModel model = dashScopeProvider.getIfAvailable();
        assertThat(model).isNotNull();
        assertThat(model).isInstanceOf(GuardedOutputChatModel.class);
    }
}
