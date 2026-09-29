# IGV 引物设计插件（IGV 2.3.80, Java 8）使用说明

## 状态
- 已编译并注入 `F:\zhaojing\IGV_2.3.80\IGV_2.3.80_jre\lib\igv.jar`（**v10**）
- 通用安装器 `install.py` 已就绪：可把独立 `PrimerPlugin.jar` 注入**任意** IGV 2.3.80 的 igv.jar（自动备份+去签名+写注册）
- 注册于 jar 内 `org/broad/igv/ui/resources/builtin_plugin_list.txt`（尾部追加 `org.broad.igv.primer.PrimerPlugin`）
- 启动方式：双击 `F:\zhaojing\IGV_2.3.80\IGV_2.3.80_jre\igv.bat`（或 igv.lnk）
- ⚠️ **IGV-GSAman.exe 不支持**（其定制构建删除了 dev/api SPI 与 initIGVPlugins，已验证并还原其原 jar）

## 功能与操作

### 启动后
IGV 启动即出现 **"Primers 引物"** 轨（空轨，在数据面板里）。

### 右键 Primers 轨（任何时候可用）
| 菜单项 | 说明 |
|---|---|
| 在此添加引物... | 弹窗设置：名称/坐标/方向(+/-)/角色(R1/R2)/**F、R 测序长度 nt**；确定后自动生成 3' 端测序延长区 |
| 进入引物编辑模式 | 激活拖拽工具（鼠标变十字） |
| 删除选中引物 / 重算参数 | 针对当前命中引物 |
| 导出 BED | 9 列 BED：block=引物本体、thick=引物本体（**引物长度纯净，不含测序延伸**）、itemRgb（R1 绿 / R2 蓝 / fail 红）、name 内嵌 role+readLen → **可直接拖回 IGV 复查** |
| 导出引物序列 FASTA | 按各自方向输出 5'→3'，头部含 Tm/GC/FAIL |
| 导出测序读段 FASTA | 引物 3' 端起 readLen nt 的读段序列（R2 已反向互补） |
| 导入 BED | 恢复之前设计继续编辑；**BED name 内嵌的 `|p配对名` 会自动回链配对**（按名重连扩增子 ID，连线/异源二聚体复原） |
| 退出引物编辑模式 | 恢复 IGV 默认工具（拖拽缩放） |

### 编辑操作（v4：启动即生效；**所有引物均可拖拽/缩放**，空白处拖动 = 正常平移视图）
- **悬停引物两端**（±6px）= 双向箭头光标 → 拖动 = 缩放引物长度
- **悬停/按住引物本体** = 移动光标 → 固定长度左右拖动，放开即固定新位置
- **单击** 引物 = 选中（出现橙色端点手柄 + 高亮框）
- **双击** 引物 = 直接弹出编辑框（可改坐标/方向/角色/测序长度/配对）
- **拖动中** 两条红色虚线竖线实时标注起/止位置及坐标
- **拖动 / 缩放中** 贯穿数据面板全高的 **红色 ruler 线**（glass pane 叠加层，对齐参考序列轨/其他 track），松开即消失，便于对照坐标对齐
- **释放鼠标** = 自动拉取参考序列 → 重算 Tm/GC/hairpin/self-dimer/异源二聚体/3'互补 → 刷新显示
- "退出引物编辑模式"可恢复 IGV 纯默认行为；再次进菜单可重新开启

### 默认参数与配对
- 添加/编辑对话框：**R1、R2 测序长度默认 0**（允许 0，纯引物不显示延长区）。
- 对话框内 **"保存配置"** 按钮：把当前 长度/方向/角色/R1/R2 写入 `~/.igv_primer_defaults.properties`，下次打开自动沿用。
- **配对引物（支持 1:N 一对多）**：在对话框"配对引物名称"填**多条引物名，逗号分隔**（如 `P002,P003,P004`）。所有列出的引物 + 本条共享同一个**配对组 ID**（即画连线 + 算异源二聚体，组内任意两条都参与二聚体评估）。后加的引物填已存在组里的任一成员名 → 自动**并入该组**（不会新建组）。导出 BED 时配对组 ID 写入 name（如 `P001|R1|read0|aG1`），**导入时按组 ID 自动复原多对配对**，无需手动重连。

### 实时参数（每条引物下方）
`P001 F Tm60 GC55 R1+150nt`；**任一参数 fail → 红色外框 + 红色参数**。
判定：长度 18–30nt；Tm 55–65°C（SantaLucia NN + 50mM Na⁺，500nM）；GC 30–75%；hairpin ΔG > -3.5；self-dimer ΔG > -5；任意两引物 3' 互补 ≥4bp 或 ΔG ≤ -5 → 双双标红。

### 显示元素
- 绿色右箭头 = 前向(+)，蓝色左箭头 = 反向(-)；条更窄（行高 30px）
- 引物**下方**浅色虚线小带 = 测序延长区（按 R1/R2 各自 readLen；与引物本体明确分开，不合并）
- **配对引物（共享配对组 ID）**：
  - size==2（常规 R1/R2）强制排在同一行，中间非配对引物自动避让到上下行，互不遮挡。
  - **size>2（一对多）走 hub-spoke 放射式**：起始居中的引物为 **hub**（标 `hub×N` + 小圆点），其余伙伴各占独立上下行；从 hub 中点向每条 spoke 中点画**不同色的放射线 + 方向箭头**，每条标注 `P1/P2…` 区分；选中组内任一引物 → 整组连线加粗高亮。
- 灰色横线 + kb 标注 = 同一扩增子 R1-R2 连线；**连线位于两条引物箭头相对的正中间**；两端实心方向三角（按链着：绿=+/蓝=-）指向扩增子内部，直观表示 R1/R2 面对面

## 重新编译/部署
```bash
cd igv_primer_plugin && bash build.sh          # 编译到 classes/
python deploy.py <v5_base_jar> <target_igv.jar> <classes_dir>
#   deploy.py：基于已知洁净的 v5 底座 jar 重建（注入 classes + 去签名 + 保留 builtin_plugin_list 注册）
#   注：Python 重打包后的 jar 不能用 jar uf 增量更新，必须用 deploy.py 整体重建
```

### 通用安装器（B 模式：分发给他人 / 重装本机）
```bash
python install.py --package            # 打包本项目 -> PrimerPlugin.jar（仅含自研插件类，不含任何 IGV 类）
python install.py <对方 igv.jar 路径>  # 给对方的 igv.jar 打补丁：自动备份 + 注入 + 去签名 + 写注册
# 不传路径则自动探测常见位置（F:\zhaojing\IGV_2.3.80\...\lib、C:\Program Files\IGV\lib 等）或当前目录 igv.jar
```
- 安装后对方重启 IGV 即自动加载 Primer 轨，**零额外操作**。
- 卸载：用安装时生成的 `igv.jar.bak-<时间戳>` 覆盖回 igv.jar 即可。
- 已 headless 验证：install.py 打补丁后的 jar 与现有改装 jar **完全等价**（注册一致 / 36 个插件类字节一致 / 签名已去 / 含 BoundedPopupMenu）。

## 回滚
- 备份：`F:\zhaojing\_backup\igv.jar.bak-20260928` → 覆盖回 `lib\igv.jar` 即完全还原

## 已知限制
1. Swing 拖拽无法 headless 验证，交互手感需本地 GUI 验收。坐标换算已统一走 IGV 权威 API `frame.getChromosomePosition()`（注意 getScale() 语义是"碱基/像素"）。
2. hairpin/dimer ΔG 为工程估算（非完整折叠算法），数量级判断可靠，精确值以 mfeprimer 复核为准。
3. 需先加载参考基因组才能算参数（没基因组时显示"无序列"红框）。
4. 每对 R1/R2 需手动成对添加（共享配对组 ID 才会画连线/算异源二聚体）。
5. 一对多编辑限制：在对话框修改某引物的"配对引物名称"会**新增**到所属组，但不会自动解除它此前已配对的旧伙伴（如需拆对，清空该引物及旧伙伴的配对字段后重新指定）。导入 BED 按组 ID 复原，无此问题。

## v9 更新（右键菜单白板修复）
- **现象**：右键 Primers 轨出现大片白板，命中信息多（含二聚体等指标）时必现。
- **根因**：`IGVPopupMenu` 宽度由其**最宽子项**决定。命中引物的信息行由 `bedName() + metricsSummary()` 拼成（名称/分组/配对/颜色 + Tm/GC/hairpin/self-dimer/异源二聚体/3'互补/FAIL），单行极长 → 整张菜单被撑到数千像素宽，其余短菜单项只占左侧，右侧留白成"白板"。
- **修复**：①新增 `BoundedPopupMenu extends IGVPopupMenu`，覆写 `getPreferredSize()` 把宽度封顶为 `MENU_MAX_W = 320`（与 IGV 原生右键菜单宽度一致，高度不限制）；②信息行超 84 字符自动截断并加 `…`。
- **headless 实测**：同一文本下 原生菜单宽度 **9239px** → 封顶后 **320px**。

## v10 更新（session 丢失兜底：自动保存 + 恢复）
- **现象**：保存 session XML 后再加载，`Primers 引物` 轨不回来。
- **根因（机制使然，非 bug）**：IGV session 只序列化**带 ResourceLocator 的数据轨**（BED/VCF/BAM 等有文件来源的）。PrimerTrack 是插件运行时注入的**内存轨**，`getResourceLocator()` 为 null → 不会被写进 session XML。
- **处理**：加自动保存兜底（而非强改 session 机制）
  - 引物任何改动（增/删/清空/导入）**立即**写盘，`refresh()`（拖动、改色）**节流 1.5s** 写盘 → `~/.igv_primer_autosave.bed`
  - 文件格式与「导出 BED」完全一致（含 `|role|read|g|a|c`），所以能被 `parseBED` **完整还原配对组、颜色、测序长度**
  - 右键新增：「自动保存引物（防丢失）」开关（持久化到配置文件）+「恢复上次编辑（自动保存）」→ 一键回到**可编辑**的 PrimerTrack
- **session 场景用法**：session 加载后发现引物没了 → 右键「恢复上次编辑」即可；长期归档仍建议用「导出 BED」随项目一起存。

### 为什么不直接变成 BED 轨（挂 ResourceLocator 方案的后果）
若给 PrimerTrack 挂上 BED 资源让 session 能保存，加载回来会是 **BED 数据轨**：
- ✅ 能看：位置、名称、`itemRgb` 颜色、链向（strand 列）
- ❌ 不能编辑（拖拽/拖边/双击全失效）
- ❌ 配对连线、1:N 汇聚曲线丢失（BED 轨不画）
- ❌ 测序延长区（R1/R2 read）不显示（BED 只存引物本体区间）
- ❌ Tm/GC/hairpin/二聚体不重算，重叠引物不自动分行（会叠在一起）
- ❌ 且插件 `init()` 仍会新建空 PrimerTrack → 出现**空 PrimerTrack + BED 轨**双轨

## v8 更新
- **自由拖动**：按住引物可上下左右任意拖动（垂直=换行，手动指定行优先于自动布局）
- **修复拖动切换 bug**：拖动经过其他引物不再误切换目标
- **曲线汇聚**：1:N 配对改为平滑贝塞尔曲线，从各成员汇聚到 R2（hub）
- **延长区占位**：测序延长区计入行布局，不再被其他引物重叠
- **右键颜色**：菜单内一行 12 常用色块，点击单独换色（随 BED 导出/导入 `|c` 段还原）
- **编辑引物**：右键命中引物时菜单文案自动变为"编辑引物..."
- **包含引物长度**：编辑框复选框，勾选后填 61 且引物 20nt → 实际延伸 41
- **显示序列**：右键开关，引物下方显示 5'->3' 碱基序列（缩放足够时）

## 分发与许可（LICENSE）
- 本插件基于 **IGV（MIT License, © Broad Institute & Regents of the University of California）** 开发，仅向 IGV 注入自研类文件。
- **分发物仅为 `PrimerPlugin.jar` + `install.py`**，**不重新分发被修改签名的 Broad igv.jar 本身**；用户以自己合法取得的 IGV 运行安装器打补丁 —— 符合 IGV 的 MIT 许可。
- 发布/分发时请随包附 IGV 的 MIT 许可原文（见 IGV 仓库 `license.txt`），并注明 "based on IGV (MIT)"。
- IGV MIT 要点：可修改、再分发、闭源、商用；**唯一硬义务是保留版权与许可声明**；不可冒充 Broad 官方出品。
