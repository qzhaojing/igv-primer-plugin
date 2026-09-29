# IGV 发展史全梳理（2007 → 2026）：版本功能、模块成熟时间线、插件消失后的落地路径

> 整理时间：2026-09-30　｜　面向：IGV 引物插件（PrimerPlugin）后续技术选型
> 所有结论均来自一手证据，不靠记忆。

---

## 〇、证据来源与可信度声明

| 证据 | 覆盖范围 | 获取方式 |
|---|---|---|
| `igvteam/igv` 仓库 `docs/web/releaseNotes.html` | IGV 2.3 → 2.3.86（2013-04 ~ 2016-11） | GitHub Contents API |
| `igvteam/igv` 仓库 `docs/RelNotes/v2.4.x.md` | IGV 2.4（2017-09） | GitHub Contents API |
| `igvteam/igv-docs` 仓库 `docs/ReleaseNotes/` | IGV 2.8 → 3.0 | GitHub Contents API |
| 全部 257 个 tag 的提交日期 | 版本时间轴 | GitHub GraphQL API |
| 逐版本源码树 diff（新增 java 文件） | 2.4.19 → 2.8.13 | GitHub Tree API |
| Thorvaldsdóttir 2013, Brief Bioinform 论文 | 2007–2011 早期史 | 公开文献 |

**两处必须坦白的缺口：**

1. **0.1 ~ 2.1 无 GitHub tag**：仓库 2012-02-11 才建立，最早 tag 是 `v2.2.0-b1`（2012-12-15）。更早的历史只能靠论文与官网旧页面。
2. **2.3.87 ~ 2.7.x 官方 changelog 没有进仓库**（当时只发在 Broad 旧官网，现已不可考）。我用**源码树逐版本 diff** 反推，结论标注为"推断"，不冒充官方说明。

---

## 一、史前时代：2007–2012（0.x → 2.1）

| 时间 | 事件 | 意义 |
|---|---|---|
| 2007 | IGV 开发启动，起因是 TCGA 项目要可视化整合的拷贝数+表达+突变+临床数据 | **需求驱动**，不是技术炫技 |
| 2008-08 | 第一个公开发布（broadinstitute.org/igv） | 只有拷贝数/表达/突变，还没有短读比对 |
| 2009-05 | 支持短读序列比对（与 1000 Genomes 合作制定 SAM/BAM 期间同步） | 趁格式未定型时抢占生态位 |
| 2011-01 | Robinson et al., *Nat Biotechnol* 29:24-26 | 学术背书 |
| **2011-05** | **IGV 2.0**：支持 VCF 变异；引入 **multi-locus 多区域并排视图**（打破"只能看一个连续区域"的限制） | 2.0 是第一个架构级版本 |
| 2012-02-11 | GitHub 仓库建立 | 开发过程公开化 |
| 2012-12 | v2.2（GitHub 上第一个 tag） | — |

**方法论注脚**：IGV 的核心技术资产 **data tiling**（多分辨率预汇总 + 运行时渲染，而非预生成图片）在 2007–2013 这个阶段定型，论文里明确写了为什么放弃"图片切片"方案（图片数量爆炸 + 表示方式被固化，无法交互改图）。**先解决"大数据跑得动"，再解决"好看"**——这个顺序贯穿 IGV 全程。

---

## 二、2.3 系列（2013-04 → 2017-07）：最长寿的一条线，93 个 tag，撑了 4 年 3 个月

这是 IGV 功能密度最高、也最"野生"的时期。你现在的 **IGV 2.3.80（2016-08-04）** 就在这条线上。

### 2.3.0（2013-04）
- **Motif finder**：正则 / IUPAC 简并码在参考基因组里搜序列，正链负链各一条轨道
- 覆盖度轨道详情可复制到剪贴板
- BAM 内存优化：移出视野的比对更频繁地清除
- Gitools 集成（热图框架，Tools 菜单）

### 2.3.10（2013-07）
- **Sashimi plot**（RNA-Seq 剪接可视化）首次出现
- ROI 批量命令支持 name 参数
- igvtools 可输出到 stdout

