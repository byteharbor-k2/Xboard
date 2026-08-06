package com.sinx.platform.node.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.node.application.NodeAccessGroupService;

@RestController
@RequestMapping("/api/v2/admin/server/group")
public class AdminNodeAccessGroupController {

    private final NodeAccessGroupService groups;

    public AdminNodeAccessGroupController(NodeAccessGroupService groups) {
        this.groups = groups;
    }

    @GetMapping("/fetch")
    XboardResponse<List<NodeAccessGroupService.GroupView>> fetch() {
        return XboardResponse.of(groups.list());
    }

    @PostMapping("/save")
    XboardResponse<Boolean> save(@RequestBody SaveGroupRequest request) {
        return XboardResponse.of(groups.save(request.id(), request.name()));
    }

    @PostMapping("/drop")
    XboardResponse<Boolean> drop(@RequestBody IdRequest request) {
        return XboardResponse.of(groups.delete(request.id()));
    }

    record SaveGroupRequest(Long id, String name) {
    }

    record IdRequest(long id) {
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) { return new XboardResponse<>(data); }
    }
}
