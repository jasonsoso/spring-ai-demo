package com.jason.demo.demo2.agentscopea2a.client;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskReviewToolTest {

    @Test
    void forwardsOnlyTheExplicitChangeDescription() {
        RemoteRiskReviewService service = mock(RemoteRiskReviewService.class);
        when(service.review(anyString())).thenReturn(Mono.just("## 结论\n可以"));

        RiskReviewTool tool = new RiskReviewTool(service);

        assertThat(tool.review("用户提供的改动说明")).isEqualTo("## 结论\n可以");
        verify(service).review("用户提供的改动说明");
    }
}
