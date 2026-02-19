package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeHandler;
import com.ooc.service.flowchart.NodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 条件分支节点处理器 - 支持布尔判断和多分支判断
 */
@Slf4j
@Component
public class ConditionNodeHandler implements NodeHandler {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    @Override
    public String getNodeType() {
        return "condition";
    }

    @Override
    public NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        String conditionMode = nodeData.getConditionMode();
        
        // 默认布尔模式
        if (conditionMode == null || "boolean".equals(conditionMode)) {
            return executeBooleanMode(nodeData, ctx);
        }
        
        // 范围模式
        if ("range".equals(conditionMode)) {
            return executeRangeMode(nodeData, ctx);
        }
        
        // 分支模式（switch/case 风格）
        return executeSwitchMode(nodeData, ctx);
    }
    
    /**
     * 布尔模式：简单的真/假判断
     */
    private NodeResult executeBooleanMode(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        String conditionExpr = nodeData.getConditionExpr();
        if (conditionExpr == null || conditionExpr.isEmpty()) {
            return NodeResult.failure("条件表达式不能为空");
        }

        try {
            StandardEvaluationContext evalContext = createEvaluationContext(ctx);
            String spelExpr = preprocessExpression(conditionExpr);

            boolean result = Boolean.TRUE.equals(parser.parseExpression(spelExpr)
                    .getValue(evalContext, Boolean.class));

            log.info("[Flowchart:{}] Condition evaluated: {} = {}",
                    ctx.getInstance().getInstanceId(), conditionExpr, result);

            // 确定下一个节点 - 从边连接中获取
            String nextNodeId = findNextNodeId(ctx, result ? "true" : "false");

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
     * 范围模式：根据数值范围匹配多个分支
     * 例如：score = 75，分支1: [0,60)，分支2: [60,80)，分支3: [80,100] → 匹配分支2
     */
    private NodeResult executeRangeMode(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        String rangeVar = nodeData.getRangeVar();
        List<FlowchartTemplate.RangeBranch> rangeBranches = nodeData.getRangeBranches();
        
        if (rangeVar == null || rangeVar.isEmpty()) {
            return NodeResult.failure("范围判断变量不能为空");
        }
        
        if (rangeBranches == null || rangeBranches.isEmpty()) {
            return NodeResult.failure("范围分支列表不能为空");
        }
        
        // 获取变量值
        Object varValue = ctx.getVariable(rangeVar);
        if (varValue == null) {
            return NodeResult.failure("变量 '" + rangeVar + "' 未定义或为空");
        }
        
        // 解析为数值
        double value;
        try {
            value = Double.parseDouble(varValue.toString());
        } catch (NumberFormatException e) {
            return NodeResult.failure("变量 '" + rangeVar + "' 的值 '" + varValue + "' 不是有效数字");
        }
        
        log.info("[Flowchart:{}] Range mode: variable {} = {}",
                ctx.getInstance().getInstanceId(), rangeVar, value);
        
        // 依次匹配每个范围分支
        for (int i = 0; i < rangeBranches.size(); i++) {
            FlowchartTemplate.RangeBranch branch = rangeBranches.get(i);
            
            if (matchesRange(value, branch)) {
                String handleId = branch.getHandleId() != null ? branch.getHandleId() : "range_" + i;
                log.info("[Flowchart:{}] Range branch {} matched: {} in range [{}{}, {}{}]",
                        ctx.getInstance().getInstanceId(), i, value,
                        branch.isMinInclusive() ? "" : "(",
                        branch.getMin() != null ? branch.getMin() : "-∞",
                        branch.getMax() != null ? branch.getMax() : "+∞",
                        branch.isMaxInclusive() ? "" : ")");
                
                String nextNodeId = findNextNodeId(ctx, handleId);
                
                return NodeResult.builder()
                        .success(true)
                        .output(i)
                        .nextNodeId(nextNodeId)
                        .build();
            }
        }
        
        // 没有匹配任何范围分支，检查是否有默认分支
        String defaultBranch = nodeData.getRangeDefaultBranch();
        if (defaultBranch != null && !defaultBranch.isEmpty()) {
            log.info("[Flowchart:{}] No range matched, using default branch",
                    ctx.getInstance().getInstanceId());
            String nextNodeId = findNextNodeId(ctx, defaultBranch);
            return NodeResult.builder()
                    .success(true)
                    .output(-1)
                    .nextNodeId(nextNodeId)
                    .build();
        }
        
        // 没有匹配任何分支，返回失败
        return NodeResult.failure("值 " + value + " 没有匹配任何范围分支");
    }
    
    /**
     * 判断值是否在指定范围内
     */
    private boolean matchesRange(double value, FlowchartTemplate.RangeBranch branch) {
        Double min = branch.getMin();
        Double max = branch.getMax();
        
        // 检查最小值约束
        if (min != null) {
            boolean minOk = branch.isMinInclusive() ? value >= min : value > min;
            if (!minOk) {
                return false;
            }
        }
        
        // 检查最大值约束
        if (max != null) {
            boolean maxOk = branch.isMaxInclusive() ? value <= max : value < max;
            if (!maxOk) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 分支模式：根据变量值匹配多个条件分支
     */
    private NodeResult executeSwitchMode(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        String switchVar = nodeData.getSwitchVar();
        List<Map<String, Object>> branches = nodeData.getBranches();
        
        if (switchVar == null || switchVar.isEmpty()) {
            return NodeResult.failure("判断变量不能为空");
        }
        
        if (branches == null || branches.isEmpty()) {
            return NodeResult.failure("分支列表不能为空");
        }
        
        Object varValue = ctx.getVariable(switchVar);
        log.info("[Flowchart:{}] Switch mode: variable {} = {}",
                ctx.getInstance().getInstanceId(), switchVar, varValue);
        
        // 依次匹配每个分支
        for (int i = 0; i < branches.size(); i++) {
            Map<String, Object> branch = branches.get(i);
            String operator = (String) branch.get("operator");
            String value = (String) branch.get("value");
            
            if (matchesCondition(varValue, operator, value)) {
                log.info("[Flowchart:{}] Branch {} matched: {} {} {}",
                        ctx.getInstance().getInstanceId(), i, varValue, operator, value);
                
                String nextNodeId = findNextNodeId(ctx, "branch_" + i);
                return NodeResult.builder()
                        .success(true)
                        .output(i)
                        .nextNodeId(nextNodeId)
                        .build();
            }
        }
        
        // 没有匹配任何分支，返回失败
        return NodeResult.failure("没有匹配任何分支条件");
    }
    
    /**
     * 判断值是否匹配条件
     */
    private boolean matchesCondition(Object varValue, String operator, String compareValue) {
        if (varValue == null || operator == null) {
            return false;
        }
        
        String strValue = varValue.toString();
        
        return switch (operator) {
            case "eq" -> strValue.equals(compareValue);
            case "ne" -> !strValue.equals(compareValue);
            case "gt" -> compareNumeric(varValue, compareValue) > 0;
            case "gte" -> compareNumeric(varValue, compareValue) >= 0;
            case "lt" -> compareNumeric(varValue, compareValue) < 0;
            case "lte" -> compareNumeric(varValue, compareValue) <= 0;
            case "contains" -> strValue.contains(compareValue);
            case "regex" -> strValue.matches(compareValue);
            default -> false;
        };
    }
    
    /**
     * 数值比较
     */
    private int compareNumeric(Object varValue, String compareValue) {
        try {
            double varNum = Double.parseDouble(varValue.toString());
            double compareNum = Double.parseDouble(compareValue);
            return Double.compare(varNum, compareNum);
        } catch (NumberFormatException e) {
            // 非数字按字符串比较
            return varValue.toString().compareTo(compareValue);
        }
    }
    
    /**
     * 查找下一个节点ID - 根据 sourceHandle 匹配边
     */
    private String findNextNodeId(ExecutionContext ctx, String sourceHandle) {
        var edges = ctx.findOutgoingEdges(ctx.getCurrentNodeId());
        return edges.stream()
                .filter(e -> sourceHandle.equals(e.getSourceHandle()))
                .findFirst()
                .map(FlowchartTemplate.Edge::getTarget)
                .orElse(null);
    }
    
    /**
     * 创建 SpEL 评估上下文
     */
    private StandardEvaluationContext createEvaluationContext(ExecutionContext ctx) {
        StandardEvaluationContext evalContext = new StandardEvaluationContext();
        for (Map.Entry<String, Object> entry : ctx.getVariables().entrySet()) {
            evalContext.setVariable(entry.getKey(), entry.getValue());
        }
        return evalContext;
    }

    /**
     * 预处理表达式，支持 {{variable}} 语法和简单变量名
     */
    private String preprocessExpression(String expr) {
        // 替换 {{variable}} 为 #variable
        java.util.regex.Matcher matcher = VARIABLE_PATTERN.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "#$1");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @Override
    public ValidationResult validate(FlowchartTemplate.NodeData nodeData) {
        String conditionMode = nodeData.getConditionMode();

        if (conditionMode == null || "boolean".equals(conditionMode)) {
            // 布尔模式验证
            if (nodeData.getConditionExpr() == null || nodeData.getConditionExpr().isEmpty()) {
                return ValidationResult.invalid("条件表达式不能为空");
            }
            try {
                parser.parseExpression(preprocessExpression(nodeData.getConditionExpr()));
            } catch (Exception e) {
                return ValidationResult.invalid("条件表达式语法错误: " + e.getMessage());
            }
        } else if ("range".equals(conditionMode)) {
            // 范围模式验证
            if (nodeData.getRangeVar() == null || nodeData.getRangeVar().isEmpty()) {
                return ValidationResult.invalid("范围判断变量不能为空");
            }
            List<FlowchartTemplate.RangeBranch> rangeBranches = nodeData.getRangeBranches();
            if (rangeBranches == null || rangeBranches.isEmpty()) {
                return ValidationResult.invalid("范围分支列表不能为空");
            }
            // 验证每个范围分支的有效性
            for (int i = 0; i < rangeBranches.size(); i++) {
                FlowchartTemplate.RangeBranch branch = rangeBranches.get(i);
                if (branch.getMin() != null && branch.getMax() != null) {
                    if (branch.getMin() > branch.getMax()) {
                        return ValidationResult.invalid("分支 " + (i + 1) + " 的最小值不能大于最大值");
                    }
                }
            }
        } else {
            // 分支模式验证
            if (nodeData.getSwitchVar() == null || nodeData.getSwitchVar().isEmpty()) {
                return ValidationResult.invalid("判断变量不能为空");
            }
            List<Map<String, Object>> branches = nodeData.getBranches();
            if (branches == null || branches.isEmpty()) {
                return ValidationResult.invalid("分支列表不能为空");
            }
        }

        return ValidationResult.valid();
    }

    @Override
    public String getDescription() {
        return "条件分支，支持布尔判断和多分支范围判断";
    }
}
