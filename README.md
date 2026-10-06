# 本地双目立体深度估计

纯 Java（Spring Boot 3 / JDK 17）实现的本地双目立体视觉深度估计流水线。输入成对的左右目灰度图与相机标定参数，
完成畸变校正、极线校正、立体匹配，输出稠密视差图、深度图与（左校正相机坐标系下的）点云。**不依赖任何外部服务或网络**，
全部正确性与精度验证均基于本地确定性生成的合成数据。

## 处理流程

1. **标定版本解析**：帧在提交时即冻结所使用的标定参数快照（`CalibrationParams`）。
2. **畸变校正 + 极线校正**：Bouguet 半旋转法（`RectificationEngine`）计算左右重映射表，
   对 Brown–Conrady 畸变模型（`k1,k2,p1,p2,k3`）做解析前向映射，再由 `ImageRectifier` 双线性重采样。
3. **可选降采样**：`resolutionScale < 1` 时在校正图上做盒式降采样，匹配在低分辨率进行。
4. **立体匹配**：窗口匹配代价（SAD/SSD/NCC）+ 可选窗口聚合（`aggregationRadius`）+ WTA +
   二次曲线亚像素精化 + 纹理检测 + 唯一性检测 + 左右一致性校验（`StereoMatcher`）。
5. **结果上采样与重建**：低分辨率视差按 `1/scale` 还原到全分辨率；深度 `Z = f·B/d`，
   点云 `X=(x-cx)Z/f`、`Y=(y-cy)Z/f`（左校正相机坐标系，单位米）。

## 无效区域语义（可区分，绝不以 0 或邻近值充当有效深度）

每个像素带一个 `InvalidReason`：

| 标记 | 含义 | 触发条件 |
| --- | --- | --- |
| `VALID` | 有效 | 通过全部校验 |
| `BORDER` | 边界不可靠 | 匹配窗口在搜索范围内触及图像边界（左 `minDisparity+supportRadius`、其余各边 `supportRadius`），或无有效候选视差 |
| `TEXTURELESS` | 无纹理/不可定位 | 窗口灰度标准差 < `textureThreshold`，或次优与最优代价区分度不足（`uniquenessRatio`） |
| `OCCLUDED` | 遮挡/左右不一致 | 左右一致性校验失败，或对极位置落在右图无效区 |

无效像素的视差、深度、点云坐标一律为 `Float.NaN`；有效深度恒为正有限值。

## 边界行为（确定）

`BorderMode` 决定匹配窗口与重映射触及边界时的行为：

- `BORDER_INVALID`（默认）：窗口只要有任一像素落在源图外，该代价/纹理评分为 `NaN`，对应像素不参与匹配。
- `ZERO_PAD`：越界源像素按 0 处理；重映射落区外写入字面量 0。
- `REFLECT`：越界坐标按镜像反射取像素。

无论哪种模式，边界像素的有效/无效判定都由上表的 `BORDER` 规则给出确定结论。

## 配置参数：默认值与取值范围

`PipelineConfig`（`defaults()` 见下表）。构造时对所有范围做强校验，越界即抛
`IllegalArgumentException`。

| 参数 | 默认 | 取值范围 | 语义 |
| --- | --- | --- | --- |
| `matchingCostType` | `SAD` | `SAD` / `SSD` / `NCC` | 匹配代价 |
| `windowRadius` | `2` | `[1,31]` | 匹配窗口半径，窗口边长 `2r+1` |
| `minDisparity` | `0` | `>=0`（像素） | 最小搜索视差 |
| `numDisparities` | `64` | `[8,512]` | 视差搜索宽度 |
| `borderMode` | `BORDER_INVALID` | 三选一 | 边界语义，见上 |
| `aggregationRadius` | `0` | `[0,31]` | 代价聚合附加半径，实际支撑半径为两者之和 |
| `leftRightCheck` | `true` | bool | 是否做左右一致性校验 |
| `leftRightThreshold` | `1.0` | `(0,8]`（像素） | 左右视差允许偏差 |
| `subpixelRefine` | `true` | bool | 二次曲线亚像素精化 |
| `textureThreshold` | `1.5` | `[0,128]`（灰度级） | 窗口灰度标准差下限，低于此判无纹理 |
| `resolutionScale` | `1.0` | `[0.25,1.0]` | 工作分辨率比例（1 为全分辨率） |
| `maxFrameHistory` | `32` | `[1,100000]` | 有界帧历史容量 |
| `uniquenessRatio` | `0.05` | `[0,1]` | `(次优−最优)/最优` 小于此值判为不可定位（无纹理） |

