package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.PolicyAgent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyKnowledgeServiceTest {

    private final PolicyKnowledgeService service = new PolicyKnowledgeService();

    @Test
    void findPolicy_leave() {
        PolicyAgent.PolicyMaterial m = service.findPolicy("请假", "年假怎么请");
        assertThat(m.title()).contains("请假");
    }

    @Test
    void findPolicy_travel() {
        PolicyAgent.PolicyMaterial m = service.findPolicy("差旅报销", "出差回来报销材料");
        assertThat(m.title()).contains("差旅");
    }
}
