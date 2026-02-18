package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeHandler;
import com.ooc.service.flowchart.NodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 变量操作节点处理器
 * 支持设置、获取、删除变量
 */
@Slf4j
@Component
public class VariableNodeHandler implements NodeHandler {

    @Override
    public String getNodeType() {
        return "variable";
    }

    @Override
    public NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        String varName = nodeData.getVarName();
        String varValue = nodeData.getVarValue();

        if (varName == null || varName.isEmpty()) {
            return NodeResult.failure("变量名不能为空");
        }

        // 渲染值模板
        Object value = renderValue(varValue, ctx);

        // 设置变量
        ctx.setVariable(varName, value);

        log.info("[Flowchart:{}] Variable set: {} = {}",
                ctx.getInstance().getInstanceId(), varName, value);

        return NodeResult.success(value);
    }

    /**
     * 渲染值（支持模板语法）
     */
    private Object renderValue(String valueTemplate, ExecutionContext ctx) {
        if (valueTemplate == null) {
            return null;
        }

        String result = valueTemplate;

        // 替换 {{variable}} 语法
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{\\{(\\w+)\\}\\}");
        java.util.regex.Matcher matcher = pattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object varValue = ctx.getVariable(varName);
            if (varValue != null) {
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf(varValue)));
            } else {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);

        String rendered = sb.toString();

        // 尝试解析为数字
        try {
            if (rendered.contains(".")) {
                return Double.parseDouble(rendered);
            } else {
                return Long.parseLong(rendered);
            }
        } catch (NumberFormatException e) {
            // 不是数字，返回字符串
            return rendered;
        }
    }

    @Override
    public ValidationResult validate(FlowchartTemplate.NodeData nodeData) {
        if (nodeData.getVarName() == null || nodeData.getVarName().isEmpty()) {
            return ValidationResult.invalid("变量名不能为空");
        }
        return ValidationResult.valid();
    }

    @Override
    public String getDescription() {
        return "设置变量值，支持模板语法 {{variable}}";
    }
}
