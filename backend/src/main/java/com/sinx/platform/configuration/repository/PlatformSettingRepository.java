package com.sinx.platform.configuration.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.configuration.domain.PlatformSetting;

public interface PlatformSettingRepository
    extends JpaRepository<PlatformSetting, String> {
}
