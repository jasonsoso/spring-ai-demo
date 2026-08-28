package com.jason.demo.demo2.order.service.infrastructure.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<OrderDO> {

    int markCompleted(@Param("orderId") long orderId,
                      @Param("memberId") Long memberId,
                      @Param("payTime") LocalDateTime payTime);

    int markCancelled(@Param("orderId") long orderId,
                      @Param("memberId") Long memberId,
                      @Param("cancelTime") LocalDateTime cancelTime);

    long countByMemberAndStatus(@Param("memberId") long memberId,
                                @Param("orderStatus") String orderStatus);

    long countPageByMemberAndTab(@Param("memberId") long memberId,
                                 @Param("orderStatus") String orderStatus);

    List<OrderDO> pageByMemberAndTab(@Param("memberId") long memberId,
                                     @Param("orderStatus") String orderStatus,
                                     @Param("offset") int offset,
                                     @Param("pageSize") int pageSize);
}
