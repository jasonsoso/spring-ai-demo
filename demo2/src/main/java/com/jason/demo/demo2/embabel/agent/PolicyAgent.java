package com.jason.demo.demo2.embabel.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.jason.demo.demo2.embabel.config.PolicyAgentProperties;
import com.jason.demo.demo2.embabel.service.PolicyKnowledgeService;

@Agent(description = "回答员工制度、差旅、报销、请假等公司政策问题")
public class PolicyAgent {

    private final PolicyKnowledgeService policyKnowledgeService;
    private final PolicyAgentProperties properties;

    public PolicyAgent(PolicyKnowledgeService policyKnowledgeService, PolicyAgentProperties properties) {
        this.policyKnowledgeService = policyKnowledgeService;
        this.properties = properties;
    }

    @Action
    public PolicyQuestion extractPolicyQuestion(UserInput userInput, Ai ai) {
        String prompt = properties.prompts().extractPolicyQuestion()
                .replace("{userInput}", userInput.getContent());
        return ai.withDefaultLlm().createObject(prompt, PolicyQuestion.class);
    }

    @Action
    public PolicyMaterial retrievePolicy(PolicyQuestion question) {
        return policyKnowledgeService.findPolicy(question.category(), question.question());
    }

    @AchievesGoal(description = "基于公司制度资料回答员工问题")
    @Action
    public PolicyAnswer answer(PolicyQuestion question, PolicyMaterial material, Ai ai) {
        String prompt = properties.prompts().answer()
                .replace("{question}", question.question())
                .replace("{category}", question.category())
                .replace("{title}", material.title())
                .replace("{content}", material.content());
        return ai.withDefaultLlm().createObject(prompt, PolicyAnswer.class);
    }

    public record PolicyQuestion(String category, String question) {
    }

    public record PolicyMaterial(String title, String content) {
    }

    public record PolicyAnswer(String title, String answer, String source) {
    }
}
