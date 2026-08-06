package com.sinx.platform.node.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "proxy_nodes")
public class ProxyNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 32, nullable = false)
    private String type;

    @Column(length = 64)
    private String code;

    @Column(name = "parent_id")
    private Long parentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id")
    private NodeMachine machine;

    @Column(name = "group_ids", nullable = false)
    private String groupIds;

    @Column(name = "route_ids", nullable = false)
    private String routeIds;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal rate;

    @Column(name = "rate_time_enable", nullable = false)
    private boolean rateTimeEnable;

    @Column(name = "rate_time_ranges", nullable = false)
    private String rateTimeRanges;

    @Column(name = "transfer_enable", nullable = false)
    private long transferEnable;

    @Column(name = "upload_bytes", nullable = false)
    private long uploadBytes;

    @Column(name = "download_bytes", nullable = false)
    private long downloadBytes;

    @Column(nullable = false)
    private String tags;

    @Column(length = 255)
    private String host;

    @Column
    private Integer port;

    @Column(name = "server_port", nullable = false)
    private int serverPort;

    @Column(name = "protocol_settings", nullable = false)
    private String protocolSettings;

    @Column(name = "custom_outbounds", nullable = false)
    private String customOutbounds;

    @Column(name = "custom_routes", nullable = false)
    private String customRoutes;

    @Column(name = "cert_config")
    private String certConfig;

    @Column(name = "is_show", nullable = false)
    private boolean show;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "online_users", nullable = false)
    private int onlineUsers;

    @Column(name = "online_connections", nullable = false)
    private int onlineConnections;

    @Column(name = "load_status")
    private String loadStatus;

    @Column(name = "metrics")
    private String metrics;

    @Column(name = "last_check_at")
    private Instant lastCheckAt;

    @Column(name = "last_push_at")
    private Instant lastPushAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ProxyNode() {
    }

    public static ProxyNode create(Instant now) {
        ProxyNode node = new ProxyNode();
        node.groupIds = "[]";
        node.routeIds = "[]";
        node.rate = BigDecimal.ONE;
        node.rateTimeRanges = "[]";
        node.tags = "[]";
        node.protocolSettings = "{}";
        node.customOutbounds = "[]";
        node.customRoutes = "[]";
        node.show = true;
        node.enabled = true;
        node.createdAt = now;
        node.updatedAt = now;
        return node;
    }

    public void configure(
        String type, String code, Long parentId, NodeMachine machine,
        String groupIds, String routeIds, String name, BigDecimal rate,
        boolean rateTimeEnable, String rateTimeRanges, long transferEnable,
        String tags, String host, Integer port, int serverPort,
        String protocolSettings, String customOutbounds, String customRoutes,
        String certConfig, boolean show, boolean enabled, int sortOrder,
        Instant now
    ) {
        this.type = type;
        this.code = code;
        this.parentId = parentId;
        this.machine = machine;
        this.groupIds = groupIds;
        this.routeIds = routeIds;
        this.name = name;
        this.rate = rate;
        this.rateTimeEnable = rateTimeEnable;
        this.rateTimeRanges = rateTimeRanges;
        this.transferEnable = transferEnable;
        this.tags = tags;
        this.host = host;
        this.port = port;
        this.serverPort = serverPort;
        this.protocolSettings = protocolSettings;
        this.customOutbounds = customOutbounds;
        this.customRoutes = customRoutes;
        this.certConfig = certConfig;
        this.show = show;
        this.enabled = enabled;
        this.sortOrder = sortOrder;
        this.updatedAt = now;
    }

    public void quickUpdate(Boolean show, Boolean enabled, NodeMachine machine, boolean updateMachine, Instant now) {
        if (show != null) this.show = show;
        if (enabled != null) this.enabled = enabled;
        if (updateMachine) this.machine = machine;
        this.updatedAt = now;
    }

    public void changeSort(int sortOrder, Instant now) {
        this.sortOrder = sortOrder;
        this.updatedAt = now;
    }

    public void resetTraffic(Instant now) {
        this.uploadBytes = 0;
        this.downloadBytes = 0;
        this.updatedAt = now;
    }

    public void replaceGroupIds(String groupIds, Instant now) {
        this.groupIds = groupIds;
        this.updatedAt = now;
    }

    public void replaceRouteIds(String routeIds, Instant now) {
        this.routeIds = routeIds;
        this.updatedAt = now;
    }

    public void recordReport(long upload, long download, int onlineUsers, int onlineConnections,
                             String loadStatus, String metrics, Instant now) {
        this.uploadBytes = saturatedAdd(this.uploadBytes, Math.max(upload, 0));
        this.downloadBytes = saturatedAdd(this.downloadBytes, Math.max(download, 0));
        this.onlineUsers = Math.max(onlineUsers, 0);
        this.onlineConnections = Math.max(onlineConnections, 0);
        this.loadStatus = loadStatus;
        this.metrics = metrics;
        this.lastCheckAt = now;
        this.lastPushAt = now;
        this.updatedAt = now;
    }

    private long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public String getCode() { return code; }
    public Long getParentId() { return parentId; }
    public NodeMachine getMachine() { return machine; }
    public String getGroupIds() { return groupIds; }
    public String getRouteIds() { return routeIds; }
    public String getName() { return name; }
    public BigDecimal getRate() { return rate; }
    public boolean isRateTimeEnable() { return rateTimeEnable; }
    public String getRateTimeRanges() { return rateTimeRanges; }
    public long getTransferEnable() { return transferEnable; }
    public long getUploadBytes() { return uploadBytes; }
    public long getDownloadBytes() { return downloadBytes; }
    public String getTags() { return tags; }
    public String getHost() { return host; }
    public Integer getPort() { return port; }
    public int getServerPort() { return serverPort; }
    public String getProtocolSettings() { return protocolSettings; }
    public String getCustomOutbounds() { return customOutbounds; }
    public String getCustomRoutes() { return customRoutes; }
    public String getCertConfig() { return certConfig; }
    public boolean isShow() { return show; }
    public boolean isEnabled() { return enabled; }
    public int getSortOrder() { return sortOrder; }
    public int getOnlineUsers() { return onlineUsers; }
    public int getOnlineConnections() { return onlineConnections; }
    public String getLoadStatus() { return loadStatus; }
    public String getMetrics() { return metrics; }
    public Instant getLastCheckAt() { return lastCheckAt; }
    public Instant getLastPushAt() { return lastPushAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
