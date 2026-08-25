package com.jason.demo.demo2.member.app.executor;

import com.jason.demo.demo2.framework.auth.model.AuthSession;
import com.jason.demo.demo2.framework.auth.service.AuthSessionService;
import com.jason.demo.demo2.member.service.core.MemberDomainException;
import com.jason.demo.demo2.member.service.core.MemberDomainService;
import com.jason.demo.demo2.member.service.core.PasswordHasher;
import com.jason.demo.demo2.member.service.core.domain.Member;
import org.springframework.stereotype.Service;

@Service
public class MemberLoginCmdExe {

    private final MemberDomainService memberDomainService;
    private final PasswordHasher passwordHasher;
    private final AuthSessionService authSessionService;

    public MemberLoginCmdExe(
            MemberDomainService memberDomainService,
            PasswordHasher passwordHasher,
            AuthSessionService authSessionService) {
        this.memberDomainService = memberDomainService;
        this.passwordHasher = passwordHasher;
        this.authSessionService = authSessionService;
    }

    public AuthSession execute(String phone, String password) {
        Member member = memberDomainService.requireLoginMember(phone);
        if (!passwordHasher.matches(password, member.getPasswordHash())) {
            throw new MemberDomainException(MemberDomainException.Code.BAD_REQUEST, "password error");
        }
        return authSessionService.createSession(
                member.getMemberId(), member.getPhone(), member.getAvatarUrl());
    }
}
