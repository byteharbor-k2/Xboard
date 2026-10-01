package com.sinx.platform.content.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A knowledge-base article. {@code category} groups articles the help centre
 * lists by; {@code language} is the article's own language tag, matching the
 * original panel's select-by-language user fetch.
 */
@Entity
@Table(name = "knowledge_articles")
public class KnowledgeArticle {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String category;

    @Column(nullable = false, length = 10)
    private String language;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "is_shown", nullable = false)
    private boolean shown = false;

    @Column(nullable = false)
    private int sort = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeArticle() {
    }

    public static KnowledgeArticle create(
        String category,
        String language,
        String title,
        Instant now
    ) {
        KnowledgeArticle article = new KnowledgeArticle();
        article.id = UUID.randomUUID();
        article.category = category;
        article.language = language;
        article.title = title;
        article.createdAt = now;
        article.updatedAt = now;
        return article;
    }

    public void redefine(
        String category,
        String language,
        String title,
        String body,
        Boolean shown,
        Integer sort,
        Instant now
    ) {
        this.category = category;
        this.language = language;
        this.title = title;
        this.body = body;
        if (shown != null) {
            this.shown = shown;
        }
        if (sort != null) {
            this.sort = sort;
        }
        this.updatedAt = now;
    }

    /** The help centre's visibility switch, toggled as-is by {@code show}. */
    public void toggleShown(Instant now) {
        shown = !shown;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }

    public String getLanguage() {
        return language;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public boolean isShown() {
        return shown;
    }

    public int getSort() {
        return sort;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
