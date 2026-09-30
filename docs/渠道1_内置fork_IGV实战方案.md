# 渠道1：源码内置 fork ——「我会不会成为 IGV 维护者」与实操路线

> 结论先行：**fork 不等于维护者，也不等于"官方主页看得到你"。**
> 只有 **PR 被 merge** 才算 contributor；只有被授予写权限才算 maintainer（基本只给 Broad/UCSD 团队）。
> 好消息：IGV 对外部 PR **确实开放**（实测最近 100 个关闭 PR 中 88 个被合并，其中 **19 个来自外部贡献者**）。
> 坏消息：整个 primer 模块被合入的概率不高；**先靠小 PR 建立信任，再谈功能**是可行路径。

---

## 一、「维护者」这三个字的三个层级

| 层级 | 怎么得到 | 名字出现在哪 | 代价 | 你现在的距离 |
|---|---|---|---|---|
| **Fork owner** | 点一下 GitHub 的 Fork 按钮 | 只在你自己主页 | 0 | 0 秒，但这不产生任何声望 |
| **Contributor（贡献者）** | PR 被 merge | 仓库 `Contributors` 图谱、commit 历史、release notes 致谢 | 中 | **可达**，需要 PR 通过 review |
| **Committer / Maintainer** | 官方授予写权限 | 团队页、issue 指派 | 极高 | 极少给外部人，需要长期稳定贡献 |

**所以"官方主页看得到我"成立的条件只有一个：PR 被 merge。** fork 本身没人看得见。

### 外部贡献的真实数据（gh api 实测，2026-09-30）

最近 100 个已关闭 PR：

- 合并 **88** 个（合并率 88%，相当友好）
- 其中作者本人 `jrobinso` **68** 个（占 77%，说明是单人主导项目）
- **外部贡献者 19 个**，分散在 13 个人：`kojix2`(3)、`sjl`(3)、`lbergelson`(3)、`parambath92`(2)、`bsgarciac`、`armintoepfer`、`amwenger`、`sloth-eat-pudding`、`fo40225`、`kirschem-roche`、`dror27`、`gokalpcelik`(各1)

**判断**：这是个"一人主导但接受外援"的项目。修 bug、小改进几乎必合；**新增一个大功能模块要看 jrobinso 是否认为它属于 IGV 的核心使命**。

---

## 二、成本账（先看清楚再决定）

### 2.1 技术成本：比想象的小

**关键实测：我们插件只依赖 IGV 的 15 个 API。**

我们 `src/` 共 2556 行 Java、41 个类，其中对 IGV 的 import 只有这 15 个：

| API | 在 IGV 3.0 (main) 中 | 说明 |
|---|---|---|
| `feature.genome.Genome` | ✅ 存在 | 仅包名 `org.broad.igv` → `org.igv` |
| `feature.genome.GenomeManager` | ✅ 存在 | 同上 |
| `track.AbstractTrack` | ✅ 存在 | 同上 |
| `track.RenderContext` | ✅ 存在 | 同上 |
| `track.TrackClickEvent` | ✅ 存在 | 同上 |
| `ui.AbstractDataPanelTool` | ✅ 存在 | 同上 |
| `ui.IGV` | ✅ 存在 | 同上 |
| `ui.panel.DataPanel` | ✅ 存在 | 同上 |
| `ui.panel.IGVPopupMenu` | ✅ 存在 | 同上 |
| `ui.panel.PanTool` | ✅ 存在 | 同上 |
| `ui.panel.ReferenceFrame` | ✅ 存在 | 同上 |
| `ui.panel.TrackPanel` | ✅ 存在 | 同上 |
| `util.LongRunningTask` | ✅ 存在 | 同上 |
| **`dev.api.IGVPlugin`** | ❌ **已删除** | 就是被砍掉的插件接口，改用内置模式 |
| **`ui.PanelName`** | ❌ **已消失** | 全树无同名，需另找替代或去掉该依赖 |

**结论：13/15 原封不动，只是包名换了 → 改名是机械的（sed 一遍）。真正要动手的只有 2 个点。**

### 2.2 3.0 里有一个现成的内置模块样板（照抄即可）

`src/main/java/org/igv/tools/motiffinder/MotifFinderPlugin.java`（3.0 唯一内置的 "Plugin"）：

