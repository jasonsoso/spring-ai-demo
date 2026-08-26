package com.jason.demo.demo2.member.service.core.domain;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.member.service.common.MemberErrorCodeEnum;
import com.jason.demo.demo2.member.service.common.MemberStatusEnum;
import com.jason.demo.demo2.member.service.infrastructure.dao.entity.MemberDO;

import java.time.LocalDateTime;

public class Member extends MemberDO {

    public static Member create(long memberId, String phone, String passwordHash, String avatarUrl, LocalDateTime now) {
        if (phone == null || phone.isBlank()) {
            throw new BusinessException(MemberErrorCodeEnum.PHONE_REQUIRED);
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new BusinessException(MemberErrorCodeEnum.PASSWORD_REQUIRED);
        }
        Member member = new Member();
        member.setMemberId(memberId);
        member.setPhone(phone.trim());
        member.setPasswordHash(passwordHash);
        member.setAvatarUrl(avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl.trim());
        member.setStatus(MemberStatusEnum.NORMAL.name());
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        return member;
    }

    public static Member from(MemberDO source) {
        if (source == null) {
            return null;
        }
        Member member = new Member();
        member.setId(source.getId());
        member.setMemberId(source.getMemberId());
        member.setPhone(source.getPhone());
        member.setPasswordHash(source.getPasswordHash());
        member.setAvatarUrl(source.getAvatarUrl());
        member.setStatus(source.getStatus());
        member.setCreatedAt(source.getCreatedAt());
        member.setUpdatedAt(source.getUpdatedAt());
        return member;
    }

    public void requireCanLogin() {
        if (!MemberStatusEnum.NORMAL.name().equals(getStatus())) {
            throw new BusinessException(MemberErrorCodeEnum.MEMBER_CANNOT_LOGIN);
        }
    }
}
