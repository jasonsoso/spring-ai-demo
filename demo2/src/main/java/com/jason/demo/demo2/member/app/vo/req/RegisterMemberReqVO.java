package com.jason.demo.demo2.member.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
@Schema(description = "会员注册请求")
public class RegisterMemberReqVO {

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Schema(description = "大陆手机号", example = "13800138000", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @NotBlank(message = "不能为空")
    @Size(min = 6, max = 32, message = "密码长度须为 6-32")
    @Schema(description = "登录密码", example = "secret12", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @URL(message = "头像 URL 格式不正确")
    @Schema(description = "头像 URL（可选，绝对地址）", example = "https://example.com/a.png")
    private String avatarUrl;
}
