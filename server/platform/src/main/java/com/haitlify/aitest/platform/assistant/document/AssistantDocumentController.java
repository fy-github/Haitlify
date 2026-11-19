package com.haitlify.aitest.platform.assistant.document;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.haitlify.aitest.platform.tenant.TenantAccessService;

/**
 * 需求文档取文本入口：前端上传 PDF/DOCX 后拿到纯文本，再走既有「需求 → 草稿」链路。
 *
 * <p>只做文本抽取：不做图片视觉理解，也不判断内容是否正确（那属于模型与人工确认）。
 */
@RestController
@RequestMapping("/api/assistant/documents")
public class AssistantDocumentController {

    private final RequirementDocumentTextExtractor documents;
    private final TenantAccessService tenants;

    public AssistantDocumentController(RequirementDocumentTextExtractor documents, TenantAccessService tenants) {
        this.documents = documents;
        this.tenants = tenants;
    }

    @PostMapping("/extract")
    @ResponseStatus(HttpStatus.CREATED)
    public RequirementDocumentTextExtractor.ExtractResult extract(@RequestParam("file") MultipartFile file,
                                                            @RequestParam long tenantId,
                                                            @RequestParam long projectId) throws IOException {
        tenants.requireTenantProject(tenantId, projectId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        try (InputStream input = file.getInputStream()) {
            return documents.extract(input, file.getOriginalFilename(), file.getSize());
        }
    }

    /** 文件的类型/大小/可解析性都由调用方决定，因此解析失败也按 400 返回，不伪装成服务端故障。 */
    @org.springframework.web.bind.annotation.ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> badRequest(RuntimeException ex) {
        return java.util.Map.of("error", "invalid_document", "message", ex.getMessage());
    }
}
