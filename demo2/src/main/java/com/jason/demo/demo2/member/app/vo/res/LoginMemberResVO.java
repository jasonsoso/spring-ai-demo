package com.jason.demo.demo2.member.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "会员登录响应")
public class LoginMemberResVO {

    @Schema(description = "会话 token")
    private String token;

    @Schema(description = "会员 ID")
    private Long memberId;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "头像 URL")
    private String avatarUrl;

    @Schema(description = "token 有效期（秒）", example = "86400")
    private long expiresInSeconds;
}
