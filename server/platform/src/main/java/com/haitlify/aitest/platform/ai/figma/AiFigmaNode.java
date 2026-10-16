package com.haitlify.aitest.platform.ai.figma;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("ai_figma_nodes")
class AiFigmaNode {

    @Id
    @Column("id")
    private Long id;
    @Column("figma_node_id")
    private String figmaNodeId;
    @Column("figma_file_pk")
    private Long figmaFilePk;
    @Column("node_id")
    private String nodeId;
    @Column("node_type")
    private String nodeType;
    @Column("node_name")
    private String nodeName;
    @Column("parent_node_id")
    private String parentNodeId;
    @Column("component_key")
    private String componentKey;
    @Column("component_name")
    private String componentName;
    @Column("variant_properties_json")
    private String variantPropertiesJson;
    @Column("style_json")
    private String styleJson;
    @Column("token_json")
    private String tokenJson;
    @Column("layout_mode")
    private String layoutMode;
    @Column("constraints_json")
    private String constraintsJson;
    @Column("absolute_bounds_json")
    private String absoluteBoundsJson;
    @Column("prototype_link_json")
    private String prototypeLinkJson;
    @Column("source_snapshot_hash")
    private String sourceSnapshotHash;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    AiFigmaNode() {
    }

    AiFigmaNode(
        Long id,
        String figmaNodeId,
        Long figmaFilePk,
        String nodeId,
        String nodeType,
        String nodeName,
        String parentNodeId,
        String componentKey,
        String componentName,
        String variantPropertiesJson,
        String styleJson,
        String tokenJson,
        String layoutMode,
        String constraintsJson,
        String absoluteBoundsJson,
        String prototypeLinkJson,
        String sourceSnapshotHash,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.figmaNodeId = figmaNodeId;
        this.figmaFilePk = figmaFilePk;
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.nodeName = nodeName;
        this.parentNodeId = parentNodeId;
        this.componentKey = componentKey;
        this.componentName = componentName;
        this.variantPropertiesJson = variantPropertiesJson;
        this.styleJson = styleJson;
        this.tokenJson = tokenJson;
        this.layoutMode = layoutMode;
        this.constraintsJson = constraintsJson;
        this.absoluteBoundsJson = absoluteBoundsJson;
        this.prototypeLinkJson = prototypeLinkJson;
        this.sourceSnapshotHash = sourceSnapshotHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    Long getId() {
        return id;
    }

    String getFigmaNodeId() {
        return figmaNodeId;
    }

    Long getFigmaFilePk() {
        return figmaFilePk;
    }

    String getNodeId() {
        return nodeId;
    }

    String getNodeType() {
        return nodeType;
    }

    String getNodeName() {
        return nodeName;
    }

    String getParentNodeId() {
        return parentNodeId;
    }

    String getComponentKey() {
        return componentKey;
    }

    String getComponentName() {
        return componentName;
    }

    String getVariantPropertiesJson() {
        return variantPropertiesJson;
    }

    String getStyleJson() {
        return styleJson;
    }

    String getTokenJson() {
        return tokenJson;
    }

    String getLayoutMode() {
        return layoutMode;
    }

    String getConstraintsJson() {
        return constraintsJson;
    }

    String getAbsoluteBoundsJson() {
        return absoluteBoundsJson;
    }

    String getPrototypeLinkJson() {
        return prototypeLinkJson;
    }

    String getSourceSnapshotHash() {
        return sourceSnapshotHash;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
