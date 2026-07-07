# Black Hole（黑洞）

一个 **Minecraft Java 版 1.21.11 / Fabric** 模组：右键使用「奇点核心」，在视线落点撕开一个黑洞。它从零开始膨胀，停滞期间吞噬周围的方块与实体，最后剧烈坍缩并爆炸。

## 效果

黑洞由五层渲染叠加而成，全部程序化生成、无需贴图：

1. **事件视界** —— 纯黑的球体（写入深度，任何光照下都是绝对的黑）
2. **光子环** —— 紧贴视界、始终朝向镜头的白热光环，向外经橙色衰减
3. **引力透镜光晕** —— 更宽的暗橙色弥散环，模拟被弯折的背景光
4. **吸积盘** —— 世界空间中的倾斜圆盘，具有：
   - 开普勒差速旋转（内圈气体转得更快）
   - 多普勒束射（迎面一侧更亮，参考真实黑洞成像）
   - 动态湍流团块（两组正弦谐波驱动）
   - 每个黑洞独有的盘面倾角与缓慢进动
5. **诞生闪光与坍缩演出** —— 生成瞬间的放射状闪光；坍缩阶段全体抖动、光环增亮，随后爆炸

玩法上：三阶段生命周期（膨胀 7.5 秒 → 停滞 8 秒 → 坍缩 0.9 秒），期间将半径内的方块撕碎吸入（碎屑粒子流向中心）、以螺旋轨迹拉拽并吞噬实体，结束时留下球形深坑并对周围造成冲击波伤害与击退。基岩等无法破坏的方块不受影响；创造/旁观玩家不受引力。

## 获取与使用

```
/give @s blackhole:singularity_core
```

或在创造物品栏「工具与实用物品」分类中找到 **奇点核心**。右键释放（最远 96 格，落点会稍微退出墙面），冷却 20 秒，非创造模式消耗一个。

## 构建

需要 JDK 21 与网络（首次构建会下载 Minecraft 与映射）：

```bash
./gradlew build
```

产物在 `build/libs/blackhole-1.0.0.jar`。开发环境直接进游戏测试：

```bash
./gradlew runClient
```

依赖：Fabric Loader ≥ 0.18.1、Fabric API（开发时使用 `0.140.0+1.21.11`）。

## 调参

数值都集中在常量里，改起来很方便：

| 位置 | 常量 | 含义 |
| --- | --- | --- |
| `BlackHoleEntity` | `GROW_TICKS` / `SUSTAIN_TICKS` / `COLLAPSE_TICKS` | 三阶段时长（tick） |
| `BlackHoleEntity` | `MAX_RADIUS` | 视界最大半径（格） |
| `BlackHoleEntity` | `DEVOUR_RADIUS_SCALE` | 吞噬方块半径 = 视界半径 × 此系数 |
| `BlackHoleEntity` | `PULL_RADIUS_SCALE` | 引力作用半径系数 |
| `BlackHoleItem` | `MAX_CAST_DISTANCE` / `COOLDOWN_TICKS` | 施放距离与冷却 |
| `BlackHoleRenderer` | `DISK_STOPS` | 吸积盘径向颜色渐变（白热 → 橙 → 暗红） |

## 技术说明

- 面向 1.21.9+ 的**提交式渲染管线**编写：实体渲染器实现 `submit(state, PoseStack, SubmitNodeCollector, CameraRenderState)`，自定义几何通过 `SubmitNodeCollector#submitCustomGeometry` 写入 `VertexConsumer`。
- 已按 1.21.11 的改名调整：`ResourceLocation` → `Identifier`；原版渲染类型的静态成员移到 `net.minecraft.client.renderer.rendertype.RenderTypes`（发光层用 `RenderTypes.lightning()` 位置-颜色加色混合，黑球体用 `RenderTypes.entitySolid` + 自带的纯白贴图）。
- 实体存档使用 1.21.6+ 的 `ValueInput` / `ValueOutput`；物品注册使用 1.21.4+ 的 `Properties#setId`。
- 本仓库在无法访问 Mojang/Fabric 下载源的沙箱中编写，**未经过实际编译验证**。代码已逐项对照 NeoForge 的 1.21.10→1.21.11 迁移指南核对；若仍有小幅 API 漂移，最可能需要微调的位置是：`BlackHoleRenderer` 中 `SubmitNodeCollector` / `CameraRenderState` / `RenderTypes` 的导入路径，以及 `EntityRenderer#shouldRender` 的签名——均为局部小改。
