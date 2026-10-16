package com.haitlify.aitest.platform.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class FigmaDesignClientTest {

    @Test
    void fetchesFigmaFileAndBuildsVisualSummary() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/files/AbCdEf123456/nodes", exchange -> handleFigmaRequest(exchange, calls));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            FigmaDesignClient client = new FigmaDesignClient(
                new FigmaDesignProperties(true, "token-demo", "http://127.0.0.1:" + server.getAddress().getPort(), 3000),
                new ObjectMapper()
            );

            FigmaDesignParseResult result = client.fetchDesign("AbCdEf123456", "12:34");

            assertEquals("desktop", result.visualSummary().viewportHint());
            assertTrue(result.visualSummary().layoutHints().contains("存在 Auto Layout 垂直流布局"));
            assertTrue(result.visualSummary().layoutHints().contains("主轴间距 24px"));
            assertTrue(result.visualSummary().layoutHints().contains("横向居中对齐"));
            assertTrue(result.visualSummary().layoutHints().contains("内边距 上24 / 右32 / 下24 / 左32"));
            assertTrue(result.visualSummary().layoutHints().contains("左右约束跟随容器变化"));
            assertTrue(result.visualSummary().styleHints().contains("主按钮填充色 #3366FF"));
            assertTrue(result.visualSummary().styleHints().contains("圆角 12px"));
            assertTrue(result.visualSummary().styleHints().contains("标题字重 700，字号 32px"));
            assertTrue(result.visualSummary().styleHints().contains("标题行高 40px，字距 0.5px"));
            assertTrue(result.visualSummary().styleHints().contains("标题字体 Inter"));
            assertTrue(result.visualSummary().styleHints().contains("标题文本色 #1A1A1A"));
            assertTrue(result.visualSummary().styleHints().contains("标题对齐 CENTER"));
            assertTrue(result.visualSummary().styleHints().contains("标题大小写 UPPER"));
            assertTrue(result.visualSummary().styleHints().contains("标题删除线 STRIKETHROUGH"));
            assertTrue(result.visualSummary().styleHints().contains("段后间距 12px"));
            assertTrue(result.visualSummary().styleHints().contains("富文本片段 CTA 字重 800，字号 18px，颜色 #FFFFFF"));
            assertTrue(result.visualSummary().styleHints().contains("富文本片段 Secondary 字重 500，字号 14px，颜色 #666666"));
            assertTrue(result.visualSummary().styleHints().contains("描边色 #D9D9D9"));
            assertTrue(result.visualSummary().styleHints().contains("投影 y=12 blur=24 rgba(0,0,0,0.18)"));
            assertTrue(result.visualSummary().styleHints().contains("不透明度 92%"));
            assertTrue(result.visualSummary().styleHints().contains("混合模式 PASS_THROUGH"));
            assertTrue(result.visualSummary().componentHints().contains("Button / Primary"));
            assertTrue(result.visualSummary().componentHints().contains("Input / Default"));
            assertTrue(result.visualSummary().componentHints().contains("State=Default"));
            assertTrue(result.visualSummary().componentHints().contains("Size=Large"));
            assertTrue(result.visualSummary().componentHints().contains("Page / Login > Form / Login > Button / Primary"));
            assertTrue(result.visualSummary().componentHints().contains("Page / Login > Form / Login > Input / Default"));
            assertTrue(result.visualSummary().componentHints().contains("Button / Primary --ON_CLICK--> Success / Home [SMART_ANIMATE 300ms]"));
            assertTrue(result.visualSummary().componentHints().contains("Card / Tips --ON_HOVER--> Tooltip / Help [MOVE_IN 200ms]"));
            assertTrue(result.visualSummary().componentHints().contains("Back / Link --ON_CLICK--> BACK"));
            assertTrue(result.visualSummary().componentHints().contains("Jump / FAQ --ON_CLICK--> Section / FAQ [SCROLL_TO 0ms]"));
            assertTrue(result.visualSummary().componentHints().contains("Card / Promo --ON_DRAG--> Drawer / Detail [MOVE_IN 250ms]"));
            assertTrue(result.visualSummary().componentHints().contains("Toast / Saved --AFTER_TIMEOUT--> null [DISSOLVE 500ms]"));
            assertTrue(result.visualSummary().componentHints().contains("Button / Close --ON_CLICK--> null [CLOSE_OVERLAY 0ms]"));
            assertTrue(result.visualSummary().layoutHints().contains("页面流: Button / Primary -> Success / Home -> Back / Link -> BACK"));
            assertTrue(result.suggestedRequirementPoints().contains("组件默认态、悬浮态、禁用态与 Figma 设计变体一致"));
            assertTrue(result.suggestedRequirementPoints().contains("主流程按钮、输入框与标题层级符合 Figma 设计结构"));
            assertTrue(result.suggestedRequirementPoints().contains("标题排版、字重、字号与 Figma 设计稿一致"));
            assertTrue(result.suggestedRequirementPoints().contains("标题对齐、大小写、装饰线与段落间距符合设计稿"));
            assertTrue(result.suggestedRequirementPoints().contains("富文本不同片段的字号、字重、颜色与设计稿一致"));
            assertTrue(result.suggestedRequirementPoints().contains("卡片描边、阴影、透明度与混合模式符合设计稿"));
            assertTrue(result.suggestedRequirementPoints().contains("组件 Size / State 等变体组合与设计稿定义一致"));
            assertTrue(result.suggestedRequirementPoints().contains("表单区、输入框、主按钮等关键组件层级与设计稿一致"));
            assertTrue(result.suggestedRequirementPoints().contains("点击主按钮后以 SMART_ANIMATE 300ms 跳转到 Success / Home"));
            assertTrue(result.suggestedRequirementPoints().contains("悬浮提示层、返回、锚点滚动等原型交互符合设计稿"));
            assertTrue(result.suggestedRequirementPoints().contains("拖拽、延时消失、关闭浮层等复杂原型交互符合设计稿"));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsWhenFigmaApiIsDisabled() {
        FigmaDesignClient client = new FigmaDesignClient(
            new FigmaDesignProperties(false, "", "https://api.figma.com", 3000),
            new ObjectMapper()
        );

        assertThrows(FigmaDesignUnavailableException.class, () -> client.fetchDesign("AbCdEf123456", "12:34"));
    }

    private void handleFigmaRequest(HttpExchange exchange, AtomicInteger calls) throws IOException {
        calls.incrementAndGet();
        assertEquals("token-demo", exchange.getRequestHeaders().getFirst("X-Figma-Token"));
        byte[] payload = """
            {
              "name": "Login Page",
              "nodes": {
                "12:34": {
                  "document": {
                    "id": "12:34",
                    "name": "Login Frame",
                    "type": "FRAME",
                    "absoluteBoundingBox": {
                      "width": 1440,
                      "height": 900
                    },
                    "layoutMode": "VERTICAL",
                    "primaryAxisAlignItems": "CENTER",
                    "counterAxisAlignItems": "CENTER",
                    "itemSpacing": 24,
                    "paddingTop": 24,
                    "paddingRight": 32,
                    "paddingBottom": 24,
                    "paddingLeft": 32,
                    "cornerRadius": 12,
                    "opacity": 0.92,
                    "blendMode": "PASS_THROUGH",
                    "fills": [
                      {
                        "type": "SOLID",
                        "color": {
                          "r": 0.2,
                          "g": 0.4,
                          "b": 1.0
                        }
                      }
                    ],
                    "strokes": [
                      {
                        "type": "SOLID",
                        "color": {
                          "r": 0.85,
                          "g": 0.85,
                          "b": 0.85
                        }
                      }
                    ],
                    "effects": [
                      {
                        "type": "DROP_SHADOW",
                        "offset": {
                          "x": 0,
                          "y": 12
                        },
                        "radius": 24,
                        "color": {
                          "r": 0,
                          "g": 0,
                          "b": 0,
                          "a": 0.18
                        }
                      }
                    ],
                    "children": [
                      {
                        "id": "100:1",
                        "name": "Title",
                        "type": "TEXT",
                        "style": {
                          "fontSize": 32,
                          "fontWeight": 700,
                          "lineHeightPx": 40,
                          "letterSpacing": 0.5,
                          "fontFamily": "Inter",
                          "textAlignHorizontal": "CENTER",
                          "textCase": "UPPER",
                          "textDecoration": "STRIKETHROUGH",
                          "paragraphSpacing": 12
                        },
                        "fills": [
                          {
                            "type": "SOLID",
                            "color": {
                              "r": 0.10196,
                              "g": 0.10196,
                              "b": 0.10196
                            }
                          }
                        ],
                        "constraints": {
                          "horizontal": "LEFT_RIGHT",
                          "vertical": "TOP"
                        }
                      },
                      {
                        "id": "100:30",
                        "name": "Page / Login",
                        "type": "FRAME",
                        "children": [
                          {
                            "id": "100:31",
                            "name": "Form / Login",
                            "type": "FRAME",
                            "children": [
                              {
                                "id": "100:15",
                                "name": "Input / Default",
                                "type": "INSTANCE"
                              },
                              {
                                "id": "100:20",
                                "name": "Rich Text",
                                "type": "TEXT",
                                "characters": "CTA Secondary",
                                "styleOverrideTable": {
                                  "1": {
                                    "fontSize": 18,
                                    "fontWeight": 800,
                                    "fontFamily": "Inter",
                                    "fills": [
                                      {
                                        "type": "SOLID",
                                        "color": {
                                          "r": 1,
                                          "g": 1,
                                          "b": 1
                                        }
                                      }
                                    ]
                                  },
                                  "2": {
                                    "fontSize": 14,
                                    "fontWeight": 500,
                                    "fontFamily": "Inter",
                                    "fills": [
                                      {
                                        "type": "SOLID",
                                        "color": {
                                          "r": 0.4,
                                          "g": 0.4,
                                          "b": 0.4
                                        }
                                      }
                                    ]
                                  }
                                },
                                "characterStyleOverrides": [1,1,1,0,2,2,2,2,2,2,2,2,2]
                              },
                              {
                                "id": "100:2",
                                "name": "Button / Primary",
                                "type": "INSTANCE",
                                "transitionNodeID": "200:1",
                                "transitionDuration": 300,
                                "transitionEasing": "EASE_OUT",
                                "interactions": [
                                  {
                                    "trigger": {
                                      "type": "ON_CLICK"
                                    },
                                    "actions": [
                                      {
                                        "type": "NODE",
                                        "destinationId": "200:1",
                                        "navigation": "NAVIGATE",
                                        "transition": {
                                          "type": "SMART_ANIMATE",
                                          "duration": 0.3
                                        }
                                      }
                                    ]
                                  }
                                ],
                                "componentProperties": {
                                  "State": {
                                    "type": "VARIANT",
                                    "value": "Default"
                                  },
                                  "Size": {
                                    "type": "VARIANT",
                                    "value": "Large"
                                  }
                                }
                              },
                              {
                                "id": "100:40",
                                "name": "Card / Tips",
                                "type": "INSTANCE",
                                "interactions": [
                                  {
                                    "trigger": {
                                      "type": "ON_HOVER"
                                    },
                                    "actions": [
                                      {
                                        "type": "NODE",
                                        "destinationId": "300:1",
                                        "navigation": "OVERLAY",
                                        "transition": {
                                          "type": "MOVE_IN",
                                          "duration": 0.2
                                        }
                                      }
                                    ]
                                  }
                                ]
                              },
                              {
                                "id": "100:41",
                                "name": "Back / Link",
                                "type": "INSTANCE",
                                "interactions": [
                                  {
                                    "trigger": {
                                      "type": "ON_CLICK"
                                    },
                                    "actions": [
                                      {
                                        "type": "BACK"
                                      }
                                    ]
                                  }
                                ]
                              },
                              {
                                "id": "100:42",
                                "name": "Jump / FAQ",
                                "type": "INSTANCE",
                                "interactions": [
                                  {
                                    "trigger": {
                                      "type": "ON_CLICK"
                                    },
                                    "actions": [
                                      {
                                        "type": "NODE",
                                        "destinationId": "400:1",
                                        "navigation": "SCROLL_TO"
                                      }
                                    ]
                                  }
                                ]
                              },
                              {
                                "id": "100:43",
                                "name": "Card / Promo",
                                "type": "INSTANCE",
                                "interactions": [
                                  {
                                    "trigger": {
                                      "type": "ON_DRAG"
                                    },
                                    "actions": [
                                      {
                                        "type": "NODE",
                                        "destinationId": "500:1",
                                        "navigation": "OVERLAY",
                                        "transition": {
                                          "type": "MOVE_IN",
                                          "duration": 0.25
                                        }
                                      }
                                    ]
                                  }
                                ]
                              },
                              {
                                "id": "100:44",
                                "name": "Toast / Saved",
                                "type": "INSTANCE",
                                "interactions": [
                                  {
                                    "trigger": {
                                      "type": "AFTER_TIMEOUT"
                                    },
                                    "actions": [
                                      {
                                        "type": "NODE",
                                        "navigation": "NAVIGATE",
                                        "transition": {
                                          "type": "DISSOLVE",
                                          "duration": 0.5
                                        }
                                      }
                                    ]
                                  }
                                ]
                              },
                              {
                                "id": "100:45",
                                "name": "Button / Close",
                                "type": "INSTANCE",
                                "interactions": [
                                  {
                                    "trigger": {
                                      "type": "ON_CLICK"
                                    },
                                    "actions": [
                                      {
                                        "type": "CLOSE_OVERLAY"
                                      }
                                    ]
                                  }
                                ]
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  },
                    "components": {
                      "200:1": {
                        "id": "200:1",
                        "name": "Success / Home"
                    },
                    "300:1": {
                      "id": "300:1",
                      "name": "Tooltip / Help"
                    },
                      "400:1": {
                        "id": "400:1",
                        "name": "Section / FAQ"
                      },
                      "500:1": {
                        "id": "500:1",
                        "name": "Drawer / Detail"
                      }
                    }
                  }
              }
            }
            """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
