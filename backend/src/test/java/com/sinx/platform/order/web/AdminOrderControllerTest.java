package com.sinx.platform.order.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.application.OrderAdminView;
import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.application.OrderService;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.OrderType;

/** The admin order surface, kept in the original panel's shape. */
class AdminOrderControllerTest {

    private static final Instant CREATED = Instant.parse("2026-09-17T04:00:00Z");
    private static final long CREATED_AT = CREATED.getEpochSecond();

    private final OrderService orders = mock(OrderService.class);
    private final OrderFulfilmentService fulfilment =
        mock(OrderFulfilmentService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new AdminOrderController(orders, fulfilment))
        .build();

    @Test
    void listsOrdersWithTheOriginalFieldNamesAndEpochTimestamps()
        throws Exception {
        when(orders.adminList(eq(OrderStatus.PENDING), anyInt()))
            .thenReturn(List.of(view()));

        mvc.perform(get("/api/v2/admin/order/fetch")
                .param("status", "PENDING")
                .param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].trade_no").value("SX-1"))
            .andExpect(jsonPath("$.data[0].plan_name").value("Pro"))
            .andExpect(jsonPath("$.data[0].total_amount").value(1000))
            .andExpect(jsonPath("$.data[0].created_at").value(CREATED_AT))
            .andExpect(jsonPath("$.data[0].paid_at").doesNotExist());

        verify(orders).adminList(OrderStatus.PENDING, 10);
    }

    @Test
    void opensAnOrderOnAnAdministratorsAuthority() throws Exception {
        mvc.perform(post("/api/v2/admin/order/paid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"SX-1"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(fulfilment).settleManually("SX-1");
    }

    @Test
    void callsOffAnOrderOnAnAdministratorsAuthority() throws Exception {
        mvc.perform(post("/api/v2/admin/order/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"SX-1"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(orders).cancelManually("SX-1");
    }

    private OrderAdminView view() {
        return new OrderAdminView(
            "SX-1",
            UUID.randomUUID(),
            "user@example.test",
            UUID.randomUUID(),
            "Pro",
            BillingPeriod.MONTHLY,
            OrderType.NEW_PURCHASE,
            OrderStatus.PENDING,
            "CNY",
            1_000,
            0,
            0,
            0,
            0,
            1_000,
            null,
            CREATED_AT,
            null
        );
    }
}
