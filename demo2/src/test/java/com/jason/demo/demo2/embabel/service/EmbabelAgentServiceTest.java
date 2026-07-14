package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.PolicyAgent;
import com.jason.demo.demo2.embabel.agent.StarNewsAgent;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbabelAgentServiceTest {

    private final EmbabelAgentService service = new EmbabelAgentService(null, null);

    @Test
    void validateOutput_rejectsBlankWriteupTitle() {
        var bad = new StarNewsAgent.Writeup("", "完整句子。", "建议完整。");
        assertThatThrownBy(() -> service.validateOutput(bad))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validateOutput_acceptsValidPolicyAnswer() {
        var ok = new PolicyAgent.PolicyAnswer("标题", "这是完整回答。", "来源");
        assertThatCode(() -> service.validateOutput(ok)).doesNotThrowAnyException();
    }
}
