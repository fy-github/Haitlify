package com.haitlify.aitest.platform.ai.runtime.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiOutputContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiSchemaCompiler compiler = new AiSchemaCompiler(objectMapper);

    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }

    // ---------------------------------------------------------------- 校验引擎：关键字全集

    @Test
    void enumViolationReportsJsonPointerPath() {
        JsonNode schema = json("""
            {"type":"object","required":["kind"],"properties":{"kind":{"type":"string","enum":["web","app"]}}}
            """);
        assertThat(AiSchemaValidationEngine.validate(json("{\"kind\":\"web\"}"), schema)).isEmpty();
        assertThat(AiSchemaValidationEngine.validate(json("{\"kind\":\"desktop\"}"), schema))
            .containsExactly("/kind: value \"desktop\" is not in enum [\"web\",\"app\"]");
    }

    @Test
    void missingRequiredAndAdditionalPropertiesUsePointerPaths() {
        JsonNode schema = json("""
            {"type":"object","required":["title"],"additionalProperties":false,
             "properties":{"title":{"type":"string"}}}
            """);
        assertThat(AiSchemaValidationEngine.validate(json("{}"), schema))
            .containsExactly("/title: missing required field");
        assertThat(AiSchemaValidationEngine.validate(json("{\"title\":\"t\",\"extra\":1}"), schema))
            .containsExactly("/extra: additional properties are not allowed");
    }

    @Test
    void boundsKeywordsAreEnforced() {
        JsonNode schema = json("""
            {"type":"object","properties":{
              "score":{"type":"number","minimum":0,"maximum":100},
              "name":{"type":"string","minLength":2,"maxLength":4},
              "points":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string"}}}}
            """);
        assertThat(AiSchemaValidationEngine.validate(json("{\"score\":70,\"name\":\"ab\",\"points\":[\"x\"]}"), schema)).isEmpty();
        assertThat(AiSchemaValidationEngine.validate(json("{\"score\":101}"), schema))
            .containsExactly("/score: value 101.0 is above maximum 100.0");
        assertThat(AiSchemaValidationEngine.validate(json("{\"name\":\"a\"}"), schema))
            .containsExactly("/name: string length 1 is below minLength 2");
        assertThat(AiSchemaValidationEngine.validate(json("{\"points\":[\"x\",\"y\",\"z\"]}"), schema))
            .containsExactly("/points: array size 3 exceeds maxItems 2");
    }

    @Test
    void itemsRecursionAndArrayIndexPointer() {
        JsonNode schema = json("""
            {"type":"object","properties":{"cases":{"type":"array","items":{
              "type":"object","required":["title"],"properties":{"title":{"type":"string"}}}}}}
            """);
        assertThat(AiSchemaValidationEngine.validate(json("{\"cases\":[{\"title\":\"a\"},{}]}"), schema))
            .containsExactly("/cases/1/title: missing required field");
    }

    @Test
    void typeMismatchReportsPointerAndActualType() {
        JsonNode schema = json("{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}}}");
        assertThat(AiSchemaValidationEngine.validate(json("{\"count\":\"3\"}"), schema))
            .containsExactly("/count: expected type integer but got string");
    }

    @Test
    void pointerKeysEscapeTildeAndSlash() {
        JsonNode schema = json("""
            {"type":"object","properties":{"a/b":{"type":"string"},"c~d":{"type":"string"}}}
            """);
        assertThat(AiSchemaValidationEngine.validate(json("{\"a/b\":1,\"c~d\":2}"), schema))
            .containsExactly("/a~1b: expected type string but got integer", "/c~0d: expected type string but got integer");
    }

    // ---------------------------------------------------------------- schema 发布自校验

    @Test
    void schemaSelfValidationRejectsMalformedKeywords() {
        assertThat(AiSchemaValidationEngine.validateSchema(json("{\"type\":\"strng\"}")))
            .containsExactly("/type: unknown schema type 'strng'");
        assertThat(AiSchemaValidationEngine.validateSchema(json("{\"type\":\"object\",\"required\":\"title\"}")))
            .containsExactly("/required: must be an array of strings");
        assertThat(AiSchemaValidationEngine.validateSchema(json("{\"type\":\"string\",\"maxLength\":\"4\"}")))
            .containsExactly("/maxLength: must be a number");
        assertThat(AiSchemaValidationEngine.validateSchema(json("""
            {"type":"object","properties":{"n":{"type":"number","minimum":0}},"required":["n"]}
            """))).isEmpty();
    }

    // ---------------------------------------------------------------- 编译器：协议目标与 fail-closed

    @Test
    void promptJsonTargetEmbedsSchemaBytes() {
        JsonNode schema = json("{\"type\":\"object\",\"required\":[\"draftTestPoints\"]}");
        AiSchemaCompiler.CompiledAiOutputContract compiled = compiler.compile(schema, AiSchemaCompiler.Target.PROMPT_JSON);
        assertThat(compiled.schemaJsonBytes()).isEqualTo(schema.toString());
        assertThat(compiled.promptDirective()).contains("draftTestPoints").contains("JSON Schema");
    }

    @Test
    void strictTargetsRejectUnsupportedKeywordsInsteadOfSilentlyDegrading() {
        JsonNode schema = json("""
            {"type":"object","properties":{"title":{"type":"string","pattern":"^a.*$"}}}
            """);
        assertThatThrownBy(() -> compiler.compile(schema, AiSchemaCompiler.Target.CHAT_JSON_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/properties/title/pattern")
            .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> compiler.compile(schema, AiSchemaCompiler.Target.RESPONSES_TEXT_FORMAT))
            .isInstanceOf(IllegalArgumentException.class);
        // JSON_MODE 不下发 schema 字节：同一 schema 可编译，本地校验仍是唯一语义来源。
        assertThat(compiler.compile(schema, AiSchemaCompiler.Target.JSON_MODE).target())
            .isEqualTo(AiSchemaCompiler.Target.JSON_MODE);
    }

    @Test
    void compilerRefusesInvalidSchemas() {
        JsonNode schema = json("{\"type\":\"object\",\"required\":\"title\"}");
        assertThatThrownBy(() -> compiler.compile(schema, AiSchemaCompiler.Target.PROMPT_JSON))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid output schema");
    }

    // ---------------------------------------------------------------- 契约服务：资源加载与版本化

    @Test
    void contractServiceLoadsVersionedSchemasAndFailsClosedOnUnknown() {
        AiOutputContractService service = new AiOutputContractService(objectMapper);
        service.loadAndValidateAll();
        assertThat(service.versionsFor("requirement-test-points")).containsExactly("v1");
        assertThat(service.versionsFor("formal-web-cases")).containsExactly("v1");
        assertThat(service.versionsFor("test-point-review-result")).containsExactly("v1");
        assertThat(service.versionsFor("vision-understanding-result")).containsExactly("v1");
        JsonNode schema = service.schemaFor("requirement-test-points", "v1");
        // 注册的 schema 必须真正可执行：符合契约的输出通过，缺必填字段被拒。
        assertThat(AiSchemaValidationEngine.validate(
            json("{\"draftTestPoints\":[{\"title\":\"t\",\"module\":\"m\",\"sourceId\":\"s\",\"reviewStatus\":\"ai_generated\"}]}"),
            schema)).isEmpty();
        assertThat(AiSchemaValidationEngine.validate(json("{\"draftTestPoints\":[]}"), schema))
            .containsExactly("/draftTestPoints: array size 0 is below minItems 1");
        assertThatThrownBy(() -> service.schemaFor("requirement-test-points", "v9"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown output contract");
    }

    // ---------------------------------------------------------------- 业务输出验证器

    private final AiBusinessOutputValidator businessValidator = new AiBusinessOutputValidator();

    @Test
    void businessValidatorRejectsUnknownAndCrossProjectSourceReferences() {
        AiBusinessOutputValidator.Context context = new AiBusinessOutputValidator.Context(Set.of("src-1"), Set.of("point-1"));
        assertThat(businessValidator.validate("requirement-test-points", json("""
            {"draftTestPoints":[{"title":"t","module":"m","sourceId":"src-1","reviewStatus":"ai_generated"}]}
            """), context)).isEmpty();
        assertThat(businessValidator.validate("requirement-test-points", json("""
            {"draftTestPoints":[{"title":"t","module":"m","sourceId":"src-other-project","reviewStatus":"ai_generated"}]}
            """), context))
            .containsExactly("/draftTestPoints/0/sourceId: unknown or cross-project source reference 'src-other-project'");
    }

    @Test
    void businessValidatorRejectsMissingStepsAssertionsAndWrongTemplate() {
        AiBusinessOutputValidator.Context context = new AiBusinessOutputValidator.Context(Set.of(), Set.of("point-1"));
        String validCase = """
            {"cases":[{"sourcePointId":"point-1","title":"t","module":"m","caseKind":"web_automation",
              "pageName":"p","startUrl":"/","browser":"chromium","viewport":"1440x900","precondition":"",
              "steps":"goto /\\nassertVisible role=heading[name=p]","assertions":"assertVisible role=heading[name=p]",
              "expectedResult":"r","sourceFile":"f","sourceText":"s"}]}
            """;
        assertThat(businessValidator.validate("formal-web-cases", json(validCase), context)).isEmpty();
        String wrongTemplate = validCase.replace("\"web_automation\"", "\"manual\"");
        assertThat(businessValidator.validate("formal-web-cases", json(wrongTemplate), context))
            .containsExactly("/cases/0/caseKind: template type must be web_automation");
        String emptyAssertions = validCase.replace("\"assertions\":\"assertVisible role=heading[name=p]\"", "\"assertions\":\"\"");
        assertThat(businessValidator.validate("formal-web-cases", json(emptyAssertions), context))
            .containsExactly("/cases/0/assertions: assertions must not be empty");
        String nonAssertionText = validCase.replace("\"assertions\":\"assertVisible role=heading[name=p]\"", "\"assertions\":\"页面看起来正常\"");
        assertThat(businessValidator.validate("formal-web-cases", json(nonAssertionText), context))
            .containsExactly("/cases/0/assertions: must contain at least one runner assertion action (assertVisible/assertHidden/assertText/assertUrl/...)");
        String unknownPoint = validCase.replace("\"sourcePointId\":\"point-1\"", "\"sourcePointId\":\"point-x\"");
        assertThat(businessValidator.validate("formal-web-cases", json(unknownPoint), context))
            .containsExactly("/cases/0/sourcePointId: unknown or cross-project point reference 'point-x'");
    }

    @Test
    void businessValidatorRequiresRegisteredTaskTypes() {
        assertThat(businessValidator.validate("unregistered-task", json("{}"), AiBusinessOutputValidator.Context.empty()))
            .containsExactly("unknown output contract taskType: unregistered-task");
    }

    // ---------------------------------------------------------------- 有限 fence 处理（T03）

    @Test
    void extractionStripsOnlyWholeFencedBlocks() {
        assertThat(AiStructuredOutputExtraction.extract("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(AiStructuredOutputExtraction.extract("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(AiStructuredOutputExtraction.extract("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(AiStructuredOutputExtraction.extract("```json\n{\"a\":1}")).isEqualTo("```json\n{\"a\":1}");
        assertThat(AiStructuredOutputExtraction.extract("```xml\n<a/>\n```")).isEqualTo("```xml\n<a/>\n```");
        assertThat(AiStructuredOutputExtraction.extract("```json\n{\"a\":\"x\"}\n```\ntrailing"))
            .isEqualTo("```json\n{\"a\":\"x\"}\n```\ntrailing");
    }

    @Test
    void extractionHandlesUnicodeAndEmbeddedBracesWithoutRewriting() {
        String fenced = "```json\n{\"title\":\"中文需求 🎯 {braces} inside\",\"note\":\"a \\\" ``` \\\" literal\"}\n```";
        // 字符串内出现 ```：不是整体单块形态，保持原样，由严格解析决定。
        assertThat(AiStructuredOutputExtraction.extract(fenced)).isEqualTo(fenced);
        String clean = "```json\n{\"title\":\"中文需求 🎯 {braces} inside\"}\n```";
        assertThat(AiStructuredOutputExtraction.extract(clean)).isEqualTo("{\"title\":\"中文需求 🎯 {braces} inside\"}");
        assertThat(AiSchemaValidationEngine.validate(
            json(AiStructuredOutputExtraction.extract(clean)),
            json("{\"type\":\"object\",\"required\":[\"title\"]}"))).isEmpty();
    }

    @Test
    void truncatedJsonIsNeverCompletedOrCoerced() {
        // 截断（fence 未闭合或 JSON 未闭合）不得被补完：提取保持原样，解析必须失败。
        String truncatedFence = "```json\n{\"draftTestPoints\":[{\"title\":\"t\"";
        assertThat(AiStructuredOutputExtraction.extract(truncatedFence)).isEqualTo(truncatedFence);
        assertThatThrownBy(() -> json(AiStructuredOutputExtraction.extract("{\"draftTestPoints\":[{\"title\":")))
            .isInstanceOf(Exception.class);
    }

    // ---------------------------------------------------------------- 复核轮修复（C1/C2/C6/C7）

    @Test
    void registeredRequirementContractRejectsEmptyDraftPoints() throws Exception {
        // C1：worker 修复验收改用本契约——空数组必须被拒（截断被“修复”为空不得成功）。
        String schemaText;
        try (java.io.InputStream in = getClass().getResourceAsStream("/ai/schemas/requirement-test-points/v1.json")) {
            schemaText = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        List<String> errors = AiSchemaValidationEngine.validate(
            json("{\"draftTestPoints\":[]}"), json(schemaText));
        assertThat(errors).anyMatch(error -> error.contains("below minItems 1"));
    }

    @Test
    void additionalPropertiesSubSchemaIsValidatedAndSelfChecked() throws Exception {
        // C6：additionalProperties 为子 schema 时额外属性要按它校验，且其内部坏 type 必须被自校验发现。
        JsonNode schema = json("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":{\"type\":\"string\"}}");
        assertThat(AiSchemaValidationEngine.validate(json("{\"a\":\"ok\"}"), schema)).isEmpty();
        assertThat(AiSchemaValidationEngine.validate(json("{\"a\":1}"), schema))
            .anyMatch(error -> error.contains("/a: expected type string"));
        assertThat(AiSchemaValidationEngine.validateSchema(json("{\"additionalProperties\":{\"type\":\"bogus\"}}")))
            .anyMatch(error -> error.contains("unknown schema type 'bogus'"));
    }

    @Test
    void prefixItemsAndArrayFormTypeAreValidated() throws Exception {
        JsonNode tuple = json("{\"type\":\"object\",\"properties\":{\"pair\":{\"type\":\"array\",\"prefixItems\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}}}");
        assertThat(AiSchemaValidationEngine.validate(json("{\"pair\":[\"x\",2]}"), tuple)).isEmpty();
        assertThat(AiSchemaValidationEngine.validate(json("{\"pair\":[\"x\",\"y\"]}"), tuple))
            .anyMatch(error -> error.contains("/pair/1: expected type integer"));
        JsonNode nullable = json("{\"type\":[\"string\",\"null\"]}");
        assertThat(AiSchemaValidationEngine.validate(json("\"s\""), nullable)).isEmpty();
        assertThat(AiSchemaValidationEngine.validate(json("null"), nullable)).isEmpty();
        assertThat(AiSchemaValidationEngine.validate(json("1"), nullable))
            .anyMatch(error -> error.contains("expected type"));
    }

    @Test
    void numericAndCodePointSemanticsFollowJsonSchema() throws Exception {
        // C7：1.0 是 integer；1.0 与 enum[1] 数值相等；字符串按码点计长。
        assertThat(AiSchemaValidationEngine.validate(json("1.0"), json("{\"type\":\"integer\"}"))).isEmpty();
        assertThat(AiSchemaValidationEngine.validate(json("1.5"), json("{\"type\":\"integer\"}"))).isNotEmpty();
        assertThat(AiSchemaValidationEngine.validate(json("1.0"), json("{\"enum\":[1]}"))).isEmpty();
        assertThat(AiSchemaValidationEngine.validate(json("\"🎯🎯\""), json("{\"type\":\"string\",\"maxLength\":2}"))).isEmpty();
        assertThat(AiSchemaValidationEngine.validateSchema(json("{\"type\":\"string\",\"maxLength\":-1}")))
            .anyMatch(error -> error.contains("non-negative"));
    }

    @Test
    void compilerRecursesIntoEverySubSchemaPosition() throws Exception {
        // C2：嵌套位置的 strict 不支持关键字必须被检出（此前可进入请求载荷）。
        assertThatThrownBy(() -> compiler.compile(
            json("{\"type\":\"object\",\"additionalProperties\":{\"anyOf\":[{\"type\":\"string\"}]}}"),
            AiSchemaCompiler.Target.CHAT_JSON_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/additionalProperties/anyOf");
        assertThatThrownBy(() -> compiler.compile(
            json("{\"type\":\"object\",\"properties\":{\"p\":{\"propertyNames\":{\"pattern\":\"^a\"}}}}"),
            AiSchemaCompiler.Target.CHAT_JSON_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/propertyNames/pattern");
        assertThatThrownBy(() -> compiler.compile(
            json("{\"type\":\"array\",\"prefixItems\":[{\"pattern\":\"^a\"}]}"),
            AiSchemaCompiler.Target.RESPONSES_TEXT_FORMAT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/prefixItems/0/pattern");
        assertThatThrownBy(() -> compiler.compile(
            json("{\"type\":\"object\",\"not\":{\"$ref\":\"#/$defs/x\"}}"),
            AiSchemaCompiler.Target.CHAT_JSON_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/not/$ref");
    }

    @Test
    void tupleFormItemsIsRejectedAtPublishAndFlaggedAtCompile() throws Exception {
        // C-R2-1：2020-12 不承认 items 数组（draft-07 tuple）；发布自校验必须拒绝，
        // 编译器必须标记该形态（含递归其条目），运行期对 ad-hoc 调用仍按 tuple 校验。
        assertThat(AiSchemaValidationEngine.validateSchema(
            json("{\"type\":\"array\",\"items\":[{\"type\":\"string\"}]}")))
            .anyMatch(error -> error.contains("/items: tuple form is not supported"));
        assertThatThrownBy(() -> compiler.compile(
            json("{\"type\":\"array\",\"items\":[{\"pattern\":\"^a\"}]}"),
            AiSchemaCompiler.Target.CHAT_JSON_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/items");
        assertThat(AiSchemaValidationEngine.validate(
            json("[\"ok\",1]"), json("{\"type\":\"array\",\"items\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}")))
            .isEmpty();
        assertThat(AiSchemaValidationEngine.validate(
            json("[1,\"x\"]"), json("{\"type\":\"array\",\"items\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}")))
            .anyMatch(error -> error.contains("/0: expected type string"));
    }
}
