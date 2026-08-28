package com.jason.demo.demo2.product.service.common;

/** Lua 返回值。code 仅作调试；业务分支看 reason。 */
public record RedisStockResult(int code, String reason) {
}
