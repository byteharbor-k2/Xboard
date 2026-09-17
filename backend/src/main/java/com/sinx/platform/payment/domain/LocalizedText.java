package com.sinx.platform.payment.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A label the admin UI renders in whichever language it is currently in. */
public record LocalizedText(
    @JsonProperty("zh-CN") String zhCn,
    @JsonProperty("en-US") String enUs
) {

    public static LocalizedText of(String zhCn, String enUs) {
        return new LocalizedText(zhCn, enUs);
    }
}
