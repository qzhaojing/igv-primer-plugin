# 引物插件多人实时协作 — POC 设计规格

> 状态：待评审（用户选定方向：局域网内网 + 协作作为可选模式 + 先 POC）
> 目标：验证「多人实时改/移引物、谁在编辑实时可见、修改有日志」的可行性，最低成本跑通。

---

## 1. 目标与非目标

**目标（POC 必须覆盖）**
- 局域网内 2+ 个 IGV 实例共享同一份引物集。
- 任一端增删/改名/移动引物，其他端毫秒级可见并重绘。
- 实时显示「谁在线、谁的光标在哪、谁正在拖哪条引物」。
- 每个操作落审计日志（谁、何时、改了哪条、前后值）。

**非目标（POC 不做）**
- 不做账号/权限系统（用首次加入时填的昵称 + 随机颜色）。
- 不做公网部署、不做 TLS（局域网内明文 WS）。
- 不做 CRDT 二进制协议（用自定义 JSON + 后端 last-writer-wins 合并）。
- 不做历史回放/撤销远程操作（仅本地撤销）。

---

## 2. 架构

```
┌─────────────┐   WS JSON    ┌──────────────────────────────┐
│ IGV 插件 A  │◄───────────►│  collab-server.js (Node+ws)   │
│ (Java8)     │             │  - 广播消息给所有客户端        │
└─────────────┘             │  - LWW 合并 primers 状态      │
       ▲                    │  - 落盘 collab.log (JSONL)    │
       │ 同机/同网           │  - 维护在线 presence 列表     │
┌──────┴──────┐             └──────────────────────────────┘
│ IGV 插件 B  │◄───────────►  （同上，可 N 个客户端）
└─────────────┘
```

- **后端**：独立 Node 进程，跑在局域网一台机器（可即插件所在机），监听如 `ws://<host>:1234`。
- **插件端**：IGV 插件新增协作模块，连 WS、收发 JSON、回放远端操作。
- **默认离线**：不点「协作」按钮时，行为完全等同现在（本地文件），零网络依赖。

---

## 3. 数据模型

### 3.1 Primer（共享引物）
| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 全局唯一（UUID），操作寻址主键 |
| `name` | string | 引物名 |
| `chr` | string | 染色体 |
| `start` | int | 起始坐标 |
| `end` | int | 终止坐标 |
| `strand` | string | `+` / `-` |
| `pairWith` | string? | 配对对端引物 id（对应现有 pairWith） |
| `color` | string? | 显示色（可选） |
| `owner` | string | 创建者昵称（用于归属显示） |

服务端维护 `Map<id, Primer>` 作为权威状态；新客户端加入时全量下发。

### 3.2 Presence（在线/光标，不持久化）
| 字段 | 类型 | 说明 |
|---|---|---|
| `user` | string | 昵称 |
| `color` | string | 分配色 |
| `cursorBp` | int? | 当前鼠标所在碱基位置（可空） |
| `draggingId` | string? | 正在拖动的引物 id（可空） |
| `ts` | int | 心跳时间戳 |

### 3.3 LogEntry（审计日志，持久化 JSONL）
见 §7。

---

## 4. 传输协议（WebSocket + JSON）

连接：`ws://<host>:1234?user=<nick>&color=<hex>`
心跳：客户端每 5s 发 `presence`；服务端 15s 无心跳判离线。

### 4.1 消息类型总表
| `type` | 方向 | 用途 |
|---|---|---|
| `hello` | C→S | 加入，带 user/color |
| `snapshot` | S→C | 全量 primers 下发（新客户端加入时） |
| `op` | C→S / S→C | 引物增删改移操作 |
| `presence` | C→S / S→C | 光标/在线/拖动状态 |
| `presence-list` | S→C | 当前在线用户列表 |
| `log` | S→C（可选） | 新日志条目推送（供 UI 实时显示） |
| `bye` | C→S / S→C | 离开 |

### 4.2 `op` 消息详表
```json
{
  "type": "op",
  "user": "zhao",
  "ts": 1696123456789,
  "op": "add" | "edit" | "move" | "delete" | "pair",
  "primer": { "id": "...", "name": "...", "chr": "...", "start": 1, "end": 2, "strand": "+", "pairWith": null, "owner": "zhao" }
}
```
- `add`：primer 为完整对象，服务端插入 Map。
- `edit`：primer 含 `id` + 需改的字段（如 `{id, name}`），服务端按字段合并。
- `move`：primer 含 `id` + 新 `start/end`，服务端更新坐标。
- `delete`：primer 仅含 `id`，服务端移除。
- `pair`：primer 含 `id` + `pairWith`（对端 id 或 null 取消），服务端更新配对。

### 4.3 `presence` 消息
```json
{ "type": "presence", "user": "zhao", "cursorBp": 123456, "draggingId": "p-xxx" }
```

---

## 5. 后端行为（collab-server.js）

1. **连接建立**：解析 query 的 user/color；分配并广播 `presence-list`。
2. **收到 `op`**：
   - 按 §4.2 更新权威 `Map<id,Primer>`（字段级 LWW：同字段后到覆盖先到）。
   - 追加一行 `LogEntry` 到 `collab.log`（见 §7）。
   - 将 `op` 转发给所有**其他**客户端。
3. **收到 `presence`**：更新该 user 的 presence；定期（或变更时）广播 `presence-list` / 转发 `presence`。
4. **断开**：从在线列表移除，广播 `presence-list`，可选追加 `bye` 日志。
5. **持久化**：`primers` 状态定期落 `primers.json`（崩溃恢复）；`collab.log` 每行追加。

