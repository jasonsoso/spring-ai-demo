package com.jason.demo.demo2.member.app.vo.req;

import lombok.Data;

@Data
public class RegisterMemberReqVO {
    private String phone;
    private String password;
    private String avatarUrl;
}
