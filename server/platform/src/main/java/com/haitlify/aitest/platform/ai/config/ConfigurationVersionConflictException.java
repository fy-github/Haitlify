package com.haitlify.aitest.platform.ai.config;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** T01：配置版本冲突（乐观并发）——对外映射为 HTTP 409。 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ConfigurationVersionConflictException extends RuntimeException {

    public ConfigurationVersionConflictException(String kind, String id, int expected, int actual) {
        super(kind + " version conflict for " + id + ": expected " + expected + ", actual " + actual);
    }
}
