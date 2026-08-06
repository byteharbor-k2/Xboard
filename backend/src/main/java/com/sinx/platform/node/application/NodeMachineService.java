package com.sinx.platform.node.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sinx.platform.node.domain.NodeMachine;
import com.sinx.platform.node.domain.NodeMachineLoadHistory;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.NodeMachineLoadHistoryRepository;
import com.sinx.platform.node.repository.NodeMachineRepository;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
@Transactional(readOnly = true)
public class NodeMachineService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NodeMachineRepository machines;
    private final NodeMachineLoadHistoryRepository history;
    private final ProxyNodeRepository nodes;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeMachineService(
        NodeMachineRepository machines,
        NodeMachineLoadHistoryRepository history,
        ProxyNodeRepository nodes,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.machines = machines;
        this.history = history;
        this.nodes = nodes;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<MachineView> list() {
        return machines.findAllByOrderByIdAsc().stream()
            .map(this::view)
            .toList();
    }

    @Transactional
    public CreatedMachine create(
        String name,
        String notes,
        boolean active
    ) {
        Instant now = clock.instant();
        String token = newToken();
        NodeMachine machine = machines.save(
            NodeMachine.create(normalizeName(name), token, notes, active, now)
        );
        return new CreatedMachine(machine.getId(), token);
    }

    @Transactional
    public void update(
        long id,
        String name,
        String notes,
        Boolean active
    ) {
        NodeMachine machine = machine(id);
        machine.update(
            normalizeName(name),
            notes,
            active == null ? machine.isActive() : active,
            clock.instant()
        );
    }

    @Transactional
    public String rotateToken(long id) {
        String token = newToken();
        machine(id).rotateToken(token, clock.instant());
        return token;
    }

    public String token(long id) {
        return machine(id).getToken();
    }

    @Transactional
    public void delete(long id) {
        NodeMachine machine = machine(id);
        List<ProxyNode> assignedNodes =
            nodes.findByMachineIdOrderBySortOrderAscIdAsc(id);
        if (!assignedNodes.isEmpty()) {
            Instant now = clock.instant();
            assignedNodes.forEach(node ->
                node.quickUpdate(null, null, null, true, now)
            );
            nodes.saveAll(assignedNodes);
            // Keep the operation independent of database-specific ON DELETE
            // behavior and guarantee the foreign keys are cleared first.
            nodes.flush();
        }
        machines.delete(machine);
    }

    public List<LoadHistoryView> history(
        long machineId,
        int limit,
        Integer rangeHours
    ) {
        machine(machineId);
        int safeLimit = Math.max(10, Math.min(limit, 1_440));
        PageRequest page = PageRequest.of(0, safeLimit);
        List<NodeMachineLoadHistory> records = rangeHours == null
            ? history.findByMachineIdOrderByRecordedAtDesc(machineId, page)
            : history.findByMachineIdAndRecordedAtGreaterThanEqualOrderByRecordedAtDesc(
                machineId,
                clock.instant().minusSeconds(Math.max(1, Math.min(rangeHours, 24)) * 3_600L),
                page
            );
        return records.reversed().stream()
            .map(item -> new LoadHistoryView(
                item.getCpu(),
                item.getMemoryTotal(),
                item.getMemoryUsed(),
                item.getDiskTotal(),
                item.getDiskUsed(),
                item.getNetworkInSpeed(),
                item.getNetworkOutSpeed(),
                item.getRecordedAt().getEpochSecond()
            ))
            .toList();
    }

    @Transactional
    public NodeMachine authenticate(long machineId, String token) {
        NodeMachine machine = machines.findByIdAndToken(machineId, token)
            .orElseThrow(() -> new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "INVALID_MACHINE_CREDENTIALS",
                "Machine not found or invalid token"
            ));
        if (!machine.isActive()) {
            throw new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "MACHINE_DISABLED",
                "Machine is disabled"
            );
        }
        machine.touch(clock.instant());
        return machine;
    }

    @Transactional
    public void recordStatus(
        long machineId,
        String token,
        MachineStatus status
    ) {
        NodeMachine machine = authenticate(machineId, token);
        Instant now = clock.instant();
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("cpu", status.cpu());
        load.put("mem", Map.of(
            "total", status.mem().total(),
            "used", status.mem().used()
        ));
        ResourceUsage swap = status.swap() == null ? ResourceUsage.ZERO : status.swap();
        ResourceUsage disk = status.disk() == null ? ResourceUsage.ZERO : status.disk();
        load.put("swap", Map.of(
            "total", swap.total(),
            "used", swap.used()
        ));
        load.put("disk", Map.of(
            "total", disk.total(),
            "used", disk.used()
        ));
        if (status.net() != null) {
            load.put("net", Map.of(
                "in_speed", status.net().inSpeed(),
                "out_speed", status.net().outSpeed()
            ));
        }
        load.put("updated_at", now.getEpochSecond());
        try {
            machine.recordStatus(objectMapper.writeValueAsString(load), now);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not encode machine status", exception);
        }
        history.save(NodeMachineLoadHistory.create(
            machine,
            status.cpu(),
            status.mem().total(),
            status.mem().used(),
            disk.total(),
            disk.used(),
            status.net() == null ? null : status.net().inSpeed(),
            status.net() == null ? null : status.net().outSpeed(),
            now
        ));
        history.deleteByMachineIdAndRecordedAtBefore(
            machineId,
            now.minusSeconds(86_400)
        );
    }

    private MachineView view(NodeMachine machine) {
        Object load = null;
        if (machine.getLoadStatus() != null) {
            try {
                load = objectMapper.readValue(machine.getLoadStatus(), Object.class);
            } catch (JacksonException ignored) {
                load = null;
            }
        }
        return new MachineView(
            machine.getId(),
            machine.getName(),
            machine.getNotes(),
            machine.isActive(),
            machine.getLastSeenAt() == null
                ? null
                : machine.getLastSeenAt().getEpochSecond(),
            load,
            nodes.countByMachineId(machine.getId()),
            machine.getCreatedAt(),
            machine.getUpdatedAt()
        );
    }

    private NodeMachine machine(long id) {
        return machines.findById(id).orElseThrow(() -> new ApiProblemException(
            HttpStatus.NOT_FOUND,
            "MACHINE_NOT_FOUND",
            "Machine does not exist"
        ));
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 255) {
            throw new ApiProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "INVALID_MACHINE_NAME",
                "Machine name is required and must not exceed 255 characters"
            );
        }
        return name.trim();
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record CreatedMachine(long id, String token) {
    }

    public record MachineView(
        long id,
        String name,
        String notes,
        boolean active,
        Long lastSeenAt,
        Object loadStatus,
        long serversCount,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record LoadHistoryView(
        double cpu,
        long memoryTotal,
        long memoryUsed,
        long diskTotal,
        long diskUsed,
        Double networkInSpeed,
        Double networkOutSpeed,
        long recordedAt
    ) {
    }

    public record MachineStatus(
        double cpu,
        ResourceUsage mem,
        ResourceUsage swap,
        ResourceUsage disk,
        NetworkUsage net
    ) {
    }

    public record ResourceUsage(long total, long used) {
        private static final ResourceUsage ZERO = new ResourceUsage(0, 0);
    }

    public record NetworkUsage(double inSpeed, double outSpeed) {
    }
}
