package com.sinx.platform.node.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "node_machines")
public class NodeMachine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(length = 64, nullable = false, unique = true)
    private String token;

    @Column
    private String notes;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "load_status")
    private String loadStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected NodeMachine() {
    }

    public static NodeMachine create(
        String name,
        String token,
        String notes,
        boolean active,
        Instant now
    ) {
        NodeMachine machine = new NodeMachine();
        machine.name = name;
        machine.token = token;
        machine.notes = notes;
        machine.active = active;
        machine.createdAt = now;
        machine.updatedAt = now;
        return machine;
    }

    public void update(String name, String notes, boolean active, Instant now) {
        this.name = name;
        this.notes = notes;
        this.active = active;
        this.updatedAt = now;
    }

    public void rotateToken(String token, Instant now) {
        this.token = token;
        this.updatedAt = now;
    }

    public void recordStatus(String loadStatus, Instant now) {
        this.loadStatus = loadStatus;
        this.lastSeenAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getToken() {
        return token;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getLoadStatus() {
        return loadStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
