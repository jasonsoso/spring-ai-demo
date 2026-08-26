package com.jason.demo.demo2.member.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "删除会话响应")
public class DeleteSessionResVO {

    @Schema(description = "是否成功", example = "true")
    private boolean success;
}
