package com.jason.demo.demo2.member.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "会员登录请求")
public class LoginMemberReqVO {

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Schema(description = "大陆手机号", example = "13800138000", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @NotBlank(message = "不能为空")
    @Size(min = 6, max = 32, message = "密码长度须为 6-32")
    @Schema(description = "登录密码", example = "secret12", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
