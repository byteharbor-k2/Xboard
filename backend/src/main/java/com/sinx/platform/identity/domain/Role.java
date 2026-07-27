package com.sinx.platform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    @Column(length = 32, nullable = false, updatable = false)
    private String code;

    @Column(length = 160, nullable = false)
    private String description;

    protected Role() {
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
