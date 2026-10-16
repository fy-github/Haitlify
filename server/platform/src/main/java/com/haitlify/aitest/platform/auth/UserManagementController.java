package com.haitlify.aitest.platform.auth;

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

@RestController
@RequestMapping("/api/users")
class UserManagementController {

    private final UserManagementService userManagementService;

    UserManagementController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    List<UserSummaryResponse> list() {
        return userManagementService.list();
    }

    @PostMapping
    UserSummaryResponse create(@RequestBody CreateUserRequest request) {
        return userManagementService.create(request);
    }

    @PutMapping("/{username}")
    UserSummaryResponse update(@PathVariable String username, @RequestBody UpdateUserRequest request) {
        return userManagementService.update(username, request);
    }

    @DeleteMapping("/{username}")
    Map<String, String> delete(@PathVariable String username) {
        userManagementService.delete(username);
        return Map.of("message", "已删除用户");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> handleBadRequest(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }
}
