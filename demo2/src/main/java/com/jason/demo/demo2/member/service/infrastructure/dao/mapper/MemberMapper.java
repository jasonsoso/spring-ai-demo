package com.jason.demo.demo2.member.service.infrastructure.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jason.demo.demo2.member.service.infrastructure.dao.entity.MemberDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberMapper extends BaseMapper<MemberDO> {
}
