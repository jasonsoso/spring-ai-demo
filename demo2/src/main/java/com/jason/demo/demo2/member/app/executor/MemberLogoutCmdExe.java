package com.jason.demo.demo2.member.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.auth.service.AuthSessionService;
import com.jason.demo.demo2.member.app.vo.res.DeleteSessionResVO;
import com.jason.demo.demo2.member.app.vo.res.LogoutMemberResVO;
import org.springframework.stereotype.Service;

@Service
public class MemberLogoutCmdExe {

    private final AuthSessionService authSessionService;

    public MemberLogoutCmdExe(AuthSessionService authSessionService) {
        this.authSessionService = authSessionService;
    }

    public LogoutMemberResVO logout() {
        String token = LoginContextHolder.require().token();
        LogoutMemberResVO response = new LogoutMemberResVO();
        response.setSuccess(authSessionService.deleteSession(token));
        return response;
    }

    public DeleteSessionResVO deleteSession(String token) {
        DeleteSessionResVO response = new DeleteSessionResVO();
        response.setSuccess(authSessionService.deleteSession(token));
        return response;
    }
}
