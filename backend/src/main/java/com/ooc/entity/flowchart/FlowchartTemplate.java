package com.ooc.entity.flowchart;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程图模板实体 - 可复用的流程图定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "flowchart_templates")
@CompoundIndex(name = "template_version_unique", def = "{'templateId': 1, 'version': 1}", unique = true)
public class FlowchartTemplate {

    @JsonProperty("id")
    @Id
    private String id;

    @JsonProperty("templateId")
    @Indexed
    private String templateId;      // 业务ID: "daily-report", "data-sync"

    @JsonProperty("name")
    private String name;            // 显示名称: "日报生成流程"

    @JsonProperty("description")
    private String description;     // 描述

    @JsonProperty("category")
    @Indexed
    private String category;        // 分类: "automation", "report", "notification"

    @JsonProperty("icon")
    private String icon;            // 图标 emoji 或 URL

    // 版本控制
    @JsonProperty("version")
    @Builder.Default
    private Integer version = 1;        // 版本号

    @JsonProperty("parentVersionId")
    private String parentVersionId; // 父版本ID（用于版本追溯）

    @JsonProperty("isLatest")
    @org.springframework.data.mongodb.core.mapping.Field("isLatest")
    @Builder.Default
    private boolean isLatest = true;    // 是否最新版本

    // 流程图定义
    @JsonProperty("definition")
    private FlowchartDefinition definition;

    // 变量定义 (模板参数)
    @JsonProperty("variables")
    @Builder.Default
    private List<VariableDef> variables = new ArrayList<>();

    // 权限
    @JsonProperty("createdBy")
    private String createdBy;

    @JsonProperty("updatedBy")
    private String updatedBy;

    @JsonProperty("allowedRoomIds")
    @Builder.Default
    private List<String> allowedRoomIds = new ArrayList<>();  // 允许使用的房间（空=全局）

    @JsonProperty("isPublic")
    @Builder.Default
    private boolean isPublic = true;    // 是否公开模板

    // 元数据
    @JsonProperty("createdAt")
    @CreatedDate
    private Instant createdAt;

    @JsonProperty("updatedAt")
    @LastModifiedDate
    private Instant updatedAt;

