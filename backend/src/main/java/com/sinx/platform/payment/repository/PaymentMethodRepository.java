package com.sinx.platform.payment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.payment.domain.PaymentMethod;

public interface PaymentMethodRepository
    extends JpaRepository<PaymentMethod, UUID> {

    /**
     * Looks a method up by the token in its notify URL. The callback endpoint
     * has nothing else to go on, and this is the only lookup it performs.
     */
    Optional<PaymentMethod> findByUuid(String uuid);

    List<PaymentMethod> findAllByOrderBySortOrderAscCreatedAtAsc();

    /** What a customer may pay with: configured, and switched on. */
    List<PaymentMethod> findByEnabledTrueOrderBySortOrderAscCreatedAtAsc();
}
