package com.jason.demo.demo2.member.app.executor;

import com.jason.demo.demo2.framework.auth.model.AuthSession;
import com.jason.demo.demo2.framework.auth.service.AuthSessionService;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.member.app.convert.MemberVoConvert;
import com.jason.demo.demo2.member.app.vo.res.LoginMemberResVO;
import com.jason.demo.demo2.member.service.common.MemberErrorCode;
import com.jason.demo.demo2.member.service.core.MemberDomainService;
import com.jason.demo.demo2.member.service.core.PasswordHasher;
import com.jason.demo.demo2.member.service.core.domain.Member;
import org.springframework.stereotype.Service;

@Service
public class MemberLoginCmdExe {

    private final MemberDomainService memberDomainService;
    private final PasswordHasher passwordHasher;
    private final AuthSessionService authSessionService;
    private final MemberVoConvert memberVoConvert;

    public MemberLoginCmdExe(
            MemberDomainService memberDomainService,
            PasswordHasher passwordHasher,
            AuthSessionService authSessionService,
            MemberVoConvert memberVoConvert) {
        this.memberDomainService = memberDomainService;
        this.passwordHasher = passwordHasher;
        this.authSessionService = authSessionService;
        this.memberVoConvert = memberVoConvert;
    }

    public LoginMemberResVO execute(String phone, String password) {
        Member member = memberDomainService.requireLoginMember(phone);
        if (!passwordHasher.matches(password, member.getPasswordHash())) {
            throw new BusinessException(MemberErrorCode.PASSWORD_ERROR);
        }
        AuthSession session = authSessionService.createSession(
                member.getMemberId(), member.getPhone(), member.getAvatarUrl());
        return memberVoConvert.toLoginRes(session);
    }
}
