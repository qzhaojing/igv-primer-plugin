# IGV 3.0 小 PR 候选清单与首个 patch 草案

> 目标：用 3–5 个极小 PR 在 `igvteam/igv` 建立可信度，为后续 primer 模块的 issue/PR 铺路。
> 筛选标准：改动 < 30 行、不动既有架构、**能明确复现**、最好无人认领。
> 官方约束（CLAUDE.md）：极简改动、禁止重构、commit 单行 < 50 字符无正文。

---

## 一、候选清单（按推荐度排序）

| # | Issue | 类型 | 无人认领 | 改动量 | 我们是否懂 | 推荐度 |
|---|---|---|---|---|---|---|
| **1** | **#1597** CommandListener NPE | **Bug（线程死亡）** | ✅ 0 评论 | **~5 行** | ✅ port 60151 我们在用 | ★★★★★ |
| 2 | #1637 Windows 不识别 interact(.inter.bed) | Bug | 4 评论 | 中（待查） | ✅ 我们做 arc/配对线 | ★★★★ |
| 3 | #1632 Session 不保存 Snps 的 Display Mode | Bug | ✅ 0 评论 | 小-中 | ✅ session 持久化我们熟 | ★★★ |
| 4 | #1774 建 FASTA index 时无 GUI 进度提示 | UX 改进 | 1 评论 | 中 | 一般 | ★★★ |
| 5 | 拼写瑕疵 `seperate`/`occured` | 文案 | — | 4 处 | ✅ | ★★（兜底） |

---

## 二、首选 #1597 —— 已定位根因，patch 可直接用

### 2.1 现象
启用 `View > Preferences > Advanced > Enable port` 后，**在 IGV 启动完成前**向 `localhost:60151` 发 `/goto?locus=X:...`，
监听线程直接死亡，此后**任何命令都失效，必须重启 IGV**。

### 2.2 根因链（已逐行验证 main 分支源码）

`src/main/java/org/igv/batch/CommandListener.java`

```java
// 第 109-143 行 run() —— 只捕获 IOException 系列
public void run() {
    try {
        CommandExecutor cmdExe = new CommandExecutor(IGV.getInstance());
        serverSocket = new ServerSocket(port);
        while (!halt) {
            clientSocket = serverSocket.accept();
            processClientSession(cmdExe);      // ← NPE 从这里逃出
            ...
        }
    } catch (java.net.BindException e) { ... }
      catch (ClosedByInterruptException e) { ... }
      catch (IOException e) { ... }            // ← 接不住 RuntimeException！
}
```

```java
// 第 348-356 行 processGet() —— 无条件解引用，无 null 检查
private String processGet(String command, Map<String,String> params, CommandExecutor cmdExe) throws IOException {
    String result = OK;
    final Frame mainFrame = IGV.getInstance().getMainFrame();   // ← 启动早期返回 null
    mainFrame.toFront();                                        // ← NPE！
    mainFrame.setAlwaysOnTop(true);
    mainFrame.setAlwaysOnTop(false);
```

**完整链条**：
`mainFrame == null` → NPE（RuntimeException）→ `processGet` 未捕获 → `processClientSession` 未捕获 →
`run()` 的三个 catch 全是 IOException 分支，**接不住 RuntimeException** →
异常逃出 `while(!halt)` → **监听线程终止** → 端口再无监听 → 永久失效，只能重启。

> 注意：这条链说明**不只是 `/goto`**——启动早期发**任何**命令（连 `/ping`、`/version` 也一样）
> 都会走到 351 行的裸解引用，都会杀死监听线程。这是个"整类问题"，价值高于单个命令的修复。

### 2.3 patch 草案（拆成两个 PR，每个都极小）

**PR-A（治本，推荐先提这个，5 行）** —— 让监听线程不再被任何异常杀死：

```java
        while (!halt) {
            clientSocket = serverSocket.accept();
            try {
                processClientSession(cmdExe);
            } catch (RuntimeException e) {
                log.error("Error processing command", e);
            }
            if (clientSocket != null) {
```

**PR-B（治标，可随后提，4 行）** —— 消除 NPE 本身的来源：

```java
        final Frame mainFrame = IGV.getInstance().getMainFrame();
        if (mainFrame != null) {
            // Trick to force window to front, the setAlwaysOnTop works on a Mac, toFront() does nothing.
            mainFrame.toFront();
            mainFrame.setAlwaysOnTop(true);
            mainFrame.setAlwaysOnTop(false);
        }
```

**两个 PR 都满足**：不动既有架构、不重构、不加依赖、改 3–6 行。
PR-A 尤其符合官方 CLAUDE.md 的 "minimalist" 口味——它一行 try 解决一整类崩溃。

### 2.4 复现步骤（写进 PR 描述，让 reviewer 一发即中）