### 2.3.16（2013-08）
- 比对轨道菜单新增"导出一致序列"

### 2.3.23（2013-10）
- VCF 轨道可分组；MotifFinder 支持一次输入多个 motif
- 发展版才有的：Delete/Backspace 删除轨道

### 2.3.26（2014-01）—— 一个"小版本"塞了 6 个功能
- **叠加数据轨道（overlay）**
- 从 ENCODE 加载文件（hg19/b37/mm9）
- 一键下载基因组与序列
- EPS 快照输出
- `remove <trackName>` 批量命令
- **read pair 同步降采样**（要么都留要么都删）—— 成对语义的第一次显式处理

### 2.3.46（2015-03）—— 本线最重要的一个版本
- **BLAT 集成**：feature / 比对 / ROI 右键都能 BLAT，默认用 UCSC 的服务器，上限 8kb
- **Combine numeric tracks**：两个数值轨道做加/减/乘/除生成新轨道
- **Supplementary alignments** 支持：过滤、分组、tooltip 增强
- 会话文件默认用相对路径
- `goto all` 批量命令
- 碱基颜色可通过 pref.properties 配置（COLOR.A / SAM.COLOR.A …）
- **外显子编号改为从第一个编码外显子起算**（语义变更）

### 2.3.50（2015-04）
- 基因列表支持 bed 格式
- **高分屏字体缩放**（>1.5×96dpi 时按比例缩放，默认关）—— HiDPI 支持的最早萌芽

### 2.3.53 ~ 2.3.59
- UCSC `.snp` 格式；`.gappedPeak`；htsjdk 升至 1.138
- VCF 颜色可自定义（Variants 偏好页）

### 2.3.60（2015-09）—— **对引物插件最关键的一个版本**
- **BED 可用 `graphType=arc` 渲染成连接起点终点的弧线**（track line 指定，建议高度 ≥250px）
  → 这就是"配对拱形连线"的原生表达，20 多年前就有了，我们完全可以不依赖插件画出弧线
- bisulfite 模式下可显示所有 C（context = None）
- 会话默认改回绝对路径

### 2.3.68（2016-01）
- **BAM 可只显示 coverage / splice junction，不加载比对本身** → 大幅省内存（这是"按需加载"哲学的延续）
- 多轨道成组 autoscale

### 2.3.69（2016-03）
- `.mut` / `.maf` 可索引；Bionano `.smap`
- **VCF 变异着色语义变更**：改为按 AF 或由 AC/AN 计算的 AF 着色，不再从 genotype 推算（必须 INFO 里有 AF 或 AC&AN）—— 官方在 changelog 里专门大写 "Note" 提醒
- 导出轨道名

### 2.3.73（2016-05）
- igvtools 支持 BAM 排序与索引；MAF→SAM 转换器
- Tools 菜单新增 blat

### 2.3.80（2016-08-04）← **你当前的版本**
- 批量脚本支持 `gs://`；ga4gh read group set；`--igvDirectory` 命令行开关
- fasta 索引创建失败时弹错误框；OAuth token 可通过 port 命令设置

### 2.3.86（2016-11-01）—— 本线最后一个有 changelog 的版本
- seg 拷贝数文件黑白配色修复

> **观察**：2.3 线的节奏是"每 1~2 周一个小版本、纯 bugfix 与功能点混发"，版本号一路累加到 98。这是典型的**小团队 + 用户需求直接驱动的发布模式**——谁在论坛提 issue，下个版本就改。

---

## 三、2.4（2017-09 → 2019-02）：Java 8 门槛 + 三代测序

**2.4 强制要求 Java 8**（sourceCompatibility=1.8），这是 IGV 第一次把 JDK 版本当硬性门槛。

