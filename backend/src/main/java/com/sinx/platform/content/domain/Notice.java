package com.sinx.platform.content.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A portal notice. The physical column {@code is_shown} carries what the
 * original panel's schema called {@code show}: an operator decides whether the
 * dashboard announcement carousel serves the notice at all, and {@code sort}
 * decides the order the carousel cycles through.
 */
@Entity
@Table(name = "notices")
public class Notice {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "img_url", length = 500)
    private String imgUrl;

    /** Encoded JSON list of tag strings, as stored by the admin save. */
    @Column(nullable = false, columnDefinition = "text")
    private String tags = "[]";

    @Column(name = "is_shown", nullable = false)
    private boolean shown = false;

    /** Whether the account dashboard may raise the notice as a popup. */
    @Column(nullable = false)
    private boolean popup = false;

    @Column(nullable = false)
    private int sort = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Notice() {
    }

    public static Notice create(String title, String content, Instant now) {
        Notice notice = new Notice();
        notice.id = UUID.randomUUID();
        notice.title = title;
        notice.content = content;
        notice.createdAt = now;
        notice.updatedAt = now;
        return notice;
    }

    public void redefine(
        String title,
        String content,
        String imgUrl,
        String tags,
        Boolean shown,
        Boolean popup,
        Integer sort,
        Instant now
    ) {
        this.title = title;
        this.content = content;
        if (imgUrl != null) {
            this.imgUrl = imgUrl;
        }
        if (tags != null) {
            this.tags = tags;
        }
        if (shown != null) {
            this.shown = shown;
        }
        if (popup != null) {
            this.popup = popup;
        }
        if (sort != null) {
            this.sort = sort;
        }
        this.updatedAt = now;
    }

    /** The carousel's visibility switch, toggled as-is by {@code show}. */
    public void toggleShown(Instant now) {
        shown = !shown;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getImgUrl() {
        return imgUrl;
    }

    public String getTags() {
        return tags;
    }

    public boolean isShown() {
        return shown;
    }

    public boolean isPopup() {
        return popup;
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
