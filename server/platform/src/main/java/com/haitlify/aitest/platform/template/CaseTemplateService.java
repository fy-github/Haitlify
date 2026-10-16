package com.haitlify.aitest.platform.template;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!jira-readonly")
@Transactional
class CaseTemplateService {

    private static final String INTERFACE_KIND = "interface";
    private static final int SCHEMA_VERSION = 2;

    private final CaseTemplateRepository repository;
    private final ObjectMapper objectMapper;

    CaseTemplateService(CaseTemplateRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    CaseTemplateResponse getInterfaceTemplate(Long projectId) {
        return repository.findByProjectIdAndKind(projectId, INTERFACE_KIND)
            .map(this::toResponse)
            .orElseGet(this::defaultInterfaceTemplate);
    }

    CaseTemplateResponse saveInterfaceTemplate(Long projectId, SaveCaseTemplateRequest request) {
        CaseTemplateConfig current = repository.findByProjectIdAndKind(projectId, INTERFACE_KIND).orElse(null);
        Instant now = Instant.now();
        CaseTemplateConfig next = new CaseTemplateConfig(
            current == null ? null : current.getId(),
            projectId,
            INTERFACE_KIND,
            request.schemaVersion(),
            stringify(request.interfaceFields()),
            stringify(request.caseTypes()),
            current == null ? now : current.getCreatedAt(),
            now
        );

        return toResponse(repository.save(next));
    }

    private CaseTemplateResponse toResponse(CaseTemplateConfig config) {
        return new CaseTemplateResponse(
            config.getKind(),
            config.getSchemaVersion(),
            parse(config.getInterfaceFieldsJson()),
            parse(config.getCaseTypesJson()),
            config.getUpdatedAt().toString()
        );
    }

    private CaseTemplateResponse defaultInterfaceTemplate() {
        return new CaseTemplateResponse(
            INTERFACE_KIND,
            SCHEMA_VERSION,
            parse(DEFAULT_INTERFACE_FIELDS_JSON),
            parse(DEFAULT_CASE_TYPES_JSON),
            null
        );
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid template JSON", exception);
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid template payload", exception);
        }
    }

    private static final String DEFAULT_INTERFACE_FIELDS_JSON = """
        [
          {"key":"caseId","label":"用例ID","type":"text","required":true,"visible":true,"exportable":true,"aiGenerated":false},
          {"key":"project","label":"项目","type":"text","required":true,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"module","label":"模块","type":"text","required":true,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"interfaceName","label":"接口名称","type":"text","required":true,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"method","label":"请求方法","type":"text","required":true,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"path","label":"请求路径","type":"text","required":true,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"title","label":"用例标题","type":"text","required":true,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"caseType","label":"用例类型","type":"select","required":true,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"precondition","label":"前置条件","type":"textarea","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"headers","label":"请求头","type":"json","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"pathParams","label":"路径参数","type":"json","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"queryParams","label":"查询参数","type":"json","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"body","label":"请求体","type":"json","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"steps","label":"测试步骤","type":"textarea","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"expectedResult","label":"预期结果","type":"textarea","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"expectedResponseBody","label":"预期返回体","type":"json","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"actualResponseBody","label":"实际返回体","type":"json","required":false,"visible":true,"exportable":true,"aiGenerated":false},
          {"key":"assertions","label":"断言规则","type":"textarea","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"priority","label":"优先级","type":"select","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"executionResult","label":"执行结果","type":"select","required":false,"visible":true,"exportable":true,"aiGenerated":false,"defaultValue":"未执行"},
          {"key":"executor","label":"执行人","type":"text","required":false,"visible":true,"exportable":true,"aiGenerated":false},
          {"key":"executedAt","label":"执行时间","type":"datetime","required":false,"visible":true,"exportable":true,"aiGenerated":false},
          {"key":"defectId","label":"缺陷ID","type":"text","required":false,"visible":true,"exportable":true,"aiGenerated":false},
          {"key":"tags","label":"标签","type":"tags","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"sourceFile","label":"来源文件","type":"text","required":false,"visible":true,"exportable":true,"aiGenerated":true},
          {"key":"remark","label":"备注","type":"textarea","required":false,"visible":true,"exportable":true,"aiGenerated":true}
        ]
        """;

    private static final String DEFAULT_CASE_TYPES_JSON = """
        [
          {"id":"positive","name":"正向","appliesTo":"all","enabled":true},
          {"id":"required_missing","name":"必填缺失","appliesTo":"all","enabled":true},
          {"id":"type_error","name":"参数类型错误","appliesTo":"interface","enabled":true},
          {"id":"boundary","name":"边界值","appliesTo":"all","enabled":true},
          {"id":"auth_missing","name":"鉴权缺失","appliesTo":"interface","enabled":true},
          {"id":"permission_denied","name":"权限不足","appliesTo":"interface","enabled":true},
          {"id":"not_found","name":"数据不存在","appliesTo":"all","enabled":true},
          {"id":"duplicate_submit","name":"重复提交","appliesTo":"interface","enabled":true},
          {"id":"business_exception","name":"业务规则异常","appliesTo":"all","enabled":true}
        ]
        """;
}

record SaveCaseTemplateRequest(
    int schemaVersion,
    JsonNode interfaceFields,
    JsonNode caseTypes
) {
}

record CaseTemplateResponse(
    String kind,
    int schemaVersion,
    JsonNode interfaceFields,
    JsonNode caseTypes,
    String updatedAt
) {
}
