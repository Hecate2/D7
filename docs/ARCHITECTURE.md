# 七日 7D · 架构规划

本文是动工前的架构约定，用于指导实现与自查。需求原文见根目录 `AGENTS.md`，面向用户的原理与操作说明见 `README.md`，界面草图见 `img/` 目录（`img/archive` 为旧稿，不参与实现）。凡本文与草图冲突之处，以 `AGENTS.md` 与 `README.md` 文字为准。

## 1. 产品目标与边界

七日是一个离线、小巧的安卓应用，帮助买房人在看房现场用手机相机测量南侧（或南半球时的北侧）建筑的顶部轮廓，把轮廓拐点记录为若干组（仰角，方位角），再由这些点连成的天际线推算站在测量位置上全年每一天的直射日照时长。它的精度定位是「判断有没有两小时够用，打官司不够」，不替代官方日照分析报告。

明确不做的事：不请求网络权限（数据全部本地）、不做测距（楼多高多远不影响结果）、不建账号不做云同步、视频不参与计算（照片只是瞄准过程的存档与 EXIF 载体）、不强制横竖屏。

## 2. 工程结构

采用两个 Gradle 模块的单应用工程，语言为 Kotlin，界面用经典 View 体系（XML + 自定义 Canvas 视图），不引入 Compose。原因有三：界面核心是相机取景器上的自绘叠加层与图表，Canvas 绘制最直接；经典 View 构建链路短、产物小，符合「非常小巧」的目标；不引入 Compose 编译插件可显著降低构建复杂度与依赖体积。

`:core` 是一个纯 Kotlin JVM 库，不含任何 Android API，承载全部天文与几何算法：太阳位置、天际线求值、日照时长统计、覆盖度计算。把它与 Android 隔离是为了能在普通 JVM 上跑单元测试——这是整个应用唯一有严格正确性要求的部位，传感器与相机只是喂数据。

`:app` 是 Android 应用，包含传感器管线、相机管线、存储、四个界面与自绘视图，依赖 `:core`。

包结构（`:app` 内）约定：`sensor`（姿态与定位）、`camera`（CameraX 封装与照片落盘）、`data`（数据模型与仓库）、`ui.groups` / `ui.capture` / `ui.result`（三个页面）、`view`（自绘控件：取景器叠加层、时间线、全年曲线、覆盖条）、`util`。

## 3. 坐标与角度约定

全文统一：方位角从正北起顺时针 0 到 360 度（北 0、东 90、南 180、西 270）；仰角地平线 0 度、天顶 90 度、向下为负。世界坐标系用 ENU（东-北-天）：x 向东、y 向北、z 向上。

手机上另有两套坐标系。设备坐标系：x 指向屏幕右侧、y 指向手机顶部、z 垂直屏幕向外（指向用户）。Android 旋转矢量传感器给出的旋转矩阵 R 把设备坐标系映射到世界坐标系。关键推论：后摄视轴方向为设备 -z 轴，在世界上表示为世界向量 `cam = -(R[2], R[5], R[8])`（R 按行主序展开），由此可一次性算出后摄视轴的方位角与仰角，且在竖持、横持下都成立，不依赖设备朝向假设。这正是瞄准十字线所对应的方向，也是记录拍摄点所用的方向。

界面上的 `+180°` 药丸保留为手动修正开关：默认填充（视为已叠加），此时读数即后摄视轴方向；点按取消后读数加 180 度（即机身上缘朝向），用于外接镜头或发现指向反向的罕见情形。两种状态恰好相差 180 度，与需求文字一致。

拍摄顺序约定为「从一端扫到另一端」：北半球面向南时从右（西，方位角大）向左（东，方位角小）依次拍，这正是相册中从右到左由旧到新的顺序；南半球面向北时镜像，从右（东）向左（西）。方位角在数据上以 0 到 360 连续存储，跨 0 度的环绕（南半球常见）在求值时以「两角间小于 180 度的短弧」处理。

## 4. 天文算法

太阳位置采用 NOAA 简化公式（Julian 世纪 → 几何平黄经、平近点角、中心差、视黄经、黄赤交角、赤纬、均时差 → 时角 → 高度角与方位角），实现于 `:core` 的 `Solar` 对象。公式取几何高度角，并另提供大气折射修正（NOAA 分段近似）；遮挡判定使用几何高度角，日出日落与「太阳在地平线上」使用几何高度角 -0.833 度阈值（等效上缘+折射），从而与常识中的日出日落表一致。与高精度星历的偏差小于 0.02 度，远小于传感器误差，不是瓶颈。

