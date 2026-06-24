package com.jason.demo.demo2.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 城市时间工具
 * WeatherAgent 携带此 Tool，LLM 可通过它获取目的地当前时间、季节及旅行时机建议，
 * 辅助分析出行天气和最佳游览时段。
 */
@Slf4j
@Component
public class TimeMethodTool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm");

    /**
     * 获取指定城市的当前时间及旅游季节信息
     * LLM 通过 Function Calling 调用此方法，参数自动从 CityRequest 反序列化
     */
    @Tool(description = "获取指定城市当前时间、季节及旅行气候提示，帮助 WeatherAgent 判断出行时机和穿搭建议")
    public String getCityCurrentTime(CityRequest request) {
        String city = request.city();
        LocalDateTime now = LocalDateTime.now();

        String timeStr  = now.format(FORMATTER);
        String season   = resolveSeason(now.getMonthValue());
        String timeSlot = resolveTimeSlot(now.getHour());
        String climate  = resolveClimateHint(city, now.getMonthValue());

        String result = String.format(
                "城市：%s | 当前时间：%s | 季节：%s | 时段：%s | 气候提示：%s",
                city, timeStr, season, timeSlot, climate
        );
        log.debug("[TimeMethodTool] city={} -> {}", city, result);
        return result;
    }

    private String resolveSeason(int month) {
        if (month >= 3 && month <= 5)  return "春季（3-5月）";
        if (month >= 6 && month <= 8)  return "夏季（6-8月）";
        if (month >= 9 && month <= 11) return "秋季（9-11月）";
        return "冬季（12-2月）";
    }

    private String resolveTimeSlot(int hour) {
        if (hour >= 6  && hour < 12) return "上午";
        if (hour >= 12 && hour < 14) return "中午";
        if (hour >= 14 && hour < 18) return "下午";
        if (hour >= 18 && hour < 22) return "傍晚";
        return "夜间";
    }

    private String resolveClimateHint(String city, int month) {
        if (city.contains("大理") || city.contains("丽江") || city.contains("云南")) {
            return month >= 5 && month <= 10
                    ? "雨季，多阵雨，建议备雨具；白天凉爽，晚间偏凉"
                    : "旱季，晴朗为主，紫外线强，早晚温差大需添衣";
        }
        if (city.contains("三亚") || city.contains("海南")) {
            return month >= 11 || month <= 4
                    ? "旱季，阳光充足，海水清澈，最佳出游季节"
                    : "雨季，台风多发，出行前关注天气预报";
        }
        if (city.contains("西安") || city.contains("北京")) {
            return month >= 4 && month <= 10
                    ? "气候适宜，适合室内外参观；夏季防暑防晒"
                    : "天气较冷，景点游览人少，注意保暖";
        }
        return "气候适宜，具体出行请查阅当地实时天气预报";
    }
}
