package com.jason.demo.demo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 电商客服知识库问答控制器
 * 提供精准条款查询与复杂场景增强查询两种接口
 */
@Tag(name = "电商客服知识库 RAG", description = "基于双 Advisor 的电商客服智能问答接口")
@RestController
@RequestMapping("/ecommerce/service")
public class CustomerServiceController {

    @Autowired
    @Qualifier("ecommerceChatClient")
    private ChatClient chatClient;

    @Autowired
    private QuestionAnswerAdvisor questionAnswerAdvisor;

    @Autowired
    private RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;

    /**
     * 精准条款查询接口（基于 QuestionAnswerAdvisor）
     * 适用场景：查询具体规则条款（如包邮条件、退换货时限、价保规则）
     */
    @Operation(summary = "精准条款查询",
            description = "基于 QuestionAnswerAdvisor 的精准条款查询，适用于具体政策匹配（如包邮条件、退换货时限）")
    @GetMapping("/chat/precise")
    public Map<String, String> preciseChat(
            @Parameter(description = "用户问题", example = "新疆地区订单多少金额包邮？")
            @RequestParam("question") String question) {
        String answer = chatClient.prompt()
                .user(question)
                .advisors(List.of(questionAnswerAdvisor))
                .call()
                .content();
        return Map.of(
                "question", question,
                "answer", answer,
                "mode", "precise（精准条款查询）"
        );
    }

    /**
     * 复杂场景增强查询接口（基于 RetrievalAugmentationAdvisor）
     * 适用场景：组合类、流程类问题（如促销期退换货、已下单改地址、VIP用户物流查询）
     */
    @Operation(summary = "复杂场景增强查询",
            description = "基于 RetrievalAugmentationAdvisor 的增强查询，适用于组合类、流程类问题（如促销+退换货）")
    @GetMapping("/chat/enhanced")
    public Map<String, String> enhancedChat(
            @Parameter(description = "用户问题", example = "双11买的口红拆封了能退吗？我是VIP用户？")
            @RequestParam("question") String question) {
        String answer = chatClient.prompt()
                .user(question)
                .advisors(List.of(retrievalAugmentationAdvisor))
                .call()
                .content();
        return Map.of(
                "question", question,
                "answer", answer,
                "mode", "enhanced（复杂场景增强）"
        );
    }
}
