package com.jason.demo.demo2.member.app.executor;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.member.app.convert.MemberVoConvert;
import com.jason.demo.demo2.member.app.vo.res.RegisterMemberResVO;
import com.jason.demo.demo2.member.service.core.MemberDomainService;
import com.jason.demo.demo2.member.service.core.PasswordHasher;
import com.jason.demo.demo2.member.service.core.domain.Member;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class MemberRegisterCmdExe {

    private final MemberDomainService memberDomainService;
    private final SnowflakeIdGenerator idGenerator;
    private final PasswordHasher passwordHasher;
    private final MemberVoConvert memberVoConvert;

    public MemberRegisterCmdExe(
            MemberDomainService memberDomainService,
            SnowflakeIdGenerator idGenerator,
            PasswordHasher passwordHasher,
            MemberVoConvert memberVoConvert) {
        this.memberDomainService = memberDomainService;
        this.idGenerator = idGenerator;
        this.passwordHasher = passwordHasher;
        this.memberVoConvert = memberVoConvert;
    }

    @Transactional
    public RegisterMemberResVO execute(String phone, String password, String avatarUrl) {
        Member member = Member.create(
                idGenerator.nextId(), phone, passwordHasher.hash(password), avatarUrl, LocalDateTime.now());
        memberDomainService.register(member);
        return memberVoConvert.toRegisterRes(member);
    }
}