时间一律先换算为 UTC 瞬时再算太阳位置，输出时再换算到组内记录的时区（`java.time`，minSdk 26 自带，无需脱糖）。均时差自动包含在公式里，所以乌鲁木齐的日照会正确落在北京时间 14 点附近，不会错算到正午。

关键日期不写死天文事件，而是取固定日期：冬至 12 月 21 日（南半球取 6 月 21 日，即当地一年中太阳最低的一天）、夏至对称取 6 月 21 日（南半球 12 月 21 日）、春分 3 月 21 日、秋分 9 月 23 日、大寒 1 月 20 日。取景器里的参考弧按赤纬直接算：春分秋分弧即赤纬 0 度弧，今日弧按当天赤纬。固定日期与真值最多差一两天，对「看最凹处」的用途无影响。

## 5. 天际线模型

一组数据存两个有序点列：外部建筑列 `external[{az, el, gapAfter}]` 与天花板列 `ceiling[{az, el}]`。点序即拍摄顺序，两列各自独立编号（界面显示为「外1、外2」「天1、天2」）。`gapAfter` 只存在于外部列，表示该点与其后一个点（拍摄序的下一点）之间是「长按产生的经地平线推断段」。

每一对相邻点构成一个线段，线段有两种模式。直接连线模式下，线段内任一方位角处的遮挡仰角由两端点仰角按方位角线性插值。经地平线模式即长按产生的空隙：从上一端点垂直降到地平线、沿地平线走到下一端点所在方位角、再垂直升到下一端点，因此两端点方位角之间（开区间）遮挡仰角为 0，两端点处仍取端点自身。这一展开与需求中「上点竖直降到地平线、沿地平线走到新点方向、再竖直升到新点」的三段推断一致。

任一查询方位角的遮挡仰角取所有覆盖该方位角的线段候选取最大值（遮挡取高者）。这一条同时解决三类问题：换向回拍造成的方位重叠、外部列内部自交、未来可能的一区多段。因此求值不假设方位角单调，环绕也不特殊处理——「覆盖」的判定用短弧包含关系：设线段两端方位差 d 取到 (-180, 180]，查询点相对起点的差 t 同法归一，d 与 t 同号且 |t| ≤ |d| 即视为落在段内。

未覆盖语义按需求区分：外部列未拍到的方位视为开阔，天花板列未拍到的方位视为全遮挡。实现上求值接口返回的是「天际线边缘仰角」，未覆盖方位一律返回 -∞，由两种判定方向自然分化：外部区判定「太阳高于边缘」，-∞ 等价于永远高于、即开阔；天花板区判定「太阳低于边缘」，-∞ 等价于永远不满足、即全遮挡。点列为空即「全周未拍到」，按同一规则退化：外部列空则全周开阔，天花板列空则全周遮挡。因此未拍天花板时涉及天花板的档位恒为 0 分钟，界面会禁用这两档并提示先拍天花板；而「只拍外部」这一默认情形正常工作。

覆盖度按需求只统计主方向半圆：北半球 90 到 270 度，南半球取补集（270 到 360 与 0 到 90）。以 1 度步长采样该半圆，统计落在外部列任一线段内的比例，用于照片组卡片与采集页覆盖条。

## 6. 日照评估

`SunlightCalculator` 对一个组、一种计算档、一个日期，以 1 分钟步长从当地 0 时到 24 时采样太阳位置，逐分钟判定「可见直射」。三种档位：仅外部（默认，只要求太阳高于外部天际线）、外部+天花板（在前者基础上再要求太阳低于天花板上沿）、仅天花板（只做后者）。点列为空时按第 5 节的未覆盖规则退化，因此「仅外部」在未拍天花板时就等于纯外部判定，而涉及天花板的档位要求先拍天花板。可见分钟累加即当日直射时长；同时记录可见区间的起止时刻，供时间线绘制与「是否全部来自空隙」判定（全部可见分钟都落在某条经地平线段的开区间内时标注「全部来自空隙」）。

日出日落时刻单独扫描 -0.833 度阈值求得，供时间线两端与文字输出。全年曲线按同样方法逐日计算 365 天（闰年 366），在后台协程执行，结果缓存在内存。

