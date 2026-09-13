# PaperizE

**ProjectE（等价交换重制版）的 Paper 插件移植** —— 以 [CEPlus](https://github.com/) 引擎承载的独立内容项目。

把 NeoForge 1.21.1 的 ProjectE 模组内容与机制，迁移到 Paper/Folia 服务端生态：
物品、方块、EMC 体系、机器矩阵与功能物品，全部以插件形式运行。

## 核心特性

### EMC 引擎
- 公共标签（`c:` 约定）+ 自定义转换 + 服务器配方图求解
- 关键物品价值 18/18 对齐 ProjectE 官方基准（钻石 8192 / 暗物质 139264 / 克莱因之星全族）
- 惰性图松弛求解，5 轮收敛；服务器配方 1515 条纳入 1377 条转换

### 内容矩阵
| 类别 | 内容 |
|---|---|
| 物品 | 86 个（工具/护甲/戒指/护符/宝石/透镜/燃料/袋子/星） |
| 方块 | 21 个（block_item 管线；箱类模型 + 台阶/紫水晶簇碰撞箱） |
| 配方 | 132 条合成配方导出 |

### 机器矩阵（全部带分区 UI）
| 机器 | 机制 |
|---|---|
| 能量收集器 MK1-3 | 光照产 EMC；14 级升级链（木炭→永恒燃料块，累计 73,696） |
| 反物质继电器 MK1-3 | 提取（燃料区→输入槽→抽 EMC）/ 产生（邻接收集器面奖励）/ 转移（注能加工中收集器） |
| 物质凝聚器 MK1-2 | 目标产物 + 燃料区；MK1 同区输出防循环、MK2 左右分区 + 进度条 |
| 熔炉（DM/RM） | 极速烧炼（RM 5 个/秒）；金属提炼（RM 100% 双倍）；上方容器吸入；成品自动转箱 |

### 功能物品（24 类主动/被动效果）
宝石（身之/生命/灵魂/念之/以太密度）、护符（潮汐/熔焰/修复）、
戒指（烈焰/零度/疾风/黑洞/虚空/丰收/奥法）、透镜与燧石、神器（贤者之石/水银之眼/大天使/时流怀表/炼金术秘卷）、
暗物质台座（11³ 范围效果 + 紫水晶簇碰撞箱）。

### 界面系统（PD 风格 Menu API）
- 会话管理 + 动作路由 + 点击节流 + Shift 快速移入 + 静态槽污染清理
- 容器皮肤：箱子 UI 底图替换为 ProjectE 配色（`minecraft` 命名空间覆盖）

## 构建

```bash
mvn clean package
# 产物：target/PaperizE-0.1.0-SNAPSHOT.jar
```

**运行依赖**：Paper 26.1.2+ / CraftEngine 26.8.2+ / CEPlus 引擎

## 工具链

| 工具 | 用途 |
|---|---|
| `tools/extract_content.py` | 从 ProjectE jar 提取资产/数据/注册表 |
| `tools/gen_container_skin.py` | 生成容器界面皮肤（原版底图 × ProjectE 配色） |
| `tools/EmcVerify.java` | EMC 求解离线验证（13/13 基准核对） |
| `tools/git_api_push.py` | Git Data API 推送（用于 git HTTPS 受限环境） |

## 目录结构

```
src/main/java/com/paperize/
├── PaperizEPlugin.java     主入口（引擎装配）
├── emc/                    EMC 引擎（标签/加载/图求解/运行时）
├── content/                内容注册表
├── machine/                机器矩阵（收集器/继电器/凝聚器/熔炉）
├── item/                   功能物品
├── gui/                    窗体渲染
├── menu/                   Menu API（会话/路由/节流）
└── world/                  世界转换（贤者之石）
src/main/resources/content/ 提取的资产/配方/转换/注册表
```

## 许可

移植内容源自 [ProjectE](https://github.com/sinkillerj/ProjectE)（MIT 协议）。
