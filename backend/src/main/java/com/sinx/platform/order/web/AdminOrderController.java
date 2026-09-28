package com.sinx.platform.order.web;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.order.application.OrderAdminView;
import com.sinx.platform.order.application.OrderAssignmentService;
import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.application.OrderService;
import com.sinx.platform.order.domain.OrderStatus;

/**
 * Order administration on the Xboard-compatible surface.
 *
 * {@code paid} is how an order is opened without a payment behind it - the only
 * way while no payment provider is wired up, and afterwards the way wire
 * transfers and corrections are handled. It is deliberately narrow: settling an
 * order in this state is a privileged act, so it stays an explicit
 * administrator action rather than something an order can reach on its own.
 */
@RestController
@RequestMapping("/api/v2/admin/order")
public class AdminOrderController {

    private static final int DEFAULT_LIMIT = 50;

    private final OrderService orders;
    private final OrderFulfilmentService fulfilment;
    private final OrderAssignmentService assignments;

    public AdminOrderController(
        OrderService orders,
        OrderFulfilmentService fulfilment,
        OrderAssignmentService assignments
    ) {
        this.orders = orders;
        this.fulfilment = fulfilment;
        this.assignments = assignments;
    }

    @GetMapping("/fetch")
    XboardResponse<List<OrderAdminView>> fetch(
        @RequestParam(name = "status", required = false)
        OrderStatus status,
        @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT)
        int limit
    ) {
        return XboardResponse.of(orders.adminList(status, limit));
    }

    @PostMapping("/paid")
    XboardResponse<Boolean> paid(@RequestBody TradeNoRequest request) {
        fulfilment.settleManually(request.tradeNo());
        return XboardResponse.of(true);
    }

    @PostMapping("/cancel")
    XboardResponse<Boolean> cancel(@RequestBody TradeNoRequest request) {
        orders.cancelManually(request.tradeNo());
        return XboardResponse.of(true);
    }

    /**
     * Hands the order, and the subscription its account holds, to another
     * customer - the correction for a purchase made under the wrong address.
     */
    @PostMapping("/assign")
    XboardResponse<Boolean> assign(@RequestBody AssignRequest request) {
        assignments.assign(request.tradeNo(), request.userId());
        return XboardResponse.of(true);
    }

    record TradeNoRequest(@JsonProperty("trade_no") String tradeNo) {
    }

    record AssignRequest(
        @JsonProperty("trade_no") String tradeNo,
        @JsonProperty("user_id") UUID userId
    ) {
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) {
            return new XboardResponse<>(data);
        }
    }
}