国标辅助卡按 GB 50180-2018 预设给出「大寒日 8:00-16:00 有效直射」的窗口内累计分钟数，并列出各气候区的考核标准文字供用户对照，不做自动达标判定。

## 7. 传感器管线

姿态使用 `TYPE_ROTATION_VECTOR`（加速度计、陀螺仪、磁力计融合），经 `getRotationMatrixFromVector` 得到 R 后按第 3 节方法直接算后摄视轴方位角与仰角，以及供叠加层使用的设备三轴世界向量。仰角以重力为基准不经罗盘，是最稳的量；方位角经磁偏角校正到真北：用 `android.hardware.GeomagneticField(lat, lon, alt, now)` 的 `declination` 加到磁方位上。该 API 内置世界地磁模型，无需联网与自带系数表。

罗盘精度来自旋转矢量传感器的 `onAccuracyChanged`，映射为高/中/低三档显示，并在低精度时给出「画 8 字校准」提示。定位只用 `LocationManager` 的 GPS provider（不申请网络权限，也不用融合定位 SDK），拿到经纬度后立即用 GeomagneticField 算磁偏角、用 `ZoneId.systemDefault()` 记录时区。定位为一次性请求（新建组时或采集页首次进入时），组内保存快照，避免测量中途经纬度跳动引起真北基准漂移。

传感器到界面用一个 `SensorFusion` 类封装：注册/注销、坐标换算、磁偏角叠加、平滑（对显示值做轻量低通，采集时刻直接取融合瞬时值，不引入额外延迟）。

## 8. 相机与照片管线

相机用 CameraX（`camera-core`、`camera-camera2`、`camera-lifecycle`、`camera-view`）：`PreviewView` 负责取景画面，`ImageCapture` 负责拍照，生命周期与 Activity 绑定。视场角从 `CameraCharacteristics` 的焦距与传感器物理尺寸算出（`Camera2CameraInfo` 取特征），失败时退回 65 度水平视场假设；视场角只影响叠加层与实景的贴合度，不影响记录的角度。

每次快门触发 `ImageCapture.takePicture` 存到 cacheDir 临时文件，随后：用 `ExifInterface` 写入拍摄点元数据（方位、仰角、分区、组名、序号、连线模式，编码为 JSON 放进 `TAG_USER_COMMENT`，同时写 GPS 经纬度与时间），然后发布到系统相册。Android 10 及以上走 MediaStore（`Pictures/7D/<组名>/`，IS_PENDING 流程），Android 9 及以下直接写入公共 Pictures 目录后扫描媒体库；发布完成后删除临时文件。记录点到组数据里保存的是 MediaStore 的 content URI，界面缩略图经 `ContentResolver` 异步加载并降采样，URI 失效时显示占位图。

照片落在系统相册是需求的核心交互之一（「相册右边的照片更旧」），因此不额外在应用私有目录留副本；导出功能提供图片与 CSV 的自救通道。权限方面：相机为必需；定位可选（用户也可手输经纬度）；Android 9 及以下写公共目录需要存储权限，Android 10 及以上不需要。

快门按钮的两种按法在 UI 层判定：按下时长超过 450 毫秒且抬起时仍在本屏视为长按（外部区：该点与上一点之间为经地平线推断段），短于阈值视为短按（直接连线）。天花板区屏蔽长按——长按时不拍照并给出提示「天花板区只支持短按连线」。删除键同样长按生效：按下即锁定一个「待删点」（按当前瞄准方位角取右侧最近点，见第 10 节），按住期间不再重算、不重复触发，抬起或移出按钮取消。

## 9. 取景器投影与叠加层

叠加层是一个覆盖在 `PreviewView` 上的自定义 View，每帧（传感器更新驱动，约 30 到 60 次每秒，做 30 毫秒节流）按当前姿态重绘：参考弧、拍摄点与连线、十字线、空隙标记。

投影用针孔模型：世界方向向量 v（由方位角、仰角生成 ENU 单位向量）依次点乘相机的右、上、前三个世界向量得到相机坐标 (x, y, z)，屏幕坐标 = 中心 + (x/z, -y/z) × 焦距像素（焦距由视场角推算）；z ≤ 0.01 的方向视为在相机背后予以剔除，剔除处把折线断开，因此跨视场边缘的弧线只画落在画面里的那一段，近天顶段自然裁剪。相机的右、上向量按显示旋转（`Display.getRotation()` 四种取值）从设备三轴映射得到，竖屏为主用形态，横屏映射在真机上验证后固化。

