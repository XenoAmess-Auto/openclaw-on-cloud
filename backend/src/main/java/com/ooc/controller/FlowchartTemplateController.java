package com.ooc.controller;

import com.ooc.entity.User;
import com.ooc.entity.flowchart.FlowchartTemplate;
import com.ooc.service.UserService;
import com.ooc.service.flowchart.FlowchartTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程图模板控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/flowchart-templates")
@RequiredArgsConstructor
public class FlowchartTemplateController {

    private final FlowchartTemplateService templateService;
    private final UserService userService;

    /**
     * 创建模板
     */
    @PostMapping
    public ResponseEntity<?> createTemplate(@RequestBody FlowchartTemplate template,
                                           Authentication authentication) {
        try {
            String username = authentication.getName();
            User user = userService.getUserByUsername(username);
            FlowchartTemplate created = templateService.createTemplate(template, user.getId());
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            log.error("Failed to create template", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取模板列表
     */
    @GetMapping
    public ResponseEntity<?> listTemplates(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            List<FlowchartTemplate> templates;

            if (roomId != null) {
                templates = templateService.listTemplatesForRoom(roomId);
            } else if (category != null) {
                templates = templateService.listTemplatesByCategory(category);
            } else {
                Page<FlowchartTemplate> pageResult = templateService.listTemplates(PageRequest.of(page, size));
                templates = pageResult.getContent();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("templates", templates);
            response.put("total", templates.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to list templates", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取模板详情
     */
    @GetMapping("/{templateId}")
    public ResponseEntity<?> getTemplate(@PathVariable String templateId) {
        try {
            FlowchartTemplate template = templateService.getTemplate(templateId);
            return ResponseEntity.ok(template);
        } catch (Exception e) {
            log.error("Failed to get template: {}", templateId, e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 更新模板
     */
    @PutMapping("/{templateId}")
    public ResponseEntity<?> updateTemplate(@PathVariable String templateId,
                                           @RequestBody FlowchartTemplate updates,
                                           Authentication authentication) {
        try {
            String username = authentication.getName();
            User user = userService.getUserByUsername(username);
            FlowchartTemplate updated = templateService.updateTemplate(templateId, updates, user.getId());
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            log.error("Failed to update template: {}", templateId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除模板
     */
    @DeleteMapping("/{templateId}")
    public ResponseEntity<?> deleteTemplate(@PathVariable String templateId) {
        try {
            templateService.deleteTemplate(templateId);
            return ResponseEntity.ok(Map.of("message", "Template deleted"));
        } catch (Exception e) {
            log.error("Failed to delete template: {}", templateId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取版本历史
     */
    @GetMapping("/{templateId}/versions")
    public ResponseEntity<?> getVersionHistory(@PathVariable String templateId) {
        try {
            List<FlowchartTemplate> versions = templateService.getVersionHistory(templateId);
            return ResponseEntity.ok(Map.of("versions", versions));
        } catch (Exception e) {
            log.error("Failed to get version history: {}", templateId, e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取特定版本
     */
    @GetMapping("/{templateId}/versions/{version}")
    public ResponseEntity<?> getTemplateVersion(@PathVariable String templateId,
                                               @PathVariable int version) {
        try {
            FlowchartTemplate template = templateService.getTemplateVersion(templateId, version);
            return ResponseEntity.ok(template);
        } catch (Exception e) {
            log.error("Failed to get template version: {}.{}", templateId, version, e);
            return ResponseEntity.notFound().build();
        }
    }
}
