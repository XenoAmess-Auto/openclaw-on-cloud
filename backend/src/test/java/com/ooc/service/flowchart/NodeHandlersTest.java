package com.ooc.service.flowchart.handler;

import com.ooc.entity.flowchart.FlowchartInstance;
import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.service.flowchart.ExecutionContext;
import com.ooc.service.flowchart.NodeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    @Test
    void testConditionNodeHandler_RangeMode_MultipleBranches() {
        ConditionNodeHandler handler = new ConditionNodeHandler();

        // 测试多个范围分支（5个区间）：不及格、及格、中等、良好、优秀
        List<FlowchartTemplate.RangeBranch> rangeBranches = new ArrayList<>();
        rangeBranches.add(FlowchartTemplate.RangeBranch.builder()
                .label("优秀")
                .min(90.0)
                .max(100.0)
                .minInclusive(true)
                .maxInclusive(true)
                .handleId("range_0")
                .build());
        rangeBranches.add(FlowchartTemplate.RangeBranch.builder()
                .label("良好")
                .min(80.0)
                .max(90.0)
                .minInclusive(true)
                .maxInclusive(false)
                .handleId("range_1")
                .build());
        rangeBranches.add(FlowchartTemplate.RangeBranch.builder()
                .label("中等")
                .min(70.0)
                .max(80.0)
                .minInclusive(true)
                .maxInclusive(false)
                .handleId("range_2")
                .build());
        rangeBranches.add(FlowchartTemplate.RangeBranch.builder()
                .label("及格")
                .min(60.0)
                .max(70.0)
                .minInclusive(true)
                .maxInclusive(false)
                .handleId("range_3")
                .build());
        rangeBranches.add(FlowchartTemplate.RangeBranch.builder()
                .label("不及格")
                .max(60.0)
                .minInclusive(false)
                .maxInclusive(false)
                .handleId("range_4")
                .build());

        // 测试优秀 (95分)
        ExecutionContext ctx1 = createContext();
        ctx1.setVariable("score", 95);
        FlowchartTemplate.NodeData data1 = FlowchartTemplate.NodeData.builder()
                .conditionMode("range")
                .rangeVar("score")
                .rangeBranches(rangeBranches)
                .build();
        NodeResult result1 = handler.execute(data1, ctx1);
        assertTrue(result1.isSuccess());
        assertEquals(0, result1.getOutput()); // 第一个分支（优秀）

        // 测试良好 (85分)
        ExecutionContext ctx2 = createContext();
        ctx2.setVariable("score", 85);
        NodeResult result2 = handler.execute(data2WithSameBranches(), ctx2);
        assertTrue(result2.isSuccess());
        assertEquals(1, result2.getOutput()); // 第二个分支（良好）

        // 测试中等 (75分)
        ExecutionContext ctx3 = createContext();
        ctx3.setVariable("score", 75);
        NodeResult result3 = handler.execute(data3WithSameBranches(), ctx3);
        assertTrue(result3.isSuccess());
        assertEquals(2, result3.getOutput()); // 第三个分支（中等）

        // 测试不及格 (55分)
        ExecutionContext ctx4 = createContext();
        ctx4.setVariable("score", 55);
        NodeResult result4 = handler.execute(data4WithSameBranches(), ctx4);
        assertTrue(result4.isSuccess());
        assertEquals(4, result4.getOutput()); // 第五个分支（不及格）
    }

    private FlowchartTemplate.NodeData data2WithSameBranches() {
        return createRangeNodeData();
    }

    private FlowchartTemplate.NodeData data3WithSameBranches() {
        return createRangeNodeData();
    }

    private FlowchartTemplate.NodeData data4WithSameBranches() {
        return createRangeNodeData();
    }

    private FlowchartTemplate.NodeData createRangeNodeData() {
        List<FlowchartTemplate.RangeBranch> rangeBranches = new ArrayList<>();
        rangeBranches.add(FlowchartTemplate.RangeBranch.builder()
                .label("优秀").min(90.0).max(100.0).minInclusive(true).maxInclusive(true).handleId("range_0").build());
        rangeBranches.add(FlowchartTemplate.RangeBranch.builder()
                .label("良好").min(80.0).max(90.0).minInclusive(true).maxInclusive(false).handleId("range_1").build());
        rangeBranches.add(FlowchartTemplate.RangeBranch.builder()
                .label("中等").min(70.0).max(80.0).minInclusive(true).maxInclusive(false).handleId("range_2").build());
        rangeBranches.add(FlowchartTemplate.RangeBranch.builder()
                .label("及格").min(60.0).max(70.0).minInclusive(true).maxInclusive(false).handleId("range_3").build());
        rangeBranches.add(FlowchartTemplate.RangeBranch.builder()
                .label("不及格").max(60.0).minInclusive(false).maxInclusive(false).handleId("range_4").build());
        return FlowchartTemplate.NodeData.builder()
                .conditionMode("range")
                .rangeVar("score")
                .rangeBranches(rangeBranches)
                .build();
    }
}
