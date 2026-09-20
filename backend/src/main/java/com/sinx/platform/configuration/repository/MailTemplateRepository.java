package com.sinx.platform.configuration.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.configuration.domain.MailTemplate;

public interface MailTemplateRepository
    extends JpaRepository<MailTemplate, String> {
}
