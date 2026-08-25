package com.jason.demo.demo2.member.app.controller;

import com.jason.demo.demo2.framework.auth.annotation.LoginRequired;
import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.member.app.convert.MemberVoConvert;
import com.jason.demo.demo2.member.app.executor.MemberGetProfileCmdExe;
import com.jason.demo.demo2.member.app.executor.MemberLoginCmdExe;
import com.jason.demo.demo2.member.app.executor.MemberLogoutCmdExe;
import com.jason.demo.demo2.member.app.executor.MemberRegisterCmdExe;
import com.jason.demo.demo2.member.app.support.MemberHttpSupport;
import com.jason.demo.demo2.member.app.vo.req.DeleteSessionReqVO;
import com.jason.demo.demo2.member.app.vo.req.LoginMemberReqVO;
import com.jason.demo.demo2.member.app.vo.req.RegisterMemberReqVO;
import com.jason.demo.demo2.member.app.vo.res.DeleteSessionResVO;
import com.jason.demo.demo2.member.app.vo.res.GetMemberProfileResVO;
import com.jason.demo.demo2.member.app.vo.res.LoginMemberResVO;
import com.jason.demo.demo2.member.app.vo.res.LogoutMemberResVO;
import com.jason.demo.demo2.member.app.vo.res.RegisterMemberResVO;
import com.jason.demo.demo2.member.service.core.MemberDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/demo/members")
public class MemberController {

    private final MemberRegisterCmdExe memberRegisterCmdExe;
    private final MemberLoginCmdExe memberLoginCmdExe;
    private final MemberLogoutCmdExe memberLogoutCmdExe;
    private final MemberGetProfileCmdExe memberGetProfileCmdExe;
    private final MemberVoConvert memberVoConvert;

    public MemberController(
            MemberRegisterCmdExe memberRegisterCmdExe,
            MemberLoginCmdExe memberLoginCmdExe,
            MemberLogoutCmdExe memberLogoutCmdExe,
            MemberGetProfileCmdExe memberGetProfileCmdExe,
            MemberVoConvert memberVoConvert) {
        this.memberRegisterCmdExe = memberRegisterCmdExe;
        this.memberLoginCmdExe = memberLoginCmdExe;
        this.memberLogoutCmdExe = memberLogoutCmdExe;
        this.memberGetProfileCmdExe = memberGetProfileCmdExe;
        this.memberVoConvert = memberVoConvert;
    }

    @PostMapping("/register")
    public RegisterMemberResVO register(@RequestBody RegisterMemberReqVO request) {
        String phone = requireText(request == null ? null : request.getPhone(), "phone").trim();
        String password = requireText(request == null ? null : request.getPassword(), "password");
        try {
            return memberVoConvert.toRegisterRes(
                    memberRegisterCmdExe.execute(phone, password, request.getAvatarUrl()));
        } catch (MemberDomainException e) {
            throw MemberHttpSupport.toHttpException(e);
        }
    }

    @PostMapping("/login")
    public LoginMemberResVO login(@RequestBody LoginMemberReqVO request) {
        String phone = requireText(request == null ? null : request.getPhone(), "phone").trim();
        String password = requireText(request == null ? null : request.getPassword(), "password");
        try {
            return memberVoConvert.toLoginRes(memberLoginCmdExe.execute(phone, password));
        } catch (MemberDomainException e) {
            throw MemberHttpSupport.toHttpException(e);
        }
    }

    @LoginRequired
    @PostMapping("/logout")
    public LogoutMemberResVO logout() {
        LogoutMemberResVO response = new LogoutMemberResVO();
        response.setSuccess(memberLogoutCmdExe.execute(LoginContextHolder.require().token()));
        return response;
    }

    @LoginRequired
    @PostMapping("/getProfile")
    public GetMemberProfileResVO getProfile() {
        try {
            return memberVoConvert.toProfileRes(memberGetProfileCmdExe.execute());
        } catch (MemberDomainException e) {
            throw MemberHttpSupport.toHttpException(e);
        }
    }

    @PostMapping("/deleteSession")
    public DeleteSessionResVO deleteSession(@RequestBody DeleteSessionReqVO request) {
        String token = requireText(request == null ? null : request.getToken(), "token");
        DeleteSessionResVO response = new DeleteSessionResVO();
        response.setSuccess(memberLogoutCmdExe.execute(token));
        return response;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return value;
    }
}
