package com.jason.demo.demo2.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 景点推荐工具
 * 实际项目中可对接旅游平台 API 或自建景点数据库
 */
@Slf4j
@Component
public class AttractionTool {

    private static final Map<String, List<AttractionInfo>> ATTRACTIONS_DB = Map.of(
            "北京", List.of(
                    new AttractionInfo("故宫", "人文", "5.0", "3-4小时", "世界文化遗产，明清皇家宫殿"),
                    new AttractionInfo("长城（八达岭）", "人文", "5.0", "半天", "世界七大奇迹之一"),
                    new AttractionInfo("颐和园", "人文", "4.8", "2-3小时", "皇家园林博物馆"),
                    new AttractionInfo("天坛", "人文", "4.7", "2小时", "明清祭天场所"),
                    new AttractionInfo("北京环球影城", "娱乐", "4.9", "全天", "顶级主题乐园")
            ),
            "上海", List.of(
                    new AttractionInfo("外滩", "人文", "4.9", "1-2小时", "万国建筑博览群"),
                    new AttractionInfo("上海迪士尼", "娱乐", "4.8", "全天", "亚洲顶级迪士尼乐园"),
                    new AttractionInfo("豫园", "人文", "4.6", "2小时", "江南古典园林"),
                    new AttractionInfo("东方明珠", "人文", "4.7", "2小时", "上海地标建筑")
            ),
            "成都", List.of(
                    new AttractionInfo("大熊猫繁育研究基地", "自然", "4.9", "半天", "近距离观赏大熊猫"),
                    new AttractionInfo("宽窄巷子", "人文", "4.7", "2-3小时", "成都历史文化街区"),
                    new AttractionInfo("都江堰", "人文", "4.8", "半天", "世界文化遗产水利工程"),
                    new AttractionInfo("青城山", "自然", "4.7", "全天", "道教名山，天然氧吧")
            ),
            "西安", List.of(
                    new AttractionInfo("兵马俑", "人文", "5.0", "半天", "世界第八大奇迹"),
                    new AttractionInfo("大雁塔", "人文", "4.8", "2小时", "唐代佛教建筑"),
                    new AttractionInfo("古城墙", "人文", "4.7", "2-3小时", "中国现存最完整古城墙"),
                    new AttractionInfo("大唐不夜城", "人文", "4.8", "晚上", "沉浸式唐文化体验")
            ),
            "杭州", List.of(
                    new AttractionInfo("西湖", "自然", "5.0", "半天", "世界文化遗产，人间天堂"),
                    new AttractionInfo("灵隐寺", "人文", "4.7", "2小时", "千年古刹"),
                    new AttractionInfo("宋城", "娱乐", "4.6", "全天", "大型演艺主题公园"),
                    new AttractionInfo("西溪湿地", "自然", "4.5", "半天", "城市湿地公园")
            ),
            "广州", List.of(
                    new AttractionInfo("广州塔", "人文/娱乐", "4.9", "2-3小时", "广州地标，小蛮腰，高空观光与游乐项目"),
                    new AttractionInfo("长隆野生动物世界", "自然/娱乐", "4.9", "全天", "国家级野生动物园，亲子游玩首选"),
                    new AttractionInfo("杜莎夫人蜡像馆", "娱乐/室内", "4.7", "2小时", "室内明星蜡像打卡，全空调舒适游览"),
                    new AttractionInfo("广东省博物馆", "人文/室内", "4.8", "2-3小时", "岭南历史文化全室内展厅，免费需预约"),
                    new AttractionInfo("永庆坊", "人文", "4.7", "2小时", "老广州骑楼街区，粤剧艺术网红打卡地"),
                    new AttractionInfo("白云山", "自然", "4.8", "半天", "广州城市绿肺，登高俯瞰全城美景")
            )
    );

    @Tool(description = "根据城市和景点类型偏好推荐热门景点，返回景点名称、类型、评分、建议游览时长、景点简介")
    public String recommendAttractions(
            @ToolParam(description = "城市名称，如：北京、上海、成都、西安、杭州、广州") String city,
            @ToolParam(description = "景点类型偏好：人文、自然、娱乐；不填则推荐全部类型", required = false) String preference
    ) {
        List<AttractionInfo> attractions = ATTRACTIONS_DB.getOrDefault(city, List.of());

        if (attractions.isEmpty()) {
            return String.format("{\"error\": \"暂无 %s 的景点数据，请尝试其他城市\"}", city);
        }

        if (preference != null && !preference.isEmpty()) {
            attractions = attractions.stream()
                    .filter(a -> a.type.contains(preference) || preference.contains(a.type))
                    .collect(Collectors.toList());
        }

        StringBuilder result = new StringBuilder();
        result.append("{\"city\": \"").append(city).append("\", ");
        if (preference != null && !preference.isEmpty()) {
            result.append("\"preference\": \"").append(preference).append("\", ");
        }
        result.append("\"attractions\": [");

        for (int i = 0; i < attractions.size(); i++) {
            AttractionInfo a = attractions.get(i);
            result.append(String.format(
                    "{\"name\": \"%s\", \"type\": \"%s\", \"rating\": \"%s\", \"duration\": \"%s\", \"intro\": \"%s\"}",
                    a.name, a.type, a.rating, a.duration, a.intro
            ));
            if (i < attractions.size() - 1) {
                result.append(", ");
            }
        }

        result.append("], \"count\": ").append(attractions.size()).append("}");
        String resultStr = result.toString();
        log.debug("AttractionTool#recommendAttractions city={}, preference={}, resultStr={}", city, preference, resultStr);
        return resultStr;
    }

    private static class AttractionInfo {
        String name;
        String type;
        String rating;
        String duration;
        String intro;

        AttractionInfo(String name, String type, String rating, String duration, String intro) {
            this.name = name;
            this.type = type;
            this.rating = rating;
            this.duration = duration;
            this.intro = intro;
        }
    }
}
