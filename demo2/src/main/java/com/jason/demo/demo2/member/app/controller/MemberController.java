package com.jason.demo.demo2.member.app.controller;

import com.jason.demo.demo2.framework.auth.annotation.LoginRequired;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "会员")
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

    @Operation(summary = "注册", description = "手机号注册会员")
    @PostMapping("/register")
    public JsonResult<RegisterMemberResVO> register(@Valid @RequestBody RegisterMemberReqVO request) {
        return JsonResults.ok(memberRegisterCmdExe.execute(
                request.getPhone().trim(), request.getPassword(), request.getAvatarUrl()));
    }

    @Operation(summary = "登录", description = "手机号密码登录，返回 token")
    @PostMapping("/login")
    public JsonResult<LoginMemberResVO> login(@Valid @RequestBody LoginMemberReqVO request) {
        return JsonResults.ok(memberLoginCmdExe.execute(
                request.getPhone().trim(), request.getPassword()));
    }

    @LoginRequired
    @Operation(summary = "登出", description = "注销当前登录会话。无请求体。")
    @PostMapping("/logout")
    public JsonResult<LogoutMemberResVO> logout() {
        return JsonResults.ok(memberLogoutCmdExe.logout());
    }

    @LoginRequired
    @Operation(summary = "会员资料", description = "查询当前登录会员资料。无请求体。")
    @PostMapping("/getProfile")
    public JsonResult<GetMemberProfileResVO> getProfile() {
        return JsonResults.ok(memberGetProfileCmdExe.execute());
    }

    @Operation(summary = "删除会话", description = "按 token 删除指定会话（调试用）")
    @PostMapping("/deleteSession")
    public JsonResult<DeleteSessionResVO> deleteSession(@Valid @RequestBody DeleteSessionReqVO request) {
        return JsonResults.ok(memberLogoutCmdExe.deleteSession(request.getToken()));
    }
}
