package com.sinx.platform.configuration.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An administrator's override for one of the bundled mail templates.
 *
 * Only customized templates are stored: the bundled default ships inside the
 * jar, so a fresh install sends working mail without anything being seeded
 * here, and deleting a row is how an override comes back to default. That is
 * the original panel's {@code v2_mail_templates} table, renamed to the
 * rewrite's plain naming.
 */
@Entity
@Table(name = "mail_templates")
public class MailTemplate {

    @Id
    @Column(length = 64, nullable = false, updatable = false)
    private String name;

    @Column(length = 255, nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MailTemplate() {
    }

    public static MailTemplate create(
        String name,
        String subject,
        String content,
        Instant now
    ) {
        MailTemplate template = new MailTemplate();
        template.name = name;
        template.subject = subject;
        template.content = content;
        template.createdAt = now;
        template.updatedAt = now;
        return template;
    }

    public String name() {
        return name;
    }

    public String subject() {
        return subject;
    }

    public String content() {
        return content;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void update(String subject, String content, Instant now) {
        this.subject = subject;
        this.content = content;
        this.updatedAt = now;
    }
}
