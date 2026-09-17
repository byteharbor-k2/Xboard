package com.sinx.platform.payment.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.payment.application.PaymentNotificationService;
import com.sinx.platform.payment.domain.PaymentVerificationException;

/**
 * Where a payment gateway reports that it took money.
 *
 * Anonymous by necessity - the gateway has no account here - which is why the
 * URL carries an unguessable token and the body has to be signed. Nothing about
 * the request is trusted until that signature has been checked.
 *
 * The replies are what Epay expects: a plain-text {@code success} when the
 * callback has been dealt with, and a non-2xx when it has not, which is the
 * gateway's cue to send it again. Nothing about why it failed is echoed back -
 * the caller is unauthenticated, and a detailed refusal tells it what to try
 * next.
 */
@RestController
@RequestMapping("/api/v1/guest/payment/notify")
public class PaymentNotifyController {

    private static final Logger log =
        LoggerFactory.getLogger(PaymentNotifyController.class);

    private static final String ACCEPTED = "success";

    private final PaymentNotificationService notifications;

    public PaymentNotifyController(PaymentNotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/{gateway}/{uuid}")
    ResponseEntity<String> notifyByGet(
        @PathVariable String gateway,
        @PathVariable String uuid,
        @RequestParam Map<String, String> params
    ) {
        return accept(gateway, uuid, params);
    }

    @PostMapping("/{gateway}/{uuid}")
    ResponseEntity<String> notifyByPost(
        @PathVariable String gateway,
        @PathVariable String uuid,
        @RequestParam Map<String, String> params
    ) {
        return accept(gateway, uuid, params);
    }

    private ResponseEntity<String> accept(
        String gateway,
        String uuid,
        Map<String, String> params
    ) {
        try {
            notifications.accept(gateway, uuid, params);
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(ACCEPTED);
        } catch (PaymentVerificationException rejected) {
            // The reason can name the merchant, the order or the amount, so it
            // is logged for the administrator and not returned to the caller.
            log.warn(
                "A {} callback to {} was refused: {}",
                gateway,
                uuid,
                rejected.getMessage()
            );
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.TEXT_PLAIN)
                .body("failed");
        }
    }
}
