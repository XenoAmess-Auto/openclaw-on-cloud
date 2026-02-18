package com.ooc.controller;

import com.ooc.entity.User;
import com.ooc.entity.flowchart.FlowchartInstance;
import com.ooc.service.UserService;
import com.ooc.service.flowchart.FlowchartInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程图实例控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/flowchart-instances")
@RequiredArgsConstructor
public class FlowchartInstanceController {

    private final FlowchartInstanceService instanceService;
    private final UserService userService;

    /**
     * 创建并启动实例
     */
    @PostMapping
    public ResponseEntity<?> createAndStart(@RequestBody CreateInstanceRequest request,
                                           Authentication authentication) {
        try {
            String username = authentication.getName();
            User user = userService.getUserByUsername(username);
            FlowchartInstance instance = instanceService.createAndStart(
                    request.getTemplateId(),
                    request.getRoomId(),
                    user.getId(),
                    user.getUsername(),
                    request.getVariables()
            );
            return ResponseEntity.ok(instance);
        } catch (Exception e) {
            log.error("Failed to create instance", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取实例详情
     */
    @GetMapping("/{instanceId}")
    public ResponseEntity<?> getInstance(@PathVariable String instanceId) {
        try {
            FlowchartInstance instance = instanceService.getInstance(instanceId);
            return ResponseEntity.ok(instance);
        } catch (Exception e) {
            log.error("Failed to get instance: {}", instanceId, e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 获取房间实例列表
     */
    @GetMapping
    public ResponseEntity<?> listInstances(
            @RequestParam String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            List<FlowchartInstance> instances = instanceService
                    .listInstancesByRoom(roomId, PageRequest.of(page, size))
                    .getContent();

            Map<String, Object> response = new HashMap<>();
            response.put("instances", instances);
            response.put("runningCount", instances.stream()
                    .filter(i -> i.getStatus() == FlowchartInstance.ExecutionStatus.RUNNING)
                    .count());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to list instances", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 停止实例
     */
    @PostMapping("/{instanceId}/stop")
    public ResponseEntity<?> stopInstance(@PathVariable String instanceId) {
        try {
            instanceService.stopInstance(instanceId);
            return ResponseEntity.ok(Map.of("message", "Instance stopped"));
        } catch (Exception e) {
            log.error("Failed to stop instance: {}", instanceId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除实例
     */
    @DeleteMapping("/{instanceId}")
    public ResponseEntity<?> deleteInstance(@PathVariable String instanceId) {
        try {
            instanceService.deleteInstance(instanceId);
            return ResponseEntity.ok(Map.of("message", "Instance deleted"));
        } catch (Exception e) {
            log.error("Failed to delete instance: {}", instanceId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取实例日志
     */
    @GetMapping("/{instanceId}/logs")
    public ResponseEntity<?> getInstanceLogs(@PathVariable String instanceId) {
        try {
            List<FlowchartInstance.NodeExecution> logs = instanceService.getInstanceLogs(instanceId);
            return ResponseEntity.ok(Map.of("logs", logs));
        } catch (Exception e) {
            log.error("Failed to get instance logs: {}", instanceId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取实例变量
     */
    @GetMapping("/{instanceId}/variables")
    public ResponseEntity<?> getInstanceVariables(@PathVariable String instanceId) {
        try {
            Map<String, Object> variables = instanceService.getInstanceVariables(instanceId);
            return ResponseEntity.ok(variables);
        } catch (Exception e) {
            log.error("Failed to get instance variables: {}", instanceId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 创建实例请求
     */
    @lombok.Data
    public static class CreateInstanceRequest {
        private String templateId;
        private String roomId;
        private Map<String, Object> variables;
    }
}
