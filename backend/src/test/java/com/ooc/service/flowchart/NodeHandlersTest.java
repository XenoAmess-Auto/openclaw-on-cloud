package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartInstance;
import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 节点处理器单元测试
 */
class NodeHandlersTest {

    private FlowchartInstance mockInstance;

    @BeforeEach
    void setUp() {
        // 创建 mock 流程图实例，供所有测试用例使用
        mockInstance = new FlowchartInstance();
        mockInstance.setInstanceId(UUID.randomUUID().toString());
        mockInstance.setTemplateId("test-template");
        mockInstance.setRoomId("test-room");
        mockInstance.setStartedAt(Instant.now());
    }

    private ExecutionContext createContext() {
        return ExecutionContext.builder()
                .instance(mockInstance)
                .variables(new HashMap<>())
                .build();
    }

    @Test
    void testStartNodeHandler() {
        StartNodeHandler handler = new StartNodeHandler();

        FlowchartTemplate.NodeData data = FlowchartTemplate.NodeData.builder()
                .label("Start")
                .build();

        ExecutionContext ctx = createContext();

        NodeResult result = handler.execute(data, ctx);

        assertTrue(result.isSuccess());
        assertEquals("Started", result.getOutput());
        assertNotNull(ctx.getVariable("__instanceId"));
    }

    @Test
    void testVariableNodeHandler() {
        VariableNodeHandler handler = new VariableNodeHandler();

        // 设置上下文变量
        ExecutionContext ctx = createContext();
        ctx.setVariable("name", "World");

        // 测试模板渲染
        FlowchartTemplate.NodeData data = FlowchartTemplate.NodeData.builder()
                .varName("greeting")
                .varValue("Hello, {{name}}!")
                .build();

        NodeResult result = handler.execute(data, ctx);

        assertTrue(result.isSuccess());
        assertEquals("Hello, World!", ctx.getVariable("greeting"));
    }

    @Test
    void testConditionNodeHandler_TrueBranch() {
        ConditionNodeHandler handler = new ConditionNodeHandler();

        ExecutionContext ctx = createContext();
        ctx.setVariable("score", 80);

        FlowchartTemplate.NodeData data = FlowchartTemplate.NodeData.builder()
                .conditionExpr("{{score}} >= 60")
                .trueTarget("pass-node")
                .falseTarget("fail-node")
                .build();

        NodeResult result = handler.execute(data, ctx);

        assertTrue(result.isSuccess());
        assertEquals(true, result.getOutput());
        assertEquals("pass-node", result.getNextNodeId());
    }

    @Test
    void testConditionNodeHandler_FalseBranch() {
        ConditionNodeHandler handler = new ConditionNodeHandler();

        ExecutionContext ctx = createContext();
        ctx.setVariable("score", 40);

        FlowchartTemplate.NodeData data = FlowchartTemplate.NodeData.builder()
                .conditionExpr("{{score}} >= 60")
                .trueTarget("pass-node")
                .falseTarget("fail-node")
                .build();

        NodeResult result = handler.execute(data, ctx);

        assertTrue(result.isSuccess());
        assertEquals(false, result.getOutput());
        assertEquals("fail-node", result.getNextNodeId());
    }

    @Test
    void testWaitNodeHandler() {
        WaitNodeHandler handler = new WaitNodeHandler();

        ExecutionContext ctx = createContext();

        FlowchartTemplate.NodeData data = FlowchartTemplate.NodeData.builder()
                .waitSeconds(1)  // 等待1秒
                .build();

        long start = System.currentTimeMillis();
        NodeResult result = handler.execute(data, ctx);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.isSuccess());
        assertTrue(elapsed >= 900, "Should wait at least 0.9s but was " + elapsed + "ms");
    }

    @Test
    void testEndNodeHandler() {
        EndNodeHandler handler = new EndNodeHandler();

        ExecutionContext ctx = createContext();
        ctx.setVariable("result", "Success");

        FlowchartTemplate.NodeData data = FlowchartTemplate.NodeData.builder()
                .outputVar("result")
                .build();

        NodeResult result = handler.execute(data, ctx);

        assertTrue(result.isSuccess());
        assertEquals("Success", result.getOutput());
        assertFalse(result.isShouldContinue());  // 应该停止流程
    }

    @Test
    void testVariableNodeValidation() {
        VariableNodeHandler handler = new VariableNodeHandler();

        // 空变量名应该验证失败
        FlowchartTemplate.NodeData invalidData = FlowchartTemplate.NodeData.builder()
                .varName("")
                .build();

        assertFalse(handler.validate(invalidData).isValid());

        // 正常数据应该验证通过
        FlowchartTemplate.NodeData validData = FlowchartTemplate.NodeData.builder()
                .varName("testVar")
                .varValue("testValue")
                .build();

        assertTrue(handler.validate(validData).isValid());
    }

    @Test
    void testConditionNodeValidation() {
        ConditionNodeHandler handler = new ConditionNodeHandler();

        // 空表达式应该验证失败
        FlowchartTemplate.NodeData invalidData = FlowchartTemplate.NodeData.builder()
                .conditionExpr("")
                .build();

        assertFalse(handler.validate(invalidData).isValid());

        // 无效表达式应该验证失败
        FlowchartTemplate.NodeData invalidExprData = FlowchartTemplate.NodeData.builder()
                .conditionExpr("{{invalid")
                .build();

        assertFalse(handler.validate(invalidExprData).isValid());

        // 有效表达式应该验证通过
        FlowchartTemplate.NodeData validData = FlowchartTemplate.NodeData.builder()
                .conditionExpr("{{score}} > 0.5")
                .build();

        assertTrue(handler.validate(validData).isValid());
    }
}
