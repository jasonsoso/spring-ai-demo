package com.jason.demo.demo2.member.service.core;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.member.service.common.MemberErrorCodeEnum;
import com.jason.demo.demo2.member.service.core.domain.Member;
import com.jason.demo.demo2.member.service.infrastructure.repository.MemberRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class MemberDomainService {

    private final MemberRepository memberRepository;

    public MemberDomainService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public void register(Member member) {
        memberRepository.findByPhone(member.getPhone()).ifPresent(existing -> {
            throw new BusinessException(MemberErrorCodeEnum.PHONE_ALREADY_REGISTERED);
        });
        try {
            memberRepository.insert(member);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(MemberErrorCodeEnum.PHONE_ALREADY_REGISTERED);
        }
    }

    public Member requireLoginMember(String phone) {
        Member member = memberRepository.findByPhone(phone)
                .orElseThrow(() -> new BusinessException(MemberErrorCodeEnum.MEMBER_NOT_FOUND));
        member.requireCanLogin();
        return member;
    }

    public Member requireByMemberId(long memberId) {
        return memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCodeEnum.MEMBER_NOT_FOUND));
    }
}