参考弧按赤纬生成：对给定赤纬按小时角采样全天太阳位置，得到 (az, el) 序列后投影成折线。夏至橙、春秋分红、冬至蓝（均虚线）、今日白（实线）、地平线与铅垂线灰短虚线（默认隐藏）。铅垂线定义为当前十字线方位角上的等方位弧（仰角 0 到 90），作为对垂直参考。显隐由「显示线」设置控制，默认显示四条太阳弧与拍摄点连线，隐藏地平线与铅垂线，设置持久化在 SharedPreferences。

拍摄点渲染：每个点画白色圆点与序号（外部 1..n、天花板 1..n 各自编号），点与点之间按模式画线——直接连线画白色实线；经地平线段拆三段画月灰虚线（上点垂直降到地平线、沿地平线横走、垂直升到新点），与草图一致。覆盖条画在取景器下方，横轴为 90 到 270 度（南半球镜像），已达区间白色、未达区间炭灰，同时可点击该条的「显示线」按钮打开线显隐设置。

## 10. 界面与交互

四个界面：

照片组管理是全应用入口。卡片显示组名、冬至结论（该组当前默认档位下的冬至日直射时长，未拍显示「未测」）、采集日期与经纬度、外部区点数与覆盖率、天花板区状态（点数或未拍虚框）。点卡片进结果页，长按弹出改名/删除/导出（删除对话框提供「同时删除相册中的 N 张照片」复选框，默认不勾；组内没有带照片的点时不显示该框，勾选确定后逐个删除照片 URI，失败数量以提示告知），右上「+ 新建」走建组对话框（组名、纬度经度输入与「用 GPS 定位」按钮、南北半球提示），确认后直接进入采集页开拍。

采集页自上而下：标题栏（组名、GPS 与真北校正状态、罗盘精度）、大号仰角与方位读数（加 `+180°` 药丸）、取景器（左上分区 chip：外部建筑/天花板，右侧为覆盖条与「显示线」）、方向提示（北半球「面向南，西·先拍 → 东·后拍」、南半球镜像）、底部三键（左「完成」、中快门、右「删除」）。快门短按：记录当前十字线指向为新的拍摄点并与上一点直接连线；长按（外部区）：记录并以经地平线推断段与上一点连接；天花板区仅短按，且与上一点直接连线。删除键长按：在当前分区内删除「十字线右侧、离当前瞄准方位最近」的一个点（判定为相对当前方位角的顺时针角距最小者，即 `normalize(az_point - az_now) ∈ (0°, 180°)` 中角距最小），按住一次只删一个；左侧的点不受影响。完成键保存并跳转结果页。覆盖条之外，取景器内直接画出已拍点与连线（第 9 节）。续拍：从结果页「回采集续拍」返回该组，新点按分区各自原序续接。

结果页自上而下：标题与元信息、日期药丸（冬至/大寒/春分/夏至/自定义，自定义弹日期选择器）、计算档三档（仅外部默认/外部+天花板/仅天花板）、主结果卡（选中日期在该档下的直射时长、日出日落、来自空隙标注）、当天时间线（日出到日落一条横条，白=直射、炭灰=被挡，标注起止时刻）、国标卡（大寒 8:00-16:00 有效直射与预设说明）、全年曲线（365 天时长折线，横轴月份刻度）、点列区（每行：分区标记、序号、缩略图、方位、仰角、与左邻点的连线模式切换按钮、删除按钮；整行可点开单点角度编辑；标题行右侧「编辑」按钮打开批量编辑；天花板点无模式切换）、底部导出 CSV / 导出图片 / 回采集续拍。所有计算在后台协程执行，界面先显示上次缓存或加载态。

结果页点列区的编辑动作直接改组数据：删除任意点；切换某点与拍摄序下一点的连线模式（直接连线 ⇄ 走地平线，仅外部点可切换）。删除点后其前后两点的连接改为直接连线（避免悬空的经地平线段），这与「删除即撤掉该点及其连接段」一致。