| 版本 | 日期 | 要点 |
|---|---|---|
| 2.4.0 | 2017-09 | **PacBio 长读支持**：Consensus Mode（只在足够多 read 不一致处显示错配）、Hide Indels（隐藏小 indel）、大 indel 标注、按碱基分组看单倍型；**10X Genomics linked reads**（BX/MI 关联、HP 单倍型着色）；YC tag 着色开关；**CRAM 3.0**（含 500MB 染色体序列缓存）；基因组管理 UI 简化 |
| **2.4.11** | **2018-07-02** | 插件接口 `IGVPlugin.java` 仍存在 |
| **2.4.13** | **2018-07-11** | **插件机制最后一个可用版本**（IGV.java 中 plugin 相关代码 15 处） |
| **2.4.14** | **2018-08-14** | **`IGVPlugin.java` 被删除**，插件注入机制死亡；`builtin_plugin_list.txt` 沦为无人读取的残留文件 |
| 2.4.19 | 2019-02-15 | 2.4 线收官（IGV.java 中 plugin 出现次数 = 0） |

> 贡献者署名值得注意：PacBio 支持明确感谢了 Pacific Biosciences 的 Aaron Wenger 贡献代码。**大平台厂商主动给 IGV 提 PR**——这是 IGV 能持续跟进测序技术的一个关键原因。

---

## 四、2.5 ~ 2.7（2019-02 → 2019-10）：官方 changelog 缺失期，用源码树反推

| 版本 | 日期 | 新增 java 文件 | 反推结论 |
|---|---|---|---|
| 2.5.0→2.5.3 | 2019-02 ~ 05 | +1（`IGVUrlHelperFactory`） | **IO/URL 抽象层重构**：把"怎么读一个 URL"抽象成工厂，为后面接各种云存储铺路。属于纯基础设施版本 |
| 2.6.0→2.6.3 | 2019-07 ~ 08 | **+22**（`bedpe` 包 11 个 + `google` 包 8 个） | **Interaction/Arc 轨道体系成型**：`InteractionTrack`、`BedPE*`（Parser/Feature/Renderer/Shape）、`ProportionalArcRenderer`、`NestedArcRenderer`；GA4GH 客户端增强 |
| 2.7.0→2.7.2 | 2019-10 | +12（`aws` 包 4 个 + `util` 5 个 + `google` 1） | **AWS S3 支持**：`S3LoadDialog`、`AmazonUtils`、`S3Presigner`；统一认证 `OAuthProvider` + `JWTParser` |

> **这段正好是"从本地文件 → 云"的转型期**，三个版本分别做了：抽象 IO → 打通交互轨道格式 → 打通 AWS。没有一个是面向用户的"炫功能"，全是地基。

---

## 五、2.8（2019-12 → 2020-11）：Java 11、macOS Catalina、现代交互

- **2.8.0（2019-12-21）**：
  - **实验类型自动推断**（RNA / 3rd Gen / Other），首次加载比对时推断，并按类型设默认值 → 这是"让软件自己理解数据"的思路
  - 翻译视图修正：起始密码子按 **AUG/ATG** 而非氨基酸 M 着色（线粒体表里 M 不总是起始）；脊椎动物线粒体的替代起始密码子 AUA/AUU/AUC 标黄
  - 按样本属性 **overlay / rename** 轨道
  - ROI 可 Ctrl 拖拽红条调整边界；cytoband 红框可拖拽换视野
  - dbSNP 搜索（hg19/hg38 build 151、mm10 build 142）
  - `--help` / `--version`
  - **移除 GenomeSpace 集成**（NHGRI 停资助，服务器关了）
- **2.8.2**：按 read name 排序；默认按碱基质量着色；soft-clip 扩展窗口可配（原来硬编码 1000bp）；`File > Reload Session` / `Reload Tracks`
- **2.8.6**：**popup 文本从 hover 改为 click 触发**（工具栏黄气球图标切换 hover/click/none）；文件对话框全改原生（Catalina 兼容）
- **2.8.9 / 2.8.10**：内置 Java 11.0.8；下载包分"带 Java / 不带 Java"两种

---

## 六、2.9 ~ 2.16（2021-02 → 2023-07）：碱基修饰、云、批量脚本的深耕期

