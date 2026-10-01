package com.sinx.platform.content.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.content.domain.Notice;

/**
 * One notice as the admin API reports it, in the original panel's admin
 * shape: snake_case field names and epoch-second timestamps.
 */
public record AdminNoticeView(
    UUID id,
    String title,
    String content,
    @JsonProperty("img_url") String imgUrl,
    List<String> tags,
    boolean show,
    boolean popup,
    int sort,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("updated_at") long updatedAt
) {
    public static AdminNoticeView from(Notice notice, List<String> tags) {
        return new AdminNoticeView(
            notice.getId(),
            notice.getTitle(),
            notice.getContent(),
            notice.getImgUrl(),
            tags,
            notice.isShown(),
            notice.isPopup(),
            notice.getSort(),
            seconds(notice.getCreatedAt()),
            seconds(notice.getUpdatedAt())
        );
    }

    private static long seconds(Instant instant) {
        return instant.getEpochSecond();
    }
}