角度编辑分两级且一律「强行」生效（不受照片限制）。单点：点击某行弹出对话框改写方位角与仰角，照片与拍摄时间保留；方位角规范化到 [0, 360)，仰角限制到 [0, 90]。批量：点列标题行右侧「编辑」按钮打开批量编辑器，外部区与天花板区各一段多行文本，逐行「方位角 仰角 [horizon]」（第三词写 horizon 或 h、0 均可，大小写不敏感，表示与下一点之间经地平线），可先填方位偏移与仰角偏移、点「应用偏移」对全文整体平移再确认；确认时按行数决定照片继承——行数不变则按序继承原照片与拍摄时间，增删行则新点为无图点（photoUri 为空，界面显示占位缩略图）。因此不拍照也能手工搭出完整点列，供计算、导出与测试使用。

分屏适配：所有 Activity 声明 `resizeableActivity=true`，不锁定方向，`configChanges` 声明常见尺寸变化避免拖动分屏时重建；相机页布局全部用约束与权重，分屏小窗时压缩而非截断关键控件（取景器优先占满剩余空间）。相机在分屏下按平台允许情况运行，不额外做多窗口互斥逻辑。

## 11. 数据模型与持久化

组数据用 kotlinx.serialization 序列化为 JSON，存 `filesDir/groups.json`，写入采用「先写临时文件再原子改名」。照片本体在系统相册，JSON 里只存 URI。模式如下：

```json
{
  "version": 1,
  "groups": [
    {
      "id": "uuid",
      "name": "阳台",
      "lat": 31.23, "lon": 121.47, "altitude": 12.0,
      "zoneId": "Asia/Shanghai",
      "createdAt": 1759400000000, "updatedAt": 1759400000000,
      "external": [
        {"az": 236.2, "el": 18.4, "gapAfter": false, "photoUri": "content://...", "takenAt": 1759400000000}
      ],
      "ceiling": [
        {"az": 182.0, "el": 64.8, "gapAfter": false, "photoUri": "content://...", "takenAt": 1759400000000}
      ]
    }
  ]
}
```

导出提供两条通道：CSV（组信息、分区、序号、方位、仰角、连线模式、拍摄时间，以及所选日期的逐分钟可见性）写入 `Downloads/7D/`；导出图片把「天际线极坐标图 + 关键结论」渲染为 PNG 存入相册 `Pictures/7D/`。两者都走 MediaStore（低版本走公共目录+扫描）。

## 12. 构建与工具链

工程使用 Gradle Wrapper 固定版本，不依赖本机 Gradle。版本矩阵：JDK 17（Temurin）、Gradle 8.11.1、Android Gradle Plugin 8.7.3、Kotlin 2.0.21（含 kotlinx.serialization 插件）、compileSdk 35、targetSdk 35、minSdk 26。依赖：androidx core-ktx / appcompat / activity-ktx / constraintlayout / recyclerview / lifecycle-runtime-ktx、Google Material Components、CameraX 1.4.x（core、camera2、lifecycle、view）、kotlinx-serialization-json、androidx exifinterface、coroutines；测试仅 JUnit4（`:core` 的 JVM 单测）。

本机缺什么装什么：JDK 与 Gradle 用 Homebrew 安装，Android SDK 用命令行工具（cmdline-tools）安装 platform-tools、platforms;android-35、build-tools;35.0.0 并接受许可。工程内 `gradle.properties` 指定 JDK 17 路径，保证命令行与 IDE 行为一致。

常用命令：`./gradlew :core:test`（算法单测）、`./gradlew :app:assembleDebug`（产出 APK）、`./gradlew :app:installDebug`（装机）。

## 13. 实施阶段与提交计划

按下列顺序实施，每阶段一个可编译、可验证的提交：

其一，架构文档与 `.gitignore`（忽略 `.workbuddy/` 与 `img/archive/`，后者按需求不提交）。其二，Gradle 工程骨架（双模块、版本矩阵、图标资源与主题配色，目标：`assembleDebug` 通过）。其三，`:core` 算法模块与单元测试（太阳位置对拍与物理合理性断言、天际线求值、空隙、环绕、半球镜像、日照统计）。其四，数据层与照片组管理界面（JSON 仓库、建组/改名/删除、卡片）。其五，采集界面（CameraX、传感器、投影叠加层、两种快门、长按删除、完成、分区 chip、覆盖条、线显隐设置）。其六，结果界面（三种档位、日期药丸、时间线、全年曲线、点列编辑、导出）。其七，打磨与真机验证（分屏、图标、配色校对、装机实测与修正）。

## 14. 风险与对策

