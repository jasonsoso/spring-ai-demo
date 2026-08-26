package com.jason.demo.demo2.member.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "删除会话请求")
public class DeleteSessionReqVO {

    @NotBlank(message = "不能为空")
    @Schema(description = "会话 token", requiredMode = Schema.RequiredMode.REQUIRED)
    private String token;
}
