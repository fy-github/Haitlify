package com.haitlify.aitest.platform.ai.figma;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiFigmaStylePrototypeExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiFigmaStyleExtractor styleExtractor;
    private AiFigmaPrototypeExtractor prototypeExtractor;

    @BeforeEach
    void setUp() {
        styleExtractor = new AiFigmaStyleExtractor(objectMapper);
        prototypeExtractor = new AiFigmaPrototypeExtractor(objectMapper);
    }

    @Test
    void extractsColorTypographyRadiusAndShadowTokens() throws Exception {
        JsonNode root = objectMapper.readTree("""
            {
              "id": "1:1",
              "name": "Login Frame",
              "type": "FRAME",
              "layoutMode": "VERTICAL",
              "itemSpacing": 16,
              "cornerRadius": 8,
              "opacity": 0.9,
              "fills": [
                {"type": "SOLID", "color": {"r": 0.2, "g": 0.4, "b": 1}, "opacity": 1}
              ],
              "effects": [
                {"type": "DROP_SHADOW", "radius": 12, "offset": {"x": 0, "y": 4}, "color": {"r": 0, "g": 0, "b": 0, "a": 0.18}}
              ],
              "children": [
                {
                  "id": "1:2",
                  "name": "Title",
                  "type": "TEXT",
                  "style": {
                    "fontFamily": "Inter",
                    "fontWeight": 600,
                    "fontSize": 24,
                    "lineHeightPx": 32,
                    "letterSpacing": 0
                  },
                  "fills": [
                    {"type": "SOLID", "color": {"r": 0.05, "g": 0.07, "b": 0.1}}
                  ]
                }
              ]
            }
            """);

        var tokens = styleExtractor.extractTokens(root);

        assertThat(tokens).anySatisfy(token -> {
            assertThat(token.tokenCategory()).isEqualTo("color");
            assertThat(token.tokenName()).contains("Login Frame", "fill", "#3366FF");
        });
        assertThat(tokens).anySatisfy(token -> {
            assertThat(token.tokenCategory()).isEqualTo("typography");
            assertThat(token.tokenName()).contains("Title", "Inter", "24");
            assertThat(token.tokenJson().path("fontWeight").asInt()).isEqualTo(600);
        });
        assertThat(tokens).anySatisfy(token -> {
            assertThat(token.tokenCategory()).isEqualTo("radius");
            assertThat(token.tokenName()).contains("Login Frame", "8");
        });
        assertThat(tokens).anySatisfy(token -> {
            assertThat(token.tokenCategory()).isEqualTo("shadow");
            assertThat(token.tokenJson().path("radius").asInt()).isEqualTo(12);
        });
        assertThat(tokens).anySatisfy(token -> {
            assertThat(token.tokenCategory()).isEqualTo("spacing");
            assertThat(token.tokenName()).contains("16");
        });
        assertThat(tokens).anySatisfy(token -> {
            assertThat(token.tokenCategory()).isEqualTo("opacity");
            assertThat(token.tokenName()).contains("90");
        });
        assertThat(tokens).anySatisfy(token -> assertThat(token.tokenCategory()).isEqualTo("layout"));
    }

    @Test
    void extractsPrototypeClickLink() throws Exception {
        JsonNode root = objectMapper.readTree("""
            {
              "id": "1:3",
              "name": "Primary Button",
              "type": "INSTANCE",
              "interactions": [
                {
                  "trigger": {"type": "ON_CLICK"},
                  "actions": [
                    {
                      "type": "NODE",
                      "destinationId": "2:1",
                      "transition": {"type": "SMART_ANIMATE", "duration": 0.3}
                    }
                  ]
                }
              ]
            }
            """);

        var links = prototypeExtractor.extractLinks(root);

        assertThat(links).hasSize(1);
        AiFigmaPrototypeLink link = links.getFirst();
        assertThat(link.sourceNodeId()).isEqualTo("1:3");
        assertThat(link.targetNodeId()).isEqualTo("2:1");
        assertThat(link.eventType()).isEqualTo("ON_CLICK");
        assertThat(link.actionType()).isEqualTo("NODE");
        assertThat(link.transitionType()).isEqualTo("SMART_ANIMATE");
        assertThat(link.durationMs()).isEqualTo(300);
    }

    @Test
    void extractsOverlayCloseBackAndScrollInteractions() throws Exception {
        JsonNode root = objectMapper.readTree("""
            {
              "id": "3:1",
              "name": "Settings",
              "type": "FRAME",
              "children": [
                {
                  "id": "3:2",
                  "name": "Open Drawer",
                  "type": "INSTANCE",
                  "interactions": [
                    {"trigger": {"type": "ON_CLICK"}, "actions": [{"type": "OVERLAY", "destinationId": "4:1", "transition": {"type": "MOVE_IN", "duration": 0.2}}]},
                    {"trigger": {"type": "ON_CLICK"}, "actions": [{"type": "CLOSE"}]},
                    {"trigger": {"type": "ON_CLICK"}, "actions": [{"type": "BACK"}]},
                    {"trigger": {"type": "ON_DRAG"}, "actions": [{"type": "SCROLL_TO", "destinationId": "3:9"}]}
                  ]
                }
              ]
            }
            """);

        var links = prototypeExtractor.extractLinks(root);

        assertThat(links)
            .extracting(AiFigmaPrototypeLink::actionType)
            .containsExactlyInAnyOrder("OVERLAY", "CLOSE", "BACK", "SCROLL_TO");
        assertThat(links).anySatisfy(link -> {
            assertThat(link.targetNodeId()).isEqualTo("4:1");
            assertThat(link.transitionType()).isEqualTo("MOVE_IN");
            assertThat(link.durationMs()).isEqualTo(200);
        });
        assertThat(links).anySatisfy(link -> {
            assertThat(link.eventType()).isEqualTo("ON_DRAG");
            assertThat(link.targetNodeId()).isEqualTo("3:9");
        });
    }
}
