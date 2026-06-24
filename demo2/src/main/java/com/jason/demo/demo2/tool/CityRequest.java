package com.jason.demo.demo2.tool;

/**
 * 城市时间查询请求参数
 * 供 TimeMethodTool 使用，LLM 调用工具时按此 Schema 传参
 */
public record CityRequest(

        /**
         * 城市名称，如：北京、大理、丽江、三亚
         */
        String city
) {
}