    /**
     * 流程图定义结构
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlowchartDefinition {
        @JsonProperty("nodes")
        @Builder.Default
        private List<Node> nodes = new ArrayList<>();

        @JsonProperty("edges")
        @Builder.Default
        private List<Edge> edges = new ArrayList<>();

        @JsonProperty("viewport")
        private Viewport viewport;
    }

    /**
     * 节点定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Node {
        @JsonProperty("id")
        private String id;

        @JsonProperty("type")
        private String type;        // 节点类型: "start", "llm", "condition", "api", "code", "wait", "end"

        @JsonProperty("position")
        private Position position;

        @JsonProperty("data")
        private NodeData data;

        @JsonProperty("style")
        private Map<String, Object> style;

        @JsonProperty("sourcePosition")
        private String sourcePosition;

        @JsonProperty("targetPosition")
        private String targetPosition;
    }

    /**
     * 节点数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeData {
        // 基础字段
        @JsonProperty("label")
        private String label;

        @JsonProperty("description")
        private String description;

        // LLM 节点
        @JsonProperty("systemPrompt")
        private String systemPrompt;

        @JsonProperty("userPrompt")
        private String userPrompt;

        @JsonProperty("model")
        private String model;       // "openclaw", "kimi", "claude"

        @JsonProperty("temperature")
        private Double temperature;

        @JsonProperty("maxTokens")
        private Integer maxTokens;

        // Condition 节点
        @JsonProperty("conditionExpr")
        private String conditionExpr;

        @JsonProperty("trueTarget")
        private String trueTarget;

        @JsonProperty("falseTarget")
        private String falseTarget;

        // Condition 节点 - 多分支模式
        @JsonProperty("conditionMode")
        private String conditionMode;  // "boolean" 或 "switch" 或 "range"

        @JsonProperty("switchVar")
        private String switchVar;  // 判断变量

        @JsonProperty("branches")
        private List<Map<String, Object>> branches;  // 分支列表

        // Condition 节点 - 范围模式
        @JsonProperty("rangeVar")
        private String rangeVar;  // 范围判断变量

        @JsonProperty("rangeBranches")
        private List<RangeBranch> rangeBranches;  // 范围分支列表

        @JsonProperty("rangeDefaultBranch")
        private String rangeDefaultBranch;  // 默认分支标签（没有匹配时）

        // API 节点
        @JsonProperty("httpMethod")
        private String httpMethod;

        @JsonProperty("url")
        private String url;

        @JsonProperty("headers")
        @Builder.Default
        private Map<String, String> headers = new HashMap<>();

        @JsonProperty("bodyTemplate")
        private String bodyTemplate;

        // Code 节点
        @JsonProperty("code")
        private String code;

        @JsonProperty("language")
        private String language;    // "groovy" | "javascript"

        // CompletionCheck 节点
        @JsonProperty("checkVar")
        private String checkVar;    // 要检查的变量名

        @JsonProperty("checkPrompt")
        private String checkPrompt; // 自定义检查提示词（可选）

        // Wait 节点
        @JsonProperty("waitSeconds")
        private Integer waitSeconds;

        // Variable 节点
        @JsonProperty("varName")
        private String varName;

        @JsonProperty("varValue")
        private String varValue;

        // 通用配置
        @JsonProperty("stopOnError")
        @Builder.Default
        private boolean stopOnError = true;

        @JsonProperty("outputVar")
        private String outputVar;

        @JsonProperty("onError")
        @Builder.Default
        private String onError = "stop";  // "stop", "continue", "retry"

        @JsonProperty("retryCount")
        @Builder.Default
        private Integer retryCount = 0;
    }

    /**
     * 连接线定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edge {
        @JsonProperty("id")
        private String id;

        @JsonProperty("source")
        private String source;      // 源节点ID

        @JsonProperty("target")
        private String target;      // 目标节点ID

        @JsonProperty("sourceHandle")
        private String sourceHandle; // 源端口 (用于条件分支)

        @JsonProperty("targetHandle")
        private String targetHandle; // 目标端口

        @JsonProperty("label")
        private String label;       // 连线标签 (如 "True", "False")

        @JsonProperty("animated")
        private Boolean animated;

        @JsonProperty("style")
        private Map<String, Object> style;
    }

    /**
     * 视口状态
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Viewport {
        @JsonProperty("x")
        private Double x;

        @JsonProperty("y")
        private Double y;

        @JsonProperty("zoom")
        private Double zoom;
    }

    /**
     * 坐标位置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Position {
        @JsonProperty("x")
        private Double x;

        @JsonProperty("y")
        private Double y;
    }

    /**
     * 变量定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariableDef {
        @JsonProperty("name")
        private String name;

        @JsonProperty("type")
        private String type;        // "string" | "number" | "boolean" | "object" | "array"

        @JsonProperty("description")
        private String description;

        @JsonProperty("defaultValue")
        private String defaultValue;

        @JsonProperty("required")
        @Builder.Default
        private boolean required = false;

        @JsonProperty("validationRegex")
        private String validationRegex;

        @JsonProperty("options")
        @Builder.Default
        private List<String> options = new ArrayList<>();
    }

    /**
     * 范围分支定义 - 用于条件节点的范围判断模式
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RangeBranch {
        @JsonProperty("label")
        private String label;       // 分支标签，用于显示

        @JsonProperty("min")
        private Double min;         // 最小值（包含）

        @JsonProperty("max")
        private Double max;         // 最大值（包含）

        @JsonProperty("minInclusive")
        @Builder.Default
        private boolean minInclusive = true;  // 是否包含最小值

        @JsonProperty("maxInclusive")
        @Builder.Default
        private boolean maxInclusive = true;  // 是否包含最大值

        @JsonProperty("handleId")
        private String handleId;    // 出边句柄ID，如 "range_0", "range_1"
    }
}
