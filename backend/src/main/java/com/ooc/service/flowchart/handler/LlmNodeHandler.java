package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.openclaw.OpenClawPluginService;
import com.ooc.service.ClaudeCodePluginService;
import com.ooc.service.KimiPluginService;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeHandler;
import com.ooc.service.flowchart.NodeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 节点处理器
 * 支持调用 OpenClaw、Claude 等 LLM 服务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmNodeHandler implements NodeHandler {

    private final OpenClawPluginService openClawPluginService;
    private final ClaudeCodePluginService claudeCodePluginService;
    private final KimiPluginService kimiPluginService;

    // 模板变量正则: {{variableName}}
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    @Override
    public String getNodeType() {
        return "llm";
    }

    @Override
    public NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        String model = nodeData.getModel();
        String systemPrompt = nodeData.getSystemPrompt();
        String userPrompt = nodeData.getUserPrompt();
        Double temperature = nodeData.getTemperature();
        Integer maxTokens = nodeData.getMaxTokens();
        String outputVar = nodeData.getOutputVar();

        // 渲染模板变量
        String renderedSystemPrompt = renderTemplate(systemPrompt, ctx);
        String renderedUserPrompt = renderTemplate(userPrompt, ctx);

        log.info("[Flowchart:{}] LLM node executing: model={}, systemPromptLength={}, userPromptLength={}",
                ctx.getInstance().getInstanceId(),
                model,
                renderedSystemPrompt != null ? renderedSystemPrompt.length() : 0,
                renderedUserPrompt != null ? renderedUserPrompt.length() : 0);

        // 根据模型类型调用不同的服务
        String response;
        try {
            response = callLlmService(model, renderedSystemPrompt, renderedUserPrompt, temperature, maxTokens, ctx);
        } catch (Exception e) {
            log.error("[Flowchart:{}] LLM call failed: model={}",
                    ctx.getInstance().getInstanceId(), model, e);
            return NodeResult.failure("LLM调用失败: " + e.getMessage());
        }

        if (response == null) {
            return NodeResult.failure("LLM返回空响应");
        }

        // 如果指定了输出变量，保存到上下文
        if (outputVar != null && !outputVar.isEmpty()) {
            ctx.setVariable(outputVar, response);
            log.info("[Flowchart:{}] LLM response saved to variable: {}",
                    ctx.getInstance().getInstanceId(), outputVar);
        }

        log.info("[Flowchart:{}] LLM node completed: responseLength={}",
                ctx.getInstance().getInstanceId(), response.length());

        return NodeResult.success(response);
    }

    /**
     * 调用 LLM 服务
     */
    private String callLlmService(String model, String systemPrompt, String userPrompt,
                                   Double temperature, Integer maxTokens, ExecutionContext ctx) {
        // 默认使用 openclaw
        if (model == null || model.isEmpty()) {
            model = "openclaw";
        }

        String sessionId = ctx.getInstance().getInstanceId();
        String roomId = ctx.getInstance().getRoomId();

        switch (model.toLowerCase()) {
            case "openclaw":
                return callOpenClaw(sessionId, roomId, systemPrompt, userPrompt);
            case "claude":
            case "claude-code":
                return callClaude(sessionId, systemPrompt, userPrompt);
            case "kimi":
                return callKimi(sessionId, systemPrompt, userPrompt);
            default:
                // 默认使用 OpenClaw
                log.warn("Unknown model '{}', falling back to openclaw", model);
                return callOpenClaw(sessionId, roomId, systemPrompt, userPrompt);
        }
    }

    /**
     * 调用 OpenClaw
     */
    private String callOpenClaw(String sessionId, String roomId, String systemPrompt, String userPrompt) {
        // 构建完整的消息内容
        StringBuilder fullPrompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            fullPrompt.append(systemPrompt).append("\n\n");
        }
        fullPrompt.append(userPrompt);

        // 创建临时用户标识
        String tempUserId = "flowchart-" + sessionId;
        String tempUserName = "Flowchart";

        // 调用 OpenClaw（同步等待结果）- 使用 boundedElastic 调度器避免阻塞问题
        try {
            OpenClawPluginService.OpenClawResponse response = Mono.from(
                openClawPluginService.sendMessage(sessionId, fullPrompt.toString(), null, tempUserId, tempUserName)
            )
            .subscribeOn(Schedulers.boundedElastic())
            .block(Duration.ofSeconds(300)); // 5分钟超时

            if (response != null && response.completed()) {
                return response.content();
            } else {
                throw new RuntimeException("OpenClaw returned unsuccessful response");
            }
        } catch (Exception e) {
            log.error("OpenClaw call failed", e);
            throw new RuntimeException("OpenClaw调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用 Claude
     */
    private String callClaude(String sessionId, String systemPrompt, String userPrompt) {
        // 先创建会话
        try {
            // 创建新会话 - 使用 boundedElastic 调度器避免阻塞问题
            List<Map<String, Object>> context = new ArrayList<>();

            // 调用创建会话
            Mono.from(claudeCodePluginService.createSession("flowchart-" + sessionId, context))
                .subscribeOn(Schedulers.boundedElastic())
                .block(Duration.ofSeconds(60));

            // 构建消息内容
            StringBuilder fullPrompt = new StringBuilder();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                fullPrompt.append(systemPrompt).append("\n\n");
            }
            fullPrompt.append(userPrompt);

            // 调用 Claude（非流式）- 使用 boundedElastic 调度器避免阻塞问题
            ClaudeCodePluginService.ClaudeResponse response = Mono.from(
                claudeCodePluginService.sendMessage(sessionId, fullPrompt.toString(), null, "flowchart", "Flowchart")
            )
            .subscribeOn(Schedulers.boundedElastic())
            .block(Duration.ofSeconds(300)); // 5分钟超时

            if (response != null && response.completed()) {
                return response.content();
            } else {
                throw new RuntimeException("Claude returned unsuccessful response");
            }
        } catch (Exception e) {
            log.error("Claude call failed", e);
            throw new RuntimeException("Claude调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用 Kimi
     */
    private String callKimi(String sessionId, String systemPrompt, String userPrompt) {
        // 先创建会话
        try {
            // 创建新会话 - 使用 boundedElastic 调度器避免阻塞问题
            List<Map<String, Object>> context = new ArrayList<>();

            // 调用创建会话
            Mono.from(kimiPluginService.createSession("flowchart-" + sessionId, context))
                .subscribeOn(Schedulers.boundedElastic())
                .block(Duration.ofSeconds(60));

            // 构建消息内容
            StringBuilder fullPrompt = new StringBuilder();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                fullPrompt.append(systemPrompt).append("\n\n");
            }
            fullPrompt.append(userPrompt);

            // 调用 Kimi（非流式）- 使用 boundedElastic 调度器避免阻塞问题
            KimiPluginService.KimiResponse response = Mono.from(
                kimiPluginService.sendMessage(sessionId, fullPrompt.toString(), null, "flowchart", "Flowchart")
            )
            .subscribeOn(Schedulers.boundedElastic())
            .block(Duration.ofSeconds(300)); // 5分钟超时

            if (response != null && response.completed()) {
                return response.content();
            } else {
                throw new RuntimeException("Kimi returned unsuccessful response");
            }
        } catch (Exception e) {
            log.error("Kimi call failed", e);
            throw new RuntimeException("Kimi调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 渲染模板变量
     * 支持 {{variableName}} 语法
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
                // 变量不存在，保留原样或替换为空
                matcher.appendReplacement(sb, "");
            }
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    @Override
    public ValidationResult validate(FlowchartTemplate.NodeData nodeData) {
        if (nodeData.getUserPrompt() == null || nodeData.getUserPrompt().isEmpty()) {
            return ValidationResult.invalid("用户提示词(userPrompt)不能为空");
        }

        String model = nodeData.getModel();
        if (model != null && !model.isEmpty()) {
            String lowerModel = model.toLowerCase();
            if (!lowerModel.equals("openclaw") &&
                !lowerModel.equals("claude") &&
                !lowerModel.equals("claude-code") &&
                !lowerModel.equals("kimi")) {
                return ValidationResult.invalid("不支持的模型类型: " + model);
            }
        }

        return ValidationResult.valid();
    }

    @Override
    public String getDescription() {
        return "调用 LLM (OpenClaw/Claude/Kimi) 生成内容";
    }
}