```java
package org.igv.tools.motiffinder;

public class MotifFinderPlugin {
    /** 菜单项入口 —— 官方在菜单栏挂它 */
    public static JMenuItem getMenuItem() {
        JMenuItem menuItem = new JMenuItem("Find Motif...");
        menuItem.addActionListener(e -> {
            MotifFinderDialog dialog = new MotifFinderDialog(IGV.getInstance().getMainFrame());
            dialog.setVisible(true);
            handleDialogResult(dialog);
        });
        return menuItem;
    }
    static void handleDialogResult(MotifFinderDialog dialog) { ... }
    static List<Track> addTracksForPatterns(...) {
        List<Track> trackList = generateTracksForPatterns(...);
        IGV.getInstance().addTracks(trackList);          // 加轨道
        IGV.getInstance().scrollToTracks(trackList);
        return trackList;
    }
}
```

**我们的 primer 就照这个模式做**：`org.igv.tools.primer.PrimerPlugin`，暴露 `getMenuItem()`，菜单栏加一行。
不用插件机制，不用 `builtin_plugin_list.txt`，**官方自己就是这么内置功能的**。

### 2.3 环境成本（你目前缺的）

| 项 | 现状 | 需要 |
|---|---|---|
| JDK | 只有 `/f/zhaojing/jdk8`（Java 8） | **Java 21**（3.0 强制 `toolchain`） |
| 构建 | javac + 手写脚本 | Gradle（`./gradlew createDist`） |
| 源码规模 | — | 940 个 java 文件 / 2471 个仓库文件，首次 clone + 依赖下载 |
| 网络 | 国内 | Gradle 依赖下载可能慢，需配镜像或代理 |

### 2.4 长期维护成本（这才是真正的"维护者"代价）

- 3.0 目前是 **beta**，API 每周都可能变 → 你需要定期 rebase
- 上游每年 2–4 次较大重构，每次要重新验证 41 个类
- 想公开发布还得处理：**macOS 签名证书**（否则 Gatekeeper 拦截）、Windows 打包、三平台 dist
- 商标：IGV 是 MIT（`Copyright 2007–2026 Broad Institute and Regents of the University of California`），**代码可自由 fork/分发，但"IGV"名称/标识归 Broad**——你的发行版必须改名（如 `IGV-PrimerEdition`），并在 README 明确标注 fork 来源

**一句话：fork 的代价不是"写代码"，是"长期跟上游 + 三平台打包 + 改名合规"。**

---

## 三、收益账（你确实能赚到什么）

1. **PR merge → Contributor 身份**：真实、可写进简历/基金申请，IGV 是生信领域基础设施级工具
2. **发一篇 Application Note**：IGV 背书的引物设计模块，投 *Bioinformatics* / *BMC Bioinformatics* 有说服力
3. **技术资产不再被官方砍**：内置进源码后，不会再出现"2.4.14 删接口、3.0 不恢复"这种事
4. **拿到官方暗色模式**：3.0 有 `FlatDarkLaf.properties`，这是唯一官方暗色路径

---

## 四、怎么做：分四阶段（第一阶段今天就能做，零成本）

### 阶段 0：开 issue 试探官方态度（**本周就做，1 小时**）

`CONTRIBUTING.md` 明文规定：

> **Do you intend to add a new feature or change an existing one?**
> *Suggest your change by creating a Github issue to open a discussion.*

**这一步不做，后面全白干。** 下面是可直接粘贴的 issue 草稿：

```markdown
Title: Feature proposal: primer design track (editable primer pairs with amplicon arc)

Hi Jim,

We have built a primer-design track for IGV (working prototype on 2.3.80 via the
legacy IGVPlugin mechanism, 41 classes / ~2.5k LOC):

- Add/move/delete primers interactively on the sequence; arrow keys for 1bp nudging
- F(+)/R(-) pairing with bezier amplicon arcs and PCR product length labels (1-to-many supported)
- Tm / GC / hairpin / dimer metrics, per-primer and bulk editing
- BED import/export that preserves layout (row assignment persisted in the name field)

Since `IGVPlugin` was removed in 3.0, we would like to contribute it as a built-in
module under `org.igv.tools.primer`, following exactly the MotifFinderPlugin pattern
(static getMenuItem() + IGVMenuBar entry).

It depends on 15 IGV APIs, 13 of which exist unchanged in 3.0 (only the package
rename org.broad.igv -> org.igv); IGVPlugin and PanelName would be dropped/replaced.

Question: is a primer-design module in scope for IGV, or would you prefer it stay
an external tool? Happy to split it into small reviewable PRs either way.

Thanks!
```

**三种回应与对策：**

| 回应 | 对策 |
|---|---|
| 欢迎，提 PR 吧 | 进阶段 2，拆成小 PR 逐步合 |
| 不在 IGV 范围内 | 进阶段 3（自建发行版），但**你已经留下了公开记录**，仍是资产 |
| 不回 / 婉拒 | 同上；同时转渠道 2+4 做独立工具 |

