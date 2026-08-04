package com.sinx.platform.node.domain;

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

@Entity
@Table(name = "node_machine_load_history")
public class NodeMachineLoadHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "machine_id", nullable = false)
    private NodeMachine machine;

    @Column(nullable = false)
    private double cpu;

    @Column(name = "mem_total", nullable = false)
    private long memoryTotal;

    @Column(name = "mem_used", nullable = false)
    private long memoryUsed;

    @Column(name = "disk_total", nullable = false)
    private long diskTotal;

    @Column(name = "disk_used", nullable = false)
    private long diskUsed;

    @Column(name = "net_in_speed")
    private Double networkInSpeed;

    @Column(name = "net_out_speed")
    private Double networkOutSpeed;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NodeMachineLoadHistory() {
    }

    public static NodeMachineLoadHistory create(
        NodeMachine machine,
        double cpu,
        long memoryTotal,
        long memoryUsed,
        long diskTotal,
        long diskUsed,
        Double networkInSpeed,
        Double networkOutSpeed,
        Instant now
    ) {
        NodeMachineLoadHistory history = new NodeMachineLoadHistory();
        history.machine = machine;
        history.cpu = cpu;
        history.memoryTotal = memoryTotal;
        history.memoryUsed = memoryUsed;
        history.diskTotal = diskTotal;
        history.diskUsed = diskUsed;
        history.networkInSpeed = networkInSpeed;
        history.networkOutSpeed = networkOutSpeed;
        history.recordedAt = now;
        history.createdAt = now;
        return history;
    }

    public double getCpu() {
        return cpu;
    }

    public long getMemoryTotal() {
        return memoryTotal;
    }

    public long getMemoryUsed() {
        return memoryUsed;
    }

    public long getDiskTotal() {
        return diskTotal;
    }

    public long getDiskUsed() {
        return diskUsed;
    }

    public Double getNetworkInSpeed() {
        return networkInSpeed;
    }

    public Double getNetworkOutSpeed() {
        return networkOutSpeed;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