| 版本 | 日期 | 核心 |
|---|---|---|
| **2.9.0** | 2021-02 | **批量执行性能与稳定性大幅改进**（不用再在脚本里插 sleep 了）；新增 `colorBy`、`setSequenceStrand`、`setShowSequenceTranslation`；按参考一致性分组；feature 轨道正负链可设不同颜色 |
| **2.10.0** | 2021-06 | **RNA/DNA 碱基修饰标签**（MM/ML，Nanopore Megalodon/Guppy 产出）；按属性过滤轨道 `Tracks > Filter Tracks`；`sortByAttribute`；修超大染色体（1024³ bp） |
| **2.11.0** | 2021-08 | **htsget 服务器**加载 BAM/CRAM/VCF；**自定义 BLAT 服务**（可换服务器或本地命令行程序）；**JSON 参考基因组**（`.genome` 格式弃用）；cytoband 可作为独立轨道加载；`.genepredext`；S3 预签名 URL 全格式支持；默认内存 4GB→**8GB** |
| 2.11.9 | 2021-12 | **移除 Log4j，改用 java.util.logging**（Log4Shell 时期）；BAM 加载优化（三代长读/超高深度） |
| **2.12.0** | 2022-01 | **JBrowse Circular View 集成**（结构变异/远距离交互的环形视图）；BEDPE interaction 轨道全基因组降采样；session 支持 `~`；批量 overlay 命令 |
| **2.13.0** | 2022-05 | AWS 默认凭证链；**bigGenePred / bigNarrowPeak**；feature 跳转居中 + 自动缩放；htsjdk 3.0（**CRAM 性能大幅提升**、CSI 索引可从 URL 加载） |
| 2.14.0 | 2022-08 | 5mC 着色；按 mapping quality 着色/分组；按比对读长排序；**不再自动检查新版本** |
| 2.15.x | 2022-10 ~ 2023-01 | 链接参数 `merge=ask`；**`.mut` 坐标默认改为 0-based half-open**（又一次语义变更，给了偏好开关和 `#coords` 指令） |
| 2.16.0 | 2023-01 | PacBio **SMRT kinetics** 着色；分组选项可应用到所有比对轨道；Load from URL 支持多 URL 空格分隔 |

---

## 七、2.17 ~ 2.19（2024-01 → 2026-06）：Java 17 → Java 21，长读与表观修饰深化

| 版本 | 日期 | 核心 |
|---|---|---|
| **2.17.0** | 2024-01-04 | **要求 Java 17**；碱基修饰配色全面升级（单色/双色方案、可在 `Base Mods` 页自定义每种修饰颜色）；**嵌合读（split read）可视化重做** + split read 比对示意图；插入统一用标尺下方三角形表示；**会话自动保存**；新格式 **GVCF**、**bedMethyl**；BLAT 上限 8kb→**25kb** |
| 2.17.1 | 2024-01 | **UCSC track hub（useOneFile 格式）可直接作为参考基因组**加载，含 GenArk 3600+ 组装 |
| 2.17.3 | 2024-02 | `Genomes > Load Genome from UCSC GenArk…` 菜单 |
| **2.18.0** | 2024-07 | **无扩展名时按文件内容嗅探格式**（BAM/CRAM/bigWig/bigBed/VCF/GFF3/TDF）；比对轨道新增 `Full` 显示模式（一行一条）；右键删除空数据面板 |
| 2.18.2 | 2024-08 | duplicate read：过滤 / 网纹高亮 |
| **2.19.1** | 2024-12-04 | **要求 Java 21**；**macOS 11+**；托管基因组可下载序列/注释到本地 |
| 2.19.3 | 2025-04 | wig/bigWig/bedGraph **默认 autoscale**；GFF 第 9 列属性搜索长度上限 20→50 字符；hg38/hg19 染色体别名识别 `23`/`chrX`/`X` |
| 2.19.5 | 2025-07 | **CRAM 3.1**；覆盖度计数纳入所有碱基字符（含简并码与 `=`） |
| 2.19.6 | 2025-09 | **Roche SBX** 比对支持；**DynSeq 渲染**（高缩放时绘成核苷酸字形，低缩放回退柱状图）；支持 >2³¹ 条 feature 的 bigBed |
| 2.19.7 | 2025-10 | LZMA 压缩 CRAM 可读 |
| **2.19.8** | 2026-06-05 | 2.x 线末版（包名仍全部是 `org.broad.igv`，构建要求 Java 21） |