姿态读数在手机接近垂直时方位抖动（机身上缘水平投影趋零所致）：主用姿态是略微后仰瞄准楼顶，抖动可接受；显示层做低通平滑；测量值取瞬时融合值。横屏时设备三轴到屏幕的映射需真机验证一次，错了只影响叠加层贴合，不影响记录数据。罗盘受阳台钢筋干扰属物理限制，界面提示画 8 字校准并在低精度时显著提示，数值不追求优于 2 到 4 度。不同厂商相机在多窗口下的可用性有差异，兜底方案是分屏下暂停预览但保留传感器读数。MediaStore URI 被外部清理时缩略图缺失、角度数据不受影响，导出功能提供数据自救。

## 附录 A：代码级实施清单

应用 ID 与包名统一用 `io.github.hecate2.sevend`，`:core` 模块包名 `io.github.hecate2.sevend.core`。

`:core` 文件清单与关键 API：

- `Solar.kt`：`data class SolarPosition(val azimuthDeg: Double, val elevationDeg: Double)`；`object Solar` 提供 `position(utcMillis: Long, latDeg: Double, lonDeg: Double, refraction: Boolean = true): SolarPosition`（NOAA 公式，见第 4 节）、`julianDay(utcMillis: Long): Double`。几何高度角配 -0.833 度阈值即日出日落。
- `Skyline.kt`：`data class ShotPoint(val azDeg: Double, val elDeg: Double, val viaHorizonAfter: Boolean = false)`；`class Skyline(points: List<ShotPoint>)` 提供 `obstructionAt(azDeg: Double): Double`（返回天际线边缘仰角；未覆盖方位返回 -∞，由判定方向分化为外部开阔与天花板全遮挡，见第 5 节）、`gapArcs(): List<Pair<Double, Double>>`、`coverage(points: Int = 360): BooleanArray`（按 1 度采样全周是否被覆盖，供覆盖条与覆盖率用）。
- `Sunlight.kt`：`enum class CalcMode { EXTERNAL_ONLY, EXTERNAL_AND_CEILING, CEILING_ONLY }`；`data class DailySunlight(date, sunriseMinute: Int?, sunsetMinute: Int?, directMinutes: Int, visibleIntervals: List<IntRange>, allFromGap: Boolean, daylightMinutes: Int)`（分钟序号自当地 0 时起）；`object SunlightEvaluator` 提供 `evaluate(lat, lon, zoneId: String, external: List<ShotPoint>, ceiling: List<ShotPoint>, mode, date: LocalDate, stepMinutes: Int = 1): DailySunlight`、`yearlyCurve(..., year: Int, mode): List<Double>`、`windowMinutes(..., date, fromMinute: Int, toMinute: Int, mode): Int`。点列为空按第 5 节退化：外部列空则全周开阔、天花板列空则全周遮挡（界面据此禁用涉及天花板的档位）。
- 单测：`SolarTest.kt`（对拍第二套独立实现的低精度日下点公式与物理合理性断言：北半球正午方位约 180、夏至正午高度角约 90-lat+23.4、春秋分日出方位约 90、赤道昼长约 12h07m、南半球正午方位约 0）、`SkylineTest.kt`（插值、空隙三段、重叠取最大、环绕 350→10、覆盖度）、`SunlightTest.kt`（三档模式语义、空隙穿透、极夜零分钟、南半球镜像）。

`:app` 文件清单（包 `io.github.hecate2.sevend` 下）：

