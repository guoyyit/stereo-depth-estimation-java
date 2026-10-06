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
- **标定内容按版本不可变**：同一版本标识一旦被注册，其内容即冻结。再次注册同一版本时：
  - 内容完全一致（`CalibrationParams.contentEquals`，含版本号与全部数组逐位相等）→ 幂等成功，
    该版本重新成为 `activeVersion`，注册表内容不变；
  - 内容有任何差异 → 显式拒绝，抛 `CalibrationVersionException` 且
    `reason() == CONTENT_CONFLICT`，注册表保持原状（原内容仍可解析，`activeVersion` 不变）。
- 失败原因机器可区分（`CalibrationVersionException.Reason`）：
  `MISSING_VERSION`（版本为空白/null）、`NO_REGISTERED_VERSION`（尚无任何注册）、
  `UNKNOWN_VERSION`（版本号不存在）、`CONTENT_CONFLICT`（同版本不同内容）。
- 帧提交（`StereoProcessingService.submit`）时立即冻结参数快照：**在途帧始终使用其启动时的
  标定内容**，处理期间发生的注册/切换不影响该帧结果（并发交错下该帧输出与无更新时的结果
  逐位一致）；后续未显式指定版本的帧使用新版本；显式请求旧版本仍可处理。
- 同一帧的全部输出（视差、深度、点云）来自同一份标定内容：校正映射缓存同时按版本号与
  内容指纹（`contentFingerprint`）校验，指纹不符即拒绝（`CONTENT_CONFLICT`），
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
- **终态保证（exactly-once）**：每个已提交帧恰好进入成功/失败/取消之一，计数器、历史与 future
  完成三者一致。执行器拒绝接收（如服务已关闭）或服务 `close()` 时仍在途/排队的帧，
  都会被显式完结为可区分的终态（拒绝为失败、关闭为取消），任何情况下 future 都不会永不完成；
  高负载与队列积压下取消任一在途或排队帧，其余帧正常完成，后续帧不被阻塞。
- 同一 `frameId` 在途重复提交会被 `InvalidFrameException` 拒绝。

## 校正几何的独立验证与精度上界

校正几何的验证建立在**独立参考**之上，不与被测流程共用映射计算：

- 测试域 `IndependentCameraModel` 以独立代码路径（四元数旋转、解析雅可比 Newton 去畸变）
  实现 Brown–Conrady 投影/去畸变；`IndependentRectificationReference` 以四元数代数独立
  重推半旋转校正映射。合成场景的原始图由该独立模型生成，**不调用** `RectificationEngine`。
- `RectificationGeometryTest` 强制校验（固定随机种子，可复现）：
  - 引擎映射 vs 独立参考映射的重投影残差 `≤ 0.05px`（实测约 `1e-5px`）；
  - 随机 3D 参考点经独立投影后，引擎校正结果的左右行对齐残差（极线残差）`≤ 0.05px`；
  - 精确对应点的射线-基线共面性残差 `≤ 0.05px`。
- **支持范围**（`RectificationEngine` 常量）：`|k1| ≤ 0.3`、`|k2| ≤ 0.5`、`|k3| ≤ 0.5`、
  `|p1|,|p2| ≤ 0.02`、旋转矢量模长 `≤ 0.35 rad`。范围内校正残差满足上述上界；
  范围外或自检不通过的标定**显式失败**，抛 `RectificationException`，原因机器可区分：
  `OUT_OF_SUPPORTED_RANGE` / `DEGENERATE_BASELINE` / `BEHIND_CAMERA` /
  `SOLVER_DIVERGED` / `NON_FINITE_MAP` / `RESIDUAL_EXCEEDS_BOUND`，
  绝不静默输出偏差过大或非有限的几何。引擎自检含去畸变往返残差 `≤ 0.01px` 与
  映射表有限性检查。

## 精度约定（合成数据，可复现）

合成场景（`SyntheticScene`）由上述独立投影/畸变模型从已知真值视差场
（含 10px 视差阶跃、无纹理矩形块、遮挡背景、相机畸变与小旋转）生成左右原始图，
带真值视差与真值 `InvalidReason`。在固定随机种子下：

- 默认配置（SAD、聚合半径 0、全分辨率）：视差平均绝对误差 `mean ≤ 1.5px`，
  `p95 ≤ 2.0px`，`p99 ≤ 4.0px`
  （评估区剔除图像确定边缘带与 2px 视差不连续邻域；这些边界像素本身被标为 `BORDER`/`OCCLUDED`）。
- 代价×聚合矩阵（SAD/SSD/NCC × 聚合半径 1/2，全分辨率）：`mean ≤ 1.5px`、`p95 ≤ 2.0px`、
  `p99 ≤ 5.0px`（NCC 尾部略宽于默认配置，上界按矩阵单独声明，默认配置上界不放宽）。
- 降采样组合（SAD/SSD/NCC × 聚合半径 0/2 × 0.5/0.25 倍）与同配置全分辨率结果，
  在同为有效的像素上：均值偏差 `≤ 1.5px`，`p90 ≤ 3.0px`。
- 固定输入 + 固定种子下，矩阵内每种组合的逐像素视差与无效标记**逐帧完全一致
  （bit-identical）**，合成数据生成本身同样确定，可复现历史结论。

这些上界由 `AccuracyTest`、`AggregationAccuracyTest`、`ResolutionAndDeterminismTest`
以断言形式强制校验。

## 验证范围

`mvn test`（42 个测试）覆盖：精度与亚像素、三种代价、遮挡/无纹理/边界可区分标记与 NaN 深度、
结果版本标注；标定内容不可变（同版本异内容冲突拒绝、同内容幂等、四类失败原因可区分）、
版本缺失/空白/不存在拒绝、在途版本冻结与逐位一致、更新后新版本、并发注册与提交交错下的
版本可追溯；校正几何独立参考验证（重投影/极线/行对齐残差、较强畸变与旋转、越界与退化
标定的显式可区分失败）；聚合半径大于零的代价×降采样组合精度与逐位确定性；
失败隔离、取消（在途与排队）、高负载积压下取消后其余帧正常完成、关闭时在途帧必然完结、
有界历史、重复帧拒绝；边界模式与参数范围校验。
