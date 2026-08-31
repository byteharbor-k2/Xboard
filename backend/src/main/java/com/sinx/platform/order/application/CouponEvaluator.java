package com.sinx.platform.order.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.domain.Coupon;
import com.sinx.platform.order.repository.CouponRedemptionRepository;
import com.sinx.platform.order.repository.CouponRepository;
import com.sinx.platform.shared.web.ApiProblemException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates a coupon against the same conditions the original panel checks -
 * activation window, global and per-customer redemption caps, and any plan or
 * period restriction - and works out what it takes off.
 *
 * Evaluation never mutates, so a quote can be refreshed as often as the
 * customer edits the form. Consuming a redemption is a separate, locked step.
 */
@Service
@Transactional(readOnly = true)
public class CouponEvaluator {

    private static final TypeReference<List<String>> STRING_LIST =
        new TypeReference<>() {
        };

    private final CouponRepository coupons;
    private final CouponRedemptionRepository redemptions;
    private final ObjectMapper objectMapper;

    public CouponEvaluator(
        CouponRepository coupons,
        CouponRedemptionRepository redemptions,
        ObjectMapper objectMapper
    ) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.objectMapper = objectMapper;
    }

    public record Applied(Coupon coupon, long discountMinor) {
    }

    public Optional<Applied> evaluate(
        String rawCode,
        UUID userId,
        UUID planId,
        BillingPeriod period,
        long amountMinor,
        Instant now
    ) {
        return evaluate(rawCode, userId, planId, period, amountMinor, now, false);
    }

    /**
     * @param forUpdate take a row lock, so two orders cannot both spend the
     *                  last redemption of a coupon
     */
    public Optional<Applied> evaluate(
        String rawCode,
        UUID userId,
        UUID planId,
        BillingPeriod period,
        long amountMinor,
        Instant now,
        boolean forUpdate
    ) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        String code = rawCode.trim();
        Coupon coupon = (forUpdate
            ? coupons.findForUpdateByCode(code)
            : coupons.findByCode(code))
            .orElseThrow(() -> rejected("The coupon does not exist"));

        if (!coupon.isRedeemableAt(now)) {
            throw rejected("The coupon is not available");
        }
        if (!allows(coupon.getLimitedPlanIds(), planId.toString())) {
            throw rejected("The coupon does not apply to this plan");
        }
        if (!allows(coupon.getLimitedPeriods(), period.name())) {
            throw rejected("The coupon does not apply to this billing period");
        }
        Integer perUser = coupon.getMaxRedemptionsPerUser();
        if (perUser != null
                && redemptions.countByCouponIdAndUserId(coupon.getId(), userId)
                    >= perUser) {
            throw rejected("You have already used this coupon");
        }

        return Optional.of(
            new Applied(coupon, coupon.discountFor(amountMinor))
        );
    }

    /** An empty restriction list means "no restriction", as in the original. */
    private boolean allows(String encodedList, String candidate) {
        List<String> allowed = decode(encodedList);
        return allowed.isEmpty() || allowed.contains(candidate);
    }

    private List<String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(encoded, STRING_LIST);
            return values == null ? List.of() : values;
        } catch (RuntimeException exception) {
            // A malformed restriction must not silently widen the coupon.
            throw new ApiProblemException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "COUPON_MISCONFIGURED",
                "The coupon restrictions could not be read"
            );
        }
    }

    private ApiProblemException rejected(String detail) {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "COUPON_REJECTED",
            detail
        );
    }
}