---

## 八、3.0（2026-05 → 2026-09，Beta）：架构重写

| 维度 | 变化 |
|---|---|
| **包名** | `org.broad.igv` → **`org.igv`**（1037 个文件；`org.broad.igv` 只剩 2 个） |
| **布局** | 取消"panel 面板制"，改为**单一可滚动轨道区**；每条轨道可单独调高；拖拽手柄排序 |
| **菜单** | 新增顶层 **Sessions**、**Track Hubs** 菜单；**Tracks 菜单被取消**，功能下沉到轨道右键菜单；新增 `View > Show Selection Checkboxes`（多选） |
| **参考基因组** | 实时拉取 **UCSC GenArk 全量列表**（原来是一份很短的精选列表）；可勾选默认注释轨道 |
| **Track Hubs** | 直连 **UCSC Track Hub Registry** 实时检索，也支持任意 URL 私有 hub |
| **会话** | XML → **JSON**，与 IGV-Web 互通，更紧凑易编辑 |
| **新轨道** | **HIC（Hi-C 接触矩阵，弧线 + 独立 2D 热图窗口）**；多样本 SEG 合成单轨道（一行一样本） |
| **临床** | **HGVS 命名法**输入跳转（如 `NM_000314.8:c.752G>T`）；点击变异显示 HGVS + ClinVar 链接 |
| **比对** | 中心默认显示；`Select by name` 支持逗号分隔多个 read name；`Select by base at position`；`Color by split` 高亮嵌合读 |
| **外观** | **视觉主题 / 暗色模式**（`View > Preferences > General`，切换后需重启）← 你关心的功能 |
| **Beta 4** (2026-08) | 鼠标滚轮滚动；恢复 ctrl-f/ctrl-b feature 跳转；`color by read name` |
| **插件** | **`builtin_plugin_list.txt` 已删除**，外部 Java 插件注入机制不复存在 |

**3.0 里还剩什么扩展口子（实测结论）：**
- `META-INF/services`：**无**；`ServiceLoader`：**无** → 没有 SPI 式插件体系
- 含 "plugin" 的文件只剩：`MotifFinderPlugin.java`（内置功能模块，不是插件接口）+ `resources/org/igv/cli_plugin/*.xml`（**bedtools / gatk / cufflinks / awk / gen 等外部命令行工具的注册描述**）
- 各种 `*Factory`（AlignmentReaderFactory、CodecFactory、RendererFactory…）都是**内部实现类，不是可替换的扩展点**
- 官网文档 `igv-docs` 中**没有任何 plugin 页面**

---

## 九、功能模块成熟时间线（横向对比）

