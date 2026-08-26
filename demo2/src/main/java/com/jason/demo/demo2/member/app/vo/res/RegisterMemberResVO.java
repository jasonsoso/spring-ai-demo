package com.jason.demo.demo2.member.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "会员注册响应")
public class RegisterMemberResVO {

    @Schema(description = "会员 ID")
    private Long memberId;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "头像 URL")
    private String avatarUrl;

    @Schema(description = "会员状态", example = "ACTIVE")
    private String status;
}