降采样时 `minDisparity/numDisparities` 按 `scale` 缩放，`leftRightThreshold` 同步缩放。

## 标定参数版本一致性保证

**内容不可变语义**：同一版本标识一旦被注册，其标定内容即不可变。

- `CalibrationRegistry.register` 注册带 `version` 的参数；最新注册者成为 `activeVersion`。
- 同一版本重复注册**内容完全一致**的参数是幂等操作（等价于首次注册的结果）。
- 同一版本注册**内容不同**的参数会被显式拒绝，抛出
  `CalibrationVersionException` 且 `kind() == CONTENT_CONFLICT`；被拒绝的注册不会改动已注册内容。
- 失败原因可区分：`CalibrationVersionException.kind()` 为
  `VERSION_MISSING`（版本为空白/null/尚未注册任何版本）、
  `VERSION_UNKNOWN`（版本号不存在）、
  `CONTENT_CONFLICT`（同版本内容冲突）。
- 帧提交（`StereoProcessingService.submit`）时立即冻结参数快照：**在途帧始终使用其启动时的版本与内容**，
  即便处理期间注册表更新；后续未显式指定版本的帧使用新版本；显式请求旧版本仍可处理。
  并发提交与标定更新交错时，每一帧的结果都可追溯到唯一确定的标定内容。
- 同一帧的全部输出（视差、深度、点云）来自同一份标定内容：校正映射缓存按
  "版本 + 内容指纹"（`CalibrationParams.contentFingerprint`）隔离，
  输出上标注的版本与该帧实际使用的标定内容一一对应。
- 每个输出（`StereoResult`、`DepthMap`、`PointCloud`）都带 `calibrationVersion` 标注。

## 帧序列、并发、失败与取消

- `StereoProcessingService` 使用固定大小守护线程池（`parallelism ∈ [1,64]`）支持并发处理。
- 帧历史为有界队列（容量 `maxFrameHistory`，FIFO 淘汰），计数器有界，内存不随帧数增长。
- 单帧失败（尺寸不符、含非有限像素、版本错误等）只影响该帧，`stats().failed()` 计数，
  后续帧照常成功——处理是无状态快照式的，不残留部分状态。
- 取消为**协作式令牌**（`cancel(frameId, …)`）：在每行匹配与各阶段边界检查；取消不中断工作线程、
  不从队列移除任务，因此不会杀死固定线程池中的线程而造成队列死锁，也不会让返回的 future 永不完成。
  被取消帧在 `history` 中 `cancelled=true`，future 以 `FrameCancelledException` 完成。
- **终止一次（terminal-once）保证**：每个已提交帧恰好进入成功/失败/取消三种终态之一，
  `submitted == succeeded + failed + cancelled` 恒成立；在有限并发度、高负载与队列积压下，
  取消任一在途帧或排队帧之后，其余帧正常完成，互不残留部分状态。
- `close()` 不丢弃未应答请求：仍在队列中的帧以取消终态完成其 future
  （`FrameCancelledException`），在途帧不被中断、协作式跑完并正常完成 future；
  关闭后再提交会被明确拒绝并回滚内部状态。
- 同一 `frameId` 在途重复提交会被 `InvalidFrameException` 拒绝。

