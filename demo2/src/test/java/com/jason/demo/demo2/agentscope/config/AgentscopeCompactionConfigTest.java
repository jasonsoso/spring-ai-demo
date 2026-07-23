package com.jason.demo.demo2.agentscope.config;

import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeCompactionConfigTest {

    @Test
    void toCompactionConfig_mapsThreeKnobs_andHardcodesFlags() {
        DevAgentProperties.Compaction input = new DevAgentProperties.Compaction(
                6, 2, "请整理：{messages}");

        CompactionConfig config = AgentScopeConfig.toCompactionConfig(input);

        assertThat(config.getTriggerMessages()).isEqualTo(6);
        assertThat(config.getKeepMessages()).isEqualTo(2);
        assertThat(config.getSummaryPrompt()).isEqualTo("请整理：{messages}");
        assertThat(config.getKeepTokens()).isEqualTo(0);
        assertThat(config.isFlushBeforeCompact()).isFalse();
        assertThat(config.isOffloadBeforeCompact()).isFalse();
    }
}
