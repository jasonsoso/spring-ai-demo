package com.jason.demo.demo2.member.service.infrastructure.repository.convert;

import com.jason.demo.demo2.member.service.core.domain.Member;
import com.jason.demo.demo2.member.service.infrastructure.dao.entity.MemberDO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MemberDoConvert {

    MemberDO toDo(Member member);

    default Member toDomain(MemberDO memberDO) {
        return Member.from(memberDO);
    }
}
