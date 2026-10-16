package com.haitlify.aitest.platform.ai.skill;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class AiBuiltInSkills {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AiBuiltInSkills() {
    }

    static List<AiSkillDefinition> all() {
        return List.of(requirementTestPointAnalysis(), formalWebCaseGeneration());
    }

    static AiSkillDefinition requirementTestPointAnalysis() {
        return new AiSkillDefinition(
            "requirement-test-point-analysis",
            "需求测试点分析",
            "根据需求文档、规则提取结果和模板上下文生成待评审测试点",
            json("""
                ["requirements","analysis","builtin"]
                """),
            json("""
                ["interface","web_automation","app_automation","ai_test","functional","performance"]
                """),
            json("""
                ["text","markdown","yaml","json","word","pdf","excel","image"]
                """),
            """
                你是需求测试点分析 Agent。
                请基于输入的需求证据、规则提取结果、模板字段和目标用例类型，输出结构化测试点。
                每条测试点必须带来源证据；没有明确证据的推断项必须标记为待确认。
                需求测试点只描述要验证的行为和验收结果，不得把自然语言步骤伪装成 CSS、XPath 或 role locator。
                Web 测试点应明确页面或功能、前置条件、可观察结果，并为后续正式自动化用例生成保留来源追溯。
                """,
            json("""
                {
                  "type": "object",
                  "required": ["draftTestPoints"],
                  "properties": {
                    "draftTestPoints": {
                      "type": "array"
                    },
                    "unclearQuestions": {
                      "type": "array"
                    },
                    "coverageSummary": {
                      "type": "object"
                    }
                  }
                }
                """),
            json("""
                ["extract-text-structure","extract-requirement-keywords","extract-field-rules","extract-state-rules","check-coverage-gaps"]
                """),
            json("""
                [
                  {
                    "input": "项目: Haitlify\\n测试点: 用户登录成功",
                    "output": {
                      "draftTestPoints": [
                        {
                          "title": "用户登录成功",
                          "module": "登录",
                          "reviewStatus": "ai_generated"
                        }
                      ]
                    }
                  }
                ]
                """),
            true,
            true,
            1
        );
    }

    static AiSkillDefinition formalWebCaseGeneration() {
        return new AiSkillDefinition(
            "formal-web-case-generation",
            "正式 Web 自动化用例生成",
            "将已确认需求测试点生成符合 Web runner 契约的完整可执行用例",
            json("""
                ["web","automation","generation","builtin"]
                """),
            json("""
                ["web_automation"]
                """),
            json("""
                ["text","markdown","yaml","json","word","pdf","excel","image","figma"]
                """),
            """
                你是正式 Web 自动化用例生成 Agent。
                只根据已确认或已修改的需求测试点生成完整用例，并保留 sourcePointId、sourceFile 和 sourceText。
                每条用例必须包含 pageName、startUrl、browser、viewport、precondition、steps、assertions 和 expectedResult。
                steps 每行只能使用 runner 支持的动作 DSL；禁止输出 manual，禁止输出编号自然语言，禁止把句子当作 locator。
                最终用例的 locator 必须来自浏览器采集证据；没有 browserLocatorEvidence 时不得猜测 CSS、XPath、role、text 或 testId locator。
                click/input/select/hover/wait/assert 动作必须包含浏览器已验证的合法目标。
                每条 Web 用例至少包含一个 assertVisible、assertHidden、assertText 或 assertUrl 断言动作。
                """,
            json("""
                {
                  "type": "object",
                  "required": ["cases"],
                  "properties": {
                    "cases": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": [
                          "sourcePointId", "title", "module", "caseKind", "pageName", "startUrl",
                          "browser", "viewport", "precondition", "steps", "assertions", "expectedResult",
                          "sourceFile", "sourceText"
                        ]
                      }
                    }
                  }
                }
                """),
            json("""
                []
                """),
            json("""
                [
                  {
                    "input": {
                      "title": "登录后进入用户管理",
                      "sourcePointId": "point-login"
                    },
                    "output": {
                      "sourcePointId": "point-login",
                      "title": "登录后进入用户管理",
                      "module": "用户管理",
                      "caseKind": "web_automation",
                      "pageName": "用户管理",
                      "startUrl": "/user-center/users",
                      "browser": "chromium",
                      "viewport": "1440x900",
                      "precondition": "已通过环境登录流程建立登录态",
                      "steps": "goto /user-center/users\\nwaitForVisible role=heading[name=用户管理]\\nassertVisible role=heading[name=用户管理]",
                      "assertions": "assertVisible role=heading[name=用户管理]",
                      "expectedResult": "用户管理标题可见",
                      "sourceFile": "requirements.md",
                      "sourceText": "登录用户可进入用户管理"
                    }
                  }
                ]
                """),
            true,
            true,
            1
        );
    }

    private static JsonNode json(String value) {
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid built-in AI skill JSON", exception);
        }
    }
}