### 阶段 1：先做 3–5 个小 PR 建立信任（并行，1–2 个月）

在你日常用 IGV 的过程中，遇到任何 bug / 小缺失就提 PR：

- 必须是**最小改动**（`CLAUDE.md` 明确：*Minimalist Changes — fewest lines necessary*；*DO NOT rewrite/refactor working code*）
- commit message 极简：**单行 < 50 字符，无正文**（`CLAUDE.md` 明文要求，别写多行中文总结）
- PR 描述里带 issue 号
- 目标：成为 `kojix2`、`sjl` 那样的"熟悉面孔"，之后提大模块才有人理

### 阶段 2：fork 3.0 + 内置 primer 模块（2–4 周）

```bash
# 1) 装 Java 21（必需）
# 2) fork + clone
git clone https://github.com/<you>/igv.git && cd igv
git remote add upstream https://github.com/igvteam/igv.git

# 3) 建分支
git checkout -b feature/primer-track

# 4) 放模块（照抄 MotifFinderPlugin 模式）
mkdir -p src/main/java/org/igv/tools/primer
cp -r <我们的src>/org/broad/igv/primer/*.java src/main/java/org/igv/tools/primer/

# 5) 机械改名：org.broad.igv -> org.igv
grep -rl 'org\.broad\.igv' src/main/java/org/igv/tools/primer/ \
  | xargs sed -i 's/org\.broad\.igv/org\.igv/g'

# 6) 手工处理两个消失的 API
#    - dev.api.IGVPlugin  -> 删掉 implements，改成 static getMenuItem()
#    - ui.PanelName       -> 全树已无此类，查 3.0 中替代写法或去掉该依赖

# 7) 在 IGVMenuBar 挂菜单（照 MotifFinderPlugin 的位置加一行）
# 8) 构建
./gradlew createDist          # 输出到 build/IGV-dist/
./gradlew test                # 跑测试（headless, 2GB heap）

# 9) 本地验证
#    Windows: build\IGV-dist\igv.bat
```

**首次编译预期会踩的坑**：`RenderContext`、`DataPanel`、`TrackPanel` 的方法签名在 8 年里大概率有漂移，逐个对齐即可（13 个 API 都还在，改签名不是改架构）。

### 阶段 3：无论官方是否接受，都自建发行版（保底）

- 仓库名 `igv-primer-edition`，README 首行写明 *fork of igvteam/igv, MIT, adds primer design module*
- 用 GitHub Actions 跑 `./gradlew createWinDist / createMacDistZip / createLinuxDistZip`
- **不要用 "IGV" 单独做产品名**（商标归 Broad），用 `IGV-PrimerEdition` 之类
- 保留 `upstream` remote，每次 IGV 发版 rebase 一次

---

## 五、给你的判断建议

| 你的目标 | 建议 |
|---|---|
| 主要想要**署名/履历** | 阶段 0 + 阶段 1 就够，成本最低，收益率最高 |
| 主要想要**官方暗色** | 必须走 fork（3.0），但**先确认暗色对你值不值这个维护成本** |
| 主要想要**工具稳定可用** | **别 fork**，走渠道 2（BED `graphType=arc`）+ 渠道 4（外部程序 + port 60151），寿命最长 |
| 全都要 | 阶段 0/1 立刻做；阶段 2 先做一次 PoC（只编译通过就算赢），再决定是否长期投入 |

**我的实话**：fork 的收益是"身份 + 不被砍"，代价是"长期跟上游 + 三平台打包"。
**最聪明的第一步是阶段 0 那个 issue**——它零成本，且能在 1 周内告诉你后面所有投入值不值。

---

## 附：本次核查的全部一手证据

| 结论 | 证据来源 |
|---|---|
| 88/100 合并率、19 个外部贡献者 PR | `gh api repos/igvteam/igv/pulls?state=closed&per_page=100` |
| 新增功能必须先开 issue | `igvteam/igv:CONTRIBUTING.md`（1701 字节） |
| 极简 commit、禁止重构 | `igvteam/igv:CLAUDE.md` |
| MIT 许可、版权方 Broad + UC Regents | `igvteam/igv:license.txt` |
| 15 个 API 中 13 个在 3.0 存在 / `PanelName` 消失 | `gh api .../git/trees/main?recursive=1` 源码树比对 |
| 内置模块样板 | `src/main/java/org/igv/tools/motiffinder/MotifFinderPlugin.java` |
| 3.0 需 Java 21、构建命令 | `CLAUDE.md` + `build.gradle`（`mainClass = org.igv.ui.Main`） |
| 3.0 源码规模 940 java / 934 在 org.igv | 源码树统计 |
