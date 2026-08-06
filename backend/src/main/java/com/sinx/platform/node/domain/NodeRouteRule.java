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
@Table(name = "node_route_rules")
public class NodeRouteRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255, nullable = false)
    private String remarks;

    @Column(name = "match_rules", nullable = false)
    private String matchRules;

    @Column(length = 16, nullable = false)
    private String action;

    @Column(name = "action_value", length = 255)
    private String actionValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected NodeRouteRule() {
    }

    public static NodeRouteRule create(
        String remarks,
        String matchRules,
        String action,
        String actionValue,
        Instant now
    ) {
        NodeRouteRule route = new NodeRouteRule();
        route.configure(remarks, matchRules, action, actionValue, now);
        route.createdAt = now;
        return route;
    }

    public void configure(
        String remarks,
        String matchRules,
        String action,
        String actionValue,
        Instant now
    ) {
        this.remarks = remarks;
        this.matchRules = matchRules;
        this.action = action;
        this.actionValue = actionValue;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getRemarks() { return remarks; }
    public String getMatchRules() { return matchRules; }
    public String getAction() { return action; }
    public String getActionValue() { return actionValue; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
