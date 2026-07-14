package com.jason.demo.demo2.embabel.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.jason.demo.demo2.embabel.config.StarNewsAgentProperties;
import com.jason.demo.demo2.embabel.service.HoroscopeService;

@Agent(description = "根据人物和星座生成一段当天运势文案")
public class StarNewsAgent {

    private final HoroscopeService horoscopeService;
    private final StarNewsAgentProperties properties;

    public StarNewsAgent(HoroscopeService horoscopeService, StarNewsAgentProperties properties) {
        this.horoscopeService = horoscopeService;
        this.properties = properties;
    }

    @Action
    public StarPerson extractStarPerson(UserInput userInput, Ai ai) {
        String prompt = properties.prompts().extractStarPerson()
                .replace("{userInput}", userInput.getContent());
        return ai.withDefaultLlm().createObject(prompt, StarPerson.class);
    }

    @Action
    public Horoscope retrieveHoroscope(StarPerson starPerson) {
        return horoscopeService.dailyHoroscope(starPerson.sign());
    }

    @AchievesGoal(description = "生成一段结合人物和星座运势的文案")
    @Action
    public Writeup writeup(StarPerson starPerson, Horoscope horoscope, Ai ai) {
        String prompt = properties.prompts().writeup()
                .replace("{name}", starPerson.name())
                .replace("{sign}", starPerson.sign())
                .replace("{horoscope}", horoscope.summary());
        return ai.withDefaultLlm().createObject(prompt, Writeup.class);
    }

    public record StarPerson(String name, String sign) {
    }

    public record Horoscope(String sign, String summary) {
    }

    public record Writeup(String title, String summary, String advice) {
    }
}
