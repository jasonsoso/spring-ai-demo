package com.jason.demo.demo2.member.app.controller;

import com.jason.demo.demo2.framework.auth.annotation.LoginRequired;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCode;
import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import com.jason.demo.demo2.member.app.executor.MemberGetProfileCmdExe;
import com.jason.demo.demo2.member.app.executor.MemberLoginCmdExe;
import com.jason.demo.demo2.member.app.executor.MemberLogoutCmdExe;
import com.jason.demo.demo2.member.app.executor.MemberRegisterCmdExe;
import com.jason.demo.demo2.member.app.vo.req.DeleteSessionReqVO;
import com.jason.demo.demo2.member.app.vo.req.LoginMemberReqVO;
import com.jason.demo.demo2.member.app.vo.req.RegisterMemberReqVO;
import com.jason.demo.demo2.member.app.vo.res.DeleteSessionResVO;
import com.jason.demo.demo2.member.app.vo.res.GetMemberProfileResVO;
import com.jason.demo.demo2.member.app.vo.res.LoginMemberResVO;
import com.jason.demo.demo2.member.app.vo.res.LogoutMemberResVO;
import com.jason.demo.demo2.member.app.vo.res.RegisterMemberResVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo/members")
public class MemberController {

    private final MemberRegisterCmdExe memberRegisterCmdExe;
    private final MemberLoginCmdExe memberLoginCmdExe;
    private final MemberLogoutCmdExe memberLogoutCmdExe;
    private final MemberGetProfileCmdExe memberGetProfileCmdExe;

    public MemberController(
            MemberRegisterCmdExe memberRegisterCmdExe,
            MemberLoginCmdExe memberLoginCmdExe,
            MemberLogoutCmdExe memberLogoutCmdExe,
            MemberGetProfileCmdExe memberGetProfileCmdExe) {
        this.memberRegisterCmdExe = memberRegisterCmdExe;
        this.memberLoginCmdExe = memberLoginCmdExe;
        this.memberLogoutCmdExe = memberLogoutCmdExe;
        this.memberGetProfileCmdExe = memberGetProfileCmdExe;
    }

    @PostMapping("/register")
    public JsonResult<RegisterMemberResVO> register(@RequestBody RegisterMemberReqVO request) {
        String phone = requireText(request == null ? null : request.getPhone(), "phone").trim();
        String password = requireText(request == null ? null : request.getPassword(), "password");
        return JsonResults.ok(memberRegisterCmdExe.execute(phone, password, request.getAvatarUrl()));
    }

    @PostMapping("/login")
    public JsonResult<LoginMemberResVO> login(@RequestBody LoginMemberReqVO request) {
        String phone = requireText(request == null ? null : request.getPhone(), "phone").trim();
        String password = requireText(request == null ? null : request.getPassword(), "password");
        return JsonResults.ok(memberLoginCmdExe.execute(phone, password));
    }

    @LoginRequired
    @PostMapping("/logout")
    public JsonResult<LogoutMemberResVO> logout() {
        return JsonResults.ok(memberLogoutCmdExe.logout());
    }

    @LoginRequired
    @PostMapping("/getProfile")
    public JsonResult<GetMemberProfileResVO> getProfile() {
        return JsonResults.ok(memberGetProfileCmdExe.execute());
    }

    @PostMapping("/deleteSession")
    public JsonResult<DeleteSessionResVO> deleteSession(@RequestBody DeleteSessionReqVO request) {
        String token = requireText(request == null ? null : request.getToken(), "token");
        return JsonResults.ok(memberLogoutCmdExe.deleteSession(token));
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(CommonErrorCode.PARAM_MISSING, fieldName + " is required");
        }
        return value;
    }
}
