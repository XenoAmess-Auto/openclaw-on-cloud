package com.ooc.controller;

import com.ooc.entity.MentionRecord;
import com.ooc.entity.UserMentionSettings;
import com.ooc.service.MentionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mentions")
@RequiredArgsConstructor
public class MentionController {

    private final MentionService mentionService;

    @GetMapping("/unread")
    public ResponseEntity<List<MentionRecord>> getUnreadMentions(
            @AuthenticationPrincipal UserDetails userDetails) {
        String userId = getUserIdFromDetails(userDetails);
        return ResponseEntity.ok(mentionService.getUnreadMentions(userId));
    }

    @GetMapping("/history")
    public ResponseEntity<Page<MentionRecord>> getMentionHistory(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = getUserIdFromDetails(userDetails);
        return ResponseEntity.ok(mentionService.getMentionHistory(userId, PageRequest.of(page, size)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            @AuthenticationPrincipal UserDetails userDetails) {
        String userId = getUserIdFromDetails(userDetails);
        long count = mentionService.getUnreadCount(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PostMapping("/{mentionId}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable String mentionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        mentionService.markAsRead(mentionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(
            @AuthenticationPrincipal UserDetails userDetails) {
        String userId = getUserIdFromDetails(userDetails);
        mentionService.markAllAsRead(userId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/settings")
    public ResponseEntity<UserMentionSettings> getSettings(
            @AuthenticationPrincipal UserDetails userDetails) {
        String userId = getUserIdFromDetails(userDetails);
        return ResponseEntity.ok(mentionService.getOrCreateSettings(userId));
    }

    @PutMapping("/settings")
    public ResponseEntity<UserMentionSettings> updateSettings(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody UserMentionSettings settings) {
        String userId = getUserIdFromDetails(userDetails);
        return ResponseEntity.ok(mentionService.updateSettings(userId, settings));
    }

    private String getUserIdFromDetails(UserDetails userDetails) {
        // 从 UserDetails 中提取用户ID
        // 实际项目中可能需要根据 username 查询用户ID
        return userDetails.getUsername();
    }

    @Data
    public static class MarkReadRequest {
        private List<String> mentionIds;
    }
}
