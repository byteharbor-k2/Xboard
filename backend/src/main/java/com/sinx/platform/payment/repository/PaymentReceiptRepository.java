package com.sinx.platform.payment.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.payment.domain.PaymentReceipt;

public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, UUID> {

    Optional<PaymentReceipt> findByGatewayIgnoreCaseAndGatewayUrlAndMerchantIdentityAndTransactionId(
        String gateway,
        String gatewayUrl,
        String merchantIdentity,
        String transactionId
    );
}
