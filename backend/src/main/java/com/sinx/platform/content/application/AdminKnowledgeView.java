package com.sinx.platform.content.application;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.content.domain.KnowledgeArticle;

/**
 * Knowledge entries as the admin API reports them, in the original panel's
 * admin shape: snake_case field names and epoch-second timestamps. The list
 * view omits the body, like the original fetch.
 */
public sealed interface AdminKnowledgeView {

    record Summary(
        UUID id,
        String title,
        String category,
        boolean show,
        int sort,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("updated_at") long updatedAt
    ) implements AdminKnowledgeView {
    }

    record Full(
        UUID id,
        String title,
        String category,
        String language,
        @JsonProperty("body") String body,
        boolean show,
        int sort,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("updated_at") long updatedAt
    ) implements AdminKnowledgeView {
    }

    static Summary summaryOf(KnowledgeArticle article) {
        return new Summary(
            article.getId(),
            article.getTitle(),
            article.getCategory(),
            article.isShown(),
            article.getSort(),
            article.getCreatedAt().getEpochSecond(),
            article.getUpdatedAt().getEpochSecond()
        );
    }

    static Full fullOf(KnowledgeArticle article) {
        return new Full(
            article.getId(),
            article.getTitle(),
            article.getCategory(),
            article.getLanguage(),
            article.getBody(),
            article.isShown(),
            article.getSort(),
            article.getCreatedAt().getEpochSecond(),
            article.getUpdatedAt().getEpochSecond()
        );
    }

    static AdminKnowledgeView from(KnowledgeArticle article, boolean full) {
        return full ? fullOf(article) : summaryOf(article);
    }
}
