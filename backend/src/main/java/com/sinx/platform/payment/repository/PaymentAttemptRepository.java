package com.sinx.platform.payment.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.payment.domain.PaymentAttempt;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    List<PaymentAttempt> findByTradeNoAndMethodUuidAndGatewayIgnoreCaseOrderByCreatedAtDesc(
        String tradeNo,
        String methodUuid,
        String gateway
    );
}
