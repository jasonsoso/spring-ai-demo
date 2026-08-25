package com.jason.demo.demo2.member.app.executor;

import com.jason.demo.demo2.framework.auth.service.AuthSessionService;
import org.springframework.stereotype.Service;

@Service
public class MemberLogoutCmdExe {

    private final AuthSessionService authSessionService;

    public MemberLogoutCmdExe(AuthSessionService authSessionService) {
        this.authSessionService = authSessionService;
    }

    public boolean execute(String token) {
        return authSessionService.deleteSession(token);
    }
}
