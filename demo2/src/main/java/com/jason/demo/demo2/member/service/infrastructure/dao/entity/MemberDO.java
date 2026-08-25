package com.jason.demo.demo2.member.service.infrastructure.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("demo_member")
public class MemberDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private String phone;
    private String passwordHash;
    private String avatarUrl;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
