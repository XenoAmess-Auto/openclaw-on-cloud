package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeHandler;
import com.ooc.service.flowchart.NodeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 等待节点处理器
 */
@Slf4j
@Component
public class WaitNodeHandler implements NodeHandler {

    @Override
    public String getNodeType() {
        return "wait";
    }

    @Override
    public NodeResult execute(FlowchartTemplate.NodeData nodeData, ExecutionContext ctx) {
        Integer waitSeconds = nodeData.getWaitSeconds();
        if (waitSeconds == null || waitSeconds <= 0) {
            waitSeconds = 1; // 默认等待1秒
        }

        log.info("[Flowchart:{}] Waiting for {} seconds",
                ctx.getInstance().getInstanceId(), waitSeconds);

        try {
            Thread.sleep(waitSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NodeResult.failure("等待被中断");
        }

        log.info("[Flowchart:{}] Wait completed", ctx.getInstance().getInstanceId());

        return NodeResult.success(waitSeconds);
    }

    @Override
    public ValidationResult validate(FlowchartTemplate.NodeData nodeData) {
        if (nodeData.getWaitSeconds() == null || nodeData.getWaitSeconds() <= 0) {
            return ValidationResult.invalid("等待时间必须大于0秒");
        }
        if (nodeData.getWaitSeconds() > 3600) {
            return ValidationResult.invalid("等待时间不能超过3600秒（1小时）");
        }
        return ValidationResult.valid();
    }

    @Override
    public String getDescription() {
        return "延迟等待指定秒数";
    }
}
