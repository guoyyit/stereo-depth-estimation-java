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

- `CalibrationRegistry.register` 注册带 `version` 的参数；最新注册者成为 `activeVersion`。
- 帧提交（`StereoProcessingService.submit`）时立即冻结参数快照：**在途帧始终使用其启动时的版本**，
  即便处理期间注册表更新；后续未显式指定版本的帧使用新版本；显式请求旧版本仍可处理。
- 请求版本为空白、`null` 且无任何已注册版本、或版本号不存在时，明确抛
  `CalibrationVersionException`，拒绝处理，绝不混用或猜测。
- 每个输出（`StereoResult`、`DepthMap`、`PointCloud`）都带 `calibrationVersion` 标注。

## 帧序列、并发、失败与取消

- `StereoProcessingService` 使用固定大小守护线程池（`parallelism ∈ [1,64]`）支持并发处理。
- 帧历史为有界队列（容量 `maxFrameHistory`，FIFO 淘汰），计数器有界，内存不随帧数增长。
- 单帧失败（尺寸不符、含非有限像素、版本错误等）只影响该帧，`stats().failed()` 计数，
  后续帧照常成功——处理是无状态快照式的，不残留部分状态。
- 取消为**协作式令牌**（`cancel(frameId, …)`）：在每行匹配与各阶段边界检查；取消不中断工作线程、
  不从队列移除任务，因此不会杀死固定线程池中的线程而造成队列死锁，也不会让返回的 future 永不完成。
  被取消帧在 `history` 中 `cancelled=true`，future 以 `FrameCancelledException` 完成。
- 同一 `frameId` 在途重复提交会被 `InvalidFrameException` 拒绝。

## 精度约定（合成数据，可复现）

合成场景（`SyntheticScene`）用与 `RectificationEngine` 相同的前向投影公式，从已知真值视差场
（含 10px 视差阶跃、无纹理矩形块、遮挡背景、相机畸变与小旋转）生成左右原始图，带真值视差与真值
`InvalidReason`。在固定随机种子下：

- 视差平均绝对误差 `mean ≤ 1.5px`，`p95 ≤ 2.0px`，`p99 ≤ 4.0px`
  （评估区剔除图像确定边缘带与 2px 视差不连续邻域；这些边界像素本身被标为 `BORDER`/`OCCLUDED`）。
- 半分辨率与全分辨率结果，在同为有效的像素上：均值偏差 `≤ 1.5px`，`p90 ≤ 3.0px`。
- 固定输入 + 固定种子下，逐像素视差与无效标记**逐帧完全一致（bit-identical）**，
  合成数据生成本身同样确定，可复现历史结论。

这些上界由 `AccuracyTest`、`ResolutionAndDeterminismTest` 以断言形式强制校验。

## 验证范围

`mvn test`（23 个测试）覆盖：精度与亚像素、三种代价、遮挡/无纹理/边界可区分标记与 NaN 深度、
结果版本标注；标定版本缺失/空白/不存在拒绝、在途版本冻结、更新后新版本；多分辨率偏差与逐帧确定性；
失败隔离、取消、并发独立版本化结果、有界历史、重复帧拒绝；边界模式与参数范围校验。
