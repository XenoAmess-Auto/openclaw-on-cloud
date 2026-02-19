package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeHandler;
import com.ooc.service.flowchart.NodeResult;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 代码执行节点处理器 - 支持 Groovy 脚本
 */
@Slf4j
@Component
public class CodeNodeHandler implements NodeHandler {

    @Override
    public String getNodeType() {
        return "code";
    }

    @Override
    public NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        String code = nodeData.getCode();
        String language = nodeData.getLanguage();
        String outputVar = nodeData.getOutputVar();

        if (code == null || code.trim().isEmpty()) {
            return NodeResult.failure("代码不能为空");
        }

        // 目前只支持 Groovy
        if (language != null && !language.equalsIgnoreCase("groovy") && !language.equalsIgnoreCase("java")) {
            return NodeResult.failure("不支持的编程语言: " + language + "，目前只支持 groovy");
        }

        try {
            // 创建绑定上下文，注入所有流程变量
            Binding binding = new Binding();

            // 注入上下文变量
            Map<String, Object> variables = ctx.getVariables();
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                binding.setVariable(entry.getKey(), entry.getValue());
            }

            // 注入特殊的上下文对象
            binding.setVariable("__ctx", ctx);
            binding.setVariable("__instanceId", ctx.getInstance().getInstanceId());

            // 创建 Groovy Shell 执行代码
            GroovyShell shell = new GroovyShell(binding);
            Object result = shell.evaluate(code);

            log.info("[Flowchart:{}] Code node executed successfully, result: {}",
                    ctx.getInstance().getInstanceId(), result);

            // 如果指定了输出变量，将结果存入上下文
            if (outputVar != null && !outputVar.isEmpty()) {
                ctx.setVariable(outputVar, result);
            }

            return NodeResult.builder()
                    .success(true)
                    .output(result)
                    .build();

        } catch (Exception e) {
            log.error("[Flowchart:{}] Code execution failed: {}",
                    ctx.getInstance().getInstanceId(), e.getMessage(), e);
            return NodeResult.failure("代码执行失败: " + e.getMessage());
        }
    }

    @Override
    public NodeHandler.ValidationResult validate(FlowchartTemplate.NodeData nodeData) {
        if (nodeData.getCode() == null || nodeData.getCode().trim().isEmpty()) {
            return NodeHandler.ValidationResult.invalid("代码不能为空");
        }

        String language = nodeData.getLanguage();
        if (language != null && !language.isEmpty()) {
            if (!language.equalsIgnoreCase("groovy") && !language.equalsIgnoreCase("java")) {
                return NodeHandler.ValidationResult.invalid("不支持的语言: " + language + "，请使用 groovy");
            }
        }

        // 尝试编译代码检查语法
        try {
            GroovyShell shell = new GroovyShell();
            shell.parse(nodeData.getCode());
        } catch (Exception e) {
            return NodeHandler.ValidationResult.invalid("代码语法错误: " + e.getMessage());
        }

        return NodeHandler.ValidationResult.valid();
    }

    @Override
    public String getDescription() {
        return "执行 Groovy 代码，可访问和修改流程变量";
    }
}
