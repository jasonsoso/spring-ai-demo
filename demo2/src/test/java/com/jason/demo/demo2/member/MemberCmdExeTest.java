package com.jason.demo.demo2.member;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.auth.context.LoginPrincipal;
import com.jason.demo.demo2.framework.auth.model.AuthSession;
import com.jason.demo.demo2.framework.auth.service.AuthSessionService;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.member.app.executor.MemberGetProfileCmdExe;
import com.jason.demo.demo2.member.app.executor.MemberLoginCmdExe;
import com.jason.demo.demo2.member.app.executor.MemberRegisterCmdExe;
import com.jason.demo.demo2.member.service.common.MemberStatus;
import com.jason.demo.demo2.member.service.core.MemberDomainService;
import com.jason.demo.demo2.member.service.core.PasswordHasher;
import com.jason.demo.demo2.member.service.core.domain.Member;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberCmdExeTest {

    @AfterEach
    void tearDown() {
        LoginContextHolder.clear();
    }

    @Test
    void registerCreatesNormalMemberWithSnowflakeId() {
        MemberDomainService domainService = mock(MemberDomainService.class);
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        when(idGenerator.nextId()).thenReturn(9001L);
        when(passwordHasher.hash("pwd123456")).thenReturn("hashed");
        MemberRegisterCmdExe exe = new MemberRegisterCmdExe(domainService, idGenerator, passwordHasher);

        Member member = exe.execute("13888999999", "pwd123456", "https://example.com/a.png");

        assertEquals(9001L, member.getMemberId());
        assertEquals("13888999999", member.getPhone());
        assertEquals("https://example.com/a.png", member.getAvatarUrl());
        assertEquals(MemberStatus.NORMAL.name(), member.getStatus());
        verify(domainService).register(member);
    }

    @Test
    void loginCreatesAuthSession() {
        MemberDomainService domainService = mock(MemberDomainService.class);
        PasswordHasher passwordHasher = mock(PasswordHasher.class);
        AuthSessionService authSessionService = mock(AuthSessionService.class);
        Member member = member();
        AuthSession session = new AuthSession(
                "t1",
                9001L,
                "13888999999",
                "https://example.com/a.png",
                LocalDateTime.now(),
                86400L);
        when(domainService.requireLoginMember("13888999999")).thenReturn(member);
        when(passwordHasher.matches("pwd123456", "hashed")).thenReturn(true);
        when(authSessionService.createSession(9001L, "13888999999", "https://example.com/a.png")).thenReturn(session);
        MemberLoginCmdExe exe = new MemberLoginCmdExe(domainService, passwordHasher, authSessionService);

        AuthSession result = exe.execute("13888999999", "pwd123456");

        assertEquals("t1", result.token());
        assertEquals("https://example.com/a.png", result.avatarUrl());
    }

    @Test
    void profileUsesLoginContext() {
        MemberDomainService domainService = mock(MemberDomainService.class);
        LoginContextHolder.set(new LoginPrincipal(9001L, "13888999999", "t1"));
        when(domainService.requireByMemberId(9001L)).thenReturn(member());
        MemberGetProfileCmdExe exe = new MemberGetProfileCmdExe(domainService);

        Member result = exe.execute();

        assertEquals(9001L, result.getMemberId());
    }

    private static Member member() {
        Member member = new Member();
        member.setMemberId(9001L);
        member.setPhone("13888999999");
        member.setPasswordHash("hashed");
        member.setAvatarUrl("https://example.com/a.png");
        member.setStatus(MemberStatus.NORMAL.name());
        return member;
    }
}
