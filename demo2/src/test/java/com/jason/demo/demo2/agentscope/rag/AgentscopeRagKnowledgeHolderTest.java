package com.jason.demo.demo2.agentscope.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeRagKnowledgeHolderTest {

    @Test
    void splitChunks_trimsAndDropsEmpty() {
        List<String> chunks = AgentscopeRagKnowledgeHolder.splitChunks("a\n----\n\n----\nb");
        assertThat(chunks).containsExactly("a", "b");
    }

    @Test
    void unavailable_hasNullKnowledge() {
        AgentscopeRagProperties defaults = new AgentscopeRagProperties(
                true,
                "agentscope-dev-knowledge.txt",
                3,
                0.3,
                false,
                "agentscope_dev_knowledge",
                1024,
                "",
                "https://open.bigmodel.cn/api/paas/v4",
                "embedding-2");
        AgentscopeRagKnowledgeHolder h = AgentscopeRagKnowledgeHolder.unavailable(defaults);
        assertThat(h.available()).isFalse();
        assertThat(h.knowledgeOrNull()).isNull();
        assertThat(h.retrieveConfig()).isNotNull();
    }
}
