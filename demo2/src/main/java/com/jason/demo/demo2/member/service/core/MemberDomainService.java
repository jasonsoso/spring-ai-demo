package com.jason.demo.demo2.member.service.core;

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
            throw new MemberDomainException(MemberDomainException.Code.CONFLICT, "phone already registered");
        });
        try {
            memberRepository.insert(member);
        } catch (DuplicateKeyException exception) {
            throw new MemberDomainException(
                    MemberDomainException.Code.CONFLICT, "phone already registered");
        }
    }

    public Member requireLoginMember(String phone) {
        Member member = memberRepository.findByPhone(phone)
                .orElseThrow(() -> new MemberDomainException(
                        MemberDomainException.Code.NOT_FOUND, "member not found"));
        member.requireCanLogin();
        return member;
    }

    public Member requireByMemberId(long memberId) {
        return memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new MemberDomainException(
                        MemberDomainException.Code.NOT_FOUND, "member not found"));
    }
}
