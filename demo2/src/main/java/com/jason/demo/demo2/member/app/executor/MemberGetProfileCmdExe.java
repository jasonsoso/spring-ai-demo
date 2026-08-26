package com.jason.demo.demo2.member.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.member.app.convert.MemberVoConvert;
import com.jason.demo.demo2.member.app.vo.res.GetMemberProfileResVO;
import com.jason.demo.demo2.member.service.core.MemberDomainService;
import org.springframework.stereotype.Service;

@Service
public class MemberGetProfileCmdExe {

    private final MemberDomainService memberDomainService;
    private final MemberVoConvert memberVoConvert;

    public MemberGetProfileCmdExe(MemberDomainService memberDomainService, MemberVoConvert memberVoConvert) {
        this.memberDomainService = memberDomainService;
        this.memberVoConvert = memberVoConvert;
    }

    public GetMemberProfileResVO execute() {
        return memberVoConvert.toProfileRes(
                memberDomainService.requireByMemberId(LoginContextHolder.require().memberId()));
    }
}
