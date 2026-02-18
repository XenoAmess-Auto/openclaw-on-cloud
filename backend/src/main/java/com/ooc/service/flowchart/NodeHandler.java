package com.ooc.service.flowchart;

import com.ooc.entity.flowchart.FlowchartTemplate;

/**
 * 节点处理器接口
 * 所有节点类型都需要实现此接口
 */
public interface NodeHandler {

    /**
     * 获取节点类型
     */
    String getNodeType();

    /**
     * 执行节点
     *
     * @param nodeData 节点数据配置
     * @param ctx 执行上下文
     * @return 执行结果
     */
    NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx);

    /**
     * 验证节点配置是否有效
     *
     * @param nodeData 节点数据
     * @return 验证结果
     */
    default ValidationResult validate(FlowchartTemplate.NodeData nodeData) {
        return ValidationResult.valid();
    }

    /**
     * 获取节点描述（用于UI显示）
     */
    default String getDescription() {
        return getNodeType() + " node";
    }

    /**
     * 验证结果
     */
    class ValidationResult {
        private final boolean valid;
        private final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }
    }
}
