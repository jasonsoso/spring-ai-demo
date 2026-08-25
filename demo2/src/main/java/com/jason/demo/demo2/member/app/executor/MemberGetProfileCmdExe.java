package com.jason.demo.demo2.member.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.member.service.core.MemberDomainService;
import com.jason.demo.demo2.member.service.core.domain.Member;
import org.springframework.stereotype.Service;

@Service
public class MemberGetProfileCmdExe {

    private final MemberDomainService memberDomainService;

    public MemberGetProfileCmdExe(MemberDomainService memberDomainService) {
        this.memberDomainService = memberDomainService;
    }

    public Member execute() {
        return memberDomainService.requireByMemberId(LoginContextHolder.require().memberId());
    }
}