| 模块 | 起步 | 成型 | 成熟/现状 |
|---|---|---|---|
| **数据加载骨架（data tiling / 索引 / 按需加载）** | 2007–2009 | 2011 (2.0) | 架构基石，至今未变 |
| **比对轨道（BAM/SAM）** | 2009-05 | 2.3 (2013) | 2.4 三代 → 2.10 碱基修饰 → 2.17 嵌合读 → 2.18 Full 模式，仍在深化 |
| **变异（VCF）** | 2011-05 (2.0) | 2.3.23 分组 / 2.3.68–69 着色 | 2.17 GVCF、3.0 HGVS+ClinVar |
| **文件格式** | 早期 BAM/VCF/TDF | 2.3 密集补格式（.snp/gappedPeak/.smap/.mut/.maf…） | 2.13 bigGenePred/bigNarrowPeak；2.17 GVCF/bedMethyl；3.0 HIC。**"补格式"是 2.x 最稳定的主线** |
| **RNA / 剪接** | 2.3.10 Sashimi（2013-07） | 2.3 系列反复打磨 | 到 2.13.2 / 2.15 / 2.19.7 仍在修 Sashimi bug —— **一个 13 年前的功能至今还在维护** |
| **交互 / arc 轨道** | **2.3.60 `graphType=arc`（2015-09）** | **2.6 InteractionTrack + ProportionalArcRenderer（2019-07）** | 2.12 全基因组降采样 |
| **云与远程** | 2.3 GenomeSpace / GA4GH / gs:// | 2.7 AWS S3（2019-10） | 2.11 htsget + S3 预签名；2.13 AWS 凭证链；2.17/3.0 UCSC GenArk |
| **参考基因组管理** | 2.3 Load Genome | 2.4 UI 简化 | 2.11 JSON 格式 → 2.17 track hub → 3.0 实时 GenArk 全量 |
| **会话（session）** | 2.3 XML | 2.3.46 相对路径 | 2.17 自动保存 → **3.0 改 JSON 与 web 互通** |
| **批量 / port 控制** | 2.3 port 命令 | **2.9.0 性能与稳定性重构（2021-02）** | 2.11 新增 saveSession/setColor 等；3.0 保留。**这是目前最稳定的程序化控制通道** |
| **UI / 交互细节** | 2.3.50 高分屏字体缩放 | 2.8.6 hover→click、原生文件对话框 | 3.0 布局重构 + 暗色主题 |
| **运行时（Java）** | Java 6/7 | **2.4 要求 Java 8** | 2.8 Java 11 → **2.17 Java 17** → **2.19.1 Java 21** → 3.0 Java 21 |
| **第三方插件机制** | ≤2.3 有 `IGVPlugin` 接口 | **2.4.13（2018-07-11）最后可用** | **2.4.14（2018-08-14）删除，8 年后 3.0 确认不再恢复** |

---

## 十、插件没了，引物功能还能做吗？—— 五条渠道 + 选型建议

先给结论：**能做，而且真正在未来活得下去的做法，恰恰不是"注入 jar"。**

### 渠道 1：源码内置 fork（功能 100% 保真）
- 做法：fork `igvteam/igv`，把 `primer` 包放进源码树，注册进菜单/track 系统，用官方 gradle 自建发行版。
- 3.0 需把 `import org.broad.igv.*` 全量改成 `org.igv.*`；2.4.13 则**完全不用改包名**。
- 成本：Java 21（3.0）/ Java 8（2.4.13）+ gradle 环境；每次上游更新要 rebase。
- 评价：**功能无损，但从此你要维护一个 IGV 分支。** 适合"这功能是我核心生产力"的场景。

### 渠道 2：用标准文件格式表达（零代码、跨版本、最抗升级）★
- **引物本体** → BED / feature 轨道（IGV 原生）。
- **配对拱形连线** → 两条路：
  1. **BED + track line `graphType=arc`**（2.3.60 起支持，2015 年就有）
  2. **BEDPE interaction 轨道**（2.6 起有完整渲染器 `ProportionalArcRenderer`）
- **PCR 产物长度** → 写在 BED 的 name 字段或单独一条标注记录。
- 评价：**我们插件最核心的视觉（引物块 + 贝塞尔拱形配对线）本来就能用标准文件表达**，这是我们之前没意识到的冗余设计。缺点是交互性（拖拽调位置、实时 Tm）为零。

### 渠道 3：cli_plugin 外挂命令行工具（3.0 里唯一保留的"插件"概念）
- 3.0 仍保留 `resources/org/igv/cli_plugin/*.xml`（bedtools / gatk / cufflinks / awk / gen 的注册描述）。
- 做法：写一个外部可执行程序，用 XML 注册进 Tools 菜单，IGV 把当前区域/选中文件传给它；程序生成或修改引物 BED 后，IGV 再 Reload。
- 评价：**官方支持、跨版本**，但只能"批处理式"交互，无法自定义渲染。

### 渠道 4：外部程序 + port/batch 联动（推荐的主力架构）★
- IGV 的 **port 命令（默认 60151）与批量脚本**在 2.x 和 3.0 里都完整保留，且 2.9.0 专门重构过稳定性。
- 架构：**独立运行的 Primer Designer 程序（Java/Python/任何语言）↔ 通过 port 命令驱动 IGV（goto / load / reload / setColor / snapshot …）↔ 用文件交换数据。**
- 优点：
  - 完全不依赖 IGV 内部 API → **IGV 怎么升级都不影响你**；
  - 交互逻辑在自己程序里，想做多复杂都行（Tm/GC/二聚体计算、批量排版、1v多配对）；
  - 视觉部分交给渠道 2 的标准文件。