1. `View > Preferences > Advanced` 勾选 `Enable port`
2. 退出 IGV
3. 起一个脚本每 200ms 向 `localhost:60151` 发 `GET /goto?locus=X:67545317`
4. 启动 IGV → 观察日志出现 `SEVERE ... NullPointerException`，之后端口不再响应

---

## 三、其余候选的评估

### #1637 — Windows 不识别 interact 格式 .bed（推荐度 ★★★★）
- 与我们的工作高度相关（arc / 配对连线就是 interact 语义）
- 3.0 有完整 `org/igv/bedpe/` 包（BedPE/BedPEParser/BedPERenderer/BedPESource…），说明功能在，
  问题大概率出在**文件扩展名→格式的识别分支**（Windows 下 `.inter.bed` 后缀匹配失败）
- 有 4 条评论，**先读完评论确认官方没已在修**，再动手
- 改动量取决于识别逻辑，可能 10–30 行，仍算小 PR

### #1632 — Session 不保存 Snps 的 Display Mode（★★★）
- 0 评论，无人管
- 相关代码：`org/igv/session/`（JSONSessionWriter / XMLSessionReader / SessionAttribute）
- 需先定位 VCF/Snps track 的 displayMode 属性是否漏了序列化/反序列化
- 我们做过 BED 元数据（`|r` 行号、`|g` 分组）的持久化，这类"属性漏存"问题手到擒来

### #1774 — 建 FASTA index 时无 GUI 提示（★★★）
- 是 UX 建议不是 bug，被合的不确定性更高
- 但需求真实（大基因组建索引时像卡死，中途关闭会留 corrupted .fai）
- 若官方已有 `LongRunningTask` / 进度条基础设施，加一个提示可能只要十几行

### 兜底 — 拼写瑕疵（★★）
`seperate`（2 处：`org/igv/ucsc/bb/BedData.java`、`org/igv/ultima/render/ColorByTagValueList.java`）
`occured`（2 处：`org/igv/ui/DefaultExceptionHandler.java`、`org/igv/tdf/TDFReader.java`）
> CONTRIBUTING.md 明确"cosmetic 改动仍然欢迎"，但**先确认是注释/变量名还是用户可见文案**——
> 只改用户可见文案才有意义，改注释容易被判为噪音。**优先级最低，仅作凑数用。**

---

## 四、提交流程（严格照官方规矩）

```bash
# 1. fork 后
git clone https://github.com/<you>/igv.git && cd igv
git remote add upstream https://github.com/igvteam/igv.git
git checkout -b fix/command-listener-npe

# 2. 改代码（3-6 行）

# 3. commit：单行 < 50 字符，无正文（CLAUDE.md 强制）
git commit -m "Fix CommandListener thread death on NPE"

# 4. 跑测试
./gradlew test

# 5. 提 PR
gh pr create --repo igvteam/igv \
  --title "Fix CommandListener thread death on NPE" \
  --body-file pr_body.md
```

### PR 描述模板（英文，务必带 issue 号）

```markdown
Fixes #1597

`CommandListener.run()` catches only `BindException`, `ClosedByInterruptException` and
`IOException`. A `RuntimeException` thrown while processing a command (e.g. the NPE at
`CommandListener.processGet` line 354, where `IGV.getInstance().getMainFrame()` is still
null during startup) escapes the `while (!halt)` loop and terminates the listener thread.
After that the port is silent until IGV is restarted.

This wraps `processClientSession` in a `RuntimeException` catch so an unexpected error
drops the single client connection instead of killing the listener.

Reproduce:
1. Enable port (View > Preferences > Advanced > Enable port), quit IGV
2. Send `GET /goto?locus=X:67545317` to localhost:60151 every 200ms
3. Start IGV -> SEVERE NullPointerException in log, port stops responding
```

### 三条纪律（决定你能不能混成脸熟）

1. **先评论 issue**：`"I can take a look at this — will submit a small PR."` 避免和别人撞车
2. **一个 PR 只做一件事**，绝不顺手改格式/重命名（CLAUDE.md 明文禁止重构）
3. **commit 单行 < 50 字符、无正文**；PR 标题同样极简

---

## 五、建议的推进节奏

| 周次 | 动作 |
|---|---|
| 第 1 周 | 在 #1597 下留言认领 → 提 **PR-A**（5 行治本）→ 再提 **PR-B**（4 行治标） |
| 第 2–3 周 | 读 #1637 全部评论，若在修就换 #1632；提第 3 个 PR |
| 第 4–6 周 | 第 4–5 个 PR（#1774 或日常使用中自己发现的 bug） |
| 之后 | 名字在 contributors 里出现 5 次后再开 primer 模块的 issue —— 此时 jrobinso 已认识你 |

**关键点**：不要一上来就提 primer。先用 3–5 个"一看就该合"的小修证明你懂规矩、懂代码，
再把大功能摆上桌。这是最小阻力的路径。
