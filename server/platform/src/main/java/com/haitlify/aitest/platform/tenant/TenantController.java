package com.haitlify.aitest.platform.tenant;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户运维接口。安全策略里整段只允许 ADMIN（见 SecurityConfig），
 * 避免普通成员给自己加租户或改成员关系。
 */
@RestController
@RequestMapping("/api/tenants")
class TenantController {

    private final TenantManagementService tenantManagementService;

    TenantController(TenantManagementService tenantManagementService) {
        this.tenantManagementService = tenantManagementService;
    }

    @GetMapping
    List<TenantManagementService.TenantView> list() {
        return tenantManagementService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TenantManagementService.TenantView create(@RequestBody CreateTenantRequest request) {
        return tenantManagementService.create(request.code(), request.name());
    }

    @PutMapping("/{tenantId}")
    TenantManagementService.TenantView update(@PathVariable long tenantId, @RequestBody UpdateTenantRequest request) {
        return tenantManagementService.update(tenantId, request.name(), request.active());
    }

    @GetMapping("/{tenantId}/members")
    List<TenantManagementService.MemberView> members(@PathVariable long tenantId) {
        return tenantManagementService.members(tenantId);
    }

    @PostMapping("/{tenantId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> addMember(@PathVariable long tenantId, @RequestBody AddMemberRequest request) {
        tenantManagementService.addMember(tenantId, request.username());
        return Map.of("message", "已加入租户");
    }

    @DeleteMapping("/{tenantId}/members/{username}")
    Map<String, String> removeMember(@PathVariable long tenantId, @PathVariable String username) {
        tenantManagementService.removeMember(tenantId, username);
        return Map.of("message", "已移出租户");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> handleBadRequest(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }

    record CreateTenantRequest(String code, String name) {
    }

    record UpdateTenantRequest(String name, Boolean active) {
    }

    record AddMemberRequest(String username) {
    }
}
