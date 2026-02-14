# TOOLS.md - Local Notes

Skills define _how_ tools work. This file is for _your_ specifics — the stuff that's unique to your setup.

## What Goes Here

Things like:

- Camera names and locations
- SSH hosts and aliases
- Preferred voices for TTS
- Speaker/room names
- Device nicknames
- Anything environment-specific

## Examples

```markdown
### Cameras

- living-room → Main area, 180° wide angle
- front-door → Entrance, motion-triggered

### SSH

- home-server → 192.168.1.100, user: admin

### TTS

- Preferred voice: "Nova" (warm, slightly British)
- Default speaker: Kitchen HomePod
```

## Why Separate?

Skills are shared. Your setup is yours. Keeping them apart means you can update skills without losing your notes, and share skills without leaking your infrastructure.

---

Add whatever helps you do your job. This is your cheat sheet.

## User Preferences

- 每次代码修改后需 commit 并 push

## OOC 项目文件结构备忘

### 路由 → 视图映射（关键！修改前必须确认）

```
/                    → HomeView.vue
/chat/:roomId        → HomeView.vue  （⚠️ 不是 ChatView.vue！）
/admin               → AdminView.vue
/login               → LoginView.vue
/register            → RegisterView.vue
```

**重要提示：** 
- `ChatView.vue` 存在但**没有被路由直接使用**
- 所有聊天功能实际在 `HomeView.vue` 中实现
- 修改消息渲染相关代码时，改 `HomeView.vue`！

### 关键函数位置

| 功能 | 文件 | 函数名 |
|------|------|--------|
| 消息内容渲染 | HomeView.vue | `renderContent()` |
| Markdown 解析 | HomeView.vue | 使用 `marked` 库 |
| XSS 清理 | HomeView.vue | `DOMPurify.sanitize()` |
| 消息列表 | HomeView.vue | `messages` computed |

### 修改前检查清单

1. 确认路由映射：`cat frontend/src/router/index.ts`
2. 确认函数位置：`grep -rn "function renderContent" frontend/src/views/`
3. 确认模板使用：`grep -rn "v-html.*renderContent" frontend/src/views/`