- 评价：**耦合最低、寿命最长。** 代价是"所见即所得"感略弱（需要 reload 才能看到变化）。

### 渠道 5：igv.js / IGV-Web 路线
- igv.js 是官方 JS 组件（Robinson 2023 Bioinformatics），IGV-Web 基于它。
- 但同样**没有稳定的第三方 track 插件 API**，要做自定义渲染仍需 fork。
- 评价：如果你的引物工具未来想做成网页版分享给合作者，这条路值得单独评估；但"让现有桌面插件活下去"不该选它。

### 选型建议（三档）

| 场景 | 推荐 |
|---|---|
| **现在就要用、要稳** | 留在 **IGV 2.3.80 + 现有注入插件**（≤2.4.13 都行），不动 |
| **想兼顾寿命与交互** | **渠道 4 + 渠道 2**：独立程序 + port 控制 + BED/BEDPE 文件渲染。逐步把插件里的计算逻辑搬到独立程序 |
| **要官方暗色 + 现代化 UI** | 上 3.0，用**渠道 1（源码内置）**，接受维护分支的成本；或者干脆等官方暗色 + 渠道 4 组合（程序独立，IGV 只当显示器） |

---

## 十一、从 IGV 演进里能抄的六条产品/工程经验

1. **先解决规模，再解决美观。** data tiling（2007–2013）花了六年，UI 现代化（3.0）放在最后。你的引物插件同理：先把"导入 200 条引物不卡"做扎实，再去抠贝塞尔曲线好看不好看。
2. **跟着用户的数据走，不跟着技术潮流走。** IGV 的云平台顺序是 GenomeSpace → Google → AWS → htsget → UCSC GenArk，每一步都是用户数据搬到哪它就搬到哪；GenomeSpace 停资助就立刻下线（2.8.0）。
3. **语义变更必须显式告知。** VCF 着色改口径（2.3.69）、`.mut` 坐标改 0-based（2.15.4），官方都在 changelog 里大写 Note 并给开关/指令兜底。**我们的插件改 BED 元数据格式时也应该留向后兼容解析**（我们已经做了：旧 BED 无 `|r` 段仍能读）。
4. **长期维护一个老功能是常态，不是负担。** Sashimi plot 从 2013 到 2025 一直在修 bug。反过来说：**功能上线只是开始**。
5. **扩展机制是负债，官方会主动砍掉它。** IGV 在 2.4.14 直接删掉插件接口，8 年后 3.0 确认不恢复。给我们的直接教训：**不要把核心能力建立在"注入/hack 宿主内部 API"上**，一旦宿主关门就归零。正确姿势是"标准文件格式 + 外部程序 + 官方稳定通道（port/batch）"三件套。
6. **版本节奏：长尾维护线 + 断代升级。** 2.3 一条线撑 4 年发 93 个 tag；同时 JDK 门槛 8→11→17→21 四次断代。**你的插件版本号 v0.1.x 每功能 +0.0.1 + 独立 commit 的规矩，和 IGV 的"小步快跑"是一致的，继续保持。**

---

## 附：关键日期速查

| 版本 | 日期 | 备注 |
|---|---|---|
| v2.4.11 | 2018-07-02 | 插件接口仍存在 |
| **v2.4.13** | **2018-07-11** | **插件机制最后可用版本** |
| **v2.4.14** | **2018-08-14** | **`IGVPlugin` 接口被删** |
| v2.4.19 | 2019-02-15 | 2.4 收官 |
| v2.8.0 | 2019-12-21 | Java 11 时代起点 |
| v2.17.0 | 2024-01-04 | Java 17 |
| v2.19.1 | 2024-12-04 | Java 21 / macOS 11+ |
| v2.19.8 | 2026-06-05 | 2.x 末版 |
| v3.0.0-beta.7 | 2026-09-24 | 3.0 最新 beta |
