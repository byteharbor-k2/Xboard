package com.sinx.platform.configuration.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "platform_settings")
public class PlatformSetting {

    @Id
    @Column(name = "setting_key", length = 160, nullable = false)
    private String key;

    @Column(name = "setting_value", nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlatformSetting() {
    }

    public static PlatformSetting create(
        String key,
        String value,
        Instant now
    ) {
        PlatformSetting setting = new PlatformSetting();
        setting.key = key;
        setting.value = value;
        setting.updatedAt = now;
        return setting;
    }

    public String value() {
        return value;
    }

    public String key() {
        return key;
    }

    public void update(String value, Instant now) {
        this.value = value;
        this.updatedAt = now;
    }
}
