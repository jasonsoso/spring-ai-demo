package com.jason.demo.demo2.order.service.core.statemachine;

import com.jason.demo.demo2.order.service.core.statemachine.action.OrderCancelAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderExpireAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPaySuccessAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPlaceAction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderStateMachineConfiguration {

    public static final String MACHINE_ID = "orderStateMachine";

    @Bean
    public OrderStateMachineExecutor orderStateMachineExecutor(
            OrderPlaceAction placeAction,
            OrderPaySuccessAction payAction,
            OrderCancelAction cancelAction,
            OrderExpireAction expireAction) {
        return OrderStateMachineExecutor.build(
                MACHINE_ID, placeAction, payAction, cancelAction, expireAction);
    }
}
