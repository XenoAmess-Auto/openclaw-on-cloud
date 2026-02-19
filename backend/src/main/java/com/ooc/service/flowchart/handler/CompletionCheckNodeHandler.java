package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.openclaw.OpenClawPluginService;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeHandler;
import com.ooc.service.flowchart.NodeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 完成判断节点处理器
 * 使用 OpenClaw AI 判断变量内容的语义是否表示"已完成"
 * 输出两个分支: completed (完成) / incomplete (未完成)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompletionCheckNodeHandler implements NodeHandler {

    private final OpenClawPluginService openClawPluginService;

    // 模板变量正则: {{variableName}}
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    // 默认系统提示词
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个语义判断助手。请判断给定的文本内容是否表示"已完成"、"完成"、"结束"、"成功"等含义。
            
            规则：
            - 如果内容明确表示任务已完成、结束、成功、搞定、做完等，回复: COMPLETED
            - 如果内容表示未完成、进行中、失败、等待、未开始等，回复: INCOMPLETE
            - 只回复这两个词之一，不要其他解释
            
            示例：
            - "任务已完成" -> COMPLETED
            - "已完成日报提交" -> COMPLETED
            - "还在处理中" -> INCOMPLETE
            - "失败，请重试" -> INCOMPLETE
            - "done" -> COMPLETED
            - "completed" -> COMPLETED
            - "pending" -> INCOMPLETE
            """;

    @Override
    public String getNodeType() {
        return "completion_check";
    }

    @Override
    public NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        String checkVar = nodeData.getCheckVar();  // 要检查的变量名
        String customPrompt = nodeData.getCheckPrompt();  // 自定义提示词（可选）
        String outputVar = nodeData.getOutputVar();  // 输出变量（可选）

        if (checkVar == null || checkVar.trim().isEmpty()) {
            return NodeResult.failure("完成判断节点需要指定要检查的变量名(checkVar)");
        }

        // 获取变量值
        Object varValue = ctx.getVariable(checkVar);
        if (varValue == null) {
            log.warn("[Flowchart:{}] Check variable '{}' is null, treating as incomplete",
                    ctx.getInstance().getInstanceId(), checkVar);
            // 变量不存在，默认走 incomplete 分支
            String nextNodeId = findNextNodeId(ctx, ctx.getCurrentNodeId(), "incomplete");
            return NodeResult.builder()
                    .success(true)
                    .output("INCOMPLETE")
                    .nextNodeId(nextNodeId)
                    .build();
        }

        String contentToCheck = String.valueOf(varValue);
        if (contentToCheck.trim().isEmpty()) {
            log.warn("[Flowchart:{}] Check variable '{}' is empty, treating as incomplete",
                    ctx.getInstance().getInstanceId(), checkVar);
            String nextNodeId = findNextNodeId(ctx, ctx.getCurrentNodeId(), "incomplete");
            return NodeResult.builder()
                    .success(true)
                    .output("INCOMPLETE")
                    .nextNodeId(nextNodeId)
                    .build();
        }

        // 渲染自定义提示词中的模板变量
        String renderedCustomPrompt = null;
        if (customPrompt != null && !customPrompt.isEmpty()) {
            renderedCustomPrompt = renderTemplate(customPrompt, ctx);
        }

        // 构建提示词
        String userPrompt = renderedCustomPrompt != null
                ? renderedCustomPrompt + "\n\n要判断的内容:\n" + contentToCheck
                : "请判断以下内容是否表示已完成:\n\n" + contentToCheck;

        log.info("[Flowchart:{}] Completion check node executing: checkVar={}, contentLength={}",
                ctx.getInstance().getInstanceId(),
                checkVar,
                contentToCheck.length());

        String sessionId = ctx.getInstance().getInstanceId();
        String tempUserId = "flowchart-" + sessionId;
        String tempUserName = "Flowchart";

        // 调用 OpenClaw 进行判断
        try {
            OpenClawPluginService.OpenClawResponse response = Mono.from(
                openClawPluginService.sendMessage(sessionId, userPrompt, null, tempUserId, tempUserName)
            )
            .subscribeOn(Schedulers.boundedElastic())
            .block(Duration.ofSeconds(60)); // 1分钟超时

            if (response == null || !response.completed()) {
                log.error("[Flowchart:{}] OpenClaw response incomplete", ctx.getInstance().getInstanceId());
                return NodeResult.failure("OpenClaw 判断请求失败");
            }

            String aiResponse = response.content().trim().toUpperCase();
            log.info("[Flowchart:{}] Completion check AI response: {}",
                    ctx.getInstance().getInstanceId(), aiResponse);

            // 解析结果
            boolean isCompleted = aiResponse.contains("COMPLETED") ||
                    aiResponse.contains("完成") ||
                    aiResponse.contains("DONE") ||
                    aiResponse.contains("SUCCESS");

            String result = isCompleted ? "COMPLETED" : "INCOMPLETE";
            String handleId = isCompleted ? "completed" : "incomplete";

            // 保存到输出变量
            if (outputVar != null && !outputVar.isEmpty()) {
                ctx.setVariable(outputVar, result);
            }

            // 同时保存原始 AI 响应供调试
            ctx.setVariable(checkVar + "_check_result", result);
            ctx.setVariable(checkVar + "_check_raw", aiResponse);

            log.info("[Flowchart:{}] Completion check result: {} -> {}",
                    ctx.getInstance().getInstanceId(), checkVar, result);

            // 查找对应的边
            String nextNodeId = findNextNodeId(ctx, ctx.getCurrentNodeId(), handleId);

            return NodeResult.builder()
                    .success(true)
                    .output(result)
                    .nextNodeId(nextNodeId)
                    .build();

        } catch (Exception e) {
            log.error("[Flowchart:{}] Completion check failed",
                    ctx.getInstance().getInstanceId(), e);
            return NodeResult.failure("完成判断失败: " + e.getMessage());
        }
    }

    /**
     * 查找下一个节点ID
     * 根据 handleId (completed/incomplete) 查找对应的边
     */
    private String findNextNodeId(ExecutionContext ctx, String currentNodeId, String handleId) {
        return ctx.findOutgoingEdges(currentNodeId).stream()
                .filter(e -> handleId.equals(e.getSourceHandle()))
                .findFirst()
                .map(FlowchartTemplate.Edge::getTarget)
                .orElse(null);
    }

    /**
     * 渲染模板变量
     */
    private String renderTemplate(String template, ExecutionContext ctx) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            Object varValue = ctx.getVariable(varName);

            if (varValue != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(varValue)));
            } else {
                matcher.appendReplacement(sb, "");
            }
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    @Override
    public ValidationResult validate(FlowchartTemplate.NodeData nodeData) {
        if (nodeData.getCheckVar() == null || nodeData.getCheckVar().trim().isEmpty()) {
            return ValidationResult.invalid("完成判断节点需要指定要检查的变量名(checkVar)");
        }
        return ValidationResult.valid();
    }

    @Override
    public String getDescription() {
        return "使用 OpenClaw AI 判断变量语义是否表示'已完成'，输出 completed/incomplete 两个分支";
    }
}
