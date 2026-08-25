package com.jason.demo.demo2.member.app.vo.res;

import lombok.Data;

@Data
public class LoginMemberResVO {
    private String token;
    private Long memberId;
    private String phone;
    private String avatarUrl;
    private long expiresInSeconds;
}
