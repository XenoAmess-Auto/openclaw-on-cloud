package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeHandler;
import com.ooc.service.flowchart.NodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 开始节点处理器
 */
@Slf4j
@Component
public class StartNodeHandler implements NodeHandler {

    @Override
    public String getNodeType() {
        return "start";
    }

    @Override
    public NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        log.info("[Flowchart:{}] Starting execution from start node", 
                ctx.getInstance().getInstanceId());

        // 初始化系统变量
        ctx.setVariable("__instanceId", ctx.getInstance().getInstanceId());
        ctx.setVariable("__templateId", ctx.getInstance().getTemplateId());
        ctx.setVariable("__roomId", ctx.getInstance().getRoomId());
        ctx.setVariable("__startedAt", System.currentTimeMillis());

        // 处理节点变量定义（如果有）
        if (nodeData.getVarName() != null && nodeData.getVarValue() != null) {
            String renderedValue = renderTemplate(nodeData.getVarValue(), ctx);
            ctx.setVariable(nodeData.getVarName(), renderedValue);
        }

        return NodeResult.success("Started");
    }

    @Override
    public String getDescription() {
        return "流程入口节点，初始化执行环境";
    }

    /**
     * 简单的模板渲染（变量替换）
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
