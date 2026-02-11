package com.ooc.controller;

import com.ooc.entity.OocSession;
import com.ooc.service.OocSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final OocSessionService oocSessionService;

    @GetMapping("/chat-room/{roomId}")
    public ResponseEntity<List<OocSession>> getSessionHistory(@PathVariable String roomId) {
        return ResponseEntity.ok(oocSessionService.getSessionHistory(roomId));
    }

    @PostMapping("/{sessionId}/archive")
    public ResponseEntity<OocSession> archiveSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(oocSessionService.archiveSession(sessionId));
    }

    @PostMapping("/{sessionId}/copy")
    public ResponseEntity<OocSession> copySession(
            @PathVariable String sessionId,
            @RequestParam String newChatRoomId) {
        return ResponseEntity.ok(oocSessionService.copySession(sessionId, newChatRoomId));
    }
}
