package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeHandler;
import com.ooc.service.flowchart.NodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 条件分支节点处理器
 */
@Slf4j
@Component
public class ConditionNodeHandler implements NodeHandler {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    @Override
    public String getNodeType() {
        return "condition";
    }

    @Override
    public NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        String conditionExpr = nodeData.getConditionExpr();
        if (conditionExpr == null || conditionExpr.isEmpty()) {
            return NodeResult.failure("条件表达式不能为空");
        }

        try {
            // 使用 SpEL 解析表达式
            StandardEvaluationContext evalContext = new StandardEvaluationContext();
            for (Map.Entry<String, Object> entry : ctx.getVariables().entrySet()) {
                evalContext.setVariable(entry.getKey(), entry.getValue());
            }

            // 支持直接引用变量（如 "score > 0.5" 会被解析为 "#score > 0.5"）
            String spelExpr = preprocessExpression(conditionExpr);

            boolean result = Boolean.TRUE.equals(parser.parseExpression(spelExpr)
                    .getValue(evalContext, Boolean.class));

            log.info("[Flowchart:{}] Condition evaluated: {} = {}",
                    ctx.getInstance().getInstanceId(), conditionExpr, result);

            // 确定下一个节点
            String nextNodeId = result ? nodeData.getTrueTarget() : nodeData.getFalseTarget();

            return NodeResult.builder()
                    .success(true)
                    .output(result)
                    .nextNodeId(nextNodeId)
                    .build();

        } catch (Exception e) {
            log.error("[Flowchart:{}] Failed to evaluate condition: {}",
                    ctx.getInstance().getInstanceId(), conditionExpr, e);
            return NodeResult.failure("条件表达式执行失败: " + e.getMessage());
        }
    }

    /**
     * 预处理表达式，支持 {{variable}} 语法和简单变量名
     */
    private String preprocessExpression(String expr) {
        String result = expr;

        // 替换 {{variable}} 为 #variable
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{\\{(\\w+)\\}\\}");
        java.util.regex.Matcher matcher = pattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "#$1");
        }
        matcher.appendTail(sb);
        result = sb.toString();

        // 如果表达式不以 # 或 $ 开头，添加必要的处理
        // 这里保持原样，让 SpEL 解析
        return result;
    }

    @Override
    public ValidationResult validate(FlowchartTemplate.NodeData nodeData) {
        if (nodeData.getConditionExpr() == null || nodeData.getConditionExpr().isEmpty()) {
            return ValidationResult.invalid("条件表达式不能为空");
        }
        try {
            parser.parseExpression(preprocessExpression(nodeData.getConditionExpr()));
        } catch (Exception e) {
            return ValidationResult.invalid("条件表达式语法错误: " + e.getMessage());
        }
        return ValidationResult.valid();
    }

    @Override
    public String getDescription() {
        return "条件分支，根据表达式结果选择执行路径";
    }
}