- `data/Model.kt`：`@Serializable PointRecord(az, el, gapAfter, photoUri: String?, takenAt: Long)`、`@Serializable GroupRecord(id, name, lat, lon, altitude, zoneId, createdAt, updatedAt, external: MutableList<PointRecord>, ceiling: MutableList<PointRecord>)`、`@Serializable Store(version = 1, groups)`；`GroupRepository`（单例，`filesDir/groups.json` 原子写，`StateFlow<List<GroupRecord>>`，CRUD、`touch()`、`updatePointAngles()` 与 `setRegionPoints()`）。
- `sensor/OrientationSensor.kt`：注册旋转矢量，输出 `data class Pose(camAzDeg, camElDeg, right: FloatArray, up: FloatArray, forward: FloatArray, accuracy: Int)`；后摄视轴 `-col2(R)`，显示旋转到屏幕右/上向量的映射四种取值（ROTATION_90 的映射需真机验证）；磁偏角经 `GeomagneticField` 叠加（绕世界 z 轴旋转三个基向量）。`sensor/LocationProvider.kt`：GPS 单次定位 + `GeomagneticField` 磁偏角 + `ZoneId.systemDefault()`。
- `camera/PhotoStore.kt`：快门拍照 → cacheDir 临时文件 → ExifInterface 写 `TAG_USER_COMMENT`（JSON：az/el/zone/group/seq/gap）+ GPS → 发布 MediaStore `Pictures/7D/<组名>/`（API 29+ IS_PENDING；API 28- 公共目录+扫描）→ 返回 content URI。`camera/CameraController.kt`：CameraX 绑定，`focalPx` 计算（见第 9 节，`focal_mm × max(viewW/传感器转屏宽mm, viewH/传感器转屏高mm)`，含 FILL_CENTER 裁剪），失败退回半视场角 32 度。
- `view/ViewfinderOverlayView.kt`：叠加层（参考弧按赤纬采样小时角生成，投影公式 `screenX = cx + (x/z)·focalPx`，`z ≤ 0.01` 剔除并断线）；`view/CoverageBarView.kt`；`view/TimelineView.kt`；`view/YearCurveView.kt`；`view/PolarSkylineView.kt`（导出图片用，极坐标天际线图）。
- `ui/groups/GroupsActivity.kt`（RecyclerView 卡片、建组对话框=组名+经纬度+GPS 按钮、长按改名/删除（可勾选连带删照片）/导出）、`ui/capture/CaptureActivity.kt`（布局自上而下：标题栏、大小读数+`+180°` 药丸、取景器+chip+覆盖条+显示线、方向提示、底部完成/快门/删除；快门 450 毫秒阈值区分短长按，天花板区长按给提示不拍照；删除长按单次删当前分区内十字线右侧最近点）、`ui/result/ResultActivity.kt`（日期药丸、三档、主卡、时间线、国标卡、全年曲线、点列区（删点、连线模式切换、单点与批量角度编辑）、导出 CSV/图片、回采集续拍）、`ui/Settings`（SharedPreferences 存线显隐与上次档位）。
- 资源：`res/values/colors.xml`（第 9 节配色，含 `ink #000000`、`card #161618`、`moon #CAC2D1`、`smoke #8E8E93`、`stroke #2E2E32`、`winter #378ADD`、`equinox #E24B4A`、`summer #EF9F27`）、Material3 暗色主题、图标由根目录 PNG 生成自适应图标。

实施时按第 13 节顺序提交，每阶段跑 `:core:test` 与 `:app:assembleDebug` 作为门槛，最后 `installDebug` 到真机（当前连接设备为 vivo PD2164PA，Android 11 / API 30）实测。

## 附录 B：国际化（internationalization，缩写 i18n）设计

界面文案一律只从资源文件 `res/values/strings.xml` 读取，Kotlin 代码中不写死任何面向用户的中文文本；这条约定覆盖的不只是布局里的标签，还包括 Toast 提示、导出逗号分隔值（comma-separated values，缩写 CSV）文件的表头与字段名、导出参考图 PNG 内绘制的文字、自绘视图画布上的刻度与提示文字。中文是默认资源（`values/` 目录），因此新增文案先以中文写进默认资源；格式化型文案（时长、日期档位名、方位刻度等）同样进资源，用带位置参数（如 `%1$s`、`%2$02d`）的格式串在代码侧填充，避免在代码里拼接语序。

第二语言的落地流程是纯机械的：新建 `res/values-<语言>/strings.xml`（例如 `values-en/`，当前已建好目录但只含 `app_name` 一条作为桩），补齐需要翻译的条目即可，未翻译的条目自动回退到中文默认值。系统级「按应用设置语言」入口由两部分提供：`res/xml/locales_config.xml` 声明支持的语言清单（`zh-Hans` 与 `en`），`AndroidManifest.xml` 的 `android:localeConfig` 属性把它挂到应用上，Android 13 及以上即可在系统设置中为应用单独选语言。

当前处于脚手架阶段，明确不做的部分：不维护中文以外的完整翻译；不做应用内自有的语言切换界面（交给系统设置）；导出文件名与 CSV 内容随当前系统语言变化，不追求跨语言的稳定一致。以 `Format` 工具类为例，时长类函数全部改为接收 `Resources` 后从字符串资源取词（`durationShort` 与 `durationLong`），它在列表页、结果页与导出模块的三个调用场景共用同一套资源。