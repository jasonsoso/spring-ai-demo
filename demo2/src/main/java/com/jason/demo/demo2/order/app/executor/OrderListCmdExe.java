package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.order.app.convert.OrderVoConvert;
import com.jason.demo.demo2.order.app.vo.req.OrderListReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderListItemResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderListResVO;
import com.jason.demo.demo2.order.service.common.OrderListTabEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderItemRepository;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OrderListCmdExe {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderVoConvert orderVoConvert;

    public OrderListCmdExe(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            OrderVoConvert orderVoConvert) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderVoConvert = orderVoConvert;
    }

    public OrderListResVO execute(OrderListReqVO req) {
        long memberId = LoginContextHolder.require().memberId();
        int pageNo = req.getPageNo() == null ? 1 : req.getPageNo();
        int pageSize = req.getPageSize() == null ? 20 : req.getPageSize();
        String orderStatus = req.getTab() == OrderListTabEnum.ALL ? null : req.getTab().name();
        int offset = (pageNo - 1) * pageSize;
        long total = orderRepository.countPageByMemberAndTab(memberId, orderStatus);
        List<Order> orders = orderRepository.pageByMemberAndTab(memberId, orderStatus, offset, pageSize);
        List<Long> orderIds = orders.stream().map(Order::getOrderId).toList();
        Map<Long, List<OrderItem>> itemsByOrderId = orderItemRepository.listByOrderIds(orderIds);

        List<OrderListItemResVO> items = new ArrayList<>();
        for (Order order : orders) {
            List<OrderItem> lines = itemsByOrderId.getOrDefault(order.getOrderId(), List.of());
            OrderListItemResVO itemVo = orderVoConvert.toListItem(order);
            itemVo.setItems(lines.stream().map(orderVoConvert::toListLine).toList());
            items.add(itemVo);
        }

        OrderListResVO res = new OrderListResVO();
        res.setPageNo(pageNo);
        res.setPageSize(pageSize);
        res.setTotal(total);
        res.setItems(items);
        return res;
    }
}