---

## 6. 插件端设计（PrimerCollab.java，新增模块）

**职责**
- 管理 WS 连接生命周期（connect / disconnect / 自动重连退避）。
- 本地操作拦截：当处于协作模式时，`PrimerStore` 的 add/edit/move/delete/pair 同时调用 `PrimerCollab.sendOp(...)`。
- 远端消息处理：收到 `op` → 乐观回放进 `PrimerStore`（带 `remote=true` 标记，避免回声重发）→ `repaint()`；收到 `presence` → 更新本地 `remoteCursors` 绘制层。
- 维持 `presence` 心跳（光标 bp 由当前鼠标位置换算；拖动时带 `draggingId`）。

**与现有代码集成点**
- `PrimerStore`：增删改/移动/配对方法加 `if (PrimerCollab.connected()) sendOp(...)` 钩子；新增 `applyRemote(op)` 方法（与本地改动共用同一套校验，只是不发回网络）。
- `PrimerTrack.render()`：在引物绘制之上叠加 `remoteCursors`（他人虚线框 + 昵称 + 颜色）。
- `PrimerEditTool`：拖动开始时设 `draggingId` 并随 move 事件更新 `presence`。
- UI：track 顶部工具条加「协作」按钮（参考现有 `TOOLBAR` 机制）。

**避免回声**：每个本地发出/收到的 op 带 `user`；回放远端 op 时不重新 sendOp（用 `remote=true` 分支）。

---

## 7. 审计日志格式（collab.log，JSONL）

每行一条：
```json
{"ts":1696123456789,"user":"zhao","op":"move","targetId":"p-abc","before":{"start":100,"end":120},"after":{"start":100,"end":125}}
{"ts":1696123460000,"user":"li","op":"add","targetId":"p-def","after":{"name":"F3","chr":"chr1","start":500,"end":520,"strand":"+"}}
{"ts":1696123465000,"user":"zhao","op":"pair","targetId":"p-abc","after":{"pairWith":"p-def"}}
```
- `before` 仅在 edit/move/delete 时存在；`after` 在 add/edit/move/pair 时存在。
- UI「日志」面板可分页加载该文件末尾 N 行实时显示。

---

## 8. UI 设计

- **工具条「协作」按钮**：点击 → 弹昵称输入（默认上次昵称）→ 连接。再点 → 断开。
- **在线列表**：track 角落小面板，列出 `presence-list` 中的 user + 色块。
- **他人光标/拖动**：`draggingId` 非空时，该引物绘制对方颜色虚线框 + 昵称悬浮标签；`cursorBp` 可选画一条细竖线。
- **日志面板**：可折叠，显示 `collab.log` 末尾若干条，新日志实时追加。

---

## 9. 冲突处理策略

- **同字段并发写**：LWW（后到覆盖先到），由服务端按 `ts` 仲裁。
- **一人删、一人改同条**：删除优先（改操作在已删 id 上无效，记一条 warn 日志）。
- **移动 vs 改名并发**：不同字段，各自独立生效（字段级合并）。
- POC 阶段不保证「两人同时拖同一条」的完美体验，靠 LWW + 视觉提示（看到对方也在拖）规避。

---

## 10. 部署与运行

```bash
# 后端（需 Node ≥ 18）
cd collab/ && npm i ws && node collab-server.js --port 1234

# 插件端
# IGV 顶部工具条点「协作」→ 填昵称 → 连 ws://<同一局域网IP>:1234
```
- 后端可跑在插件所在机（localhost），也可跑在内网任意机。
- 防火墙放行 1234/TCP。

---

## 11. 风险与后续升级

| 风险 | 缓解 |
|---|---|
| 自定义 JSON 非真 CRDT，极端并发可能短暂不一致 | POC 仅几人局域网；LWW + 视觉提示足够；后续可换 Yjs 桥接 |
| Java8 无 Yjs 客户端 | 已规避：插件只发 JSON，CRDT 复杂度封在后端（若升级） |
| 后端单点 | POC 可接受；崩溃后由 `primers.json` 恢复 |
| 大引物集全量快照卡顿 | POC 规模小；后续可改增量快照 |

**升级路线（非 POC）**：若需真·无冲突合并，后端改用 Yjs（`y-websocket`），插件侧加一个本地 Node 桥接进程对接 Yjs，Java 仍只说 JSON——零改 CRDT 编解码。

---

## 12. 里程碑（实施分解）

1. **M1 后端 skeleton**：`collab-server.js` 起 WS、收 op 广播、落 `collab.log`、presence 列表。（~150 行）
2. **M2 插件连接**：`PrimerCollab.java` 连/断/重连 + 发 `op`，收到 `op` 回放 PrimerStore。（~300 行）
3. **M3 UI 接入**：工具条「协作」按钮 + 昵称 + 在线列表 + 他人光标/拖动高亮 + 日志面板。（~200 行）
4. **M4 联调**：同机两个 IGV 互相同步 + 看日志验证。

---

## 13. 开放问题（待你确认）

1. 后端跑在哪：插件同机（localhost）还是独立内网服务器？
2. 引物集初始来源：加入协作时从谁的状态全量拉取？还是从一个共享文件/空开始？
3. 昵称/颜色：首次手填即可，还是要有简单持久化（本机存上次昵称）？
4. 退出协作时：本地引物是否保留（合并进本地文件）还是仅内存、退出即丢？
