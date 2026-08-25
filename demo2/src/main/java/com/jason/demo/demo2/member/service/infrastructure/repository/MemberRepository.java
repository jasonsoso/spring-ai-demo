package com.jason.demo.demo2.member.service.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jason.demo.demo2.member.service.core.domain.Member;
import com.jason.demo.demo2.member.service.infrastructure.dao.entity.MemberDO;
import com.jason.demo.demo2.member.service.infrastructure.dao.mapper.MemberMapper;
import com.jason.demo.demo2.member.service.infrastructure.repository.convert.MemberDoConvert;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MemberRepository {

    private final MemberMapper memberMapper;
    private final MemberDoConvert memberDoConvert;

    public MemberRepository(MemberMapper memberMapper, MemberDoConvert memberDoConvert) {
        this.memberMapper = memberMapper;
        this.memberDoConvert = memberDoConvert;
    }

    public void insert(Member member) {
        memberMapper.insert(memberDoConvert.toDo(member));
    }

    public Optional<Member> findByPhone(String phone) {
        MemberDO row = memberMapper.selectOne(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getPhone, phone));
        return Optional.ofNullable(memberDoConvert.toDomain(row));
    }

    public Optional<Member> findByMemberId(long memberId) {
        MemberDO row = memberMapper.selectOne(
                new LambdaQueryWrapper<MemberDO>().eq(MemberDO::getMemberId, memberId));
        return Optional.ofNullable(memberDoConvert.toDomain(row));
    }
}