## 校正几何验证与显式失败

- **独立参考验证**：`IndependentStereoModel`（测试作用域）从模型方程独立重写投影、
  Brown–Conrady 畸变与 Bouguet 校正，不与被测 `RectificationEngine` 共用任何映射计算；
  `IndependentSceneRenderer` 用射线投射独立渲染合成双目图对。校正几何与视差精度的验证结论
  都建立在该独立参考之上，能真实暴露畸变校正与极线校正的重投影误差（本仓库即借此发现并修复了
  Bouguet 主方向半旋转符号错误导致的极线残差）。
- **支持范围与残差上界**：在文档支持的参数范围（旋转旋转向量模长 ≲ 0.35 rad，
  `|k1|,|k2|,|k3| ≲ 0.35`，`|p1|,|p2| ≲ 0.05`）内，校正几何相对独立参考的
  极线残差、重投影残差与视差残差均 `≤ 0.5px`（`RectificationGeometryTest`，固定随机种子，
  残差逐位可复现）。引擎内部另做前向/逆向自校验，上界 `0.1px`
  （`RectificationEngine.MAX_REPROJECTION_RESIDUAL_PX`）。
- **显式失败**：零基线、投影到相机后方、畸变反解不收敛、映射出现非有限值、自校验残差超限，
  一律抛 `RectificationException`（可与版本错误、帧错误区分），绝不静默输出偏差过大或非有限结果。

## 精度约定（合成数据，可复现）

合成场景（`SyntheticScene`）从已知真值视差场（含 10px 视差阶跃、无纹理矩形块、遮挡背景、
相机畸变与小旋转）经逆向映射渲染生成左右原始图，带真值视差与真值 `InvalidReason`；
另有独立渲染场景（`IndependentSceneRenderer`）提供不共用映射计算的交叉验证。在固定随机种子下：

- 视差平均绝对误差 `mean ≤ 1.5px`，`p95 ≤ 2.0px`，`p99 ≤ 4.0px`
  （评估区剔除图像确定边缘带与 2px 视差不连续邻域；这些边界像素本身被标为 `BORDER`/`OCCLUDED`）。
  独立渲染场景上同样满足 `mean ≤ 1.5px`、`p95 ≤ 2.0px`、`p99 ≤ 4.0px`。
- 代价聚合半径大于零（`aggregationRadius = 2`）时，三种匹配代价 × 三种降采样比例
  （1.0 / 0.5 / 0.25）全部满足 `mean ≤ 2.0px`。
- 半分辨率与全分辨率结果，在同为有效的像素上：均值偏差 `≤ 1.5px`，`p90 ≤ 3.0px`。
- 固定输入 + 固定种子下，逐像素视差与无效标记**逐帧完全一致（bit-identical）**，
  对每种代价 × 降采样 × 聚合组合均成立；合成数据生成本身同样确定，可复现历史结论。

这些上界由 `AccuracyTest`、`IndependentAccuracyTest`、`AggregationAccuracyTest`、
`ResolutionAndDeterminismTest` 以断言形式强制校验。

## 验证范围

`mvn test`（38 个测试）覆盖：精度与亚像素、三种代价、遮挡/无纹理/边界可区分标记与 NaN 深度、
结果版本标注；标定内容冲突拒绝与幂等重注册、失败类别可区分、版本缺失/空白/不存在拒绝、
在途版本冻结、更新后新版本、并发提交与标定更新交错时可追溯；强畸变与旋转下校正几何相对独立参考的
极线/重投影/视差残差上界与复现性、极端畸变与极端旋转显式失败；独立渲染场景端到端精度；
聚合半径大于零的多代价多降采样精度与逐像素确定性；多分辨率偏差与逐帧确定性；
失败隔离、积压下取消在途与排队帧、关闭时排队帧取消收尾、并发独立版本化结果、有界历史、
重复帧拒绝；边界模式与参数范围校验。
