package com.sinx.platform.order.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.order.domain.Coupon;
import com.sinx.platform.order.domain.CouponDiscountType;
import com.sinx.platform.order.repository.CouponRepository;
import com.sinx.platform.shared.web.ApiProblemException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates, edits and retires coupons on the Xboard-compatible admin surface.
 *
 * This is the missing creation path: the schema and the pricing pipeline
 * understand coupons already, but until now only a hand-seeded row could ever
 * reach a quote. Validation here is the coupon's constitution - type bounds,
 * window, usage caps and restrictions - because a misconfigured coupon either
 * discounts orders it was never meant to, or fails every customer who types it
 * in.
 */
@Service
public class AdminCouponService {

    private static final TypeReference<List<String>> STRING_LIST =
        new TypeReference<>() {
        };

    private static final int DEFAULT_LIMIT = 100;

    private final CouponRepository coupons;
    private final ServicePlanRepository plans;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminCouponService(
        CouponRepository coupons,
        ServicePlanRepository plans,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.coupons = coupons;
        this.plans = plans;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * The newest coupons first, capped at what the caller asked for, so an
     * operator hunting for a coupon finds the one just created at the top.
     */
    @Transactional(readOnly = true)
    public List<AdminCouponView> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        PageRequest page = PageRequest.of(
            0,
            safeLimit,
            Sort.by(Sort.Direction.DESC, "createdAt")
        );
        List<Coupon> found = coupons.findAll(page).getContent();
        return found.stream().map(this::viewOf).toList();
    }

    @Transactional
    public AdminCouponView create(CouponDraft draft) {
        Values values = validate(draft);
        requireUnusedCode(values.code(), null);
        Instant now = Instant.now(clock);
        Coupon coupon = Coupon.create(
            values.code(),
            values.name(),
            values.discountType(),
            values.discountValue(),
            now
        );
        configure(coupon, values, now);
        coupons.save(coupon);
        return viewOf(coupon);
    }

    /**
     * Replaces the editable fields of one coupon. {@code redemptions_used} is
     * deliberately absent from the draft: how many times a coupon was actually
     * redeemed is customer history, not an editable number, and an
     * administrator may not lower the global cap below it either.
     */
    @Transactional
    public AdminCouponView update(UUID id, CouponDraft draft) {
        Values values = validate(draft);
        Coupon coupon = coupons.findById(id).orElseThrow(() -> problem(
            HttpStatus.NOT_FOUND,
            "COUPON_NOT_FOUND",
            "The coupon does not exist"
        ));
        requireUnusedCode(values.code(), id);
        if (values.maxRedemptions() != null
                && coupon.getRedemptionsUsed() > values.maxRedemptions()) {
            throw problem(
                HttpStatus.CONFLICT,
                "COUPON_LIMIT_CONFLICT",
                "The coupon has already been redeemed "
                    + coupon.getRedemptionsUsed()
                    + " times; the global cap cannot go below that"
            );
        }
        Instant now = Instant.now(clock);
        coupon.redefine(
            values.code(),
            values.name(),
            values.discountType(),
            values.discountValue(),
            now
        );
        configure(coupon, values, now);
        return viewOf(coupon);
    }

    /**
     * Deletes the coupon outright. Referencing orders keep their priced
     * discounts - the schema nulls their {@code coupon_id}, which is right:
     * the money already left in their total, and the code only told the
     * checkout how to arrive there. Redemption history cascades away.
     */
    @Transactional
    public void delete(UUID id) {
        Coupon coupon = coupons.findById(id).orElseThrow(() -> problem(
            HttpStatus.NOT_FOUND,
            "COUPON_NOT_FOUND",
            "The coupon does not exist"
        ));
        coupons.delete(coupon);
    }

    /**
     * One admin request for a quote-eligible coupon, already normalised into
     * domain types: instants, arrays of codes, no JSON left to guess at.
     *
     * @param limitedPlanIds empty means every plan, matching the original
     * @param limitedPeriods empty means every billing period
     */
    public record CouponDraft(
        String code,
        String name,
        CouponDiscountType discountType,
        Long discountValue,
        Instant startsAt,
        Instant endsAt,
        Integer maxRedemptions,
        Integer maxRedemptionsPerUser,
        List<String> limitedPlanIds,
        List<String> limitedPeriods,
        boolean enabled
    ) {
    }

    private void configure(Coupon coupon, Values values, Instant now) {
        coupon.configureLimits(
            values.startsAt(),
            values.endsAt(),
            values.maxRedemptions(),
            values.maxRedemptionsPerUser(),
            encode(values.limitedPlanIds()),
            encode(values.limitedPeriods()),
            values.enabled(),
            now
        );
    }

