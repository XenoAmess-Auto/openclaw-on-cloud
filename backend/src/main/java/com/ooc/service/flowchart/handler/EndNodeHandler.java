package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeHandler;
import com.ooc.service.flowchart.NodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 结束节点处理器
 */
@Slf4j
@Component
public class EndNodeHandler implements NodeHandler {

    @Override
    public String getNodeType() {
        return "end";
    }

    @Override
    public NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        log.info("[Flowchart:{}] Reached end node",
                ctx.getInstance().getInstanceId());

        // 如果有输出变量配置，获取其值作为最终输出
        String outputVar = nodeData.getOutputVar();
        Object finalOutput = null;

        if (outputVar != null && !outputVar.isEmpty()) {
            finalOutput = ctx.getVariable(outputVar);
        }

        // 如果没有指定输出变量，尝试使用 nodeData.label 作为最终输出
        if (finalOutput == null && nodeData.getLabel() != null) {
            finalOutput = renderTemplate(nodeData.getLabel(), ctx);
        }

        // 设置流程结束时间
        ctx.getInstance().setCompletedAt(java.time.Instant.now());
        long duration = System.currentTimeMillis() - ctx.getInstance().getStartedAt().toEpochMilli();
        ctx.getInstance().setDurationMs(duration);

        log.info("[Flowchart:{}] Execution completed in {}ms",
                ctx.getInstance().getInstanceId(), duration);

        return NodeResult.builder()
                .success(true)
                .output(finalOutput)
                .shouldContinue(false) // 结束流程
                .build();
    }

    @Override
    public String getDescription() {
        return "流程结束节点";
    }

    /**
     * 简单的模板渲染
     */
    private String renderTemplate(String template, ExecutionContext ctx) {
        if (template == null) {
            return null;
        }
        String result = template;
        for (Map.Entry<String, Object> entry : ctx.getVariables().entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, String.valueOf(entry.getValue()));
            }
        }
        return result;
    }
}
