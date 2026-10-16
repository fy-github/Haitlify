package com.haitlify.aitest.platform.system;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
class SystemHealthController {

    private final String version;
    private final String gitSha;
    private final String buildTime;

    SystemHealthController(@Value("${app.version}") String version,
                           @Value("${APP_GIT_SHA:unknown}") String gitSha,
                           @Value("${APP_BUILD_TIME:unknown}") String buildTime) {
        this.version = version;
        this.gitSha = gitSha == null || gitSha.isBlank() ? "unknown" : gitSha;
        this.buildTime = buildTime == null || buildTime.isBlank() ? "unknown" : buildTime;
    }

    /**
     * T15：版本接口必须携带 Git SHA 与构建时间（与镜像 OCI label 同源）——
     * 「验证的是哪个镜像」由运行中的进程直接回答，而不是比较 latest tag 的创建时间。
     */
    @GetMapping("/health")
    Map<String, String> health() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("status", "UP");
        payload.put("service", "haitlify-platform");
        payload.put("version", version);
        payload.put("gitSha", gitSha);
        payload.put("buildTime", buildTime);
        return payload;
    }
}
