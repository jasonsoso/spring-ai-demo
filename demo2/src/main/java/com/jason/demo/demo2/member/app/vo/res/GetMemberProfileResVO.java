package com.jason.demo.demo2.member.app.vo.res;

import lombok.Data;

@Data
public class GetMemberProfileResVO {
    private Long memberId;
    private String phone;
    private String avatarUrl;
    private String status;
}
