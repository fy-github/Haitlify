package com.haitlify.aitest.platform.ai.runtime;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * T06：job API 的错误映射（限定到 {@link AiJobController}，不影响其他控制器）。
 * 「job_not_found」前缀 ⇒ 404，其余参数错误 ⇒ 400。
 */
@RestControllerAdvice(assignableTypes = AiJobController.class)
class AiJobExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException exception) {
        return Map.of("error", exception.getMessage() == null ? "invalid_request" : exception.getMessage());
    }

    @ExceptionHandler(AiJobController.JobNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> notFound(AiJobController.JobNotFoundException exception) {
        return Map.of("error", exception.getMessage());
    }

    /** C-7：幂等冲突（同 key 异输入）以 409 表达，绝非未映射 500。 */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(IllegalStateException exception) {
        return Map.of("error", exception.getMessage() == null ? "conflict" : exception.getMessage());
    }

    /** C-7：绕过服务层预校验直插数据库引发的约束异常也必须可读。 */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> duplicateKey(org.springframework.dao.DuplicateKeyException exception) {
        return Map.of("error", "duplicate_key");
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> dataIntegrity(org.springframework.dao.DataIntegrityViolationException exception) {
        return Map.of("error", "data_integrity_violation");
    }

    /** R3-2：连接池暂不可得时给出可读的 503，而非裸 500。 */
    @ExceptionHandler(org.springframework.transaction.CannotCreateTransactionException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> transactionUnavailable(
            org.springframework.transaction.CannotCreateTransactionException exception) {
        return Map.of("error", "transaction_unavailable");
    }
}
