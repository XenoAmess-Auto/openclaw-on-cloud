package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.openclaw.OpenClawPluginService;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeHandler;
import com.ooc.service.flowchart.NodeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenClaw 节点处理器
 * 专门处理类型为 "openclaw" 的节点，提供简化的 OpenClaw 调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenClawNodeHandler implements NodeHandler {

    private final OpenClawPluginService openClawPluginService;

    // 模板变量正则: {{variableName}}
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    @Override
    public String getNodeType() {
        return "openclaw";
    }

    @Override
    public NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        String prompt = nodeData.getUserPrompt();
        String systemPrompt = nodeData.getSystemPrompt();
        String outputVar = nodeData.getOutputVar();

        // 如果没有 userPrompt，尝试使用 label 作为提示词
        if (prompt == null || prompt.isEmpty()) {
            prompt = nodeData.getLabel();
        }

        // 渲染模板变量
        String renderedPrompt = renderTemplate(prompt, ctx);
        String renderedSystemPrompt = renderTemplate(systemPrompt, ctx);

        log.info("[Flowchart:{}] OpenClaw node executing: promptLength={}",
                ctx.getInstance().getInstanceId(),
                renderedPrompt != null ? renderedPrompt.length() : 0);

        String sessionId = ctx.getInstance().getInstanceId();
        String roomId = ctx.getInstance().getRoomId();

        // 构建完整的消息内容
        StringBuilder fullPrompt = new StringBuilder();
        if (renderedSystemPrompt != null && !renderedSystemPrompt.isEmpty()) {
            fullPrompt.append(renderedSystemPrompt).append("\n\n");
        }
        fullPrompt.append(renderedPrompt);

        // 创建临时用户标识
        String tempUserId = "flowchart-" + sessionId;
        String tempUserName = "Flowchart";

        // 调用 OpenClaw（同步等待结果）
        try {
            OpenClawPluginService.OpenClawResponse response = openClawPluginService
                    .sendMessage(sessionId, fullPrompt.toString(), null, tempUserId, tempUserName)
                    .block();

            if (response == null) {
                return NodeResult.failure("OpenClaw 返回空响应");
            }

            if (!response.completed()) {
                return NodeResult.failure("OpenClaw 响应未完成");
            }

            String content = response.content();

            // 如果指定了输出变量，保存到上下文
            if (outputVar != null && !outputVar.isEmpty()) {
                ctx.setVariable(outputVar, content);
                log.info("[Flowchart:{}] OpenClaw response saved to variable: {}",
                        ctx.getInstance().getInstanceId(), outputVar);
            }

            log.info("[Flowchart:{}] OpenClaw node completed: responseLength={}",
                    ctx.getInstance().getInstanceId(), content.length());

            return NodeResult.success(content);

        } catch (Exception e) {
            log.error("[Flowchart:{}] OpenClaw call failed", ctx.getInstance().getInstanceId(), e);
            return NodeResult.failure("OpenClaw 调用失败: " + e.getMessage());
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
                // 变量不存在，替换为空字符串
                matcher.appendReplacement(sb, "");
            }
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    @Override
    public ValidationResult validate(FlowchartTemplate.NodeData nodeData) {
        // OpenClaw 节点只需要有提示词即可，可以是 userPrompt 或 label
        boolean hasPrompt = (nodeData.getUserPrompt() != null && !nodeData.getUserPrompt().isEmpty())
                || (nodeData.getLabel() != null && !nodeData.getLabel().isEmpty());

        if (!hasPrompt) {
            return ValidationResult.invalid("OpenClaw 节点需要设置提示词(userPrompt)或节点名称(label)");
        }

        return ValidationResult.valid();
    }

    @Override
    public String getDescription() {
        return "调用 OpenClaw AI 生成内容";
    }
}
