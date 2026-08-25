package com.jason.demo.demo2.member.app.convert;

import com.jason.demo.demo2.framework.auth.model.AuthSession;
import com.jason.demo.demo2.member.app.vo.res.GetMemberProfileResVO;
import com.jason.demo.demo2.member.app.vo.res.LoginMemberResVO;
import com.jason.demo.demo2.member.app.vo.res.RegisterMemberResVO;
import com.jason.demo.demo2.member.service.core.domain.Member;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MemberVoConvert {

    RegisterMemberResVO toRegisterRes(Member member);

    GetMemberProfileResVO toProfileRes(Member member);

    LoginMemberResVO toLoginRes(AuthSession session);
}
