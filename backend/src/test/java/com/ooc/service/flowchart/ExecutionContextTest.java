package com.ooc.service.flowchart;

import com.ooc.entity.flowchart.FlowchartInstance;
import com.ooc.entity.flowchart.FlowchartTemplate;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecutionContext 单元测试
 */
class ExecutionContextTest {

    @Test
    void testVariableOperations() {
        ExecutionContext ctx = ExecutionContext.builder()
                .variables(new HashMap<>())
                .build();

        // 设置变量
        ctx.setVariable("name", "test");
        ctx.setVariable("count", 42);

        // 获取变量
        assertEquals("test", ctx.getVariable("name"));
        assertEquals(42, (int) ctx.getVariable("count"));

        // 获取不存在的变量
        assertNull(ctx.getVariable("nonexistent"));

        // 带默认值的获取
        assertEquals("default", ctx.getVariable("nonexistent", "default"));

        // 判断变量存在
        assertTrue(ctx.hasVariable("name"));
        assertFalse(ctx.hasVariable("nonexistent"));

        // 删除变量
        ctx.removeVariable("name");
        assertFalse(ctx.hasVariable("name"));
    }

    @Test
    void testFromInstance() {
        // 创建模板
        FlowchartTemplate template = FlowchartTemplate.builder()
                .templateId("test-template")
                .name("Test Template")
                .variables(java.util.List.of(
                        FlowchartTemplate.VariableDef.builder()
                                .name("inputVar")
                                .type("string")
                                .defaultValue("defaultValue")
                                .build()
                ))
                .build();

        // 创建实例
        Map<String, Object> instanceVars = new HashMap<>();
        instanceVars.put("customVar", "customValue");

        FlowchartInstance instance = FlowchartInstance.builder()
                .instanceId("test-instance")
                .templateId("test-template")
                .variables(instanceVars)
                .build();

        // 创建上下文
        ExecutionContext ctx = ExecutionContext.fromInstance(instance, template);

        // 验证实例变量
        assertEquals("customValue", ctx.getVariable("customVar"));

        // 验证模板默认值
        assertEquals("defaultValue", ctx.getVariable("inputVar"));
    }

    @Test
    void testBreakpoints() {
        ExecutionContext ctx = ExecutionContext.builder().build();

        assertFalse(ctx.hasBreakpoint("node1"));

        ctx.addBreakpoint("node1");
        assertTrue(ctx.hasBreakpoint("node1"));

        ctx.removeBreakpoint("node1");
        assertFalse(ctx.hasBreakpoint("node1"));
    }
}
