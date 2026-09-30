# Stereo Depth Estimation (Java)

本地双目立体深度估计库：输入成对采集的左/右目灰度图像与相机标定参数，
经畸变校正与极线校正后输出稠密视差图、深度图与可选点云。全部验证基于
本地生成的合成数据，不依赖任何外部服务。

构建与测试：

```bash
mvn test
```

## 处理流程

`StereoPipeline.process(pair, wantPointCloud)` 依次执行：

1. **钉住标定版本**：从 `CalibrationRegistry` 取当前版本，整帧只使用该版本。
2. **畸变校正与极线校正**（`Rectifier`）：对每个校正后像素，经内参反投影、
   校正旋转的逆、径向畸变模型（k1, k2）与再投影得到源图坐标，双线性采样；
   源坐标越界时确定性地填 0。
3. **立体匹配**（`StereoMatcher`）：代价计算 → 聚合 → 赢家通吃 →
   左右一致性校验 → 亚像素精化。
4. **深度转换**：`z = focalPixels * baselineMeters / disparity`。
5. **可选点云**：仅由有效深度像素生成，坐标系为校正后左目相机系。

每个 `StereoResult` 都标注其使用的标定参数版本
（`calibrationVersion()`，深度图与点云同样携带），版本不一致的混用
会被 `CalibrationException` 明确拒绝。

## 标定版本语义

- `CalibrationRegistry.publish(params)` 发布新版本并设为当前版本；
  已发布版本的内容不可变——以不同内容重复发布同一版本号会被拒绝，
  以相同内容重复发布是空操作。
- 在途帧（已开始处理的帧）继续使用其启动时钉住的版本；之后提交的帧
  使用新版本。
- 查询不存在的版本、空版本号、结果内各产物版本不一致，均抛出
  `CalibrationException`。

## 配置参数（`StereoConfig`）

| 参数 | 默认值 | 取值范围 | 语义 |
| --- | --- | --- | --- |
| `minDisparity` | 0 | [0, ∞) | 最小视差（像素） |
| `numDisparities` | 64 | [1, 256] | 视差搜索个数，搜索范围为 [minDisparity, minDisparity+numDisparities) |
| `blockRadius` | 4 | [0, 32] | 匹配/聚合窗口半径，窗口边长 2r+1 |
| `cost` | `SAD` | `SAD`/`SSD`/`NCC` | 逐像素匹配代价；NCC 在窗口上计算，半径取 max(blockRadius, 1) |
| `aggregation` | `BOX` | `NONE`/`BOX` | 代价聚合策略；`NONE` 要求 blockRadius=0，`BOX` 要求 blockRadius≥1 |
| `textureThreshold` | 0.002 | [0, ∞) | 局部灰度方差低于该值判为无纹理 |
| `lrTolerance` | 1.0 | [0, ∞) | 左右一致性允许的最大视差差（像素） |
| `subpixelEnabled` | true | true/false | 是否对赢家视差做抛物线亚像素精化 |
| `downsampleFactor` | 1 | [1, 8] | 降采样因子；图像宽高必须整除该因子 |
| `randomSeed` | 0 | 任意 long | 预留的可复现性种子；当前管线完全确定 |

非法取值在构建时抛出 `IllegalArgumentException`。

### 边界行为（确定性）

- 代价与聚合窗口在图像边界处裁剪，仅统计界内样本；`BOX` 聚合取界内样本均值。
- 视差 d 在列 x 可评估当且仅当匹配列（左→右为 x−d，右→左为 x+d）在图内；
  不可评估的视差代价为 +∞，除非别无选择否则不会被选中；聚合不会把
  可评估性从邻居借来（中心像素为 ∞ 则聚合后仍为 ∞）。
- 所有视差均不可评估的像素标记为 `BORDER`。
- 赢家通吃的平局取最小视差。

## 无效标记语义

无效像素的视差/深度恒为 `NaN`，**绝不**以零值或邻近值填充；每个像素
携带可区分的 `InvalidReason`：

| 标记 | 含义 |
| --- | --- |
| `VALID` | 有效测量 |
| `OCCLUSION` | 左右一致性校验失败（遮挡/无可靠匹配） |
| `TEXTURELESS` | 局部方差低于 `textureThreshold` |
| `OUT_OF_RANGE` | 视差无法产生有限正深度（d ≤ 0） |
| `BORDER` | 搜索范围内无可评估视差 |

判定优先级：`BORDER` > `TEXTURELESS` > `OCCLUSION`。深度图继承视差图
的逐像素标记。

## 精度约定（合成数据上可复现验证）

以下误差上界由 `StereoAccuracyTest` / `MultiResolutionTest` 在固定种子
合成场景（128×96，随机噪声纹理 + 遮挡盒 + 无纹理区）上验证：

- 视差：有效且有纹理像素（半径 4 窗口方差 ≥ 4×textureThreshold）上
  平均绝对误差 ≤ 0.35 px；距深度突变（3×3 真值视差跨度 > 2 px）
  blockRadius+1 以内的像素不参与最大误差统计，其余像素最大误差 ≤ 1.5 px。
- 深度：平均相对误差 ≤ 6%。
- 亚像素精化后的平均误差严格优于整数视差。
- 块匹配在深度突变两侧各 blockRadius+1 像素内可能沿边缘扩散错误视差，
  这是窗口匹配的固有行为，不计入最大误差上界。

## 多分辨率

`downsampleFactor > 1` 时，图像按因子盒式平均降采样，视差搜索范围、
窗口半径与一致性容差按比例缩放，结果最近邻上采样回输入分辨率并乘回
视差尺度。低分辨率与全分辨率结果在共同有效且非突变像素上的偏差：
平均 ≤ 1.0 px，95 分位 ≤ 2.0 px（个别像素因低分辨率聚合差异可能更大，
见 `MultiResolutionTest`）。图像尺寸不能整除因子时抛出
`StereoProcessingException`。

## 帧序列与并发

`FrameProcessor(pipeline, maxHistory, parallelism)`：

- `submit(pair, wantPointCloud)` 异步处理单帧，返回 `CompletableFuture`。
- 历史结果保存在容量为 `maxHistory` 的有界队列中，超龄自动淘汰，
  内存不随帧数无界增长。
- 单帧失败以异常完成该帧的 future，不影响后续帧；历史只记录完整成功的帧。
- 取消 future 会在下一个阶段边界协作式中止该帧
  （`ProcessingCancelledException`），不留下影响后续帧的部分状态。
- 并发处理与顺序处理结果逐比特一致。

## 可复现性保证

固定输入与固定随机种子下，输出逐帧完全一致（逐比特相同）：匹配管线
无随机性、无线程间共享可变状态，合成数据生成器由种子唯一确定。
`StereoAccuracyTest.fixedSeedAndInputReproduceIdenticalOutput` 与
`FrameProcessorTest.concurrentResultsMatchSequentialResults` 覆盖该保证。

## 代码结构

```
src/main/java/com/github/guoyyit/stereodepthestimationjava/stereo/
  CalibrationParams      标定参数（含版本号，不可变）
  CalibrationRegistry    版本化标定注册表
  Rectifier              畸变校正 + 极线校正
  StereoMatcher          代价/聚合/WTA/左右一致性/亚像素
  StereoConfig           匹配配置（默认值与取值范围校验）
  StereoPipeline         单帧编排（版本钉住、降采样、取消）
  FrameProcessor         帧序列增量/并发处理（有界历史）
  DisparityMap/DepthMap  稠密输出（NaN + InvalidReason）
  PointCloud/StereoResult 点云与结果（均标注标定版本）
  SyntheticDataGenerator 本地合成数据（含真值）
```