    private record Values(
        String code,
        String name,
        CouponDiscountType discountType,
        long discountValue,
        Instant startsAt,
        Instant endsAt,
        Integer maxRedemptions,
        Integer maxRedemptionsPerUser,
        List<String> limitedPlanIds,
        List<String> limitedPeriods,
        boolean enabled
    ) {
    }

    private Values validate(CouponDraft draft) {
        String code = draft.code() == null ? "" : draft.code().trim();
        if (code.isEmpty() || code.length() > 64) {
            throw invalid("Coupon code must be between 1 and 64 characters");
        }
        String name = draft.name() == null ? "" : draft.name().trim();
        if (name.isEmpty() || name.length() > 120) {
            throw invalid("Coupon name must be between 1 and 120 characters");
        }
        if (draft.discountType() == null) {
            throw invalid("Discount type is required");
        }
        if (draft.discountValue() == null) {
            throw invalid("Discount value is required");
        }
        long discountValue = draft.discountValue();
        if (draft.discountType() == CouponDiscountType.PERCENTAGE) {
            if (discountValue < 1 || discountValue > 100) {
                throw invalid(
                    "A percentage discount must be between 1 and 100"
                );
            }
        } else if (discountValue < 1) {
            throw invalid("A fixed-amount discount must be greater than zero");
        }
        validateWindow(draft.startsAt(), draft.endsAt());
        if (draft.maxRedemptions() != null && draft.maxRedemptions() < 1) {
            throw invalid("The redemption cap must be at least 1");
        }
        if (draft.maxRedemptionsPerUser() != null
                && draft.maxRedemptionsPerUser() < 1) {
            throw invalid("The per-user redemption cap must be at least 1");
        }
        return new Values(
            code,
            name,
            draft.discountType(),
            discountValue,
            draft.startsAt(),
            draft.endsAt(),
            draft.maxRedemptions(),
            draft.maxRedemptionsPerUser(),
            validatePlanIds(draft.limitedPlanIds()),
            validatePeriods(draft.limitedPeriods()),
            draft.enabled()
        );
    }

    private void validateWindow(Instant startsAt, Instant endsAt) {
        if (startsAt != null
                && endsAt != null
                && !endsAt.isAfter(startsAt)) {
            throw invalid("The coupon must end strictly after it starts");
        }
    }

    /** An unknown plan would silently narrow the coupon to nothing. */
    private List<String> validatePlanIds(List<String> rawIds) {
        Set<String> ids = trimmed(rawIds);
        List<String> unknown = ids.stream()
            .filter(id -> {
                try {
                    return !plans.existsById(UUID.fromString(id));
                } catch (IllegalArgumentException exception) {
                    return true;
                }
            })
            .toList();
        if (!unknown.isEmpty()) {
            throw invalid("Unknown plan: " + unknown.getFirst());
        }
        return List.copyOf(ids);
    }

    private List<String> validatePeriods(List<String> rawPeriods) {
        for (String period : trimmed(rawPeriods)) {
            try {
                BillingPeriod.valueOf(period);
            } catch (IllegalArgumentException exception) {
                throw invalid("Unknown billing period: " + period);
            }
        }
        return List.copyOf(trimmed(rawPeriods));
    }

    private Set<String> trimmed(List<String> values) {
        Set<String> trimmed = new LinkedHashSet<>();
        if (values == null) {
            return trimmed;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw invalid("Restriction lists cannot contain blank entries");
            }
            trimmed.add(value.trim());
        }
        return trimmed;
    }

    private void requireUnusedCode(String code, UUID ownId) {
        Optional<Coupon> existing = coupons.findByCode(code);
        if (existing.isEmpty() || existing.get().getId().equals(ownId)) {
            return;
        }
        throw problem(
            HttpStatus.CONFLICT,
            "COUPON_CODE_TAKEN",
            "Another coupon already uses this code"
        );
    }

    private AdminCouponView viewOf(Coupon coupon) {
        return AdminCouponView.from(
            coupon,
            decode(coupon.getLimitedPlanIds()),
            decode(coupon.getLimitedPeriods())
        );
    }

    private String encode(List<String> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        return objectMapper.writeValueAsString(values);
    }

    private List<String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        try {
            List<String> values =
                objectMapper.readValue(encoded, STRING_LIST);
            return values == null ? List.of() : values;
        } catch (RuntimeException exception) {
            // A malformed restriction list must not silently widen the coupon,
            // so surfacing the breakage beats rendering an empty list.
            throw problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "COUPON_MISCONFIGURED",
                "The coupon restrictions could not be read"
            );
        }
    }

    private ApiProblemException invalid(String detail) {
        return problem(HttpStatus.BAD_REQUEST, "COUPON_DEFINITION_INVALID", detail);
    }

    private ApiProblemException problem(
        HttpStatus status,
        String code,
        String detail
    ) {
        return new ApiProblemException(status, code, detail);
    }
}
