# OpenClaw on Cloud (OOC) 待办事项

**最后更新**: 2026-02-20

---

## 🔴 未完成事项

### 1. 代码质量与可维护性

| 🔴 问题 | 状态 | 风险 |
|--------|------|------|
| ChatView.vue 废弃 | ❌ 未完成 | 文件仍存在，需删除或修复路由映射 |
| TODO 遗留 | ❌ 未完成 | Claude Code API、Kimi 调用、WebSocket 通知 |

#### Phase 1: 代码重构（剩余任务）

- [ ] **完成 HomeView.vue 拆分**
  - [ ] 提取 SessionPanel 组件
  - [ ] 提取 TaskQueuePanel 组件

- [ ] **删除废弃的 ChatView.vue 或修复路由映射**

- [ ] **服务层解耦**
  - [ ] 拆分 ChatWebSocketHandler (2300+行)
  - [ ] 提取 OpenClawResponseParser

- [ ] **完成 TODO**
  - [ ] Claude Code API 集成
  - [ ] Kimi 直接调用实现

---

### 2. 测试覆盖

```
缺失:
├── Controller 测试              ❌ 未完成 (0%)
├── WebSocketHandler 测试        ❌ 未完成 (0%)
├── OpenClawPluginService 测试   ❌ 未完成 (0%)
├── Repository 测试              ❌ 未完成 (0%)
└── E2E 测试自动化               ❌ 未完成
```

#### Phase 2: 测试体系建设

- [ ] **后端测试** (目标: 60% 覆盖率)
  - [ ] Controller 层测试 (WebTestClient)
  - [ ] WebSocket 集成测试
  - [ ] Repository 测试 (@DataMongoTest)
  - [ ] OpenClawPluginService Mock 测试

- [ ] **前端测试**
  - [ ] Playwright E2E 测试

- [ ] **CI/CD 增强**
  - [ ] GitHub Actions 测试阶段
  - [ ] 测试覆盖率报告 (Codecov)

---

### 3. 性能与扩展性

| ⚠️ 问题 | 影响 | 状态 |
|---------|------|------|
| 消息全量加载 | 进入聊天室加载所有历史消息 | ❌ 未完成 |
| WebSocket 单机 | 不支持集群部署，无 Sticky Session | ❌ 未完成 |
| 缺少连接池调优 | MongoDB、HTTP 连接池使用默认配置 | ❌ 未完成 |
| 无消息分页 | 消息量大时性能下降 | ❌ 未完成 |

#### Phase 4: 性能优化

- [ ] **缓存层**
  - [ ] Redis 集成
  - [ ] 用户会话缓存
  - [ ] 聊天记录分页缓存
  - [ ] 热点数据缓存

- [ ] **消息分页**
  - [ ] 后端: 支持 cursor-based 分页
  - [ ] 前端: 虚拟滚动 (vue-virtual-scroller)

- [ ] **数据库优化**
  - [ ] MongoDB 索引优化
  - [ ] 连接池配置调优

- [ ] **WebSocket 集群支持**
  - [ ] Redis Pub/Sub 消息广播

---

### 4. 用户体验功能缺失

| 功能 | 状态 | 优先级 |
|------|------|--------|
| 消息搜索 | ❌ 未完成 | 🔴 高 |
| 消息撤回/编辑 | ❌ 未完成 | 🟡 中 |
| 已读回执 | ❌ 未完成 | 🟡 中 |
| 消息表情回复 | ❌ 未完成 | 🟢 低 |
| 文件预览 (PDF/图片) | ⚠️ 基础支持 | 🟡 中 |
| 消息转发 | ❌ 未完成 | 🟢 低 |

#### Phase 6: 功能增强

- [ ] **消息系统**
  - [ ] 全文搜索 (MongoDB Text Search / Elasticsearch)
  - [ ] 消息撤回/编辑 (5分钟内)
  - [ ] 已读回执

- [ ] **AI 能力**
  - [ ] 完成 Claude Code 集成
  - [ ] RAG 知识库

- [ ] **移动端**
  - [ ] 推送通知 (FCM/APNs)

---

### 5. AI 能力扩展

| 功能 | 状态 |
|------|------|
| Claude Code 集成 | ❌ 未完成 |
| Kimi 直接调用 | ❌ 未完成 |
| 多模态 (图片理解) | ⚠️ 仅基础上传 |
| RAG (知识库) | ❌ 未完成 |

---

### 6. 文档缺失

| 文档 | 状态 |
|------|------|
| API 文档 (OpenAPI/Swagger) | ❌ 未完成 |
| 架构设计文档 | ❌ 未完成 |
| 生产部署指南 | ❌ 未完成 |
| 常见问题排查 | ❌ 未完成 |

---

### 7. 待创建文件

```
docs/
├── API.md                    ❌ 未完成 # OpenAPI/Swagger 文档
├── ARCHITECTURE.md           ❌ 未完成 # 架构设计文档
├── DEPLOYMENT.md             ❌ 未完成 # 生产部署指南
└── TROUBLESHOOTING.md        ❌ 未完成 # 常见问题排查

frontend/src/components/chat/
├── MessageItem.vue           ❌ 未完成
├── SessionPanel.vue          ❌ 未完成
└── TaskQueuePanel.vue        ❌ 未完成

backend/src/test/java/com/ooc/integration/
├── WebSocketIntegrationTest.java     ❌ 未完成
├── OpenClawPluginIntegrationTest.java ❌ 未完成
└── FlowchartExecutionTest.java       ❌ 未完成

monitoring/
├── prometheus.yml            ❌ 未完成
├── grafana-dashboard.json    ❌ 未完成
└── alertmanager.yml          ❌ 未完成
```

---

## 🎯 立即执行建议（本周）

1. **删除/修复废弃的 ChatView.vue** - 避免混淆，防止修改错误文件
2. **添加消息分页** - 解决性能隐患
3. **完成 Claude Code TODO** - 功能完整性

---

## 📊 待办统计

| 类别 | 未完成数 | 优先级 |
|------|----------|--------|
| 代码质量 | 6 | 🔴 高 |
| 测试覆盖 | 8 | 🔴 高 |
| 性能优化 | 11 | 🟡 中 |
| 功能增强 | 6 | 🟡 中 |
| 文档 | 4 | 🟢 低 |
| 待创建文件 | 13 | 🟡 中 |
| **总计** | **48** | - |

---

## ✅ 已完成项目（存档参考）

<details>
<summary>点击展开查看已完成项目</summary>

### 项目现状
- ✅ 核心功能成熟（聊天、OpenClaw集成、流程图、用户系统）
- ✅ 代码规模 21,837 行
- ✅ 移动端完整（Android App + 响应式 Web）
- ✅ 部署自动化（auto-deploy.sh + Docker + CI/CD）

### Phase 1 已完成
- ✅ 提取 MessageList 组件
- ✅ 提取 MessageInput 组件
- ✅ 提取 ChatHeader 组件
- ✅ 提取 RoomSidebar 组件

### Phase 2 已完成
- ✅ Vitest 单元测试（auth.spec.ts）
- ✅ Markdown 渲染测试

### 现有测试文件
```
backend/src/test/java/com/ooc/service/
├── ChatRoomServiceTest.java      ✅
├── FileStorageServiceTest.java   ✅
├── MentionServiceTest.java       ✅
├── OocSessionServiceTest.java    ✅
└── UserServiceTest.java          ✅

frontend/src/stores/__tests__/
└── auth.spec.ts                  ✅

frontend/src/views/__tests__/
├── ChatView.markdown.spec.ts     ✅
└── markdown-render.spec.ts       ✅
```

</details>
