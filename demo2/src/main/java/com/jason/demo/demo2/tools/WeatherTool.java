package com.jason.demo.demo2.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 天气查询工具
 * 实际项目中可替换为真实天气 API（如和风天气、彩云天气）
 */
@Slf4j
@Component
public class WeatherTool {

    @Tool(description = "获取指定城市的实时天气信息，返回天气状况、温度范围、空气质量、出行建议")
    public String getWeather(
            @ToolParam(description = "城市名称，支持中文，如：北京、上海、广州、成都、西安、杭州") String city
    ) {
        String resultStr = simulateWeatherApi(city);
        log.debug("WeatherTool#getWeather city={}, resultStr={}", city, resultStr);
        return resultStr;
    }

    /**
     * 模拟天气 API（演示用）
     * 实际项目可接入：和风天气 API、彩云天气 API 等
     */
    private String simulateWeatherApi(String city) {
        String weatherData;
        if (city.contains("北京")) {
            weatherData = "晴，气温 18-25°C，空气质量优，紫外线较强，建议防晒";
        } else if (city.contains("上海")) {
            weatherData = "多云，气温 20-28°C，空气质量良，有轻微雾霾，建议佩戴口罩";
        } else if (city.contains("广州")) {
            weatherData = "小雨，气温 22-26°C，湿度 85%，建议携带雨具";
        } else if (city.contains("成都")) {
            weatherData = "阴天，气温 16-22°C，空气质量良，适合室内外活动";
        } else if (city.contains("西安")) {
            weatherData = "晴朗，气温 15-23°C，空气质量良，适合户外观光";
        } else if (city.contains("杭州")) {
            weatherData = "多云转晴，气温 19-27°C，空气质量优，西湖游览佳日";
        } else {
            weatherData = "晴，气温 20-25°C，空气质量良，适合出行";
        }

        return String.format(
                "{\"city\": \"%s\", \"weather\": \"%s\", \"source\": \"实时天气数据\"}",
                city, weatherData
        );
    }
}
