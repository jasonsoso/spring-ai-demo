package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.StarNewsAgent;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class HoroscopeService {

    private static final Map<String, String> HOROSCOPES = Map.ofEntries(
            Map.entry("白羊座", "适合把手头任务拆小，先完成最关键的一步。沟通时少绕弯，直接说结论会更顺。"),
            Map.entry("金牛座", "今天适合处理预算、排期和资源确认。别急着拍板，先把边界条件问清楚。"),
            Map.entry("双子座", "信息量大的一天，先筛选再行动。多确认一次细节，能避免返工。"),
            Map.entry("巨蟹座", "适合整理情绪和优先级。把重要关系里的期待说清楚，会更轻松。"),
            Map.entry("狮子座", "表达欲强，但先听后说效果更好。把亮点落在具体成果上。"),
            Map.entry("处女座", "细节控上线，适合查漏补缺。别追求完美，完成比完美更重要。"),
            Map.entry("天秤座", "适合做选择和取舍。权衡时写下三条标准，决策会更快。"),
            Map.entry("天蝎座", "专注力强，适合攻坚难题。注意别把情绪带进协作讨论。"),
            Map.entry("射手座", "适合学习新东西或拓展视野。计划留一点弹性，惊喜可能来自变化。"),
            Map.entry("摩羯座", "务实推进的一天。把大目标拆成可交付的小里程碑。"),
            Map.entry("水瓶座", "灵感活跃，适合头脑风暴。落地时找一个可执行的下一步。"),
            Map.entry("双鱼座", "直觉敏锐，适合创意表达。重要事项仍建议书面确认。")
    );

    public StarNewsAgent.Horoscope dailyHoroscope(String sign) {
        String summary = HOROSCOPES.getOrDefault(sign, "今天适合先把目标说清楚，再决定下一步动作。");
        return new StarNewsAgent.Horoscope(sign, summary);
    }
}
