package com.sinx.platform.catalog.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.catalog.application.ManagedPlanView;
import com.sinx.platform.catalog.application.PlanManagementService;

@RestController
@RequestMapping("/control/catalog/plans")
public class PlanManagementController {

    private final PlanManagementService plans;

    public PlanManagementController(PlanManagementService plans) {
        this.plans = plans;
    }

    @GetMapping
    List<ManagedPlanView> list() {
        return plans.list();
    }

    @PostMapping
    ResponseEntity<ManagedPlanView> create(
        @RequestBody PlanManagementService.PlanDraft draft
    ) {
        ManagedPlanView created = plans.create(draft);
        return ResponseEntity.created(
            URI.create("/control/catalog/plans/" + created.id())
        ).body(created);
    }

    @PutMapping("/{id}")
    ManagedPlanView update(
        @PathVariable UUID id,
        @RequestBody PlanManagementService.PlanDraft draft
    ) {
        return plans.update(id, draft);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        plans.delete(id);
        return ResponseEntity.noContent().build();
    }
}
