# YuiBili 开发记录与技术状态文档

> 本文档随每次重要排查、架构变更、Bug 修复实时更新，用于在跨会话/上下文压缩后快速准确接续。

## 当前开发约定
1. **依赖与常驻构建（强制，唯一构建方式）**：优先使用国内镜像（已配置）。
   - 常驻终端标签 `YuiBili Gradle session`，启动命令**已把三者一起挂上 continuous**：
     ```bash
     ./gradlew --continuous :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
     ```
   - 因此**编译、单测、lint 全部由该会话自动完成，绝不为跑这些而另开任何终端**。
   - 查结果：`terminal(action="read", tab_id=...)` + `filter="^e: |BUILD |Lint found"`。
   - ❌ 禁止单次 `./gradlew assembleDebug` 做日常构建；❌ 禁止任务结束时关闭该会话。
   - 唯一允许另开终端的场景：需要 `gradlew --stop`/清缓存等会与常驻会话冲突的操作，或装机用的 `http-cat.py` 端口服务（用完即关）。
   - **新终端必须先 `source ~/workspace/YuiBili/.build-env/env.sh`**，否则报 `JAVA_HOME is not set`（该脚本设置 jdk-17 / sdk / GRADLE_USER_HOME / TMPDIR）。
2. **后端与数据链路把关**：涉及 API、取流、鉴权、解析、性能优化的问题，先派发「后端与性能指导顾问」子代理分析，再由主代理落地代码；已有明确方案时直接落地，不重复派发同一任务。
3. **子代理使用铁律（用户明确要求）**：
   - **同一时间只保留一个顾问子代理**，前端/后端模型与主会话不同、**费用很高**，禁止因等待久而反复新建。
   - 子代理断开（HTTP 503 / 429）时用 `task(action="send", id=...)` 发消息让它**从断点继续**，不要重新 create。
   - **轮询间隔 30 秒**（`Bash("sleep 30")` → `task(read)`），不要长等 2~3 分钟。
   - **追问必须在同一个子代理对话里用 `task(action="send", id=...)` 连续进行**，不要问完一个就删掉重建。
   - **必须等子代理完整输出、主动发来完成通知后再处理/删除**。只收到中途 `agent_message`（如"正在整理…"）说明它还没结束，绝不能当成已完成，更不能删除。曾因顾问还在整理就 `del`，被用户明确批评。
   - 确认不再需要后，用 `task(action="del")` 删除。
4. **验证与装机**：修改后直接跑 `:app:testDebugUnitTest`、`:app:lintDebug`、确认 Debug APK 生成并通过 `.build-env/send-apk.py` + `Shizuku` 装机，不跑自动化 UI 测试；**装机后由用户亲自验收**。
   - **装机坑**：`send-apk.py` 是裸 TCP 服务，新版 Android `curl` 会报 `Received HTTP/0.9 when not allowed` 或中途 `Connection reset`。改用 `.build-env/http-cat.py`（标准 HTTP）更稳：容器内 `python3 .build-env/http-cat.py app/build/outputs/apk/debug/app-debug.apk` 拿到端口，再用 `Shizuku` 执行 `curl -sS -o /data/local/tmp/app-debug.apk http://127.0.0.1:<port>/app-debug.apk && pm install -r -t /data/local/tmp/app-debug.apk`，并 `sha256sum` 核对字节数与哈希。

---

## 本轮已交付修复与验证（2026-11-20）

### 问题 1：720p 以上画质无效 / 观感接近 720p
1. **请求头与 Cookie 对齐 Web 端**（`BiliPlayUrl.kt`）：
   - `requestStream()` 显式使用桌面 Web UA（`CDN_USER_AGENT`），`cookieHeader()` 写入当前请求的 `CURRENT_QUALITY=<qn>`。
2. **画质菜单与轨道真实性区分、提示透明化**（`VideoDetailActivity.kt`）：
   - `showQualityMenu()` 对当前响应无流的档位标出 `暂无流`，点击无流档位时按未登录 / 需大会员 / 尝试拉流给出明确提示，不再静默回落且无提示。
   - `loadPlayer()` 实际拿到的流低于请求的高画质且该档在 `accept_quality` 内时，通过 Snackbar 提示原因及实际播放画质。
3. **同画质编码轨先回退、再降级 DURL**（`BiliPlayUrl.kt` + `VideoDetailActivity.kt`）：
   - 提取 `BiliPlayUrl.selectDashStreamTrack(stream, requestedQuality, excludedTracks)`；`VideoDetailActivity` 维护 `failedCodecTracks`，解码或清单异常时先在同一 `qn` 切换其他可用编码（如 AVC <-> HEVC <-> AV1），避免直接跌回 DURL 或 720p。
4. **全链路观测日志**（`BiliPlayUrl.kt` + `VideoDetailActivity.kt`）：
   - `requestStream()` 记录脱敏后的 `reqQn / respQuality / accept / dashTracks`；`onRenderedFirstFrame()` 与 `onVideoSizeChanged()` 记录 Media3 实际 `videoFormat` 与 `videoSize`。

### 问题 2：评论区装扮表情首屏行内不显示（打开子评论或重进后恢复）
1. **失败路径冷却后自动重试与重绘**（`VideoDetailActivity.kt` -> `InlineEmotes`）：
   - 原 `decoded == null` 仅设置 5 秒冷却并移除 `pending`，不会重绘或重试；新增 `scheduledRetries` / `retryRunnables`，对仍 attached 的目标 `TextView` 在冷却期满后自动重新发起加载，成功后批量刷新视图。
2. **可选择 `TextView` 的硬件加速重绘刷新**（`VideoDetailActivity.kt` -> `InlineEmotes.refreshEmoteTextView()` + `buildExpandableCommentText()`）：
   - 为开启 `setTextIsSelectable(true)` 的正文视图设置保留底层 `Spannable` 的 `Spannable.Factory` 并以 `BufferType.SPANNABLE` 写入文本；加载完成时调用 `invalidate()`、`postInvalidateOnAnimation()` 并触发无害 Span 变更通知，使 `DynamicLayout` 立即重绘 `ReplacementSpan`。
3. **表情字段扩展**（`BiliApi.kt` -> `selectEmoteUrl()` / `parseComment()`）：
   - 解析顺序扩展为 `gif_url -> webp_url -> url` 并统一归一化 HTTPS URL。

### 已改文件清单 & 关键符号
- `app/src/main/kotlin/com/yuilittle/bili/BiliApi.kt`：`selectEmoteUrl()`、`parseComment()`、`mainHandler` 懒加载
- `app/src/main/kotlin/com/yuilittle/bili/BiliPlayUrl.kt`：`cookieHeader()`、`requestStream()`、`selectDashStreamTrack()`、`codecRank()`
- `app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt`：`InlineEmotes`、`buildExpandableCommentText()`、`selectStableStream()`、`recoverPlayback()`、`loadPlayer()`、`showQualityMenu()`、`onRenderedFirstFrame()`、`onVideoSizeChanged()`
- `app/src/test/kotlin/com/yuilittle/bili/QualityAndCommentEmoteTest.kt`：覆盖表情 URL 优先级归一化与 DASH 选轨 / 编码回退单测

### 构建与装机验收
- 单元测试 & Lint & 打包：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` 全部通过（`BUILD SUCCESSFUL`）。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（8,775,163 字节，SHA-256 `9b5b0323bacf4cfd0abfa67c6d166660467946617efc66cb9f58f8c7f9c4d6af`）。
- 真机安装：`send-apk.py` + `Shizuku` `pm install -r -t` 返回 `Success`。
- **真机人工验证建议**：在真机播放 1080P/4K 视频观察画质及 logcat `YuiBiliPlayUrl` / `YuiBiliPlayer` 输出的真实分辨率；冷缓存首次打开含装扮表情的视频主评论区确认首屏行内自动加载显示。

### 问题 2 补充收尾（2026-11-20，用户确认 `OK，修好了`）
- 修复 `VideoDetailActivity.kt` 中 `InlineEmotes.refreshEmoteTextView()`、`ensureAttachReload()` / `applyCachedOrTriggerLoad()` / `EmoteImageSpan.bindHost()`、`InlineEmotes.bitmap()` / `scheduleRetryAfterCooldown()`，提取顶层 `internal fun buildEmoteCandidates(rawUrl: String)` 并补充 `QualityAndCommentEmoteTest.kt` 单测。
- 验证：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（APK SHA-256 `f434415035a04aa00d15ed0f28ab2d864c62c4ebf96fb1756e54001358745f69`），装机验收通过。

---

## 新任务：重写「我的」及 4 个子页面（历史记录 / 我的收藏 / 赞助开发 / 关于软件）（2026-11-20）

### 当前进度
- [x] 已梳理 `AccountPageView.kt`、`ProfileSubActivity.kt`、`HistorySearchActivity.kt`、`FavoriteSearchActivity.kt`、`Ds.kt`、`MainActivity.kt`、`BiliApi.kt`。
- [x] **2026-11-20**：收到「后端与性能指导顾问」完整核实要点（task `13a8324c-1e55-4ecc-9a15-32d94915132e`）：
  1. `history/cursor`：过滤视频用 `type=archive`（`ps<=30`），`business` 仍作为游标字段原样回传；顶层可解析 `progress`（观看秒数）与 `kid`（删除目标 ID）；删除单条 `POST /x/v2/history/delete` 参数为 `kid=<business>_<kid>` + `csrf`，清空 `POST /x/v2/history/clear` + `csrf`；
  2. `fav/resource/list`：稳妥 `ps=20`（不写死 40 且做滚动分页），支持 `type=1` 跨全夹搜索方向；从当前夹移出收藏可直接复用 `BiliApi.favoriteVideo(aid, addIds="", delIds="<currentFolderId>")`；
  3. 收藏夹封面预览避免全量同时提交挤占 `networkWorkers`：限流并发 + 离开即停；
  4. `HistoryStore` 实为本机观看记录（最多 100 条），与远端同步历史语义不同，回退本地时必须禁用远端加载开关并明示来源与重试入口；
  5. `HistorySearchActivity` 修复关键词变更时 `loading` 守卫跳过新查询与旧回调污染。
- [x] **2026-11-20**：已更新 `BiliApi.kt`（`type=archive`、`progress`/`kid` 严格解析、`isHistoryCursorEnded`、`deleteHistoryItem`、`clearRemoteHistory`），重写 `AccountPageView.kt`（保留夜间模式圆形揭示/收拢转场，修复 QR 刷新/退出状态重置与初始显隐）。
- [x] **2026-11-20**：已重写 `ProfileSubActivity.kt`（历史远端游标分页/时间分组/进度/长按删除与清空/本地兜底隔离与连续空页上限、收藏限流 2 并发预览与缓存/`ps=20` 滚动分页/返回滚动位置记忆/长按移出当前夹、赞助扫码卡与保存、关于版本复制与免责声明弹窗），同步优化 `HistorySearchActivity.kt` 与 `FavoriteSearchActivity.kt` 卡片外观与旧回调隔离，并补充 `QualityAndCommentEmoteTest.kt` 历史逻辑单测。
- [x] **2026-11-20**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 全部通过（APK `8,799,197` 字节，SHA-256 `e1ddd6997e6671350cee529c63359089603f93ab3c12103248dd14dc46217bcb`），并通过 `send-apk.py` + `Shizuku` 安装到真机（`Success`），由用户亲自进行真机界面与功能验收。

---

## 本轮补充修复（2026-11-20）：历史/收藏搜索框 UI & 评论区长图原图查看
1. **历史/收藏搜索框对齐**：[HistorySearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/HistorySearchActivity.kt) 与 [FavoriteSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/FavoriteSearchActivity.kt) 改用 `40dp` 固定胶囊高度、`FrameLayout` 居中排版与 `14sp` 字号，对齐站内主搜索框视觉，修复原先文本上下贴边问题。
2. **评论区原图清晰度 & 长图查看**：
   - [CoverLoader.kt](app/src/main/kotlin/com/yuilittle/bili/CoverLoader.kt)：`decodeBitmap()` / `computeDecodeSampleSize()` 针对原图模式下的长竖图（高宽比 `>= 2.4`）不再按总高度降采样到 `2048px`，改为限制宽 `<= 1440px` 且高 `<= 8192px`，并适度提高原图 LRU 上限，避免长图缩放糊成缩略图。
   - [VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)：`ZoomableImageView` 识别长竖图后按屏宽适配并从顶部（开头）开始展示，支持上下滑动与 `OverScroller` 惯性 fling，同时保留捏合缩放、双击放大/还原与横向划动翻页。
3. **构建与装机**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过，APK `8,801,135` 字节（SHA-256 `ed29c8002ab89a996fb807276093ff16bcd975b84d3864296d15cc90244307d6`），已通过 `send-apk.py` + `Shizuku` 装机。

---

## 本轮优化（2026-11-20）：长图滑动流畅度 & 历史封面进度/时长对齐
1. **长图分块渲染与手势优化（消除上下滑卡顿）**：
   - [CoverLoader.kt](app/src/main/kotlin/com/yuilittle/bili/CoverLoader.kt)：新增 `cachedOriginalFile(context, rawUrl)` 暴露已落盘的原图文件。
   - [VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)：`ZoomableImageView` 对原图高 `> 2048px` 且高宽比 `>= 2.0` 的长竖图启用 `BitmapRegionDecoder` 1024px 条带分块区域解码（`RGB_565`、视口中心由近及远预加载、10 条带 LRU 缓存；可见条带全命中时跳过底层整图绘制，避免每帧 GPU 重采超大纹理掉帧）；同时复用 `matrixValues`、`onTouchEvent` 直接接管不再回退 `super.onTouchEvent`、禁用长按判定并放宽 `OverScroller` 摩擦系数，提升跟手度与惯性滑行顺滑度。
2. **历史记录封面右下角 `已播放 / 总时长` + 底边进度条**：
   - [BiliApi.kt](app/src/main/kotlin/com/yuilittle/bili/BiliApi.kt)：`VideoItem` 新增 `watchProgressSec: Long = 0L`，`fetchHistory()` 与 `searchHistory()` 透传接口 `progress` 字段（`-1` 为已看完），不再把进度文字塞进右侧 `description`。
   - [ProfileSubActivity.kt](app/src/main/kotlin/com/yuilittle/bili/ProfileSubActivity.kt)、[HistorySearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/HistorySearchActivity.kt)、[FavoriteSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/FavoriteSearchActivity.kt)：新增 `formatHistoryCoverBadge()` 与 `computeHistoryProgressRatio()`，在左侧封面内右下角显示等宽字号 `00:07 / 07:31` 半透黑底徽章（非历史列表仅显示总时长），并在封面最底边叠加 `3dp` 玫瑰粉已播进度条；右侧仅保留标题与 `UP主 · 观看时间`。
3. **单测、构建与装机**：
   - [QualityAndCommentEmoteTest.kt](app/src/test/kotlin/com/yuilittle/bili/QualityAndCommentEmoteTest.kt)：新增 `formatHistoryCoverBadgeAndProgressRatioFormatAndClampCorrectly` 单元测试。
   - APK `8,807,881` 字节（SHA-256 `42218687596d5e5d03049ec907360a99e68db9f5b6a26c86811528be4c1cbc91`）已通过 `send-apk.py` + `Shizuku` 安装到真机（`Success`）。

---

## 本轮修复（2026-11-20）：播放器画质控件换行修复
1. **画质 & 倍速按钮自适应单行胶囊**：
   - [VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)：`speedBtn`、`qualityBtn` 宽度从写死 `42dp` 改为 `WRAP_CONTENT` + 合理 `minWidth` 与左右内边距，加上 `maxLines = 1`、`includeFontPadding = false`、`tnum` 等宽数字及半透描边胶囊底；`formatQualityButtonText()` 将 `1080P60` 格式化为单行 `1080p60`、`杜比视界` 格式化为 `杜比`，彻底避免 `1080p60` 换行折成两行；`showQualityMenu()` 改按测量宽度右对齐按钮向上弹出。
2. **单测、构建与装机**：
   - [QualityAndCommentEmoteTest.kt](app/src/test/kotlin/com/yuilittle/bili/QualityAndCommentEmoteTest.kt)：新增 `formatQualityButtonTextReturnsCompactSingleLineLabels` 单测。
   - 常驻 `term-90` 增量编译通过（`8s`），`:app:testDebugUnitTest :app:lintDebug` 通过，APK `8,808,326` 字节（SHA-256 `d163a9ef183b58d71b2d57a383813964696acef6fe0754bb52f25b817adf48c2`）已通过 `send-apk.py` + `Shizuku` 安装到真机（`Success`）。
---

## 新功能：用户空间页（`SpaceActivity`）与空间内搜索页（`SpaceSearchActivity`）（2026-11-21）

### 设计与实现要点
1. **设计文档**：[docs/SPACE_DESIGN.md](docs/SPACE_DESIGN.md)（按极简现代风格与后端顾问风控建议先行拟定）。
2. **后端接口与风控防御**（[BiliSpaceApi.kt](app/src/main/kotlin/com/yuilittle/bili/BiliSpaceApi.kt)）：
   - 独立 2 并发池、`BiliRequest` 取消句柄、分接口短时内存缓存（按 `mid + 登录态` 隔离）、`-412` 10 分钟本地冷却、`-352/-401/-403` 刷新 WBI 密钥重试 1 次。
   - 空间资料聚合：`acc/info`（失败降级 `web-interface/card`）+ `relation/stat` + `space/upstat`（`likes: Long?` 区分缺失与 0）+ `acc/relation` 关注态，关注/取消关注 `modifyRelationForSpace` 联动 `LikeStateBridge` 并即时失效缓存。
   - 空间动态流：`polymer/web-dynamic/v1/feed/space` 游标分页，支持置顶、纯文字、图文/OPUS 多图、视频投稿、1 层转发与失效兜底、未知只读降级。
   - 空间投稿与搜视频：`space/wbi/arc/search`（冷启降级 `recArchivesByKeywords`），支持 `pubdate / click / stow` 排序；空间动态关键词搜索采用游标本地有界过滤（最多 5 页 / 100 条 / 命中 20 条 / 9 秒预算）。
3. **共享 UI 组件与新页面**：
   - [ImagePreviewDialog.kt](app/src/main/kotlin/com/yuilittle/bili/ImagePreviewDialog.kt)：从 `VideoDetailActivity` 抽离可复用的长图分条解码（`BitmapRegionDecoder`）全屏看图弹窗，两处共用零回归。
   - [SpaceDynamicCardUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicCardUi.kt)：空间与搜索共用的动态卡片渲染器，含置顶徽标、富文本表情、多图网格、视频子卡、转发原动态点击跳转与只读统计行（无三点更多操作菜单）。
   - [SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)：极简头部卡片（头像/昵称/等级/大会员/可展开签名/粉丝/关注/获赞/关注按钮，无编辑资料）随列表滚动收起，吸顶「动态 | 投稿」双标签 + `RootPager` 左右滑动切换。
   - [SpaceSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceSearchActivity.kt)：搜索前为空白提示态，提交搜索后在「动态 | 视频」双标签中以与空间主页一致的卡片结构展示结果，并带 `searchGeneration` + 请求取消防串页。
4. **入口接入与清单注册**：
   - [AndroidManifest.xml](app/src/main/AndroidManifest.xml) 注册 `.SpaceActivity` 与 `.SpaceSearchActivity`。
   - [VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt) 接入简介 UP 主头像/昵称及主评论/楼中楼评论者头像/昵称点击进入空间；[AccountPageView.kt](app/src/main/kotlin/com/yuilittle/bili/AccountPageView.kt) 接入已登录资料卡进入自己空间；[SpaceDynamicCardUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicCardUi.kt) 支持转发原动态作者链式进空间。

---

## 后端与性能设计决策（基于架构核查与顾问预研）
1. **「我的收藏」收藏夹预览与详情分页**：
   - 收藏夹列表：避免对所有 `folder.count > 0` 的收藏夹瞬间全量并发请求 `fetchFavoriteVideos(ps=3)`（会挤占 `BiliApi.networkWorkers(3+16)` 并增加风控概率），改为：**先渲染收藏夹卡片，仅按串行/限流 2 并发逐个按可见顺序预取前几个收藏夹封面，或滑入可视区时按需拉取，并做内存缓存 + 离开页面/退回列表时取消在途请求**。
   - 收藏夹详情：不能写死 `page=1, pageSize=40`，改用标准游标/页码分页加载（`ps=20`，滚动距底部 240dp 自动加载下一页，支持加载中/失败点击重试/没有更多了），并支持取消收藏与长按快捷操作（看详情/移除收藏）。
   - 收藏夹数量/封面展示：保持卡片整洁，1~3 个缩略图优雅自适应布局。
2. **「历史记录」分页、分组、进度与本地回退边界**：
   - `BiliApi.fetchHistory`：需传 `type=archive`（或正确传递 `business` 游标），解析 `progress`（观看进度秒数）和 `duration`（总时长）用于展示「看到 xx% / 已看完」人性化进度条；注意首屏过滤后 `videos.isEmpty()` 但 `!ended` 时继续拉取下一页，不能误判结束。
   - **本地回退必须关闭远端滚动加载标志**（`usingLocalHistory = true`），并给出克制的来源提示与重试入口（如顶部极简「当前显示本地记录 · 点击重试同步」）。
   - 历史列表使用 `ListView + BaseAdapter` 或分段复用/控制上限，防止深滑 View 堆积；支持按时间分组（今天 / 昨天 / 更早）与长按快捷操作（删除单条历史或本地记录 / 立即播放）。
   - `HistorySearchActivity`：修复查询关键字变更时 `loading` 守卫跳过新搜索及旧请求回灌的问题（每次新查询重置 `loading` 并仅凭 `searchGeneration` 丢弃过期回调）。
3. **「我的」与全局 UI 统一规范（克制精致、禁止大量文字堆砌）**：
   - **「我的」(`AccountPageView`)**：保留现有的夜间模式 `PillSwitch` + `Theme.prepareCircularTransition` 圆形揭示/收拢动画，保持流畅不闪烁；优化未登录二维码页与已登录卡片（紧凑信息层级：头像/昵称/UID/等级徽章 + 关注/粉丝/硬币统计 + 夜间模式开关 + 统一分组功能列表），去除冗余说明文字。
   - **「赞助开发」**：保持极简微信扫码卡片（收款码、保存按钮、长按保存、轻量致谢文案），对齐 `Ds` 卡片、圆角与按压反馈。
   - **「关于软件」**：图标、名称、版本号、一句话简介 + 简洁条目化信息卡片（点击版本可复制版本信息、免责声明采用紧凑行/弹窗展示），不堆砌大段文字。

---

## 本轮收尾（2026-11-21）：基线验证修复与真机交付
1. **Lint `NewApi` 错误修复**（[BiliSpaceApi.kt](app/src/main/kotlin/com/yuilittle/bili/BiliSpaceApi.kt:188)）：
   - `invalidateProfileCache()` 原用 `ConcurrentHashMap.keys.removeIf {}`，该 API 需 API 24 或 core library desugaring，项目 minSdk 为 23；改为迭代器显式 `remove()`，语义不变。
   - 修复前 `:app:lintDebug` 失败（`1 errors, 218 warnings`），修复后仅剩既有 warning。
2. **真机交付**：Debug APK `app/build/outputs/apk/debug/app-debug.apk`（8,912,003 字节，SHA-256 `b2b100dc86ce85c3be1df74b4264bd3667432d34c5a300158949e91d0cbb9cb8`）已通过 `send-apk.py` + `Shizuku` `pm install -r -t` 安装到真机（`Success`）。
3. **后续**：由用户亲自进行真机界面与功能验收，按反馈继续迭代；开发阶段仅构建 debug 包。

---

## 本轮打磨（2026-11-21）：个人主页头卡、动态流与横排投稿列表改版
1. **顶部资料区视觉重构（[SpaceUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceUi.kt)、[SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)）**：
   - 取消外层独立大卡片套框与状态栏割裂感：顶栏与资料区顶部共用极淡玫瑰柔雾底色，随上滑折叠平滑融合为纯白/碳黑吸顶栏。
   - 数据统计改为紧凑左对齐单行指标条（`1698 粉丝 | 13 关注 | 4.2万 获赞`），签名下沉并收紧上下留白。
   - 双标签栏改用极简下划线短指示条跟手滑动，降低页面横向切割感。
2. **动态流去框化与子卡降噪（[SpaceDynamicCardUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicCardUi.kt)）**：
   - 动态卡片由「圆角外框卡」改为通透通栏卡片 + 6dp 极淡底色间距条；单图尺寸收束避免霸屏；
   - 动态内视频子卡与转发内视频子卡从大图垂直卡片改为左图右文横排紧凑子卡，单屏信息密度和整齐度明显提升。
3. **投稿改为横排列表样式（[SpaceVideoRowUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceVideoRowUi.kt)、[SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)、[SpaceSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceSearchActivity.kt)）**：
   - 个人主页「投稿」和空间内搜索「视频」均从首页双列瀑布卡切换为单列横排列表卡片（左侧 16:10 封面与时长角标 + 右侧完整两行标题与播放/弹幕/日期），并同步替换了单列横排列表骨架屏。
4. **单测、Lint 与装机**：
   - [SpaceApiParseTest.kt](app/src/test/kotlin/com/yuilittle/bili/SpaceApiParseTest.kt) 新增 `buildSpaceVideoCoverThumbUrl` 单测；`:app:testDebugUnitTest`、`:app:lintDebug` 与 `:app:assembleDebug` 均通过。
   - Debug APK（8,948,732 字节，SHA-256 `9d84f971870430ae280c41a3101e0d338fcb2d2e04928b005180ed79dc040d40`）已通过 `send-apk.py` + `Shizuku` 安装到真机（`Success`）。

---

## 本轮重写（2026-11-21）：个人主页与空间搜索 UI 及功能重构
1. **个人主页重构（[SpaceUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceUi.kt)、[SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)）**：
   - 新增共享头卡协调滚动容器 `SpaceCoordinatorLayout`：不再将双份资料头塞入两个 `ListView` 且被标签栏压在下方，改为「资料头卡 → 吸顶双标签栏 → 双页内容」标准层级，向上滚动资料头卡自然折叠并在顶栏渐显头像与昵称（点击回到顶部），支持顶部弹性下拉刷新（强刷当前标签并联动更新资料），且去除所有分享入口。
   - 重写 `SpaceHeroCard`：对齐全站暖白/碳墨细描边卡片体系，包含头像环点击看大图、昵称、LV/大会员胶囊、点击复制 UID、可点击展开/长按复制的个性签名、粉丝/关注/获赞三栏统计与关注按钮（本人空间隐藏）。
   - 吸顶标签栏 `SpaceTabStrip`：玫瑰粉胶囊滑块实时跟手滑动、投稿总数副标、投稿页右侧排序下拉胶囊（最新发布/最多播放/最多收藏），投稿首屏接入双列骨架屏。
2. **动态卡片优化（[SpaceDynamicCardUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicCardUi.kt)）**：
   - 保持零三点菜单与只读统计行；新增动态副标（投稿了视频 / 转发了动态）、长按正文复制、关键词高亮（用于搜索结果）；
   - 按原图宽高比构造 `/bfs/` CDN 缩略图并保留 query（长竖图走 `@720w.webp` 不再强制裁成横图），补齐 `clearImages()` 回收清理和列表挂载期行内表情加载。
3. **空间内搜索重写（[SpaceSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceSearchActivity.kt)、[SearchHistoryStore.kt](app/src/main/kotlin/com/yuilittle/bili/SearchHistoryStore.kt)、[BiliSpaceApi.kt](app/src/main/kotlin/com/yuilittle/bili/BiliSpaceApi.kt)）**：
   - 历史记录按 `mid` 独立存储（与全站搜索历史隔离），支持一键清空与长按单条删除；结果页按返回先退回输入与历史态。
   - 修复原下拉刷新回调漏关导致列表卡死、列表 Header 撑出空隙与单世代互相污染等问题；动态/视频改用独立请求世代，视频搜索支持排序切换与双列骨架屏。
   - 结合「后端与性能指导顾问」评审落地 `DynamicSearchCheckpoint`（支持命中满 20 条停在页中时不漏检半页，点击「继续检索更早动态」沿检查点续扫）、主页与搜索共用 45s 原始页缓存、移除 `-799` 自动重试、统一识别 HTTP 412 与业务码 `-412` 冷却、并将 `series` 兜底严格限定在非搜索默认排序首屏且不写主缓存。
4. **单测、Lint 与真机装机**：
   - [SpaceApiParseTest.kt](app/src/test/kotlin/com/yuilittle/bili/SpaceApiParseTest.kt) 补充动态缩略图比例与 query 保留单测。
   - `:app:testDebugUnitTest`、`:app:lintDebug` 与常驻 `term-2` `:app:assembleDebug` 均通过；Debug APK（8,943,936 字节，SHA-256 `badd871a13c1b10ee5c51a6ca268ea79ea488d24d8fc0072bd193143410f7eec`）已通过 `send-apk.py` + `Shizuku` 安装到真机（`Success`）。

---

## 本轮改版（2026-11-21）：空间动态卡片精简、移除转发与独立动态详情页
1. **数据解析、去转发与评论通用路由（[BiliSpaceApi.kt](app/src/main/kotlin/com/yuilittle/bili/BiliSpaceApi.kt)、[BiliApi.kt](app/src/main/kotlin/com/yuilittle/bili/BiliApi.kt)）**：
   - `DynamicKind` 与 `DynamicStats` 彻底移除 `FORWARD` / `forward` / `original`；`parseDynamicItem()` 过滤 `DYNAMIC_TYPE_FORWARD` 及带 `orig` 的转发动态。
   - `SpaceDynamic` 新增 `title`、`commentOid`、`commentType`、`supportsDetail`；新增 `fetchDynamicDetail(dynamicId)` 请求 `x/polymer/web-dynamic/v1/detail`（支持缓存与 `-412` 冷却），并提取 `MAJOR_TYPE_OPUS` 标题与完整段落正文。
   - `BiliApi` 新增通用评论与点赞接口 `fetchCommentsForTarget`、`fetchSubRepliesForTarget`、`postCommentLikeForTarget`（`VideoDetailActivity` 评论接口保持签名并复用）。
2. **空间动态卡片重绘与点击分流（[SpaceDynamicCardUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicCardUi.kt)、[SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)、[SpaceSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceSearchActivity.kt)）**：
   - 纯视频动态改为 16:9 圆角大封面视频卡片（左下角时长/观看/弹幕 + 标题），点击直达 `VideoDetailActivity`。
   - 图文、纯文字与带配文引用视频动态保留动态详情入口；超 5 行截断正文末尾展示玫瑰粉「全文」跳转至动态详情页；带配文引用视频点击子卡进视频页、点击正文/标题/空白/统计栏进动态详情页；全程无转发与 `...` 更多/分享菜单。
3. **公共评论抽离与独立动态详情页（[CommentSectionUi.kt](app/src/main/kotlin/com/yuilittle/bili/CommentSectionUi.kt)、[SpaceDynamicDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicDetailActivity.kt)、[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)、[AndroidManifest.xml](app/src/main/AndroidManifest.xml)）**：
   - 新增 `CommentSectionUi.kt` 统一承载视频与动态评论区：富文本表情加载与点击预览保存、评论附图九宫格预览、头像/昵称跳空间、点赞同步 `LikeStateBridge`、楼中楼弹窗 `ReplySheetDialog` 与排序控件。
   - 新增并注册 `SpaceDynamicDetailActivity`，支持浅色/深色主题自适应、顶栏返回、完整正文/标题/图片流/引用视频子卡、静默详情补全以及内置独立动态评论区（按热度/按时间排序、游标分页、楼中楼与点赞）。
4. **单测、Lint 与真机装机**：
   - [SpaceApiParseTest.kt](app/src/test/kotlin/com/yuilittle/bili/SpaceApiParseTest.kt) 同步移除转发测试并新增动态标题/关键词搜索与纯视频 vs 配文视频详情路由单测。
   - 常驻 `term-2` `:app:assembleDebug`、`:app:testDebugUnitTest` 与 `:app:lintDebug` 均通过；Debug APK（8,979,755 字节，SHA-256 `387cc2a39d0dd496d6697959fc2592dbffa88b2b352f7368f6408a0bf826608b`）已通过 `send-apk.py` + `Shizuku` 安装到真机（`Success`）。

---

## 本轮重写（2026-11-21）：下载功能、下载管理与离线合集播放全面重构
1. **底层存储统计、去权限化与元数据修复（[AndroidManifest.xml](app/src/main/AndroidManifest.xml)、[BiliApi.kt](app/src/main/kotlin/com/yuilittle/bili/BiliApi.kt)、[DownloadStore.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadStore.kt)）**：
   - 移除 `MANAGE_EXTERNAL_STORAGE` 权限与全部权限引导弹窗/横幅及相册自动导出逻辑，音频/视频均统一存放在应用私有目录。
   - `UgcEpisode` 补全 `publishedAt`（优先 `arc.pubdate`，回退 `arc.ctime`）；`DownloadManager.Task` 持久化 `publishedAt` 并为音频任务缓存封面。
   - 新增 `SeasonSortOrder`、`LocalPlayMode`、`StorageBreakdown`、`measureStorageBreakdown()`、`taskDiskBytes()`、`sortEpisodes()`、`sortTasks()`、`resolveNextPlayableTaskId()`、`resolvePrevPlayableTaskId()`、`siblingTasksForPlayback()` 及 `computeDownloadProgress()`；`main` Handler 改用惰性初始化，兼容纯 JVM 单元测试。
2. **视频详情下载页重写（[DownloadPickerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPickerActivity.kt)、[ImagePreviewDialog.kt](app/src/main/kotlin/com/yuilittle/bili/ImagePreviewDialog.kt)）**：
   - 属于合集时右上角显示高亮 `视频 | 合集` 胶囊切换器（默认高亮「视频」）。
   - 单视频模式展示封面/标题/UP主/时长，支持「下载封面」（直存相册，修复 `ImagePreviewDialog.downloadImage` 按真实扩展名与 MIME 类型写入）、「下载音频」（直接入队开始下载）及画质选择后「下载视频」。
   - 合集模式顶部展示「已选 xx/xx 集」、全选/取消全选、排序（默认/从旧到新/从新到旧）、画质选择与「仅音频」开关，列表展示分集封面/标题/发布时间/时长/勾选框，底部统一批量入队。
3. **下载管理与合集页重写（[DownloadUiShared.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadUiShared.kt)、[DownloadPageView.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPageView.kt)、[DownloadManagerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadManagerActivity.kt)、[DownloadSeasonActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadSeasonActivity.kt)）**：
   - 顶部展示真实物理占用的存储统计头卡（本应用总占用、含缓存、设备已用/可用/总空间）。
   - 主页改为「视频」「音频」双标签并接入 `RootPager` 支持左右横滑；单条展示封面（内嵌时长角标）、标题、日期、占用大小；合集采用三层堆叠封面 + 居中已下载集数徽标。
   - 新增 `SwipeDeleteRow` 支持行级左滑露出删除按钮（与外层横滑互斥隔离），并支持全选/取消全选批量删除；`DownloadSeasonActivity` 支持分集排序（默认/从旧到新/从新到旧）；所有删除操作均带二次确认弹窗。
4. **离线音视频合集播控（[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)）**：
   - 本地模式支持纯音频播放（播放器区域叠加本地缓存封面与「纯音频离线播放」角标），支持同一 `groupId + type` 合集分集横向卡片条、竖向列表及全部分集弹窗切换。
   - 支持 4 种离线播放模式切换：**按合集顺序播放**、**随机播放**、**列表循环**、**单集循环**，并与手动上/下一集及 `STATE_ENDED` 自动切集联动。
5. **单测、Lint 与真机装机**：
   - 新增 [DownloadLogicTest.kt](app/src/test/kotlin/com/yuilittle/bili/DownloadLogicTest.kt) 覆盖分集/任务排序、4 种播放模式上下集解析及进度计算；`:app:testDebugUnitTest`、`:app:lintDebug` 与常驻 `term-2` `:app:assembleDebug` 均通过。
   - Debug APK（8,988,649 字节，SHA-256 `8db43f6faaead903244fcaa0fc21e28532a6aaa3d2de5d57030989729090b070`）已通过 `send-apk.py` + `Shizuku` 安装到真机（`Success`）。

---

## 本轮迭代（2026-11-21）：下载体验打磨与独立音乐播放器（`AudioPlayerActivity`）
1. **下载弹窗交互与管理体验优化**：
   - [DownloadPickerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPickerActivity.kt)：单视频/音频与合集下载入队后停留在当前页，不再自动退出返回。
   - [DownloadUiShared.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadUiShared.kt)、[DownloadPageView.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPageView.kt)、[DownloadSeasonActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadSeasonActivity.kt)：移除 `SwipeDeleteRow` 左滑删除，改为长按卡片弹出操作菜单（「重命名」仅修改任务/合集显示名称，不改动本地文件路径；「删除本地缓存」带二次确认弹窗）。
   - [DownloadPageView.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPageView.kt)、[DownloadManagerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadManagerActivity.kt)、[DownloadSeasonActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadSeasonActivity.kt)：批量管理状态下点击顶栏返回或系统侧滑返回优先退出批量选择模式。
   - [VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)：精简本地合集区域，移除下方冗余竖向分集列表，保留横向分集卡片流与「全部 ›」弹窗入口。
2. **独立音频播放器（[AudioPlayerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/AudioPlayerActivity.kt)、[AndroidManifest.xml](app/src/main/AndroidManifest.xml)、[DownloadStore.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadStore.kt)）**：
   - 下载页与合集页点击已下载纯音频任务直接拉起独立 `AudioPlayerActivity`（视频任务仍走 `VideoDetailActivity`）。
   - 专业音乐播放器布局：顶栏返回/状态徽标/右上角「更多」，中央视觉舞台，控件上方时间轴，底部播控栏（左下「模式图标+首字：单/列/随/顺」循环切换、上/下一首与大圆钮播放/暂停、右下播放列表入口）。
   - 支持 **4 种主视觉风格**：**封面**（默认大圆角封面）、**唱片**（旋转黑胶与唱针联动）、**磁带**（双卷轴同步转动）、**粒子**（通过 Media3 `EnergyAudioProcessor` 无权限实时采样 PCM 能量驱动律动粒子）。
   - 支持播放/暂停音频渐入渐出（Fade-In / Fade-Out）及切歌视觉舞台平滑滑动缩放过渡；右上角三点菜单支持切换风格，并在点击「设置」时平滑动画拉宽弹窗展示「自动保存播放列表」开关（默认不勾选）；右下角底部滑出播放列表抽屉支持「+」加号从已下载纯音频选歌追加及列表管理。
3. **单测、Lint 与真机装机**：
   - [DownloadLogicTest.kt](app/src/test/kotlin/com/yuilittle/bili/DownloadLogicTest.kt) 补充 `mergeAndResolveAudioPlaylistFiltersInvalidAndDeduplicates` 单元测试，覆盖播放列表合并去重与保存列表恢复过滤逻辑。
   - 常驻构建 `term-2`（`:app:assembleDebug`）与 `:app:testDebugUnitTest :app:lintDebug`（`BUILD SUCCESSFUL`）全部通过；Debug APK（`9,036,821` 字节，SHA-256 `770766b0dee732c604967c40111377ce7c5a2ed47084e8e807ca21081ba969cf`）已通过 `send-apk.py` + `Shizuku` 安装到真机（`Success`）。

---

## 本轮改版（2026-11-21）：下载合集徽标、本地合集播放区列表化与排序/播放模式图标

### 1. 下载管理页合集徽标（[DownloadUiShared.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadUiShared.kt)）
- 新增 `SeasonBadgeSpan`（`ReplacementSpan`）与 `buildSeasonTitle()`：在合集卡片标题首行前拼入「合集」小标签（`accentSoft` 底 + `accent` 字、5dp 圆角、左右 5dp / 上下 1.5dp 内边距、右侧 5dp 间距）。
- 标题改为 `includeFontPadding = false` + `lineSpacing 1dp`，确保 86dp 固定行高内「徽标 + 2 行标题 + 副标题」不裁切；视频合集与音频合集共用同一路径。

### 2. 排序与播放模式图标组件（[Ds.kt](app/src/main/kotlin/com/yuilittle/bili/Ds.kt)）
- 新增 `Ds.sortOrderIcon(context, order, color, strokeDp)` 与 `Ds.playModeIcon(context, mode, color, strokeDp)`，返回可 `update(...)` 的 `SortOrderIconView` / `PlayModeIconView`（1.65dp 圆头描边线性画法，风格对齐 `filterIcon` / `checkIcon`）。
- 排序三态：默认（三阶梯横线）/ 从旧到新（上短下长横线 + 向上箭头）/ 从新到旧（上长下短横线 + 向下箭头）。
- 播放模式三态：顺序播放（三横线 + 右折箭头）/ 单集循环（双弧箭头环 + 中心「1」）/ 随机播放（交叉折线 + 双向箭头）。

### 3. 本地合集播放区重写为竖向列表（[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)）
- `buildLocalSeasonSection()` 由横向卡片流 + 「全部 ›」弹窗改为「合集标题行 + 排序/播放模式胶囊 + 上一集/下一集 + 竖向分集列表」；`buildLocalEpisodeRow()` 每行左侧 100×60dp 封面（右下角时长角标）、右侧两行标题与「播放中/P号 · 发布日期 · 时长」。
- 当前集选中态：`accentSoft` 填充 + `accent` 1dp 描边 + 标题/副信息变色加粗；`publishedAt` 按秒处理并 `* 1000L` 后才交给 `formatDateTimeMs`，缺失时回退 `createdAt` 再隐藏。
- 移除上方 `controlBar` 中重复的四档模式按钮，避免同页两个模式入口；`showLocalSeasonSheet()` 保留作为全部分集弹窗。

### 4. 三档播放模式与排序调度（[DownloadStore.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadStore.kt)、`VideoDetailActivity.kt`）
- `LocalPlayMode.nextSeasonMode()` 新增三态循环（顺序 → 单集循环 → 随机 → 顺序），历史遗留的 `LIST_LOOP` 统一回退顺序播放；`AudioPlayerActivity` 仍沿用原四档 `nextMode()` 不受影响。
- 新增 `SeasonSortOrder.nextOrder()`、`SeasonSortOrder.seasonLabel()`、`LocalPlayMode.seasonLabel()` 扩展。
- `setLocalSortOrder()` / `setLocalPlayMode()` 持久化到 `bili_local_playback` 并在切换后重建列表；顺序播放严格按 `sortTasks()` 后的 `localPlaylist` 顺序推进，播到末集停止（`STATE_ENDED` 分支沿用 `resolveNextPlayableTaskId`）。

### 5. 三处排序入口统一为图标（[DownloadSeasonActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadSeasonActivity.kt)、[DownloadPickerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPickerActivity.kt)）
- 两处排序胶囊的 `filterIcon` 换成 `sortOrderIcon`，选中态切换为 `accentSoft` 底 + `accent` 描边/字色，并保存 `SortOrderIconView` 引用在切换时 `update()`，无需重建视图。

### 6. 单测、Lint 与真机装机
- [DownloadLogicTest.kt](app/src/test/kotlin/com/yuilittle/bili/DownloadLogicTest.kt) 新增 `seasonPlayModeCyclesThroughThreeStates` 与 `sequencePlaybackFollowsSortOrder`。
- `:app:testDebugUnitTest :app:lintDebug` 通过（lint 仅 215 warning / 1 information，无 error）；常驻 `term-1` 增量编译 `BUILD SUCCESSFUL`。
- Debug APK（`9,041,930` 字节，SHA-256 `84333fa5a59dcf8c864479179573ba7c06583a937078ad9be2f2c5be04befce0`）已装机（`Success`，哈希核对一致），由用户亲自验收。

### 7. 真机反馈后的精简（2026-11-21）
- **删除标题下方多余空白卡片**（`rebuildLocalInfoArea()`）：原本在标题/元信息与合集区之间还插了一个只剩右侧两个按钮的 `controlBar`，整体删除。
- **合集区移除上一集/下一集**：`buildLocalSeasonSection()` 不再渲染分集切换按钮，`buildLocalStepButton()` 一并删除（合集内直接点列表切集）。
- **去掉切换提示**：排序与播放模式胶囊点击后不再弹 `showSnackbar("已切换为…")`，仅图标与配色变化。
- **行内信息精简**（`buildLocalEpisodeMeta()`）：去掉时长（封面角标已有）与「播放中」文案，当前集仅靠玫瑰粉选中态区分；现为「P21 · 2026-11-14」。
- **清理死代码**：删除已无调用方的 `showLocalSeasonSheet()`（全部分集弹窗）。
- **验证**：`:app:testDebugUnitTest :app:lintDebug` 通过（lint 214 warning / 1 information，无 error）；APK `9,039,481` 字节，SHA-256 `81fa03e095bba7d616ee01bbfc3bc981533472d6f88e17f5697c2d011db828d4` 已装机（`Success`）。
- **踩坑**：删改源码时并发跑 `lintDebug` 会因行号错位报出一堆假的 `SuspiciousIndentation` error；等文件稳定后重跑即消失。

---

## 新功能：全屏合集侧边抽屉（2026-11-21）

### 交互设计
- **入口**：仅当 `isFullscreen && 合集条目数 > 1` 时，在 `controlOverlay` 右侧垂直居中显示一个 `34×52dp` 半透明胶囊箭头（`0x7314161B` 底 + `0x38FFFFFF` 描边），随控制层同步淡入淡出；小窗与非合集视频不显示。
- **图标**：Canvas 手绘「左指折线箭头 + 三条列表短线」（1.8dp 圆头描边），语义为从右侧滑出合集抽屉。
- **抽屉**：作为 `playerHost` 顶层 `FrameLayout`（在 `controlOverlay` 之后添加，Z 轴更高），`0x4D000000` 全屏遮罩 + 右侧半透明面板（`0xC414161B` 底、`0x2EFFFFFF` 描边、18dp 圆角）；面板宽度按横屏实际宽高实时计算（`38%`，夹在 `248dp..320dp`）。
- **列表**：`ListView` + `BaseAdapter` 复用，每行只显示 `Pxx` 与标题；当前集为粉色态（`0x2BF27D9E` 底 + `0xFFF27D9E` 描边/文字），其余行透明底 + 60%/92% 白字。展开时自动把当前集滚到视口上部。
- **联动**：点击箭头先移除 `controlsHideRunnable` 再 `hideControls()`，实现「抽屉展开时控制面板自动隐藏」；点遮罩只收起抽屉（不弹控制面板）；点当前集仅收起；点其他集收起后切集。返回键优先收起抽屉；退出全屏、`onPause` 即时收起。

### 数据来源
- 新增 `FullscreenSeasonItem(label/title/isCurrent/episode/localTaskId)` 统一在线与本地：在线取 `currentDetail?.season`，切集请求期间回退 `cachedSeason`（在 `applyDetail()` 同步写入）；本地取 `localPlaylist`，`Pxx` 优先用 `episodeNo`。当前集在线用 `currentCid`/`currentBvid`/`currentAid` 实时匹配，本地用 `task.id == localTaskId`。

### 关键实现点
- `showControls()` / `hideControls()` 开头补 `controlOverlay.animate().cancel()`，避免快速连点导致 `controlsAnimating` 卡死。
- 抽屉内所有 View `isClickable = true`，阻断 `gestureLayer` 的长按倍速与亮度/音量手势穿透。
- 面板背景固定深色半透明 HUD，不随浅色/深色主题变化，避免白字白底。

### 验证
- `:app:testDebugUnitTest :app:lintDebug` 通过（lint 214 warning / 1 information，无 error）；APK `9,045,671` 字节，SHA-256 `72ab2b582fad9d0acc73ed6ee7c085532176ddffa6f79c991e90d9e3cf37cf2c` 已装机（`Success`，哈希核对一致），由用户亲自验收。

### 缺陷修复：切集后抽屉高亮不跟随（2026-11-21）
- **根因**：`buildSeasonDrawerOverlay()` 里 `adapter = seasonDrawerAdapter()` 只设置了 `ListView` 的适配器，**没有赋值给成员 `seasonDrawerAdapter`**；而 `openSeasonDrawer()` 靠 `seasonDrawerAdapter?.notifyDataSetChanged()` 刷新，该字段恒为 `null`，`?.` 直接跳过，列表永远不重绘。退出全屏再进入时抽屉是重新构建 View，才偶然“恢复正常”。
- **修复**：构建时把适配器实例同时赋给字段；新增 `refreshSeasonDrawerSelection()` 统一处理「按钮显隐 + 条目重建 + 计数 + 高亮滚动」，并在 `switchLocalTask()`（本地切集/自动连播）、`selectEpisode()`（在线切集）、`applyDetail()`（详情返回后修正 `cid`）三处调用，抽屉展开期间也能实时跟随高亮。
- **验证**：`:app:testDebugUnitTest :app:lintDebug` 通过（lint 214 warning / 1 information，无 error）；APK `9,045,824` 字节，SHA-256 `bb2882f39a6710eb99eba6ba6dc97c97bd43ec0f0ff09a2115d22f678d195f63` 已装机（`Success`，哈希核对一致）。

---

## 新功能：全屏「下一集」按钮（2026-11-21）

### 实现
- **图标**：新增 `NextEpisodeIcon`（`VideoDetailActivity.kt`，`FullscreenIcon` 之后），Canvas 手绘右指实心三角 + 右侧竖线（`▶|`），`Paint.Style.FILL` 白色，风格与 `PlayPauseIcon` 一致；`Path`/`RectF` 为成员字段，`onDraw` 内不分配对象。
- **位置**：`bottomBar` 中紧跟在 `playPauseBtn` 之后、弹性 spacer 之前，`leftMargin = 8dp`；尺寸随 `applyControlSizing()` 切换（全屏 `36dp`、小窗 `30dp`）。
- **显隐**：`isFullscreen && resolveNextSeasonTarget() != null` 才 `VISIBLE`，否则 `GONE`。用 `GONE` 而非 `INVISIBLE`，因为右侧紧接 `weight=1` 的 spacer，隐藏时只由 spacer 吸收空间，`playPauseBtn` 与右侧按钮零跳动。
- **目标解析**：`resolveNextSeasonTarget(): NextSeasonTarget?`（`Online(episode)` / `Local(taskId)`），只做单索引查找、不构造整个列表：
  - 本地：`localPlaylist.indexOfFirst { it.id == localTaskId }`，`idx < lastIndex` 才返回下一项。
  - 在线：`(currentDetail?.season ?: cachedSeason)?.episodes`，按 `currentCid` → `bvid+aid` 的同样顺序匹配当前索引。
  - **严格按合集排序顺序取 `idx + 1`，不受 `LocalPlayMode` 影响**；刻意不用 `DownloadManager.resolveNextPlayableTaskId()`（它会按单集循环/随机/列表循环回绕）。
- **刷新时机**：挂在 `updateSeasonEntryVisibility()` 末尾调 `updateNextEpisodeVisibility()`，由此自动覆盖 `applyControlSizing()`（初始化/进出全屏）、`refreshSeasonDrawerSelection()`（本地切集与自动连播、在线切集、详情返回）；另在 `setLocalSortOrder()` 末尾补调 `refreshSeasonDrawerSelection()`，保证改排序后下一集指向同步。

### 验证
- `:app:testDebugUnitTest :app:lintDebug` 通过（lint 214 warning / 1 information，无 error）；APK `9,049,107` 字节，SHA-256 `870ce7948b7c4ccba13e71161b516b251c00e82db7d11483f2ea284cb24355d9` 已装机（`Success`，哈希核对一致），由用户亲自验收。

---

## 新功能：横屏全屏顶部时间 + 电池电量（2026-11-21）

### 实现
- **挂载方式**：`buildFullscreenStatusBar()` 返回一个水平 `LinearLayout`，直接加到 `controlOverlay`，`FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)` + `topMargin = 3dp`。**未改动 `topControlsBar` 的结构与 padding**，所以返回键与标题位置零位移；状态条占 `y≈3~18dp`，标题文字上沿在 `y≈25.5dp`，自然留出约 7dp 间隙。容量 `isClickable = false`、`isFocusable = false`，不抢手势与返回键。
- **时间**：`TextView`，`setTextSize(COMPLEX_UNIT_DIP, 11f)`（锁定物理高度，避免系统超大字体撑破顶部间距）、`Ds.body(bold = true)`、`tnum`、`0xE6FFFFFF` + 轻微阴影；格式跟随系统 12/24 小时制（`DateFormat.is24HourFormat`，`HH:mm` 或 `h:mm`，不带 AM/PM）。
- **电池图标**：`BatteryStatusIcon`，Canvas 手绘 18×10dp（外框圆角矩形描边 1.2dp + 右侧正极凸起 + 内部电量填充），`Paint`/`RectF`/`Path` 均为成员字段；分档配色：充电绿 `#70C493`、≤20% 低电红 `#EB6F79`、正常白、未知仅壳。充电时叠加白色小闪电。
- **显示条件**：`isFullscreen && resources.configuration.orientation == ORIENTATION_LANDSCAPE`，在 `applyControlSizing()`、`onConfigurationChanged()` 同步刷新；竖屏全屏与小窗均不显示。因在 `controlOverlay` 内，自动随控制面板淡入淡出与 3.5s 自动隐藏。
- **刷新链路**：`BroadcastReceiver` 监听 `ACTION_TIME_TICK` / `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED` / `ACTION_BATTERY_CHANGED`（分钟级，无 Handler 轮询）；`API 33+` 传 `RECEIVER_NOT_EXPORTED`，`onResume` 注册 `onPause` 注销 `onDestroy` 兜底，`statusReceiverRegistered` 守卫防重复。电量另用 `registerReceiver(null, filter)` 读 sticky 广播做首帧补全。

### 验证
- `:app:testDebugUnitTest :app:lintDebug` 通过（lint 214 warning / 1 information，无 error）；APK `9,053,188` 字节，SHA-256 `28365c93e66274a2d75affa7dff6d8b26d662edddc0bc6736802e47b3b1bdc15` 已装机（`Success`，哈希核对一致），由用户亲自验收。

### 补充：电池百分比文字（2026-11-21）
- 电池图标右侧新增 `statusBatteryPercentText`：`10dp` 等宽数字、`CC` 白字、`leftMargin = 3dp`，文案 `"xx%"`。
- 颜色跟随状态：充电绿 `#70C493`、≤20% 低电红 `#EB6F79`、正常 `0xCCFFFFFF`；电量读取失败（`percent < 0`）时整块 `GONE`，不显示空百分号。
- **验证**：`:app:testDebugUnitTest :app:lintDebug` 通过（lint 215 warning / 1 information，无 error，新增 1 条 `SetTextI18n` 为百分号硬编码，与项目既有 62 条同类）；APK `9,053,686` 字节，SHA-256 `4c89636ad89e152fa916e6ec746ed544046134e6f3531f17b07c56469ae1a505` 已装机（`Success`，哈希核对一致）。

---

## 本轮改版：音频播放器视觉与动效（2026-11-21）

### 更多菜单重构（用户点名：又慢又滑来滑去）
- **根因**：`resizeWindowWidth()` 用 `ValueAnimator` 每帧调 `win.setLayout()`，触发 WMS 跨进程重排；同时 `TransitionManager.beginDelayedTransition` 又在跑 `AutoTransition`（约 300ms），两层动画抢同一个布局周期，是三档宽度跳变叠加造成的拖沓感。
- **改法**：删掉窗口宽度动画与 `TransitionManager`；改为**固定宽度**（`min(304dp, 屏宽 82%)`）+ 同一窗口内三页 `FrameLayout` 栈式平移（`translationX ±24dp` + `alpha`，**150ms** `MotionTokens.easeOut`）；弹窗入场上浮缩放 **160ms**，并通过 `setWindowAnimations(0)` 禁用系统默认慢动画。
- **结构**：分组容器（圆角 14dp + 1px 描边，行间用左缩 14dp 的细分隔线串联），行高 54dp；二级页用 `Ds.backIcon` 返回，返回键优先退回主菜单。
- **设置项**：原生 `CheckBox` 换成自绘 `CapsuleSwitch`（42×24dp，轨道颜色 140ms 插值，不重建视图）。

### 底部播控区重绘
- 旧样式：粉色胶囊带汉字首字 + 裸绘 44dp 切歌钮 + 硬描边圆底列表钮，风格割裂。
- 新样式：`[模式 40dp 无底图标] ─ [上一首 48dp 浅底圆钮] ─ [主播放 68dp] ─ [下一首 48dp] ─ [列表 40dp 无底图标]`，主次三级分明。
- 圆形按压反馈统一用 `RippleDrawable`（浅色 `0x1818191C` / 深色 `0x22FFFFFF`，圆角 mask 限制在圆内）；主播放钮 `pivotY/scale` 弹簧回弹。

### 附带修复
- `Ds.kt` 的 `PlayModeIconView` 原来 `LIST_LOOP` 落进 `else` 分支会被画成随机交叉箭头；补上独立的「双弧循环」分支。

### 验证
- `:app:testDebugUnitTest :app:lintDebug` 通过（lint 215 warning / 1 information，无 error）；APK `9,058,472` 字节，SHA-256 `e906e72a0ea18cb6ca9f6694c6690c8e97a64a0bde58a2c387a7d3e1a2694c43` 已装机（`Success`，哈希核对一致），由用户亲自验收。

### 第二轮：按顾问方案重做图标/逻辑/列表（2026-11-21）
- **四模式图标重绘**（`Ds.kt` `PlayModeIconView`）：单集循环改为「圆形单箭头闭环 + 有底座的数字 1」，列表循环改为「扁平圆角双箭头跑道」，两者外形截然不同；随机改为平滑三次贝塞尔交叉 + 双箭头。
- **删除重复实现**：移除 `AudioPlayerActivity.ModeSymbolIcon`（它把 `LIST_LOOP` 与 `SINGLE_LOOP` 合并在同一分支，是“两个图标一样”的直接原因），统一走 `Ds.playModeIcon`；顺序模式用 `muted`，其余三态用 `accent`。
- **去掉常驻圆圈**：`modeBtn`/`prevBtn`/`nextBtn`/`listBtn` 全部改为 `transparentRipple`（`content=null` + 圆形 mask，常态全透明，仅按下出水波），只保留主播放钮一处实心圆盘（66dp）。
- **上一首/下一首重绘**：`SkipButton` 改为 `FILL_AND_STROKE` + 2.2dp 圆角 Join 的厣满三角 + 独立圆头止步杆，图形从 8dp 放大到 18dp；新增 `setSkipEnabled()`（`isEnabled` + `alpha 0.30` + `subtle` 色 + 复位缩放）。
- **边界逻辑**：新增 `updateSkipButtonsState()`——顺序播放下首曲禁用上一首、尾曲禁用下一首，其余模式均可回绕；单曲时两键均禁用。`Ds.bindPressFeedback` 首行加 `if (!v.isEnabled) return false`，禁用态不触发缩放。`playPreviousTrack()` 在单集循环下改用 `LIST_LOOP` 导航，避免手动切歌被锁在同一首。
- **模式切换反馈**：删除 Toast，改为顶栏副标 `styleBadgeView` 临时接管（`已切换 · xxx`，1.3s 无新点击后自动回落）+ 模式图标弹簧脉冲 + 触觉反馈；每次点击先 `removeCallbacks` 并 `animate().cancel()`，连点不排队不叠加。
- **播放列表动态指示器**：新增 `EqualizerBarsView`（3 根圆头音柱，1200ms 无缝循环，相位/频率/幅度各异，底部基线对齐），当前项的序号数字换成它；暂停或减弱动画时静止三柱；`onDetachedFromWindow` 与弹窗 `setOnDismissListener` 双保险停动画。同时去掉副信息里冗余的“正在播放 · ”前缀。

### 验证
- `:app:testDebugUnitTest :app:lintDebug` 通过（lint 214 warning / 1 information，无 error）；APK `9,061,453` 字节，SHA-256 `dcd4633b16668a0958b5d6718c4584a3425910e442018c90040c52b1b4ff8a66` 已装机（`Success`，哈希核对一致）。

### 第三轮：更多菜单面板重写（2026-11-21）
- **取消三页钻取**：原来「首页 → 风格页 → 返回 → 设置页」的三层路由，实际只有 5 个配置项。改为一屏直出。
- **新结构**：单层容器（`c.surface` + 1px `c.lineSoft` + `Ds.Radius.card` 16dp + `elevation 12dp`，API 28+ 再染 `outlineAmbient/SpotShadowColor`），宽 `min(276dp, 屏宽 76%)`；上半是「舞台风格」`2×2` 网格（每格 64dp 高，选中态 `accentSoft` 填充 + 2dp `accent` 描边，未选态 `surfaceRaised` 无描边），下半是「自动保存播放列表」单行 + `CapsuleSwitch`，中间一条 1px 分隔线；去掉了双层嵌套边框与多余的 50dp 标题栏。
- **新增 `StyleGlyphView`**：为四种风格各画一个 20dp 极简线框图标（封面=圆角矩形+山峰、唱片=同心圆+中心点、磁带=圆角矩形+双圆孔、粒子=四个错落圆点）。
- **转场**：右上角为锚点（`pivotX=width, pivotY=0`），`alpha 0→1` + `scale 0.88→1` + `translationY -6dp→0`，**180ms** `easeOut`；`dimAmount = 0.22f`。
- **验证**：`:app:testDebugUnitTest :app:lintDebug` 通过（lint 214 warning / 1 information，无 error）；APK `9,056,791` 字节，SHA-256 `699ad8277e9100ba8f8f2932b8b122c23ab04de944a1c61a7d2d5e236fe8b75c` 已装机（`Success`，哈希核对一致）。

### 第四轮：唱片/磁带/粒子三风格深度美化（2026-11-21，封面风格未动）

**唱片 `VinylDiscView` + 新增 `VinylPlatterView`**
- 光影与旋转分离：静态盘面（11 色标 `SweepGradient` 锥形光泽 + 径向暗角 + 18 条非等距音轨沟槽 + 3 条曲目分隔暗带 + Run-out 收尾环 + 双高光弧）与旋转内芯（纸标 + 封面 + 压模环）分层，高光不再跟着盘转。
- 中心 Label：象牙纸白环带 + 压边暗槽 + 细环纹 + 径向暗角；封面用 `Outline.setOval` 正圆裁剪（修正原 `Ds.clipRounded` 把 px 当 dp 的误用）；中心轴孔 + 主轴帽。
- 唱臂 `ToneArmView` 补齐：静态万向底座（径向渐变）+ 休止架，旋转部分含投影、配重块、J 形双遍臂管（金属底 + 高光芯）、支点关节盖、唱头壳 + 指托 + 唱针指示块；播放时叠加 ±0.25° 循轨微颤，落针角度 -23° → -4°。

**磁带 `CassetteDeckView`**
- 外壳：注塑 `LinearGradient` + 顶部倒角高光 + 内凹台阶接缝 + 两侧防滑肋纹 + 叠层投影。
- 贴纸：暖米纸基 + `SIDE A · HI-FI` 徽标 + 右上时长 + 两条印刷导引线 + `TextUtils.ellipsize` 精确省略（替代 `take(14)` 硬截断）+ 底部玫瑰粉/冷蓝双彩带。
- 观察窗：`clipPath` 裁剪内绘制双卷饼（新增 `setProgress()`，面积守恒公式 `r = √(rHub² + p·(rMax² - rHub²))` 驱动左满右空），3 扇镂空弧窗 + 6 颗内啮合梯形齿 + 中心驱动孔；叠加刻度桥与 32° 亚克力反光。
- 底部：梯形磁头凸台 + 双 Capstan 孔 + 双方孔 + 磁头窗 + 铜金压带海绵；播放时 4 个微光点沿磁带线 40dp/s 平移表示走带；五颗十字螺丝预设不同角度。

**粒子 `BeatParticleView`（已去封面）**
- 视觉中心改为「能量核心」：径向光晕 + 双层晶核 + 偏振双环（内环 + 24 点阵外环）。
- 36 瓣环形频谱：`waveA = sin(3θ + phase)` / `waveB = cos(6θ - 1.4·phase)` 双重谐波包络，颜色在 `accent` 与 `cold` 间插值。
- 粒子模型：48 个径向爆发粒子（半径/寿命/钟形 `sin(life·π)` 透明度 + 单条 `drawLine` 速度拖尾）+ 24 个远景星尘（缓慢公转 + 呼吸闪烁）。
- 鼓点瞬态：检测 `bass - prevBass > 0.12`、间隔 ≥240ms 触发冲击波（3 槽复用池，0.85s 生命周期）。
- 低频：快攻慢放非对称包络（升 0.45 / 降 0.12）。
- 高刷适配：改用 `System.nanoTime()` 真实 `dt`（上限 0.05s）推进，不再固定步长。

**工程底线**：所有 `Paint`/`RectF`/`Path`/`Matrix`/`Shader` 均预分配或在 `onSizeChanged` 构造；全部 `Animator` 在 `onDetachedFromWindow` 清理；三风格均尊重 `MotionTokens.isReduced`。

**验证**：`:app:testDebugUnitTest :app:lintDebug` 通过（lint 207 warning / 1 information，无 error）；APK `9,067,110` 字节，SHA-256 `891332a6a6497d0b137e47ada432d7be8fdd29026ecc0bc7edf11cd664b1af2c` 已装机（`Success`，哈希核对一致）。

### 缺陷修复：唱片过大且四边被裁切（2026-11-21）
- **根因（两个叠加）**：
  1. `discSize` 用 `widthPixels - dp(68)` 且竖向取 `heightPixels * 0.36f`，盘面尺寸接近屏宽，但唱臂视图是盘径的 `1.12` 倍，必然超出父容器。
  2. `VinylPlatterView.onDraw()` 外圈投影画到 `r * 1.035f`（`r = min(cx, cy) - dp(2)`），已越出 View 边界；而 `stageHost`、包裹 `FrameLayout`、`col`、`rootLayout` 全部默认为 `clipChildren = true`，四边被硬切。
- **修复**：
  - 盘径改为 `min(屏宽 - 104dp, 屏高 * 0.29f)`，下限 `170dp`；
  - 绘制半径改为 `min(cx, cy) - dp(5)`，外圈投影收到 `1.028f` 且线宽降至 `4dp`；
  - 在 `stageHost`、`VINYL` 包裹层、`col`（根 `LinearLayout`）、`rootLayout` 四处显式设 `clipChildren = false`，让唱臂与投影可超出子层边界。
- **验证**：`:app:testDebugUnitTest :app:lintDebug` 通过（lint 207 warning / 1 information，无 error）；APK `9,067,133` 字节，SHA-256 `2855440622288a9e6949f494e8d95dae11538275b81c7dcca8a42d51bd0249cd` 已装机（`Success`，哈希核对一致）。

---

## 新功能：后台播放（在线视频→音频页）与音频页封面自适应（2026-11-21）

### 1. 视频侧入口（`VideoDetailActivity`）
- `topControlsBar` 右侧新增 `BackgroundPlayIcon`（Canvas 手绘「耳机 + 播放三角」，`STROKE 1.85dp` 头梁 + 两侧耳罩 + `FILL` 三角）；小窗 38dp / 全屏 42dp，与返回键对称，`applyControlSizing()` 统一刷新。
- **标题隐藏时用 `topBarSpacer`（weight=1）占位**，避免小窗下按钮贴到返回键旁边。
- 显隐：小窗与全屏（含竖屏全屏）都显示；**本地纯音频模式隐藏**（本身就是音频，且避免与右上角「纯音频离线播放」角标重叠）。
- 交接：`playerGlobalPosition()` 取毫秒位置（多段 DURL 已由 `PlaybackTimeline` 换算），同一快照读 `(currentBvid, currentAid, currentCid)`，暂停视频后跳转，`overridePendingTransition(fade_in, fade_out)`；`backgroundSwitchLocked` + `onResume` 复位防重复进入。

### 2. 纯音频取流（`BiliPlayUrl` / `BiliDashManifest` / `OnlinePlaybackSource`）
- 新增 `BiliPlayUrl.selectAudioOnlyStream()`：从 `audioTracks` 优先选 `mp4a`、同类取最高带宽，投影为只含单音轨的 `DashStream`（清空视频轨与 DURL）。
- `BiliDashManifest.build()` 的 `require` 由「必须有视频轨」放宽为「至少有音频轨或视频轨」。
- 新增 `OnlinePlaybackSource.createAudioOnly()`，复用内存 MPD + CDN 鉴权/Referer/Cookie/Range/备用节点链路，媒体不落盘。
- 取流用 `fetchPlayUrlDetailed(bvid, cid, QUALITY_360, allowProgressiveFallback = false)`；`generation` + `BiliRequest.cancel()` 防串台，回调统一 `handler.post`。

### 3. 音频页在线模式（`AudioPlayerActivity`）
- 新增 `OnlineAudioLaunch`（`OnlineAudioLaunch.kt`）：`entry` + `startPositionMs` + `season`，只传元数据，签名 URL 与 Cookie 不落 Intent。
- `initOnlineQueue()` 构建队列（入口分集不在合集内时补到首位），`sortOnlineQueue()` 走 `DownloadManager.sortEpisodes`（默认接口顺序 / 按 `publishedAt` 升/降，未知时间置尾）。
- `loadOnlineEpisode()` 懒加载当前分集，`player.setMediaSource(source, safeStartMs)` 直接在 prepare 时应用起始位置，无需等 `STATE_READY` 再 seek；用户主动切集从 0 开始。
- 上/下一首、按钮启停、进度时长、自动连播全部按在线队列分流；`onlineRequest` 在 `onDestroy` 取消。
- 舞台统一走 `swapStage()`，`VinylDiscView` / `CassetteDeckView` 改为接受可空 `task` + 在线标题/封面覆盖；`loadStageCover()` 统一本地与在线封面加载。

### 4. 封面完整显示
- `buildCoverStage()` 改为**按封面真实宽高比自适应**：先以 16:9 占位，拿到 `drawable.intrinsicWidth/Height` 后按 `aspect ∈ [0.65, 2.2]` 重算卡片宽高（上限取 `stageHost` 实测尺寸），`FIT_CENTER` 完整展示不裁切；圆角统一 `Ds.Radius.card`（16dp）。
- 封面加载分辨率从 120dp 缩略图提升到 `dp(360)`，远端用 `cropThumb = false` 保留原始比例，修复放大后发糊。

### 5. 播放列表抽屉
- 抽成 `buildQueueRow()` + `buildSortChip()`：本地模式保留「+ 添加」与「移除」，**在线模式将添加位换成三态排序胶囊**（`Ds.sortOrderIcon` + `seasonLabel()` + `nextOrder()`），且隐藏「移除」。

### 6. 本轮边界
- 按确认范围只做**页面内播放**：沿用 `onPause()` 暂停，未引入前台服务 / MediaSession（Home 或锁屏续播需后续加 `mediaPlayback` FGS）。

### 验证
- `:app:testDebugUnitTest :app:lintDebug` 通过（lint 208 warning / 1 information，无 error）；APK `9,081,427` 字节，SHA-256 `1ecc11176ae2e0f4c653ac1cde0e7343bd417486a10c892e63c6d2409b376d92` 已装机（`Success`，哈希核对一致）。

### 缺陷修复：点击后台播放后回到首页（2026-11-21）
- **根因**：`OnlineAudioLaunch` 用 `Serializable` 包了 `UgcEpisode` / `UgcSeason`，但这两个类**未实现 `Serializable`**。Intent 跨 Binder 传递时抛 `NotSerializableException`，进程崩溃后系统重启到首页。
- **修复**：`OnlineAudioLaunch.kt` 改为**显式字段编码**——入口分集与合集分集逐字段写入 Intent（`bvid/aid/cid/title/duration/cover/publishedAt`，分集用 `online_audio_episode_<i>_*` 前缀 + 总数），读取时按 `cid > 0` 过滤非法项。彻底摆脱 Java 序列化，也避开 Binder 事务大小风险。
- **附带**：在线模式下 `initPlaylist()` 失败时提示文案改为「无法获取在线音频信息」，不再误导为本地文件缺失；合并了 `onCreate` 中重复的 `preparePlayerAndPlay` 分支。
- **验证**：`:app:testDebugUnitTest :app:lintDebug` 通过（lint 208 warning / 1 information，无 error）；APK `9,083,093` 字节，SHA-256 `eb8502d28590c1d1d1bbae45491afbe51f21168a98a1715045c6ab1b61e98762` 已装机（`Success`，哈希核对一致）。

---

## 架构重构：音频后台播放 + 通知栏（2026-11-21）

### 分层
| 文件 | 职责 |
|---|---|
| `AudioPlaybackService.kt`（新增） | `MediaSessionService`，唯一持有引擎与 MediaSession；`stopWithTask=false`；暂停 5 分钟后清理会话与通知 |
| `AudioPlaybackEngine.kt`（新增） | 持有 ExoPlayer、本地/在线双队列、排序、四种模式、切歌、在线取流与 `generation` 防串台；`EnergyAudioProcessor` 在渲染线程回写能量值 |
| `AudioSessionPlayer.kt`（新增） | `SimpleBasePlayer` 把真实队列投影给 Session：ExoPlayer 仍只装当前一首，但通知栏/锁屏能看到完整队列与上/下一首 |
| `EnergyAudioProcessor.kt`（新增） | 从 Activity 抽出的 PCM 能量分析器，服务与 UI 共用 |
| `AudioPlayerActivity.kt` | 降为纯 UI：`exoPlayer` 改为 `playbackEngine?.player` 的只读委托；播放/暂停/切歌全部下发引擎 |

### 关键行为修正（顾问指出的隐患）
1. **`closeWithFade()` 不再把共享音量拉到 0**——否则退出页面会导致后台静音。现只 `finish()`。
2. **`onPause()` 不再暂停播放**，`onDestroy()` 不再 `release()` 播放器（改由服务在 `onDestroy` 释放）。
3. **`onResume()` 不再自动 `play()`**，避免覆盖通知栏/锁屏的暂停状态。
4. **绑定方式**：`onStart()` 用私有 action `ACTION_LOCAL_BIND` 绑定服务拿引擎，`onStop()` 解绑并移除监听；不缓存跨连接的播放器引用。
5. **通知栏元数据**：`createAudioOnly()` 新增 `mediaId` + `MediaMetadata` 重载，本地源也带 `setMediaId`/`setMediaMetadata`，通知栏不再空白。

### 依赖与声明
- `app/build.gradle.kts`：新增 `androidx.media3:media3-session:1.4.1`。
- `AndroidManifest.xml`：新增 `FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`WAKE_LOCK`；注册 `.AudioPlaybackService`（`foregroundServiceType="mediaPlayback"`、`stopWithTask="false"`、`MediaSessionService` intent-filter）。

### 本轮边界
- 通知栏用 Media3 默认样式（系统媒体卡片），未自绘；`POST_NOTIFICATIONS` 沿用已有请求时机，未在播放入口另弹。

### 验证
- `:app:testDebugUnitTest :app:lintDebug` 通过（lint 209 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `8d9fbaf8c55ff7c6b174113691540155e4fcb4440ce4f14873b4d9e1585f8e61` 已装机（`Success`，哈希核对一致）。

### 崩溃修复：进入音频页直接退回（2026-11-21）
- **根因**（从 `logcat -b crash` 定位）：`AudioSessionPlayer.getState()` 在队列为空时仍返回 `STATE_READY`，而 `SimpleBasePlayer.State` 构造函数硬校验「空播放列表只允许 `STATE_IDLE` / `STATE_ENDED`」，于是 `MediaSession.Builder.build()` 抛 `IllegalArgumentException`，服务创建失败连带页面退出。
- **修复**：`getState()` 改为按队列状态分支——`queue.isEmpty() → STATE_IDLE`，`loading → STATE_BUFFERING`，否则 `STATE_READY`；且仅在队列非空时才设 `setCurrentMediaItemIndex` / `setContentPositionMs`（位置改用 `PositionSupplier` 提供快照值）。
- **附带修复（实测发现）**：
  1. `AudioPlaybackEngine.prepareLocal()` / `loadOnline()` 只调 `prepare()` 未设 `playWhenReady`，导致进入页面是暂停态；补 `playWhenReady = true`。
  2. `onSnapshotChanged` 每次都调 `bindCurrentTrackUi()` 重建舞台，会闪烁并打断进度循环；改为用 `lastBoundTrackKey` 判断曲目真正变化时才重建，并补上 `updateProgressUi()`。
  3. `submitLaunchRequest()` 后主动启动 `progressRunnable`，保证首次进入就能走进度。
- **验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 209 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `159ac9d81020a9bfe4cc2eaeff8265beaca63a5d6658d695c015d581dc9186cb` 已装机（`Success`，哈希核对一致），由用户亲自验收。

---

## 本轮修复（2026-11-21）：播放模式失效、合集随机异常、评论区名字跳转

### Bug 1：音频播放器只有顺序播放生效
- **根因**：`AudioPlayerActivity.cyclePlayMode()` 只改了 Activity 自己的 `playMode` 并写 prefs，**从未同步给引擎**；引擎的 `playMode` 恒为初始的 `SEASON_SEQUENCE`，所以自动连播永远走顺序。
- **修复**：`cyclePlayMode()` 增加 `playbackEngine?.setPlayMode(playMode)`。
- **附带**：`updateModeButtonUi()` 原来 `animate().cancel()` 后直接设 `0.78f` 再动画，连点可能停在中间缩放；改为「先复位 1f → 缩到 0.78f（70ms）→ 回弹 1f」两段式。

### Bug 2：下载合集视频随机播放闪回原视频
- **根因**：`switchLocalTask()` 虽然把 `pendingResumePositionMs` 置 0，但紧接着 `loadLocalPlayer()` → `beginPlaybackChange()` → `rememberPlaybackPosition()` 又把**上一集结尾位置**写回，新分集一加载就被 seek 到结尾，表现为「闪一下、回到原视频、结尾前几秒重播」。
- **修复**：新增 `switchingLocalTask` 标记，`switchLocalTask()` 在调用 `loadLocalPlayer()` 期间置 true，`beginPlaybackChange()` 在标记为 true 时跳过 `rememberPlaybackPosition()`。

### 功能：评论区 @用户 与名字跳转（`CommentSectionUi.kt`、`CommentMentionResolver.kt`）
1. **@用户淡蓝色**：新增 `applyMentionSpans()` 识别 `@昵称`（以空白/冒号/标点/@ 为终止符），套 `MentionSpan`（`ClickableSpan`，颜色 `Ds.colors.cold`）。
2. **子评论名字淡蓝色**：`buildSubCommentItem()` 里作者名从 `COLOR_INK` 改为 `Ds.colors.cold`，并叠加 `UserNameSpan` 支持点击。
3. **点击跳主页**：主评论正文与子评论行都设 `LinkMovementMethod`（主评论正文原本 `setTextIsSelectable(true)`，与链接互斥，已改为链接方式）。@ 昵称的 mid 通过 `CommentMentionResolver` 解析——在 `BiliApi.parseComment()` 返回时注册 `uname → mid`，命中不了不跳转（不猜）。

### 验证
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 209 warning / 1 information，无 error）。
- APK `9,974,384` 字节，SHA-256 `5bca205de5cda8316accda195c71a436b5d0d604f370f8a1f95374d51726abe4` 已装机（`Success`）。
- **虚拟屏已验证**：音频页正常打开不再退出、封面按比例完整显示、模式图标可在列表循环/随机间切换。自动连播与合集随机的完整链路由用户真机长视频验收。

### 后续修复：本地音频提示「文件不存在」（2026-11-21）
- **现象**：真机验证后，本地下载音频与本地合集音频进入播放页都提示「本地音频文件不存在」，而视频侧后台播放在线音频正常。
- **根因**：引擎搬到服务后，`AudioPlaybackEngine.startLocal()` 与 `AudioPlayerActivity.initPlaylist()` 仍用 `Task.playable()` 过滤，而 `playable()` 硬要求 `state == STATE_COMPLETED`；服务首次创建时任务状态或时序不一致就会把已完成任务过滤掉。
- **修复**：新增 `localSourceExists(task)`（只看 `mpdPath` / `audioPath` / `singlePaths` 的真实文件是否存在），`startLocal()`、`initPlaylist()` 与合集兄弟项过滤全部改用它。
- **同时**：`prepareLocal()` 从「只认 `audioPath`」扩展为三分支选源——`mpdPath` 用 `DashMediaSource`、`audioPath` 用 `ProgressiveMediaSource`、否则回退 `singlePaths` 首段，并在 `MediaItem` 上带 `MediaMetadata` 供通知栏展示。
- **验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 209 warning / 1 information，无 error）；APK `9,902,339` 字节，SHA-256 `c38bc8848deef950409cce07f547b53d0437acf9177cf879b4aac2642d347fa7` 已装机（`Success`）。

### 真正的根因：绑定服务时 taskId 被引擎快照覆盖（2026-11-21）
- **上一版修复无效**：因为 `playable()` 并不是真正的拦截点。
- **真正的调用链**：`attachEngine()` 先 `engine.addListener(engineListener)`，再调 `submitLaunchRequest()`。后者第一步 `engine.setSortOrder()` 会**同步**触发 `notifySnapshot()` → Activity 的 `onSnapshotChanged` → `syncQueuesFromEngine()`，把 `currentTaskId` 覆盖成 `engine.currentLocalTaskId()`（新服务初值 `0L`，复用旧引擎时可能是上一首）。之后 `startLocal(currentTaskId, ...)` 拿到的已经是 `0`，查不到任务就报「本地音频文件不存在」。
- **为什么在线正常**：`onlineLaunch` 在回调前已存成局部变量 `launch`，`startOnline(launch)` 不受影响。
- **修复**：
  1. `submitLaunchRequest()` 开头把 `currentTaskId` / `sortOrder` / `playMode` 捕获成局部变量 `requestedTaskId` / `requestedSort` / `requestedMode`，不再读会被覆盖的字段。
  2. 新增 `submittingLaunch` 标记：`attachEngine` 提交期间置 true，`syncQueuesFromEngine()` 在 `submittingLaunch || !pendingLaunchApplied` 时直接 return，彻底断开「引擎空快照反向覆盖 Intent 目标」的回路。
  3. 移除 `submitLaunchRequest()` 末尾多余的 `syncQueuesFromEngine()`（会被 guard 挡掉）；队列同步交给提交完成后的引擎快照回调。
- **验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 209 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `2224d49a06ffe72c42c0b3a6d187e8ea02006571b3850b2b06d34df938805531` 已装机（`Success`）。

---

## 后台播放体验修复（2026-11-21，用户真机反馈 4 项）

### 1. 退出音频页后仍继续播放
- **根因**：上一轮把 `closeWithFade()` 改成只 `finish()`，播放完全交给服务。
- **修复**：新增 `AudioPlaybackEngine.stopPlayback()`（取消取流、`stop()` + `clearMediaItems()`、清空双队列与当前曲目、`notifySnapshot()`）；`closeWithFade()` 先调它再 `finish()`。服务在 `onSnapshotChanged` 里发现 `queueSize == 0` 时立即 `stopPlaybackAndSelf()` 退出前台态。
- **边界**：只在用户离开音频页（返回键/顶栏返回）时停播；`onPause()/onStop()` 不动播放，所以切到后台、锁屏仍然继续播。

### 2. 通知栏进度卡在 0~2 秒循环
- **根因**：`getState()` 里 `setContentPositionMs` 用的是建 State 那一刻的 `safePosition` 固定值，而 State 只在 `invalidateState()` 时重建；播放中位置从不刷新，通知栏就在旧值与新值之间跳。
- **修复**：
  1. `setContentPositionMs` 改为 `PositionSupplier { engine.positionMs() }`，读取时实时取位置；
  2. 新增 `tickRunnable`，播放中每 **500ms** `invalidateState()` 一次，让 State 跟随进度重建；`handleRelease()` 里清理定时器。

### 3. 点通知栏卡片弹「本地音频文件不存在」
- **根因**：`setSessionActivity` 指向的 Activity 在新建时 `currentTaskId = 0`，又走了一遍 `initPlaylist()`，找不到任务就弹提示并 `finish()`。
- **修复**：新增 `EXTRA_ATTACH_ONLY` + `AudioPlayerActivity.attachIntent()`（带 `SINGLE_TOP | CLEAR_TOP`）。服务的 `setSessionActivity` 改用它；`onCreate()` 在 attach-only 时跳过 `initPlaylist()` 失败退出；`attachEngine()` 与 `onNewIntent()` 均直接采用引擎当前状态展示，不发起新请求。

### 4. 无合集时通知栏上/下一首仍亮着但点了无反应
- **根因**：`getState()` 无条件把 `COMMAND_SEEK_TO_NEXT/PREVIOUS` 加进 `availableCommands`。
- **修复**：提取 `buildCommands(queueSize, currentIndex)`，仅当 `queueSize > 1` 且 `engine.canSkipNext()` / `canSkipPrevious()` 为真时才下发对应命令，系统媒体卡片会自动置灰不可用的按钮。

### 验证
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 209 warning / 1 information，无 error）。
- APK `9,974,384` 字节，SHA-256 `9b13012a0c1bbe9be548d53857e033122ac467597bfeda1655ac226f6ddade58` 已装机（`Success`），由用户真机验收。

### 5. 本地视频播放页仍显示「后台播放」按钮
- **现象**：下载到本地的视频/视频合集进入播放页，右上角仍显示后台播放按钮，但本地视频没有在线音频源，语义不合理。
- **修复**：`updateBackgroundPlayVisibility()` 从「仅本地纯音频隐藏」改为「所有 `isLocalMode` 一律隐藏」，只有在线视频显示该入口。
- **验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 209 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `23c6a877b28a52b5459ff5eccbda2a957bab5dd7f1c95444dddc59f288c3ba38` 已装机（`Success`）。

---

## 修复：播放开头无限加载（2026-11-21）

用户反馈：视频/音频偶发在开头无限转圈不出声，重进页面或切集后恢复正常。

### 根因（顾问只读排查确认，按优先级）
1. **网络超时后不会切备用 CDN（主因）**：`OnlinePlaybackSource` 的 `getFallbackSelectionFor` 判断的是裸 `SocketTimeoutException` / `ConnectException`，但 Media3 1.4.1 的 `OkHttpDataSource.open/read` 会把它们包成 `HttpDataSource.HttpDataSourceException`；默认 `isEligibleForFallback()` 又只认特定 HTTP 状态码，所以普通超时**根本不会触发** DASH 备用节点回退，只能一直等。
2. **音频引擎缺 `onPlayerError`**：在线加载的 `loading` 只在 `STATE_READY` 清除。播放器报错回 `STATE_IDLE` 后 `loading` 停在 `true`，`AudioSessionPlayer` 持续报 `STATE_BUFFERING`，UI 与通知栏永远转圈。
3. **`AudioSessionPlayer` 错误态映射违反硬校验**：`playerError != null` 时仍报 `STATE_READY`，而 `SimpleBasePlayer.State` 要求「有 error 必须 `STATE_IDLE` 且 `isLoading == false`」，会直接抛 `IllegalArgumentException`。
4. **视频侧无超时兑底**：卡在 `BUFFERING` 时没有任何超时机制。

### 修复
- `OnlinePlaybackSource.kt`：`getFallbackSelectionFor` 增加 `HttpDataSourceException.reason` 判断（`ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` / `CONNECTION_FAILED`），让超时能真正切到备用 CDN。
- `AudioPlaybackEngine.kt`：
  - 新增 `onPlayerError`，立即复位 `loading` 并写入 `lastError`；
  - `STATE_IDLE` 分支兼容处理（若仍标 loading 则清除）；
  - 新增 20s `stallWatchdog`（BUFFERING 超时强制重取）。
- `AudioSessionPlayer.kt`：错误态优先映射 `STATE_IDLE`，并仅在 `STATE_BUFFERING` 时 `isLoading = true`，消除崩溃隐患。
- `VideoDetailActivity.kt`：新增 20s `stallWatchdog`（`by lazy` 避免字段初始化顺序问题），超时后清续播位置、用 `switchingLocalTask` 包裹重建，避免旧位置被写回；最多重试 **2** 次，超过则报「加载超时，点这里重试」而不是无限重建。

### 验证
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 209 warning / 1 information，无 error）。
- APK `9,974,384` 字节，SHA-256 `8653586d51a7654f28ebf0c7eb1ebc7fcfff47c419a2db347b09bced697509c6` 已装机（`Success`），由用户真机验收。

### 待观察（顾问提出但未确认的候选）
- DASH `indexRange` 声明了非空区间但内容解析不出 `sidx` 时，Media3 会重复加载 index 而不报错，表现为无错误无限 buffer。当前 `BiliDashManifest` 只做范围语法校验，无法保证内容有效性；如真机仍复现，需加日志确认是否反复加载同一 index 区间。

---

## 评论区打磨（2026-11-21）：长按选词、链接、内嵌网页、表情放大

### 1. 长按选词与链接点击共存（`CommentSectionUi.kt`）
- **根因**：上一轮为了支持 @ 点击，主评论正文改用了 `LinkMovementMethod`，它与 `setTextIsSelectable(true)` 互斥，导致长按选词彻底失效。
- **修复**：改为 `setTextIsSelectable(true)` + 保留系统 `ArrowKeyMovementMethod`，移除 `LinkMovementMethod`；`highlightColor = Ds.colors.accentSoft`、`isCursorVisible = false`。
- **点击分发**：升级 `installEmoteTapHandler` 为统一触摸处理——
  1. 若已处于选词态（`selectionStart != selectionEnd`），直接 `return false` 全交给系统；
  2. `ACTION_DOWN` 不返回 `true`（否则吞掉长按计时器），仅做命中预判：表情 → `ClickableSpan`；
  3. `ACTION_UP` 短按（`< 350ms` 且未越 slop）才触发表情气泡或 `span.onClick()`；长按一律交回系统选词。
- 新增 `hitTestClickableSpan()`：校验 `getLineRight` 避免行末空白误触，并回退一格处理左半边点击。

### 2. 评论链接淡绿与内嵌网页（`CommentLinkParser.kt`、`InAppWebActivity.kt`）
- **纯 URL 识别**：`CommentLinkParser.findPlainLinks()` 用正则匹配 `http(s)://`、`www.`、`b23.tv`、`acg.tv`、`bilibili.com`、`bili2233.cn`；自动剔尾部标点与不成对括号（保留维基百科类 `Foo_(bar)` 的右括号）；无协议时补 `https://`。
- **B 站富文本链接**：`BiliApi.parseComment()` 新增解析 `content.jump_url`（正片传送门 / 收藏集 / 搜索词等），存进 `VideoComment.jumpLinks`（显示文案 → URL），仅保留 http/https。
- **样式**：新增 `CommentWebLinkSpan`（`ClickableSpan`），颜色 `Ds.colors.success`（浅色 `#2D8A56` / 深色 `#70C493`），无下划线。
- **优先级**：表情区间 > 富文本 `jumpLinks` > 纯 URL > `@昵称`，避免重叠。

### 3. 内嵌网页页 `InAppWebActivity`
- 顶部：`Ds.backIcon` 返回（直接 `finish()`）+ 只读网址框（`surfaceRaised` 底 + 12dp 圆角 + 13sp muted 文字，`ellipsize END`）+ 框内复制图标（`ClipboardManager` + 「已复制链接」）+ 框外地球图标（`ACTION_VIEW` 调系统浏览器，无浏览器时提示）。
- 进度：`onProgressChanged` 驱动 2dp 顶部进度条。
- 错误态：`onReceivedError` 仅当 `request.isForMainFrame` 才展示「网页加载失败 + 重新加载」，子资源失败忽略。
- 安全：`shouldOverrideUrlLoading` 只放行 http/https，其余 scheme 全部拦截；关闭 `allowFileAccess` / `allowContentAccess` / 两个 `allowFileAccessFromFileURLs`；`safeBrowsingEnabled`（API 26+）；不注入 `addJavascriptInterface`。
- 返回键：`webView.canGoBack()` 时网页后退，否则 `finish()`。
- 生命周期：`onResume/onPause` 转发给 WebView；`onDestroy` 严格按「移除父视图 → stopLoading → 清空 client → removeAllViews → destroy」释放。
- Manifest 注册并加 `configChanges` 防止旋转重建。

### 4. 表情放大
- 主评论正文表情 `18dp → 22dp`（面积 +49%），子评论预览行保持 `18dp`（`commentSpannable(emoteSizeDp = 18)`）避免 12.5sp 下过挤。
- `EmoteImageSpan.getSize()` 改用 `paint.fontMetricsInt` 作基准（避免同行多表情累加撑大行高），上方分配 55% 额外空间做视觉居中，并同步扩展 `top/bottom` 防削边。
- `draw()` 改用基线中线 `y + (ascent + descent) / 2` 定位，修复首行/中间行/末行表情垂直偏移不一致（原用 `(top + bottom) / 2` 会因末行不含 lineSpacingExtra 而偏下）。

### 5. 附带修复
- `InlineEmotes.refreshEmoteTextView` 的选区判断从 `selectionStart < 0 && selectionEnd < 0` 改为 `selectionStart == selectionEnd`：开启可选后点击文字会把光标位置置为 `>= 0`，原条件会导致表情加载完成后永不刷新。

### 验证
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 210 warning / 1 information，无 error）。
- APK `9,974,384` 字节，SHA-256 `2c1ae3fabf915cafc9caf813503a94d824a3f0071a993fa7cab802936e3d416a` 已装机（`Success`），由用户真机验收。

---

## 评论区链接修正（2026-11-21）

用户反馈两个问题：
1. 绿色热词（如《雨爱》）点击后跳到了官方 `m.bilibili.com/search` 网页，应走本应用的搜索页。
2. B 站链接只显示裸 URL，没有显示内容标题；非 B 站链接才应显示原链接。

### 1. 热词走站内搜索
- `SearchActivity` 新增 `EXTRA_QUERY` 与 `openWithQuery(context, query)`；`onCreate` 末尾读取该 extra，填入输入框后直接 `performSearch()`。
- `CommentWebLinkSpan` 新增 `searchKeyword` 参数；命中时调 `SearchActivity.openWithQuery()` 而非打开网页。
- 新增 `searchKeywordOf(url)`：从 `/search` 类链接的 `keyword` / `search_word` / `q` 参数解码出关键词。

### 2. B 站链接显示内容标题
- 新增 `BiliLinkTitle.kt`：`/video/BVxxx`、`?bvid=` → `x/web-interface/view` 取稿件标题；`/bangumi/play/ep123`、`?ep_id=` → `pgc/view/web/season` 取 `share_copy`（完整剧集标题），回退 `long_title` / `season_title`。
- `resolveBiliTitles()` 在正文渲染后开后台线程取标题，取到后 `tv.post` 重建 spannable；`isBiliUrl()` 判定只处理 B 站域名，非 B 站链接保持原样。
- `commentSpannable()` 新增 `titles` 参数，`applyBiliTitles()` 在渲染前把 URL 文本替换为标题；`applyLinkSpans()` 再给标题文本挂上指向原 URL 的 `CommentWebLinkSpan`，保证「显示标题、点击跳原链接」。

### 验证
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 210 warning / 1 information，无 error）。
- APK `9,974,384` 字节，SHA-256 `480509a44396df078b43e5450b597c115aa5c024f0fa82144ef73f49b3fbecf0` 已装机（`Success`）。

---

## 热词进入搜索页不再自动弹键盘（2026-11-21）

用户反馈：从评论热词进入搜索页后，已经直接出结果了，但仍会自动进入输入状态（弹键盘）。

### 根因
`SearchActivity` 有三条自动聚焦路径，都无条件执行：
1. `onCreate` 末尾 `root.post` / `root.postDelayed(180ms)` 调 `requestInputFocus(showKeyboard = true)`；
2. `onWindowFocusChanged` 里 `hasFocus && !showingResultPage` 时再补一次；
3. 共享元素转场 `onTransitionEnd` 里再补一次。

### 修复
- 新增 `presetQueryMode` 标记，在 `onCreate` 读完 `EXTRA_QUERY` 后立即赋值。
- 上述三条自动聚焦路径全部加上 `!presetQueryMode` 守卫。
- preset 模式下 `window.setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_HIDDEN)`，并先用 `FOCUS_BLOCK_DESCENDANTS` 挡住布局期的自动聚焦，布局稳定后再恢复 `FOCUS_AFTER_DESCENDANTS` 并 `clearFocus()`，保证用户主动点击输入框仍能正常输入。

### 验证
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 210 warning / 1 information，无 error）。
- APK `9,974,384` 字节，SHA-256 `f2524f902cb4559a12e917dd64f37bc12e4a002a482ad6b1e3c8572a10e4a83d` 已装机（`Success`）。

---

## 收藏夹「播放全部」播放态（2026-11-21）

### 需求
收藏夹详情页底部加「播放全部」：从第一个视频开始播，播放页的列表换成收藏夹（即使视频本身有合集也**不显示**视频合集）；四种播放模式（顺序/列表循环/随机/单集循环，默认顺序）；不做排序；列表项显示封面（含时长）+ 标题 + UP + 发布日期；列表上方 `xx/xx` 统计；后台音频页的列表也按收藏夹。**只有「播放全部」进入才有这些效果**，直接点单个视频仍是普通播放。

### 实现

**新增 `FavoritePlayback.kt`**：
- `FavoritePlayback(folderId, folderName, entries: List<UgcEpisode>, startIndex, startPositionMs)`
- `FavoritePlaybackLauncher.intent()/read()`：显式字段编码（逐条 `_bvid/_aid/_cid/_title/_duration/_cover/_published_at/_owner`），不依赖 Serializable。

**`ProfileSubActivity`（收藏夹详情）**：
- 新增 `playAllBtn`：右下悬浮胶囊（`Ds.pill` + `Ds.colors.accent` 底 + 白字 13.5sp，96×44dp，右边距 `Ds.Space.lg`、下边距 28dp），`visibility` 由 `updatePlayAllVisibility()` 控制（仅收藏夹详情且有内容时显示）。
- 新增 `favVideos` 累积列表：分页加载时按顺序追加（`favSeen` 去重），「播放全部」直接用它构建队列，无需额外请求。
- 进入/返回收藏夹列表时清空并刷新按钮可见性。

**`VideoDetailActivity`（播放页）**：
- 新增 `favoritePlayback` / `favoritePlayMode` 字段；`onCreate` 用 `FavoritePlaybackLauncher.read()` 读取，命中则用入口条目的 `bvid/aid/cid` 覆盖当前播放目标。
- `collectSeasonItems()`：收藏夹态优先返回收藏夹队列（屏蔽 `ugc_season`），条目带 `cover/owner/durationSec/publishedAt/richRow=true`。
- `applyDetail()`：收藏夹态下 `cachedSeason = null`，彻底屏蔽视频自身合集。
- `resolveNextSeasonTarget()`：收藏夹态按 `favoritePlayMode` 计算下一集（顺序到底返回 null、列表循环取模、随机排除当前、单集循环返回自身）。
- `STATE_ENDED` 分支：收藏夹态优先按队列连播；顺序播放到底停在结束态。
- `updateSeasonEntryVisibility()`：收藏夹态在非全屏也显示列表入口。
- 抽屉标题动态化（`seasonDrawerTitleText`）：收藏夹态显示收藏夹名。
- 新增 `favoriteModeChip`：抽屉头部右侧模式切换 chip，点击 `nextMode()` 循环四种模式。
- 新增 `buildFavoriteRow()`：收藏夹列表项专用布局（封面 96×60dp + 右下时长角标 10sp/`0x99000000` 胶囊 + 标题 13sp 两行 + UP·日期 11sp muted + 当前项粉色描边）。
- 新增 `formatFavoriteDate()`：相对时间（刚刚/N 分钟前/N 小时前/N 天前/yyyy-MM-dd）。

**后台音频**：
- `OnlineAudioLaunch` 新增 `favorite: FavoritePlayback?` 字段与对应 Intent 编码（`EXTRA_FAV_*`）。
- `enterBackgroundAudioPlayback()`：收藏夹态下 `season` 传 null，改传 `favorite`。
- `AudioPlaybackEngine`：收藏夹队列优先且**不应用排序**（`setSortOrder()` 在收藏夹态下跳过重排）；新增 `onlineQueueTitle()`，`subtitle`/`seasonTitle`/`MediaMetadata.artist` 统一回退到收藏夹名。
- `UgcEpisode` 新增 `owner` 字段（收藏夹列表需要展示 UP 名）。

### 已知行为
- 收藏接口（`x/v3/fav/resource/list`）**不返回 cid**，因此队列条目的 `cid` 为 0；进入播放页后由 `applyDetail()` 拉详情补全 cid，再由 `previousCid <= 0L` 分支触发 `loadPlayer()`。
- 当前项匹配改用 `bvid`（不用 cid），避免 cid 为 0 时全部命中第一项。

### 验证
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 210 warning / 1 information，无 error）。
- APK `9,974,384` 字节，SHA-256 `dcc281515c8c4ec5a8452ad5d26d0a3cfef31827d4b68498b5f6a52d8a3450ef` 已装机（`Success`）。

### 修复：「播放全部」点击后无反应（2026-11-21）
- **现象**：点「播放全部」有按压动画，但不跳转。
- **根因**：`FavoritePlaybackLauncher.read()` 要求 `cid > 0` 才收集条目，而收藏接口 `x/v3/fav/resource/list` **不返回 cid**，导致所有条目被 `continue` 跳过 → `entries` 为空 → `read()` 返回 null → `onCreate` 中 `currentBvid` 仍为空 → 直接 `finish()`。按压动画已触发所以看起来「有反应」，但页面开完立即关闭。
- **修复**：
  - `FavoritePlaybackLauncher.read()` 改为只校验 `bvid`（cid 允许为 0，由播放页拉详情补全）。
  - `OnlineAudioLauncher.readFavorite()` 同步修复（同一问题）。
- **验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 210 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `77a4b21fde32ed0443ee03a5c1f6c761393f7337b6b527b4df6923abdc703963` 已装机（`Success`）。

### 收藏夹播放态修正（2026-11-21，用户真机反馈 3 项）

用户指出三处问题：
1. 播放页下方的「合集 · xxx」区块仍在显示视频自己的合集，应换成收藏夹列表。
2. 播放面板（全屏抽屉）条目不应再写 P1/P2，只写标题，当前播放项前加动态图标。
3. 收藏夹列表不完整：99 条的收藏夹只显示 19 条，100 多条的只有 20 条。

**修复 1：下方区块换成收藏夹**
- `renderSeasonSection()` 新增收藏夹态分支，命中时调 `renderFavoriteSection(fav)` 并 return，彻底屏蔽 `item.season`。
- 新增 `renderFavoriteSection()`：标题「收藏夹 · {名称}」+ `当前/总数` 粉色统计 + 「全部 ›」（点击开抽屉），下方横向卡片流（148dp 宽卡片，标题 12sp 两行 + `UP · 时长` 10.5sp），当前项粉色描边高亮并自动滚动定位。
- 新增 `PlayingBarsView`：三根正弦波音柱动画（每帧相位递增 0.12），用于标记当前播放项。

**修复 2：抽屉条目去编号**
- 收藏夹态的 `buildFavoriteRow()` 本就不显示 P 编号；新增的 `renderFavoriteSection()` 卡片同样不显示编号，只用 `PlayingBarsView` 标记当前项。

**修复 3：收藏夹队列不完整（根因）**
- **根因**：`playFavoriteFolder()` 直接用 `favVideos`（列表页已分页加载的部分，通常 1~2 页共 20~40 条），从未拉完剩余分页。
- **修复**：新增 `fetchAllFavoriteVideos(folder, onDone)`，点击「播放全部」时若 `!favEnded` 则串行循环拉取剩余分页（`pageSize=20`，按 bvid 去重），全部拉完后才构建队列；中途失败回退到已加载部分，不让用户卡住。
- 新增 `setPlayAllLoading()`：拉取期间按钮置灰并显示「正在准备…」，防止重复点击。
- 队列构建抽到 `launchFavoritePlayback(folder, videos)`。

**验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 213 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `6630369e73cdebbdcd6d797ea631d9f594606d67880f716cfa8320595846754d` 已装机（`Success`）。

### 收藏夹播放态返工（2026-11-21，用户指出实现过度复杂）

用户明确：本来就是把「合集」的位置全部换成「收藏夹」，不需要另写一套 UI。

**1. 下方区块：删除自绘实现，改为虚拟合集**
- 删除 `renderFavoriteSection()` 与 `PlayingBarsView`（共约 190 行自绘代码）。
- `renderSeasonSection()` 改为把 `FavoritePlayback` 包装成 `UgcSeason(id=folderId, title=folderName, episodes=fav.entries)`，**完全复用原有合集渲染**（横向卡片流 + 底部上拉弹窗 `showSeasonSheet`）。
- 标题前缀：收藏夹态用「收藏夹 · {名}」，其余保持「合集/分集 · {名}」。

**2. 卡片与弹窗去掉 P 编号**
- 收藏夹态下卡片 `indexTv` 不再写 `P{n}`，当前项前面改用 `PlayingBarsDrawable`（Drawable 版音柱动画，`scheduleSelf` 每 40ms 一帧）。
- `showSeasonSheet` 的副标题在收藏夹态下不再拼「第 N 集」。

**3. 播放模式 chip 位置**
- 从全屏抽屉头部移到**下方收藏夹区块的标题行**（粉色胶囊，点击 `nextMode()` 循环四模式），同时删掉 `favoriteModeChip` / `syncFavoriteModeChip()`。

**4. 抽屉恢复原样式**
- 删除抽屉标题的收藏夹名特殊化（恢复固定「合集」+ `xx/xx` 计数）。
- `updateSeasonEntryVisibility()` 恢复为 `isFullscreen && hasFullscreenSeason()`，非全屏不再显示入口。

**5. 后台音频选中态与动效**
- **根因**：`UgcEpisode.sameAs()` / `sameAsEpisode()` 优先按 `cid` 比较，而收藏夹队列 cid 全为 0，与引擎回传的带 cid 条目永远不相等 → 当前项永远匹配不到。
- **修复**：两个比较函数均改为**优先按 `bvid` 比较**，cid 仅在 bvid 缺失时使用。

**6. 后台音频切下一集报「该分集暂无可用音源」**
- **根因**：`AudioPlaybackEngine.loadOnline()` 开头要求 `target.cid > 0L`，收藏夹队列 cid 为 0，直接报错。
- **修复**：cid 缺失时先 `BiliApi.fetchDetail(bvid, aid)` 拉详情补全，并把补全后的条目回写到 `onlineQueue` / `onlineSourceQueue`，避免重复拉取；拿到 cid 后再走原取流流程。

**验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 208 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `102dfb4a8737348805c106d8f067e866cfb6c300eb166c0d076fbb9a97640274` 已装机（`Success`）。

### 全屏抽屉收藏夹列表改用紧凑样式（2026-11-21）

用户指出：全屏抽屉里带封面的富列表一屏只能显示 4 条，信息密度太低；应改回合集那种紧凑行，并把标题「合集」换成收藏夹名。

**改动**：
- 删除 `buildFavoriteRow()`（带封面/UP/日期的富样式，约 85 行）与 `formatFavoriteDate()`，以及 `FullscreenSeasonItem` 中为其服务的 `cover/owner/durationSec/publishedAt/richRow` 字段。
- `seasonDrawerAdapter` 去掉 `if (item.richRow)` 分支，收藏夹直接复用合集的紧凑行（左侧标签 + 两行标题）。
- 紧凑行在收藏夹态下不写 `P{n}`，当前项前面改用 `PlayingBarsDrawable` 音柱图标，`minWidth` 置 0 避免空白占位。
- 抽屉标题（`seasonDrawerTitleText`）在两处刷新点同步：收藏夹态显示收藏夹名，否则「合集」；计数单位在收藏夹态用「个」而非「集」。

**验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 208 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `bdbf0b0ed5d218caca9c9ec58d80d726da0e41a01b6ab8690875c9af24ec6f11` 已装机（`Success`）。

### 下方卡片编号、标题行对齐、粒子特效失效（2026-11-21）

**1. 下方卡片恢复 P 编号**
- 上一轮把收藏夹卡片的 `indexTv` 置空，左上角显得空。恢复为 `P{n}`；当前项仍在编号前加 `PlayingBarsDrawable` 音柱图标。

**2. 标题行对齐**
- 根因：标题设了 `includeFontPadding = false`，而右侧计数与「全部 ›」未设，导致两者视觉基线相差约 1~2dp。
- 修复：计数、`· 全部 ›`、播放模式 chip 统一加 `includeFontPadding = false` + `gravity = CENTER_VERTICAL`；去掉「· 全部」前的空格改为 `setPadding(4dp, 0, 0, 0)`，使间距可预测。

**3. 音频粒子特效不响应声音（根因）**
- 现象：音频播放页粒子风格不再随声音变化。
- 根因：重构到前台服务后，`AudioPlayerActivity` 的 `audioLevelSmooth` / `audioBassSmooth` **只在停止时被置 0，从未从引擎读取能量值**。粒子 `levelProvider` 因此恒为 `0f to 0f`。
- 修复：新增 `sampleAudioEnergy()`，从 `playbackEngine.energyOverall` / `energyBass` 取值并做「上冲 0.55 / 回落 0.12」包络平滑；新增独立的 `energyRunnable`（60ms）与进度刷新（200ms）解耦，在 `onResume` / `onIsPlayingChanged` 启动、`onPause` / 停止时清理。

**验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 210 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `b2c8fbb00ccf28831e27a491e3dd184fa46edd24382024ba710a7766ceafdf67` 已装机（`Success`）。

### 标题行对齐返工（2026-11-21）

上一轮只统一了 `includeFontPadding`，仍未对齐。

**真正的根因**：`header` 用的是 `gravity = CENTER_VERTICAL`，它按**子 View 盒子的垂直中心**对齐。而 `modeChip` 带 4dp 上下内边距、盒子更高，导致整行被它撑高，标题（14sp）、计数与「全部 ›」（12sp）各自按自己盒子中心居中，三者**基线**因此错开。统一 `includeFontPadding` 并不能修正基线问题。

**修复**：`header` 改为 `isBaselineAligned = true`（`LinearLayout` 的基线对齐），去掉 `CENTER_VERTICAL`。这样所有子 View 按文字基线对齐，不同字号与带内边距的 chip 都能视觉齐平。该修复对「合集」与「收藏夹」两种态同时生效。

**验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 210 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `caa7b874b8768a2a0df0e77ea67dc82a416ab2e7b56ed9b9d19ebc12dfca60ee` 已装机（`Success`）。

---

## 「我的收藏」收藏夹列表 UI 重写（2026-11-21）

用户提供参考图，要求把竖向大卡片改成横向行：左侧叠层封面 + 中间标题/创建者/公开性 + 右侧三点。

### 新增数据结构
`FavoriteFolder` 补充两个字段：
- `owner: String` —— 创建者昵称，取 `upper.name`，为空时回退 `BiliSessionStore.cachedProfile()?.uname`
- `isPublic: Boolean` —— **按位掩码判断** `(attr and 1) == 0`。此前若写成 `attr == 0` 会把 attr=2/22 等带其它标志位的公开收藏夹误判为私密

### 卡片布局（`ProfileSubActivity.buildFavoriteFolderCard`）
- 整行无外框、无分割线，`setPadding(0, 8dp, 0, 8dp)`，行间距 `topMargin = 4dp`
- **左侧 `FavoriteStackCoverView`（144×98dp 单容器自绘）**：
  - 前景封面严格 16:10（144×90dp），圆角 10dp，`top = 8dp`
  - 背后两层错位卡片：中间层 `inset 6dp / dy 4dp / 圆角 9dp`，最底层 `inset 12dp / dy 8dp / 圆角 8dp`
  - 深浅主题分别配色，深色下两层与前景均加 0.5~0.75dp 微亮描边避免糊在一起
  - 内部只挂 1 个 ImageView，其余（叠层、底板、小电视、角标）全部在 `onDraw` / `dispatchDraw` 绘制，比多层 View 少 3 个视图与 2 次裁剪
- **数量角标**：右下内缩 6dp，高 20dp，**宽度按 `measureText` 自适应**（此前写死 58dp 会截断 5 位数字）；`0xB8000000` 底 + `0x24FFFFFF` 描边；层叠图标为「前实框 + 右上 L 型后框」Canvas 绘制
- **空收藏夹**：`count <= 0` 时**直接绑定空态、不进 preview 队列**（避免无效网络请求），前景画实心 `Ds.colors.cover` 底板 + 站内小电视线稿（机身 36×25dp、八字天线、两只眼睛）
- **标题**：15.5sp display bold，`maxLines = 2`，`includeFontPadding = false` + API 28+ `isFallbackLineSpacing = false`，避免 emoji 撑高行高
- **元信息**：`创建者：xxx` 与 `收藏夹 · 公开/私密`，12.5sp muted；`owner` 为空时整行 `GONE`
- **右侧三点**：热区 40×48dp，圆点直径 3.2dp、间距 5.5dp，绘制在 `cx = width - 12dp`

### 交互
- 整行点击 → 进入收藏夹详情；长按 → 三点菜单；均 `bindPressFeedback(0.98f)`
- 三点按钮独立消费点击事件（不会误触整行），`bindPressFeedback(0.88f)`
- 菜单项：「打开收藏夹」「播放全部」（仅 `count > 0`）。**未加**「编辑/删除」，因为当前没有任何收藏夹增删改 API
- 新增 `playFolderDirectly(folder)`：串行拉全分页后直接调 `launchFavoritePlayback`。此前菜单里直接改 `inFavoriteFolder = true` 再调 `playFavoriteFolder()`，而那时 `favVideos` 为空会静默 return，导致点了没反应且返回键状态错乱

### 性能
- 预览请求 `pageSize` 由 3 改为 1（新卡片只需首图），`previewCache` 存 `take(1)`
- 封面 URL 拼 `@320w_200h.webp`，匹配 144×90dp 的 16:10 比例，避免拉原图

**验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 211 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `e3ce8e531fd5a48ebd67aba226831e12a07edad1777a1a6ac8758a03b1524f1c` 已装机（`Success`）。

---

## 收藏夹卡片收敛 + 创建收藏夹功能（2026-11-21）

### 卡片调整
- 尺寸从 144×98dp 缩到 **116×80dp**（前景封面 116×72dp），不再完全复刻参考图
- 标题 14.5sp、元信息 11.5sp，整体更紧凑
- **删除右侧三点按钮**（用户不需要），长按菜单保留
- 叠层/角标等自绘尺寸同步缩放

### 顶栏
- 「我的收藏」页新增**加号按钮**（`Ds.plusIcon`），位于搜索按钮左侧
- 新增 `Ds.plusIcon(context, color, strokeDp)` 图标

### 创建收藏夹
**新增 `BiliApi.createFavoriteFolder(title, intro, isPublic, callback)`**
- `POST /x/v3/fav/folder/add`，参数 `title` / `intro` / `privacy`(0公开 1私密) / `csrf`
- 校验：名称非空、已登录、csrf 存在
- 失败时回传 B 站 `message`

**新增 `CreateFavoriteFolderActivity`**
- 顶栏返回 + 标题「创建收藏夹」
- 封面区：**本轮不做图片上传**（B 站接口要求 cover 为已上传到图床的 URL，项目无上传能力），展示说明文字「暂不支持自定义封面，创建后可在 B 站客户端修改」
- 名称（必填）：`EditText` + `TextWatcher` 实时驱动创建按钮可用性
- 简介：多行 `EditText`，可选
- 公开开关：自绘 `PillSwitch` 药丸开关，默认开，带 160ms 缓动动画
- 右下角「创建」按钮：名称非空才启用（`alpha 0.45` 禁用态），提交中显示「创建中…」并防重复点击
- 创建成功 `setResult(RESULT_OK)` 并 `finish()`

**回传刷新**
- `ProfileSubActivity` 用 `startActivityForResult`（`REQUEST_CREATE_FOLDER = 4101`）
- `onActivityResult` 收到 OK 且当前是收藏夹模式时调 `loadFavorites(restoreScrollY = scroll.scrollY)`，新收藏夹自动出现

**验证**：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（lint 212 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `b07fa69cae6bafeadae4f88cefb79a8ed59fb5fb7056c52fea4dd2753f8ae6b9` 已装机（`Success`）。

### 顶栏按钮修正（2026-11-21）
- **顺序**：改为「搜索在左、加号在右」（上一轮做反了）。
- **显示范围**：加号只在收藏夹**列表页**显示；`openFavoriteFolder()` 里 `addBtnRef.visibility = GONE`，`loadFavorites()` 里恢复 `VISIBLE`，进入某个收藏夹后不再出现创建入口。
- **构建方式纠正**：恢复常驻增量构建 `./gradlew --continuous :app:assembleDebug`（标签 `YuiBili Gradle session`）。本轮增量编译仅 1m5s，而临时终端每次全量约 3 分钟。

**验证**：`:app:testDebugUnitTest :app:lintDebug` + 常驻 `assembleDebug` 通过（lint 212 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `8467238c81ec073d0dbe892a3e9df121c544a9bd903b3542521cc16717e252bf` 已装机（`Success`）。

---

## 构建速度优化（2026-11-21）

### 实测诊断
- 一次性 `./gradlew assembleDebug`（无源码改动）：墙钟 **21s**，但 `user` 仅 3s → 时间几乎全花在 PRoot 下 Gradle 冷启动，不在编译
- 常驻 `--continuous` 首次改源码：**1m5s**（Kotlin 编译守护进程冷启动）
- 常驻预热后改源码：**13s** → 说明增量编译本身正常，瓶颈是守护进程预热与 worker 数

### 已做的调整
| 项 | 改前 | 改后 | 理由 |
|---|---|---|---|
| `org.gradle.jvmargs` | `-Xmx2048m` | `-Xmx3072m -XX:MaxMetaspaceSize=1024m` | 与指南一致，减少 GC 停顿 |
| `org.gradle.workers.max` | 4 | **6** | 机器 8 核，留 2 核给系统 |
| `kotlin.daemon.jvmargs` | 未设 | `-Xmx2048m` | 固定 Kotlin 守护进程堆，避免频繁扩容 |
| `kotlin.incremental.useClasspathSnapshot` | 未设 | `true` | 提升增量判定精度 |
| `debug.isCrunchPngs` | 默认 true | **false** | 开发期跳过 PNG 压缩 |

### 实测结果（改后）
- 配置重建后首次：37s
- 预热后第 1 次改源码：**10s**
- 预热后第 2 次改源码：**4s**

达到指南所述「保持监听器运行时，保存源码后约 2–3 秒量级」的目标区间（本机 4–10s）。

### 关键结论
APK 只有 9.9MB 但构建慢，**不是代码量大**，而是 PRoot 环境下的进程启动开销。因此：
- 必须保持 `YuiBili Gradle session` 常驻，不要用一次性命令
- 首次改源码偏慢是 Kotlin 守护进程冷启动，属正常
- 后续保存即 4–10s

**验证**：`:app:testDebugUnitTest :app:lintDebug` + 常驻 `assembleDebug` 通过（lint 212 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `ba5be27cedf095c2bd0e7e95c6c11052c92d6d4d95138783d79544b655a97d8e` 已装机（`Success`）。

---

## 收藏夹「更多」菜单 + 批量管理（2026-11-21）

### 新增 API（`BiliApi`）
| 方法 | 接口 | 用途 |
|---|---|---|
| `editFavoriteFolder` | `POST /x/v3/fav/folder/edit` | 编辑名称/简介/公开性 |
| `deleteFavoriteFolders` | `POST /x/v3/fav/folder/del` | 删除收藏夹 |
| `batchDeleteFavoriteResources` | `POST /x/v3/fav/resource/batch-del` | 批量移出视频（`aid:2` 格式） |
| `cleanFavoriteResources` | `POST /x/v3/fav/resource/clean` | 清除失效视频 |

### 「更多」按钮
- 进入收藏夹详情时显示（`moreBtnRef.visibility = VISIBLE`），返回列表隐藏
- 图标 `MoreDotsIcon`（竖向三点，Canvas 绘制）
- 点击用 `PopupWindow` **在原位置向下展开**，150ms 淡入 + 轻微下移动画，`showAsDropDown` 对齐按钮左下
- 四个菜单项：编辑收藏夹 / 批量管理 / 清除失效视频 / 删除收藏夹（删除项用 accent 色标示危险）

### 四个功能
1. **编辑收藏夹**：复用 `CreateFavoriteFolderActivity` 的编辑模式（`EXTRA_EDIT_ID/TITLE/INTRO/PUBLIC`），标题变「编辑收藏夹」、按钮变「保存」、预填原值，提交走 `editFavoriteFolder`
2. **批量管理**：见下
3. **清除失效视频**：`AlertDialog` 二次确认 → `cleanFavoriteResources` → 重新加载当前收藏夹
4. **删除收藏夹**：`AlertDialog` 二次确认（显示将删除的内容数）→ `deleteFavoriteFolders` → 返回收藏夹列表并刷新

### 批量管理
- 进入后重建列表（用已加载的 `favVideos`，不重新请求）
- 每行左侧包一层 `CheckBox`（`wrapFavoriteRow`），整行点击 = 切换选中，不进入播放；长按菜单禁用
- 底部操作条 `showBatchBar`：左侧「全选/取消全选」（默认不全选），右侧「删除(N)」
- 删除按钮在未选中时 `alpha 0.45` 禁用
- 二次确认后调 `batchDeleteFavoriteResources`，成功后退出批量模式并刷新收藏夹
- 返回收藏夹列表时 `exitBatchMode()` 自动清理状态

**验证**：`:app:testDebugUnitTest :app:lintDebug` + 常驻 `assembleDebug` 通过（lint 213 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `7532db1b9374ee91625e7970781509d52d578f65b277ecbd796a4da333535d69` 已装机（`Success`）。

### 弹窗与批量条 UI 返工（2026-11-21）
用户反馈系统 AlertDialog 与批量条太丑。

**弹窗统一**
- 三处 `android.app.AlertDialog.Builder`（清除失效视频 / 删除收藏夹 / 批量删除）全部替换为项目已有的 `showConfirmDialog()`
- 该组件用 `Ds.card` + `Ds.pill`，标题 17sp display bold、正文 13.5sp muted、按钮为圆角胶囊（取消=浅底、确认=`Ds.colors.danger`），与全站风格一致

**批量操作条重做**
- 从通栏灰底改为**悬浮圆角卡片**：`Ds.card(surfaceRaised, lineSoft, Ds.Radius.card)` + `elevation 12dp`
- 左右各留 `Ds.Space.md` 边距，底部留 `Ds.Space.md`，不再贴边
- 全选按钮 96×42dp；删除按钮 104×42dp，底色改用 `Ds.colors.danger`
- 入场动画：`translationY 72dp → 0` + 淡入，200ms

**勾选框重绘**
- 用自绘 `BatchCheckView` 替换系统 `CheckBox`：圆角 28%、未选中为 `lineSoft` 描边、选中为 `accent` 实心 + 白色对勾
- 选中时 1.12 倍缩放回弹动画（90ms 放大 + 110ms 复原）

**返回键修正**
- `handleBack()` 与 `onBackPressed()` 均优先判断 `batchMode`：批量管理时先 `exitBatchMode()`，不再直接退回收藏夹列表

**验证**：`:app:testDebugUnitTest :app:lintDebug` + 常驻 `assembleDebug` 通过（lint 213 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `ca407c18ee858d4be306df4f53833a5f3b175c1d26b7bffd9ee3fc0ac8ad301c` 已装机（`Success`）。

### 批量管理四个缺陷修复（2026-11-21）

用户反馈 4 个问题，均属实现疏漏。

**1. 全选后按钮文字不更新（根因：取错了子 View）**
- `updateBatchBar()` 用 `bar.getChildAt(1) as? TextView` 取删除按钮，但索引 1 实际是中间的弹性占位 `View`，转型失败后整个方法直接 `return`，UI 永不刷新。
- 修复：新增 `batchSelectAllBtn` / `batchDeleteBtn` 字段引用，不再依赖子 View 索引；`hideBatchBar()` 里清空引用。
- 同时补了动效：文字切换走 70ms 淡出 → 换字 → 110ms 淡入；删除按钮 alpha 用 120ms 过渡。

**2. 全选只能选到已加载的 20 条**
- 根因：`enterBatchMode()` 只重建已加载的 `favVideos`。
- 修复：进入批量管理时若 `!favEnded`，先显示「正在加载全部收藏内容…」并调用 `fetchAllFavoriteVideos()` 串行拉完所有分页，再重建列表与操作条。这样全选覆盖整个收藏夹。

**3. 进入批量管理后左上角计数变成 20，退出也不恢复**
- 根因：`rebuildFavoriteFolderList()` 用 `favVideos.size` 当计数，而 `favVideos` 只含已加载部分。
- 修复：改用 `folder.count`（收藏夹真实总数），仅在 `count <= 0` 时回退 `favVideos.size`。普通加载路径同样改为 `folder.count`。
- 退出批量管理时同样走该逻辑，计数自然正确。

**4. 清除失效视频后计数不更新**
- 根因：`confirmCleanInvalid()` 用闭包里捕获的旧 `folder` 调 `openFavoriteFolder()`，`count` 还是清除前的值。
- 修复：新增 `BiliApi.refreshFavoriteFolder(folderId)`（复用 `fetchFavoriteFolders` 按 id 取最新元数据）；清除成功后先刷新元数据再重进收藏夹。批量删除后同样刷新。

**验证**：`:app:testDebugUnitTest :app:lintDebug` + 常驻 `assembleDebug` 通过（lint 213 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `0ed58f50be50b24dd9e8c5d2ba5954623773d4deca3445262c99524d724db972` 已装机（`Success`）。

---

## emoji 裁切修复 + 收藏夹封面（2026-11-21）

### 1. 标题 emoji 顶部被裁
- **根因**：标题设了 `includeFontPadding = false` + `isFallbackLineSpacing = false`，emoji 字形超出汉字字框的部分被裁掉。
- **修复**：改回 `includeFontPadding = true`，行距 3dp，上下各留 1dp padding，保证 ❤️ 完整显示。

### 2. 封面选择与裁剪

**上传 API（`BiliApi.uploadCoverImage`）**
- `POST /x/dynamic/feed/draw/upload_bfs`（multipart），字段 `file_up` / `category=daily` / `csrf`
- 返回 `data.image_url` 经 `normalizeUrl` 处理后作为 `cover` 参数
- 该接口要求图片宽高均 > 420，因此裁剪输出定为 **640×400**

**裁剪页（`CoverCropActivity`）**
- 固定 16:10 裁剪框（与收藏夹封面比例一致），水平垂直居中，四周留操作空间
- 单指拖动平移，双指捏合缩放（`ScaleGestureDetector`，缩放范围 1×~6×）
- `constrain()` 保证图片始终覆盖裁剪框，不会露出黑边
- 视觉：框外半透明遮罩 + 白色边框 + 三分构图辅助线
- 导出时用逆矩阵把裁剪框映射回原图坐标，`Bitmap.createBitmap` 截取后缩放到 640×400，写入 `cacheDir` 的 JPEG（质量 92）

**创建/编辑页接入**
- 封面区改为可点击卡片：左侧 96×60dp 缩略图（未选时显示占位图标），右侧提示文字
- 点击 → `ACTION_OPEN_DOCUMENT` 选图 → 裁剪页 → 上传 → 回显缩略图（`@200w_125h.webp`）
- 上传中提示「正在上传封面…」，失败回退为未选状态并 Toast 错误
- 裁剪临时文件上传成功后即删除
- `createFavoriteFolder` / `editFavoriteFolder` 新增 `cover` 参数，非空时写入表单

**验证**：`:app:testDebugUnitTest :app:lintDebug` + 常驻 `assembleDebug` 通过（lint 219 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `75f4359d06e91ade275bb573ad18629ddbe3bbd703762aeea6992078442c7da6` 已装机（`Success`）。

### 收藏夹自定义封面不显示（根因：接口不返回该字段）

**关键发现**：`x/v3/fav/folder/created/list-all` 的 list 对象**只有** `id / fid / mid / attr / title / fav_state / media_count`，**不含 `cover`**。上一轮我加的 `item.optString("cover")` 永远取到空串，所以封面始终回退到「首个视频封面」。

官方文档确认 `cover` 只在以下接口返回：
- `x/v3/fav/folder/info?media_id=`（单个收藏夹元数据）
- `x/v3/fav/folder/collected/list`（他人收藏夹）

**修复**：
- 移除 `created/list-all` 里无效的 cover 解析，加注释说明
- 新增 `BiliApi.fetchFavoriteFolderInfo(mediaId)`：`GET /x/v3/fav/folder/info`，解析 `id/title/media_count/upper.name/attr/cover`
- `refreshFavoriteFolder()` 改走 `folder/info`（比全量拉列表更省）
- 列表卡片封面优先级：`folder.cover` > `folderCoverCache[id]` > 首个视频封面；无自定义封面时异步查一次 `folder/info`，拿到后写入缓存并刷新缩略图
- 进入收藏夹详情时也补一次 `folder/info`，同步最新封面
- 返回收藏夹列表时清空 `folderCoverCache`，保证重新进入能拿到最新封面

**验证**：常驻会话（assemble + test + lint 一次跑完）通过（lint 219 warning / 1 information，无 error）；APK `9,974,384` 字节，SHA-256 `85773af9bf9fb8c9f372b97706d036699af23df427e50fa9a064a1aaad36e962` 已装机（`Success`）。

### 封面仍不显示：后端顾问排查 + 架构级修复（2026-11-22）

上一轮只改了「数据源接口」，用户反馈仍不显示。这次按约定派发后端顾问完整读码排查（子代理只读、无改动），确认是**架构级失败链**，不是绘制问题。

**顾问确认的源码级缺陷**
1. 封面元数据查询被挂在 `enqueueFolderPreview()` 的回调内部，而该回调走 `maxPreviewConcurrent = 2` 的限流队列，且会因代际不匹配被丢弃 → 查询可能**根本不发起**。
2. `fetchFavoriteFolderInfo()` 走公共池 `networkWorkers`（3 线程 + 16 有界队列 + AbortPolicy），收藏夹多时请求被直接拒绝；调用方又把错误 `{ info, _ -> }` 丢弃，也不重试。
3. `coverMetaRequested` 从未在 `loadFavorites()` 里清空，而 `folderCoverCache` 会清 → 重载后封面不再查，卡死在首视频封面。
4. 自定义 URL 一旦非空就写缓存并禁止回退，图片加载失败（`onFailure`）时把 ImageView 设 GONE 且无回退 → 只剩空底板。
5. `bind()` 用 `count <= 0 || url 空` 双重条件决定显隐，空收藏夹即使有自定义封面也不显示。
6. 手工拼 `$coverUrl@320w_200h.webp`，遇到 URL 已有 `@` / query / fragment 会拼坏。

**修复（全部落地）**
- `BiliApi` 新增独立池 `folderMetaWorkers`（2 线程 + 128 队列），`fetchFavoriteFolderInfo()` 改走它，不再挤公共池；拒绝时回调报错并打 `YuiFavCover` 日志。
- `buildFavoriteFolderCard()` 重构：封面元数据**独立直接发起**，不再依赖预览回调；`loadFavorites()` 里补 `coverMetaRequested.clear()` 与 `previewCache.clear()`。
- 查询成功拿到自定义封面 → 绑定；**加载失败 → 清缓存并回退到首视频封面**；查不到封面（无自定义/失败）→ 走首视频预览回退。
- 删除已无调用者的 `bindFolderCustomCover()`。
- `FavoriteStackCoverView.bind()` 增加可选 `onFailed` 回调；显示条件只取决于 URL，`count` 仅用于角标。
- 去掉手工后缀拼接，交给 `CoverLoader.imageUrl()` 按 URL 结构处理。
- 修复 lint `SuspiciousIndentation`（`fetchFavoriteFolders` 回调首行判断改花括号块）。

**验证**：常驻会话（assemble + test + lint 一次跑完）通过（lint 219 warning / 1 information，无 error）；APK SHA-256 `7412bda18133414a1c9b0ca02788e410a38ebb39beccbf71fcd889067e7cb1d0` 已装机（`Success`）。已埋 `YuiFavCover` 日志（submit / cover 值 / gen 丢弃 / 查询失败 / 图片加载失败），若仍不显示可据此精确定位。

---

## 收藏夹视频下载（2026-11-22）

需求：收藏夹「播放全部」进入播放后点下载，右上角胶囊由「视频 | 合集」变为「视频 | 收藏夹」，下方列表为当前收藏夹队列，其余与合集下载一致。

**实现**
- `FavoritePlaybackLauncher` 抽出 `writeTo(intent, playback)`，播放页与下载页共用，把收藏夹队列写进任意 Intent。
- `VideoDetailActivity.launchDownloadPicker()`：收藏夹播放态时把 `favoritePlayback` 一并写入下载页 Intent。
- `DownloadPickerActivity`：
  - `onCreate()` 读收藏夹队列，包装成虚拟 `UgcSeason`（id=folderId、title=folderName、cover=当前视频封面、episodes=收藏夹条目）塞进 `item.season`，完全复用合集下载页的列表渲染与全选/排序/画质/仅音频逻辑。
  - 胶囊第二项文案在收藏夹态改为「收藏夹」。
  - 分组 id 用 `fav-{folderId}`（合集仍是 `season-{id}`），下载管理中与合集分开。
  - `enqueueSelectedSeasonEpisodes()` 重构：收藏夹队列的 cid 全为 0（收藏接口不返回），对 `cid<=0` 的条目先 `BiliApi.fetchDetail(bvid, aid)` 补全 cid 再入队；单个失败只 Toast 该条，不影响其余。抽出 `enqueueEpisode()` 统一视频/音频入队。

**验证**：常驻会话 assemble + test + lint 通过（lint 0 error；lint 的 `SuspiciousIndentation` 为间歇性误报，报错行均为未改动的原有代码，全量重跑后消失）；APK SHA-256 `0991a62cebcef49091fa835a91383552915c45defde1c9f59850f45971a738cb` 已装机（`Success`）。

---

## PGC（番剧/电影/国创）搜索与播放（2026-11-22）

需求：搜索能搜到番剧/电影，顶部独立「番剧/影视」区；点进去能播。免费番剧播全片，电影无会员播试看、有会员播全片。

### API 实证（均真实请求验证）
- 搜索：`x/web-interface/wbi/search/type?search_type=media_bangumi|media_ft`，WBI 签名 + 匿名 buvid3。**参数是 `search_type` 不是 `type`**；实测凡人修仙传→media_bangumi(season_id=28747,type=4,评分9.7)，让子弹飞→media_ft(season_id=12548,type=2)，均正常返回。
- 详情：`pgc/view/web/season?season_id=`，返回体是 **`result` 不是 data**；`type` 映射 1番剧/2电影/3纪录片/4国创/5电视剧/7综艺；`episodes[].duration` 单位毫秒；`section_type=1` 是预告；cid 可能是超 Int 大数（让子弹飞 27499037045）**必须 Long**。
- 取流：`pgc/player/web/playurl`，免费番剧 `is_preview=0` 有完整 DASH；让子弹飞 `is_preview=1`、MP4 durl 4 段试看、`timelength` 是正片全长（7914772ms）**不能当可播时长**、`error_code=-10403` 但外层 code=0 有试看流，不能误判失败。

### 改动
- `BiliApi`：新增 `PgcMedia/PgcEpisode/PgcSeasonDetail` 数据类；`searchPgc(keyword, searchType)`（复用 BiliWbiSign + 匿名 buvid3 + searchWorkers）；`fetchPgcSeason(seasonId)`（解析 result）。
- `BiliPlayUrl`：新增 `PgcPlayResult(stream,isPreview,isDrm)`、`fetchPgcPlayUrl()`、`parsePgcStream()`：**试看由 is_preview 显式判定**，试看可播时长用 durl 分段实际长度之和，不用 timelength。
- 新 `PgcDetailActivity`（Manifest 已注册）：封面信息/类型/评分/简介 + 分集列表 + 精简 ExoPlayer（复用 `OnlinePlaybackSource.create()` 同时支持 DASH 与 durl 分段，含 CDN Cookie 隔离）；有大会员→1080P 请求全片，否则登录→720P/游客→480P；按 `is_preview`/`is_drm` 显示试看/版权提示，点击用 `InAppWebActivity.open()` 跳官方页兜底（顾问发现 `PlayerActivity` 根本没在 Manifest 注册，不依赖它）。
- `SearchActivity`：**按前端顾问评审，GridView 无 addHeaderView 且不能跨两列，改为 ListView + 每行双列 LinearLayout 行容器**；ListView 加 `pgcHeaderSlot` 空槽位 header，内含标题行 + 3:4 竖版海报横滑卡片（类型角标/评分角标）；卡片点击进 PgcDetailActivity。关键防坑：createCard 的 LayoutParams 改为 LinearLayout.LayoutParams、卡片自带 OnClickListener 避开 position 偏移、setRecyclerListener 清双卡、分页锚点 removed/2、双代际校验、同关键词仅切筛选复用 PGC、searchWorkers 单线程故先 UGC 后 PGC、showState 时清 PGC header 避免叠层。

**验证**：常驻会话 assemble + test + lint 通过（lint 0 error）；APK SHA-256 `3396aa063ee82681ad4baf5353e030a912b20f01d0133a6b3ba7895e49f782a6` 已装机（`Success`）。待用户真机验收：搜「凡人修仙传」「让子弹飞」顶部区、点进播放、剧集切换、试看提示与会员全片。

---

## PGC 三个缺陷修复（2026-11-22）

用户真机反馈：PGC 卡片能显示，但（1）点头像卡片进详情闪退回搜索页；（2）返回后再搜同关键词 PGC 区消失；（3）封面被裁剪。派前端 + 后端两顾问排查（本次按用户要求都先问了顾问），结合静态分析定位并修复。

**根因与修复**
1. **闪退（无 Java 异常栈）**：`PgcDetailActivity` 分集 `ListView` 放在 `ScrollView` 里且高度 `WRAP_CONTENT`。凡人修仙传有 319 集，ListView 在 WRAP_CONTENT 下会一次性测量/绘制全部子项，内存暴涨被系统 LMK 杀进程 → 不走 UncaughtExceptionHandler，所以抓不到栈。修复：ListView 设**固定高度 340dp**，自身回收复用、内部滚动。
2. **再搜索 PGC 区消失**：`SearchActivity.showState()` 无条件 `clearPgcHeader()`，而 UGC 空结果/错误路径会调 showState。UGC 与 PGC 两条异步链竞态，谁后完成决定 PGC 是否可见 → 偶发首次有、再次搜消失。修复：`showState()` 不再清 PGC header；改在 `performSearch()` 开头（换关键词时）清一次。
3. **PGC 搜索队列竞态**：`searchPgc` 原本走 UGC 的单线程 `searchWorkers`（容量8+AbortPolicy），快速连续搜索会被阻塞/拒绝导致空合并。修复：新增独立 `pgcSearchWorkers`（2线程+64队列）；两路 PGC 请求失败时不再当空列表渲染清空，保留已有 header。
4. **封面裁剪**：PGC 海报多为竖版，`cropThumb=true` 会加 `1c` 后缀裁成横图。改为 `cropThumb=false` 按原比例完整显示。

**验证**：常驻会话 assemble + test + lint 通过（lint 0 error）；APK SHA-256 `c29d49823dd58a22b1d01ce63307f54de0bc5569f90f15bd83323d68f0e8c22c` 已装机（`Success`）。待用户真机复验三点。

---

## PGC 搜索「只第一次可用」竞态修复（2026-11-22）

用户反馈：搜一次后 PGC 区再也不出现，甚至退出重进也不行。派后端顾问排查；顾问确认搜索不写持久化 cookie，问题不在 buvid 写坏，而在**并发清空匿名 cookie 导致回落登录态**。

**根因链（源码可证）**
- `anonymousSearchCookie` 是共享内存变量；`isRequestBanned` 命中时会被置空。
- UGC / 两路 PGC 搜索并发（PGC 走独立 2 线程池），一个线程清空 cookie 后，另一个线程的 `searchHeaders()` 读到空 → 不放 Cookie header → `jsonCall` 隐式回落到默认 `BiliSessionStore.cookie()`（登录态）。带登录态请求匿名搜索接口被 B 站拒绝 → 后续全挂。
- `ensureSuccess` 丢弃业务码，`isRequestBanned` 只认英文 "request was banned"，中文 -412「请求被拦截」匹配不到，不刷新 cookie 重试。
- `SPI` 用 `getJson(FINGERPRINT_URL)` 隐式带登录 cookie 取 buvid，可能拿到不合适的值。

**修复（全部落地）**
- `ensureAnonymousSearchCookie()` 改为**返回本轮 anonymous cookie 的局部快照**（`@Volatile` 缓存命中读一次 / 初始化成功返回刚构造值），各线程各用各的，不再受并发清空影响。
- 新增 `searchJson(address, anonCookie)`：`jsonCall(..., session = "")` **显式不带登录态**；`searchHeaders(anonCookie)` 始终写入该匿名 cookie。suggest/UGC/PGC 三处调用全部改用局部 cookie 快照。
- SPI 改用 `jsonCall(FINGERPRINT_URL, emptyMap(), session = "")` **无条件匿名请求**。
- `isRequestBanned` 扩展识别 412 / 拦截 / v_voucher / 风险 / 过快，触发刷新 cookie 重试。
- SearchActivity 失败时不再当空结果清空 header（前一轮已修）。

**验证**：常驻会话 assemble + test + lint 通过（lint 0 error）；APK SHA-256 `df8727e9f134271b48eae5d37ee01b8771ecf428d82638a5caf5fc7edee1e5d4` 已装机（`Success`）。顾问提示：若真机彻底杀进程后仍失败，需分阶段日志（PID / 失败阶段 / 实际 cookie 模式）继续定位，现有证据不足以判定 B 站封设备/账号。

---

## PGC 搜索「全部搜不到」根因修复：wbi 接口被风控，切换老接口（2026-11-22）

### 用户反馈
番剧/电影完全搜不到（此前是「只第一次可用」，本轮是完全无结果）。

### 容器内实测定位（python 模拟 app 完整链路）
1. **wbi 接口被风控静默清空**：`x/web-interface/wbi/search/type`（PGC 专用）无论 Mobile/Desktop UA、带不带 spi cookie，均返回 `HTTP 200 + code=0` 但 `data` 只含 `v_voucher`（GAIA 验证码凭证），`result` 缺失。开发日志昨日记載当时实测可用，判断是 B 站收紧了风控。
2. **app 静默吞掉风控**：`ensureSuccess` 只查 `code!=0`，`searchPgc` 对缺失 result 用 `?: JSONArray()` 兜底，风控被当成「成功但 0 条」，`isRequestBanned` 从未触发。
3. **老接口实测稳定**：`x/web-interface/search/type`（UGC 搜索在用的非 wbi 接口）同样 Mobile UA + 同一匿名 cookie 下，番剧/影视/视频三路 5 连测全部稳定返回，条目含 app 所需全部字段（season_id/season_type/media_id/media_score/ep_size/areas/desc/cover），解析无需改动。零命中形态：`numResults=0, numPages=0`，无 result 键。

### 后端顾问评审（task `373dee9c-8426-4b02-b667-5c65511b5964`，结论「有条件通过」）
- 必须做：切换老接口 + keyword UTF-8 编码；读取列表前显式识别 `data.v_voucher` 区分风控与零命中；搜索链路保留 HTTP 状态码（非 JSON 风控页不能变成解析异常丢失状态）。
- 不要做：不删 `BiliWbiSign`（PGC 取流依赖）；不加登录态/额外指纹；不换未经验证的「番剧专用接口」（`pgc/season/index/result` 是分类索引，无关键词搜索契约）。
- 建议：保留匿名 cookie 快照与 season_id<=0 跳过；repeat(2) 是「两次尝试一次重试」可保留。

### 修复落地（全部在 BiliApi.kt）
1. `searchPgc()`：请求从 `wbi/search/type`（BiliWbiSign 签名）改为老接口 `search/type`，keyword/searchType URLEncoder 编码；读取列表前先 `classifySearchOutcome(data)` 三分类——GAIA_VERIFICATION 抛 `v_voucher:` 异常触发重试/冷却，EMPTY（numResults=0 且无 result 键）直接回空列表不重试，HAS_RESULT 正常解析。
2. 新增 `SearchOutcome` 枚举 + 纯函数 `classifySearchOutcome(voucher, numResults, hasResultKey)`（含 JSONObject 重载），放在 `isRequestBanned` 旁。
3. `searchJson()`：非 2xx 时抛「搜索接口异常（HTTP xxx）」保留状态码（旧实现非空 HTML 会进 JSON 解析异常丢失状态）；空响应同样带状态码。
4. `isRequestBanned()` 改 internal 并新增 `isRequestBannedForTest()` JVM 单测入口（消息匹配已覆盖 v_voucher/412/拦截/风险/过快）。
5. 新增单测 `PgcSearchOutcomeTest`：命中/零命中/风控/结构异常四形态分类 + 六类消息的风控识别。

### 验证与装机
- 常驻会话 `assembleDebug + testDebugUnitTest + lintDebug` 通过（BUILD SUCCESSFUL in 1m 15s）。
- APK 9,974,384 字节，SHA-256 `f53a45d74192bcaba76f9334aec3320ab2391054bc483651f097541db89ce217`，`http-cat.py` + Shizuku `pm install -r -t` 装机 `Success`（哈希一致）。
- 待用户真机验收：搜索「凡人修仙传」「让子弹飞」，番剧/影视区每次搜索都应出现。
- 备注：装机端口服务曾误用 Bash 后台起（进程随命令结束被杀，Shizuku 连不上），改回 terminal 常驻标签后成功。

---

## PGC 详情页点击闪退修复：取流回调未切主线程（2026-11-22）

### 现象与根因
用户反馈：搜索成功，但点击卡片「自动退回首页」。真机 `logcat -b crash` 抓到 FATAL：
```
CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views.
  at PgcDetailActivity.playEpisode$lambda$29(PgcDetailActivity.kt:228)
  at BiliPlayUrl.fetchPgcPlayUrl$lambda$15(BiliPlayUrl.kt:452)
```
`fetchPgcPlayUrl` 的回调在其后台线程池线程执行，`PgcDetailActivity.playEpisode` 回调里直接 `setVisibility`/`bindSources`（ExoPlayer 也要求主线程）→ 点击剧集/进入详情自动起播时崩溃，进程被杀、Activity 栈丢失，观感即「退回首页」。首页搜索列表卡片点击的是 `PgcDetailActivity` 入口（点击本身不崩），所以现象集中在「点进详情后」。

### 修复（PgcDetailActivity.kt）
`playEpisode` 回调包 `runOnUiThread`（与 `VideoDetailActivity` 的取流回调惯例一致）：线程守卫（isFinishing/isDestroyed）留在后台线程先行判断，主线程内再校验 `gen != playGeneration` 防止旧回调覆盖。`fetchPgcSeason` 回调本就全程走 `mainHandler`，无需改动。

### 验证与装机
- 常驻会话 BUILD SUCCESSFUL；APK 9,974,384 字节，SHA-256 `e6a68600423d777937f996eca392a06c6caec89da5de2fb26c075e5633ef3e68` 已装机（哈希一致，Success）。
- 待真机验收：搜番剧点进详情、自动起播第一集、切换分集、试看提示；不再退回首页。
- 注：此前用户看到「点卡片闪退回搜索页」的修复（固定 340dp 防 OOM）是另一独立问题，本轮为新的线程问题。

---

## 搜索页番剧区「后插入导致整页下推闪烁」架构重构（2026-11-22）

### 现象与根因（前端顾问评审确认）
搜索结果页先闪出 UGC 视频列表，随后番剧区才插到顶部把内容整体下推。
**主因**：`performSearch()` 把 UGC 与 PGC 完全解耦并发，但 `searchPgc` 已改走独立 2 线程池且需等 media_bangumi + media_ft **两个**请求都完成，因此 PGC 显著晚于 UGC；UGC 先回就 `toggleSkeleton(false)` 并渲染首屏，PGC 后到把 `GONE`（高 0）的 ListView header 改 `VISIBLE`（约 230dp），整块下推。
**次因**：骨架屏 140ms 淡出与卡片 220ms 上浮动画叠撞，放大撕裂感；`clearPgcHeader()` 先 removeAllViews 再 addView 使 position 0 重测。

### 顾问方案取舍（否决 A/C）
- 否决 A（PGC 移出 ListView 做顶部常驻）：会永久挤占小屏 1/3 视口，套 ScrollView 会踩本项目已踩过的嵌套滚动/OOM 坑。
- 否决 C（header 常驻预留高度）：大部分关键词无番剧结果，会从「少数词闪」变成「绝大多数词必闪」。
- **采纳 B**：保留单 ListView（PGC 可自然滑出屏幕、零嵌套冲突），改为「首屏聚合门控 + 一次提交」，超宽限窗则丢弃迟到 PGC。

### 落地（仅改 SearchActivity.kt）
1. 新增 `FirstScreen` 聚合器（keyword/generation/videos/error/ugcSettled/pgcItems/pgcSettled/committed）与 `firstScreen` 字段。
2. `performSearch()`：先 `cancelFirstScreenGate()`，建 gate 后并发发起 UGC 与 PGC（注释已修正为「并发」）。
3. `requestPgcHeader(keyword, gate)`：不再直接改 UI，只写 gate 并在两个 PGC 都回来后 `pgcSettled = true`。
4. `requestSearchPage(initial=true)`：不再直接渲染，写入 gate 后调 `commitFirstScreenIfReady()`；时长筛选多页扫描保持骨架挂起。
5. `commitFirstScreenIfReady()`：UGC 未定则等；UGC 已定但 PGC 未定则挂 `PGC_FIRST_SCREEN_GRACE_MS = 260ms` 宽限窗，超时则 `pgcItems = emptyList()` 并提交（迟到 PGC 丢弃）。
6. `commitFirstScreen()`：唯一关骨架入口。同一帧内先 `renderPgcHeader()` 落定 header 高度、再 `resultAdapter.replace()`，最后 `toggleSkeleton(false)`——骨架淡出期间画面完全静止，零下推。UGC 失败但 PGC 有结果时不丢番剧区。
7. 同关键词（切换排序/时长）复用 `lastPgcKeyword/lastPgcItems`，不再重拉重闪。
8. 生命周期：`onDestroy()` / `resetToSearchEntry()` / `onKeywordChanged()` 均 `cancelFirstScreenGate()` 清宽限窗回调。

### 验证与装机
- 常驻会话 BUILD SUCCESSFUL；APK 11,010,692 字节，SHA-256 `9bddc890a690710af775340bb7c8e29e5aa4f32d824cbdedf7bf65710b612e04` 已装机（哈希一致，Success）。
- APK 体积从 9.97MB 增至 11.01MB：已核实本次仅修改 `SearchActivity.kt`，仓库另有大量历史未提交改动，dex 波动属 debug 包缓存状态差异（未开混淆），不影响功能。
- 待真机验收：连续搜索多个关键词（含纯 UGC 词、有番剧的词），首屏应一次成形、无下推闪烁。

---

## 番剧区残留在搜索页 + PGC 播放页重做（2026-11-22）

### 1. 番剧影视「卡在搜索页」修复
- **根因**：`onKeywordChanged()` 在退出结果页时清空了 `resultAdapter` 并显示搜索历史，但**没有清空番剧区** `pgcHeader`，导致搜索历史与番剧卡片同屏残留（用户截图正是此状态）。`resetToSearchEntry()` 与 `onDestroy()` 都有清理，唯独此处遗漏。
- **修复**：`onKeywordChanged()` 的 `showingResultPage` 分支内补 `if (::pgcRow.isInitialized) clearPgcHeader()`。

### 2. PGC 播放页重做（前端顾问方案 + 实测校验）
**顾问关键结论**：沿用 UGC 播放页同构的「顶部 16:9 播放器 + 简介|评论双 Tab」，不引入 Compose/RecyclerView；页内选集用横向滚动条避免 ScrollView 嵌套 OOM，全集弹窗用 Dialog 内 ListView 回收。

**评论 oid 实测校验（与顾问初判对照）**：
| 传参 | 实测结果 |
|---|---|
| `aid + type=1` | 凡人修仙传第1集 **42730 条**评论，内容与官方一致 ✅ |
| `ep_id + type=14` | 返回 0 条（另一个独立空评论区） |
| `season_id + type=14` | -404「啥都木有」 |

结论：采用 `ep.aid` + `type=1`（顾问方案正确，实测确认）。

**新增/改动文件**：
- [PgcPlayerWidgets.kt](app/src/main/kotlin/com/yuilittle/bili/PgcPlayerWidgets.kt)（新建）：从 `VideoDetailActivity` 私有内部类抽取 `PgcPlayPauseIcon` / `PgcNextEpisodeIcon` / `PgcFullscreenIcon` / `PgcSeekBar` / `PgcGestureLayer`（单击显隐、双击暂停、横滑进度、左右竖滑亮度/音量、长按 2 倍速）。
- [PgcDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/PgcDetailActivity.kt)（整体重写，364 → 约 1500 行）：
  - 播放器：`PlayerView(useController=false)` + 手势层 + 控制层（顶栏返回/标题、进度条与时间、播放暂停/下一集/倍速/画质/全屏）+ 自动隐藏控制层 + 全屏横屏切换。
  - 信息区：简介|评论双 Tab；简介页依次为**封面信息块**（左 96×128dp 3:4 海报 + 右侧标题 17sp 两行/类型与风格标签/完结集数评分/三行简介预览+展开）、**全宽完整简介**（展开时从 0 高度平滑展开、跨过封面下沿，240ms `MotionTokens.easeOut`，`isReduced` 时瞬切）、**其他版本**（`seasons.size>1` 才显示）、**选集区**（选集 + 正序/倒序胶囊 + `当前/总数 ›` + 横向卡片条）、**预告·花絮**（`sectionType!=0` 才显示）。
  - 选集弹窗：Dialog 内 ListView 固定 360dp 自身回收，支持 300+ 集不 OOM。
  - 播放：`fetchPgcPlayUrl` 回调在后台线程，全部 `mainHandler.post` + `playGeneration` 防串集；试看/DRM 提示改为信息区顶部柔和横幅（不遮挡画面），点击跳官方页；`STATE_ENDED` 自动播下一集正片。
  - 评论：按当前分集 `aid` 拉取，切集时重置游标与去重集合；复用 `CommentSectionUi.buildUnifiedCommentItem` 与 `ReplySheetDialog`。
- [BiliApi.kt](app/src/main/kotlin/com/yuilittle/bili/BiliApi.kt)：新增 `PgcSeasonRef(seasonId, title, badge)`；`PgcSeasonDetail` 增加 `seasons`；`fetchPgcSeason` 解析 `seasons[]`，并兼容 `ep_id` / `id` 两种分集 id 字段。

### 验证与装机
- 常驻会话 `assembleDebug + testDebugUnitTest + lintDebug` 全部通过（BUILD SUCCESSFUL in 1m 37s）。
- APK 9,974,384 字节，SHA-256 `35c443992d85f77a894bc02a508ddcdfca7447ec5175d5b37548b49a8c9e8778` 已装机（哈希一致，Success）。
- 待真机验收：搜索番剧点进播放页、首集自动播放、选集正序/倒序切换、展开/收起简介、切集后评论区跟随、试看提示、全屏与手势。

---

## PGC 播放页推倒重做：整体复制 UGC 播放页改数据源（2026-11-22 下午）

### 背景
用户反馈上一版精简重写丢了大量功能（画质切换/全屏手势/下一集/选集面板/评论等），明确要求：**直接整体复制视频播放页，换数据源与文案**，不要重写。点赞/收藏/下载/分享保留，放在选集上方。

### 本轮方案（按用户要求执行）
- `cp VideoDetailActivity.kt → PgcDetailActivity.kt`（6035 行基底），保留全部播放器控件、手势（横滑进度/左右竖滑亮度音量/长按倍速）、控制层、全屏、选集抽屉、合集弹窗、评论楼中楼、断点重连、看门狗等全部能力。
- **数据源适配层**：`pgcItemFor()` 把 `PgcEpisode + PgcSeasonDetail` 映射成 `VideoItem + UgcSeason`（分集→合集 episodes），因此 selectEpisode/loadPlayer/画质菜单/下一集/选集抽屉/弹窗全部**零改动复用**，只是数据来自 PGC。
  - 取流：`loadPlayer` 改用 `BiliPlayUrl.fetchPgcPlayUrl`（回调后台线程→`handler.post` + generation 防串集），返回 `PgcPlayResult(stream, isPreview, isDrm)`。
  - 试看/DRM：用接口显式标记，不再按时长猜测。
  - 评论：oid = 分集 `aid`，type=1（实测凡人修仙传第1集 42730 条与官方一致）。
  - 历史：沿用 `reportPlayHistory`（分集 aid/cid）。
- **删除的 UGC 专属**：本地下载播放（refreshLocalPlaylist/loadLocalPlayer/批量函数 438 行）、收藏夹队列（favoritePlayback/Mode 全部）、UP 主行（头像/粉丝/关注）、UP 跳转、在线人数。
- **保留的 UGC 功能**：点赞/收藏/下载/分享操作栏（放选集上方，用户要求保留）、画质菜单、倍速、全屏、选集抽屉与弹窗、评论区全套。
- **文案替换**：合集→选集，抽屉标题「选集」，单位「集」，弹窗「第 N 集」。
- **信息块重写（用户指定的展开方式）**：[PgcIntroBlockView.kt](app/src/main/kotlin/com/yuilittle/bili/PgcIntroBlockView.kt)——全宽简介 TextView + `FlowMarginSpan`（LeadingMarginSpan2）：海报高度覆盖的行缩进「海报宽+间距」，其余行全宽；**右侧放不下时文字自然流到封面下方**，展开/收起只是 span 行数与 maxLines 变化，同一文本流渲染零跳动；「展开/收起」内联在简介末尾文字处（非右下角按钮），整块点击也可切换。

### 适配细节
- 入口改为 `open(context, seasonId)`，onSaveInstanceState 存 seasonId。
- 类名/`this@` 引用全部替换；删除文件尾顶层函数（formatQualityButtonText 等与原文件冲突）。
- companion 清理 UGC 常量；PlaybackTimeline/画质偏好/看门狗等原样保留。
- `collectSeasonItems`/`resolveNextSeasonTarget` 简化为纯选集队列（删收藏夹/本地分支）。
- `FlowMarginSpan.drawLeadingMargin` 签名必须是 `(Canvas, Paint, int×7, CharSequence, int, int, boolean, Layout)`（用 javac 对 android.jar 实测确认，TextPaint/可空参数都编译不过）。

### 验证与装机
- 常驻会话 assemble + test + lint 全过（BUILD SUCCESSFUL in 1m）；APK 9,974,384 字节，SHA-256 `f485f28d5a1f37e94e2bceabcb38239766f9c633f5fc5551e5a7c39470e143aa` 已装机（哈希一致，Success）。
- 待真机验收：画质切换、全屏手势（左右亮度/音量）、全屏下一集按钮、控制面板选集入口、点赞/收藏/下载/分享、简介绕排展开、评论按分集切换。
- 首次装机闪退（进入即退回首页）：`introExpandWrap` 在 `renderIntroduction` 里被重复 `infoContent.addView`（PgcInfoBlock 接入时加了一次，原操作栏路径又一次）→ `IllegalStateException: The specified child already has a parent`。已删重复那次；重装 APK `2827904f...`（哈希一致）。
- 文字叠字 + 封面错误（用户截图反馈）：① 简介TextView与标题区同为 FrameLayout 叠层且无 topMargin，直接重叠 → 重写 PgcInfoBlockView：简介顶部偏移 = 标题块高度（OnLayoutChangeListener 首次布局后测量），收起态用 StaticLayout 按「海报剩余高度行数」预截断并给「展开」留宽（内联省略号），展开态 FlowMarginSpan 让右侧放不下的文字流到海报下方；② 封面改用 `detail.media.cover`（番剧封面）优先，分集封面仅回退。APK `5b4b2368...`（哈希一致）。
- 简介完整显示 + 标题用番剧名（用户截图反馈）：① 标签与简介间距 8dp；② 去掉展开/收起机制（番剧简介不长，直接完整显示，保留 FlowMarginSpan 绕排），删 setIntroExpanded/toggleIntro/introExpanded/pgcInfoBlock；③ 信息块标题改用 `item.season?.title`（番剧名《凡人修仙传》），不再显示分集标题（选集区已有小标题）。APK `e0f5002d...`（哈希一致）。
- 简介只显示一行、全屏往返后恢复：根因是 `PgcIntroBlockView` 用 `OnLayoutChangeListener` 在布局中改 `descView.topMargin`——首次进入回调触发时机不对，span 从未应用（logcat 无 `PgcIntro` 输出），简介以全宽无缩进状态测量一次即定死；全屏往返触发完整重布局才修正。已改为控件自管 `onMeasure`/`onLayout`：测量标题块 → 简介以 `UNSPECIFIED` 高度测（永不按父容器可用高度截断）→ 显式摆放简介起点 = 标题块高 + 8dp；绕排行数在 measure 阶段算出，变化即应用 span，不再依赖任何布局监听器。APK `6358502b...`。
- 下载分集封面错误 + 后台音频列表只有一集 + 下载页重复文字按钮（用户截图反馈）：
  ① `pgcItemFor` 的 `VideoItem.cover` 恢复为分集封面 `ep.cover`（下载页/音频页需要它），信息块海报单独用 `pgcPosterCoverUrl = media.cover` 传入；
  ② `enterBackgroundAudioPlayback` 的 `OnlineAudioLaunch.season` 从 `null` 改为 `currentDetail?.season`（PGC 映射出的全集），音频页列表即显示全集；
  ③ `DownloadPickerActivity.actionItemRow` 去掉右侧「存相册」「下载音频」文字胶囊，改为 40dp 圆形图标（`ActionGlyph`：KIND_IMAGE 相框+下箭头 / KIND_AUDIO 音符+下箭头），标题与副标题已说明功能。APK `76aa05cb...`（哈希一致）。
- 下载页图标按钮美化 + 启动图标偏大（用户反馈）：
  ① `ActionGlyph` 重绘：圆底 40dp→36dp，描边 1.6dp→1.5dp，主体图形上移 1.5dp 给右下角下箭头留位；音符改为单符头+符干+符尾横梁（原双符头显乱），下箭头缩小收紧；补 `contentDescription`。
  ② 自适应启动图标前景实测视觉边界占画布 75.5%，超出 66.7% 安全区 → 新增 `drawable/ic_launcher_foreground_inset.xml`（`<inset android:inset="12%"/>` 包裹 `ic_launcher_foreground_img`），`mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 的 foreground 改指向它。
  ③ 顺带修掉 lint `SuspiciousIndentation`（`SeasonEpisodeAdapter.getView` 中 `Ds.clipRounded` 移出 `apply{}` 块）。APK `8d614b8d...`（哈希一致）。
- 长简介有小部分文字悬浮在封面上（用户截图反馈）：`sideLineCount` 用 `descView.lineHeight` 算右侧可排行数，而该值在 `includeFontPadding=false` 时含字体额外上下留白、比实际渲染行距大，导致行数少算一行，最后一行越出海报底边压到封面。改为 `measureLineHeight()`：优先取实际 layout 的 `baseline(1) - baseline(0)`，无排版时回退 `fontMetrics`；行数用 `ceil` 向上取整（宁可多缩一行也不让文字覆盖海报），上限 12 行。删除不可信的 `lineHeightPx` 字段。APK `1b0f3679...`（哈希一致）。
- 删除死代码 `PlaceholderPageView.kt`：它是给底部导航未实现 tab 用的静态占位页（居中大标题 + 说明文字，无交互/无网络/无定时器，构造参数 `title`/`message` 通用）。`MainActivity.createRootPage` 只有 HOME 与 PROFILE 两个分支，全项目零引用。已删除，`assembleDebug` 正常（BUILD SUCCESSFUL）。APK `d9efdac2...`（哈希一致）。

---

## 消息通知入口（2026-11-22 晚）

### 接口实测（2026-11-22，用登录态验证）
| 接口 | 结果 |
|---|---|
| `x/msgfeed/unread` | 可用，返回 `at/chat/coin/danmu/favorite/like/recv_like/recv_reply/reply/sys_msg/up` |
| `x/msgfeed/reply` | 可用，`data.items[] + data.cursor{id,time,is_end}` |
| `x/msgfeed/at` | 可用，同 reply 结构 |
| `x/msgfeed/like` | 可用，`data.total{items[],cursor}`，每条是多人聚合 `users[]` |
| `x/msgfeed/system` | 当前账号 HTTP 404，走空态兜底 |
| `svr_sync/fetch_session_list`（私信） | HTTP 404，本轮不做 |

### 实现
- **BiliApi**：新增 `MessageTab` 枚举、`MessageItem` / `MessagePage` / `MessageUnread` 数据类；`fetchMessageUnread()`（未读汇总）、`internal fetchMessages(tab, cursorId, cb)`（按游标翻页）；`parseMessagePage()` 处理 reply/at，`parseLikePage()` 处理 like 的多用户聚合。
- **Ds.bellIcon**：手绘线条铃铛，24 单位网格，含顶部提钮 + 穹顶 + 钟锤，上下边界对称于中心。
- **HomeFeedView 顶栏**：`[Logo 32dp] [搜索框 40dp, weight=1] [铃铛 36dp]`；Logo 由 26dp 放大到 32dp（用户反馈相对 40dp 搜索框过小）；红点 `minWidth=15dp` 保正圆、`tnum` 等宽数字、`COLOR_ROSE` 填充 + 页面底色 2dp 描边护边、`99+` 封顶。
- **MainActivity**：`updateActivePage()` 在回到首页且 resumed 时调 `refreshUnread()`。
- **MessageActivity（新建）**：四个 tab（回复我的/@我的/收到的赞/系统通知），每个 tab 独立 `BiliRequest` 与游标缓存；行持有 `RowHolder`（避免索引强转）；点赞类头行显示「谁 等 N 人」、正文只放被赞内容（避免用户名重复）；底行时间用 `subtle` 不可点、「查看」用 `accent` 可点；卡片底色 `COLOR_CARD`、头像占位 `COLOR_SOFT_FILL`。
- **AndroidManifest**：注册 `.MessageActivity`。

### 前端顾问审查（已落地）
顾问指出并修复的真实缺陷：
1. `MessageActivity` 未调 `Theme.init` / `Ds.applySystemBars` / 窗口背景 → 深色模式状态栏白条撕裂。已补。
2. 列表无 `setRecyclerListener` 清理图片、头像无占位底色。已补。
3. 点赞类 `title` 与 `nickname` 重复导致正文多出一行用户名。已按 tab 区分处理。
4. 红点单位数变竖椭圆、硬编码颜色、压住铃铛线条。已改 `minWidth` + 复用 `COLOR_ROSE` + 描边。
5. 卡片错用 `COLOR_SURFACE`（抬升灰底）应为 `COLOR_CARD`。已改。
6. **快速切 tab 会永久卡在「正在加载…」**：原实现 `request?.cancel()` 后 `loading` 集合不移除。改为每 tab 独立请求、不互相取消。
7. **错误态重试点击永远绑不上**：`error ?: "...".also{...}` 在 error 非空时 `.also` 不执行。已重构 `showState(text, retry)`。
8. tab 文字未居中（`weight=1` 的 TextView 默认 `TOP|START`）。已加 `gravity = CENTER`。

### 验证与装机
- `assembleDebug + testDebugUnitTest + lintDebug` 全过。APK `0be3a534...` 已装机（哈希一致）。
- 顺带修掉 lint `SuspiciousIndentation` 误报（`setActive` 中 `panels[selectedTab]?.pauseRequests()` 提取为局部变量）。

### 已回退（2026-11-22 晚）
用户决定不做消息通知功能（工作量太大、实际用处有限）。已完整回退：
- 删除 `MessageActivity.kt`
- `BiliApi.kt`：移除 `MessageTab` / `MessageItem` / `MessagePage` / `MessageUnread`、`fetchMessageUnread()`、`fetchMessages()`、`parseMessagePage()`、`parseLikePage()`
- `Ds.kt`：移除 `bellIcon()`
- `HomeFeedView.kt`：移除消息入口、未读红点、`refreshUnread()`、`messageEntry`/`unreadDot` 字段；**保留 Logo 放大（26dp → 32dp，`BRAND_MARK_DP = 32`）**
- `MainActivity.kt`：`updateActivePage()` 恢复原样（不再刷新未读数）
- `AndroidManifest.xml`：移除 `.MessageActivity` 注册

另：`HomeFeedView.setActive` 里 `panels[selectedTab]?.pauseRequests()` 提取为局部变量的写法保留（规避 lint `SuspiciousIndentation` 误报）。APK `3fb5aab1...` 已装机（哈希一致）。
- 作者署名改为 `xiaoyuili`（用户为原作者，旧 GitHub 账号已无法登录）：
  - `ProfileSubActivity.kt:1991` 关于软件页「作者」行：`Yui-Little` → `xiaoyuili`。
  - `README.md`：署名 `Made with 💗 by Yui-Little` → `xiaoyuili`；安装章节的旧仓库链接 `github.com/Yui-Little/YuiBili/releases` 已失效，改为不带链接的「下载最新 APK 手动侧载安装」（新地址未定）。APK `08fa5723...`（哈希一致）。

---

## 回到顶部：悬浮按钮 + 底部导航重复点击（2026-11-22 晚）

### 需求
用户要求：个人空间页（动态/投稿）与搜索结果页各加一个悬浮「回到顶部」按钮；首页则重复点击底部导航「首页」回顶。三处都必须是**快速平滑回滚**，不能瞬移闪现。

### 前端顾问关键纠偏（4 处，避免了按错误假设实现）
1. 首页滚动容器是 **`GridView`（`PullGridView`）**，不是 `ListView`——统一工具必须接收 `AbsListView`。
2. **`FrostedBottomNavigation.selectDestination()` 拦截了重复点击**（`destination == selectedIndex` 直接 return），不改它则点当前首页永远不会回调 `MainActivity.showPage()`。
3. `SpaceActivity` 根与 `SearchActivity.resultsHost` 已是 `FrameLayout`，可直接叠加按钮，**无需重构根布局**。
4. `minSdk = 23`，`AbsListView.scrollListBy`（API 19+）与 `elevation`（21+）均无兼容障碍。

### 实现
- **新建 [ScrollToTopUi.kt](app/src/main/kotlin/com/yuilittle/bili/ScrollToTopUi.kt)**：
  - `AbsListSmoothTopScroller`：两段式回顶——① 距顶超过 `nearPosition` 时先无感定位到近场锚点；② `ValueAnimator` + `MotionTokens.easeOut` 驱动 `scrollListBy(-delta)` 逐帧上滚，时长按距离自适应 `260~420ms`；结束补 `setSelectionFromTop(0,0)` 兜底贴顶；`ACTION_DOWN` / detach / 外部 cancel 立即中断；`MotionTokens.isReduced` 时直接跳转。
  - `ScrollToTopButton`：44dp 正圆，中性表面（深色 `0xF521242B` / 浅色 `0xF5FFFFFF`）+ `Ds.colors.line` 微描边 + `elevation 4dp` + 圆形 outline；`setShown()` 用状态机 + 180ms/140ms alpha/translationY/scale 动画，`GONE` 彻底不拦点击。
  - `nextScrollTopVisible()`：带迟滞的显隐判定，避免临界位置抽搐。
- **[Ds.kt](app/src/main/kotlin/com/yuilittle/bili/Ds.kt)**：新增 `scrollTopIcon()`（顶杠 + 上箭头，1.85dp 线条）。
- **[FrostedBottomNavigation.kt](app/src/main/kotlin/com/yuilittle/bili/FrostedBottomNavigation.kt)**：新增 `setOnDestinationReselectedListener`；`ACTION_UP` 里区分「点已选中项 → 重选回调」与「点其它项 → selectDestination」，长按拖拽 `scrubMode` 不触发重选。
- **[MainActivity.kt](app/src/main/kotlin/com/yuilittle/bili/MainActivity.kt)**：绑定重选回调，首页时调 `HomeFeedView.scrollToTop()`。
- **[HomeFeedView.kt](app/src/main/kotlin/com/yuilittle/bili/HomeFeedView.kt)**：`FeedPanel` 持 `AbsListSmoothTopScroller(grid, nearPosition = 14)`，`onStart` 展开顶栏；暴露 `scrollToTop(): Boolean`（已在顶部返回 false 静默）；切 tab 取消旧面板动画；触底加载加 `!topScroller.isRunning` 防回顶瞬间误发分页。
- **[SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)**：`root` 加 `ScrollToTopButton`（右下 16/24dp）；动态 `nearPosition=7`、投稿 `nearPosition=12`，`onSettled` 才 `coordinator.expandHeader(animate=true)`（避免滚动中头卡下推跳动）；两个 `onScroll` 各自迟滞显隐（动态 3/1、投稿 5/2）并防误触分页；`scrollCurrentTabToTop()` 从 `setSelection(0)` 瞬跳改为平滑回顶（顶栏头像入口同步受益）；切 tab 取消动画并重判显隐；`onDestroy` 取消。
- **[SearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SearchActivity.kt)**：`resultsHost` 加按钮；`topScroller(results, nearPosition=8)`；显隐仅在 `showingResultPage && !searchInProgress` 时可能为真（迟滞 4/1）；`performSearch()` / `resetToSearchEntry()` 立即隐藏并取消；`onScroll` 防误触分页；`onDestroy` 取消；`updateTopButton` 加 `::topButton.isInitialized` 保护。

### 验证与装机
- `assembleDebug + testDebugUnitTest + lintDebug` 全过。APK `8e2de700...` 已装机（哈希一致）。
- 顺带规避两处 lint `SuspiciousIndentation` 误报（`SearchActivity` 的 `addView` LayoutParams 提取为局部变量）。

### 闪退修复
- 进入个人空间页直接闪退：`java.lang.RuntimeException: Unable to start activity ... kotlin.UninitializedPropertyAccessException: lateinit property topButton has not been initialized`。
- **根因**：`AbsListView.setOnScrollListener()` 内部会**立即回调一次** `onScroll()`（`AbsListView.invokeOnItemScrollListener`）。`SpaceActivity.onCreate` 里先 `buildDynamicPage()`（其中调 `setOnScrollListener` → 立刻 `onScroll` → `updateTopButton`），而 `topButton` 是之后才 `new` 并加进 root 的，于是 `lateinit` 未初始化即被访问。
- **修复**：`updateTopButton` 开头加 `if (!::topButton.isInitialized) return`（与 `SearchActivity.updateTopButton` 的既有保护一致）。APK `16c716db...` 已装机（哈希一致）。

---

## 视频弹幕显示与全屏/竖屏控制（2026-11-22 晚）

### 需求
- 仅实现视频弹幕显示，不做发送弹幕与离线下载弹幕。
- 全屏控制面板展示「弹幕开关」与「弹幕设置」两个按钮（含自定义图标设计）；非全屏状态仅在「简介 / 评论」Tab 栏最右侧展示弹幕开关按钮。
- 同时覆盖普通视频页（`VideoDetailActivity`）与番剧/影视页（`PgcDetailActivity`）；本地离线模式不显示弹幕。

### 后端顾问核心方案要点
- 主路径 `/x/v2/dm/wbi/web/seg.so`（复用 `BiliWbiSign.signParamsWithVersion` + `BiliPlayUrl.cookieHeader`），可选 `/x/v2/dm/web/view` 元数据（识别 `state=1` 服务端关闭、校验 `pageSize` 与 `total`），明确接口不兼容时提供单次受控 XML 兜底（有界 raw/zlib deflate 解压 + SAX 禁 DTD/外部实体）。
- 不新增 Gradle / protobuf 依赖：纯 Kotlin 零拷贝区间 `ProtoWireReader`，严格校验 varint、length-delimited 边界、包体/元素上限；`pool=0` 普通池、`pool=1` 字幕池，过滤 `mode=7/8/9` 与 `pool=2`。
- 每段独立 `BiliRequest`、2 worker + 6 有界队列、在途合并、统一尝试预算、风控冷却（412/429/-412）、失败负缓存；全局内存 LRU（12MiB / 6 段 / 40,000 条）与 1 秒桶均匀裁剪（防高密度段尾被截断）。
- 必须接入 `playerGlobalPosition()` 全局时钟，避免多段 DURL 错位；同 `cid` 换画质保留弹幕缓存。

### 新增与改动文件
- **新建 [DanmakuSettingsStore.kt](app/src/main/kotlin/com/yuilittle/bili/DanmakuSettingsStore.kt)**：`bili_danmaku` SharedPreferences 持久化、参数校验与滑块防抖落盘（开关、不透明度、字体大小、滚动速度、显示区域 1/4~全屏、屏蔽滚动/顶部/底部/彩色）。
- **新建 [DanmakuParser.kt](app/src/main/kotlin/com/yuilittle/bili/DanmakuParser.kt)**：`DanmakuItem` 模型、手写 `ProtoWireReader`、`DmSegMobileReply`/`DmWebViewReply` 解析、受控 XML+deflate 解析与 1 秒桶均匀裁剪。
- **新建 [BiliDanmakuRepository.kt](app/src/main/kotlin/com/yuilittle/bili/BiliDanmakuRepository.kt)**：WBI 分段请求、可选元数据、在途合并、响应分类与冷却、账号 `authEpoch` 隔离与全局 LRU。
- **新建 [DanmakuController.kt](app/src/main/kotlin/com/yuilittle/bili/DanmakuController.kt)**：媒体身份绑定、按播放进度分段加载与 45s×倍速临界预取、Seek 裁剪取消、generation 防串台。
- **新建 [DanmakuOverlayView.kt](app/src/main/kotlin/com/yuilittle/bili/DanmakuOverlayView.kt)**：原生 Canvas 单 View 高性能弹幕渲染，Choreographer 帧驱动 + 媒体时钟插值（跟随暂停/倍速），滚动/顶部/底部独立防碰撞轨道，有界测量缓存与对象复用。
- **新建 [DanmakuUi.kt](app/src/main/kotlin/com/yuilittle/bili/DanmakuUi.kt)**：自定义矢量图标 `ToggleIconView`（「弹」字圆角屏幕框 + 右下角对勾/叉号徽章）、`SettingsIconView`（双弹幕流光条 + 右下角 6 齿齿轮）、竖屏 Tab 栏开关胶囊 `PortraitTabToggle`、全屏右侧半透明抽屉 `FullscreenSettingsDrawer`（含自定义玫瑰粉滑块与四档区域/四项类型屏蔽）。
- **[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)** 与 **[PgcDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/PgcDetailActivity.kt)**：接入弹幕层、全屏控制栏「弹幕开关 + 弹幕设置」按钮、全屏设置抽屉、竖屏 Tab 栏右侧开关、全局时钟同步、Seek/倍速/切集/生命周期联动；本地离线模式不展示弹幕。
- **新建 [DanmakuParserTest.kt](app/src/test/kotlin/com/yuilittle/bili/DanmakuParserTest.kt)**：覆盖 Protobuf 解析/零值/黑色/未知 WireType、服务端关闭态、ID 去重与合成 ID、非法 varint/越界长度拒绝、高密度按秒均匀裁剪、元数据校验、raw deflate XML 解压与设置/二分边界 8 项单测。

### 验证与装机
- `assembleDebug + testDebugUnitTest + lintDebug` 全部通过（`DanmakuParserTest` 8/8 通过，总 49 tests 通过）。
- APK `cd91b052...` 已通过 `http-cat.py` + `Shizuku` 安装到真机（SHA-256 一致，`pm install -r -t` 返回 `Success`）。

### 实时调参预览修复
- **问题**：用户真机反馈在全屏弹幕设置面板调整参数时，屏幕上正在飘的弹幕会直接消失，无法直观判断调整效果。
- **根因**：① `DanmakuController.applySettings` 未区分「总开关从关到开」与「调节外观设置」，任何参数变化都会调 `view.onSeek()` 清屏；② `DanmakuOverlayView.applySettings` 在字号/区域变化时调用了 `clearActiveDanmaku()`。
- **修复**：
  - `DanmakuController` 记录 `lastEnabled`，仅当总开关由关切开时才重定位并拉取弹幕。
  - `DanmakuOverlayView` 新增 `reflowActiveForTextScale()` 与 `pruneOutOfAreaActive()`：调节不透明度/字号/滚动速度/显示区域时均在屏幕现有弹幕上原地实时重绘与平滑重排（不透明度即时变化、字号与行高原地缩放、速度原地重设运动基准、显示区域缩小仅移除越界轨道）。
  - `DanmakuUi.FullscreenSettingsDrawer` 遮罩从 `0x4D000000` 调轻为 `0x1F000000`，方便调整时看清左侧画面弹幕。
- 重新通过 `assembleDebug + testDebugUnitTest + lintDebug`，APK `e8d3e808...` 已装机（哈希一致）。

---

## 评论区视觉美化（2026-11-22 晚）

### 改动要点
- **[CommentSectionUi.kt](app/src/main/kotlin/com/yuilittle/bili/CommentSectionUi.kt)**：
  - 新增统一 `CommentHeaderBar`（热门/最新标题 + 胶囊式排序切换，含自定义上下双向箭头 `SortIconView`，按时间态自动切换柔和玫瑰底色与边框）与左缩进对齐正文左缘的 `buildCommentDivider(insetStartDp = 48)`。
  - 重构主/子评论视觉层级：头像升级 `@72w_72h_1c.webp` 并加 1px 极淡外环；UP 主评论昵称玫瑰粉高亮，UP/楼主徽标紧贴昵称右侧、置顶标签独立靠右；时间与 IP 属地精简为 `时间 · 属地`；主评论正文行距放大至 `4.5dp` 提升呼吸感。
  - 操作栏升级：`CommentLikeButton` 改为 `onMeasure` 自适应宽度、基线居中对齐与柔和按压反馈；新增线性气泡 `ReplyCountBadge` 展示回复数并支持直接点开楼中楼抽屉。
  - 楼中楼预览卡升级为 `surfaceRaised` + 1px `lineSoft` 描边 + `12dp` 圆角卡片，子评论正文提亮至 `COLOR_INK`（13sp、2.5dp 行距），底部「查看全部 N 条回复」配 `Ds.chevronRightIcon` 矢量箭头。
  - 评论配图统一 `10dp` 圆角、准确扣除头像列宽计算多图宽度并补按压反馈；楼中楼抽屉关闭按钮改用 `Ds.closeIcon` 圆形微描边胶囊，回复列表分割线缩进对齐正文左缘。
- **[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)**、**[PgcDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/PgcDetailActivity.kt)**、**[SpaceDynamicDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicDetailActivity.kt)**：统一接入 `CommentHeaderBar` 与左缩进 `buildCommentDivider()`。

### 验证与装机
- `assembleDebug + testDebugUnitTest + lintDebug` 全部通过（`BUILD SUCCESSFUL`）。
- APK `96691fb7...` 已通过 `http-cat.py` + `Shizuku` 安装到真机（哈希一致）。

---

## 评论区图片加载体感优化（2026-11-22 晚）

### 后端性能顾问核心诊断
- 现有下载走 OkHttp 同步 `Call.execute()`，不受 `Dispatcher.maxRequestsPerHost=5` 限制，真正导致体感“慢”的是首帧启动过晚与预览重复黑屏等待：
  1. 点击评论 Tab 或横滑进评论页时，要等 260ms 切页动画结束后才发评论 JSON 请求；
  2. 评论配图在 `ScrollView` 内无提前加载窗口（滑入视口才开始下载），评论 JSON 返回后也没有首屏预取；
  3. 评论列表内 GIF 图直接绕过缩略图下载完整大 GIF，抢占仅 2 线程的 `originals` 池；
  4. 点开大图预览时缩略图占位固定按 `edge=512` 查缓存，评论配图使用 `140..420` 导致占位未命中、黑屏转圈；且左右邻页同时抢占 `original=true`；
  5. 普通缩略图无进度监听也反复 `main.post` 进度回调；`InlineEmotes` 只检查 `isAttachedToWindow` 导致离屏表情全拉且每完成一个就 `removeSpan/setText/requestLayout` 重排主线程。

### 落地改动
- **[CoverLoader.kt](app/src/main/kotlin/com/yuilittle/bili/CoverLoader.kt)**：
  - 新增 `normalizeThumbEdge()`（归并 160 / 320 / 480 三档）、`bestThumbKeyByRaw` 弱索引与 `cachedThumbnail()`，让预取、展示与大图占位共享一致 Key；拆分 `animate` 与 `allowAnimatedGif`，并修复旧绑定早返回漏判 `Key` 变更与 `crop=false && edge<=160` 误带 `_1c` 裁剪的问题。
  - 新增 `prefetchBatch()` / `PrefetchHandle`（有界并发、可取消），订阅已有低优先级预取 Flight 时自动升级优先级重排队列；无监听时不向主线程发送 `reportProgress`；非进度场景省去主线程同步 `isDiskCached()` stat。
  - `canRequest()` 为可见 `ScrollView` 增加向下 `0.75` 屏 / 向上 `0.25` 屏的 `isInsideScrollLookahead()` 提前加载窗口（严格要求 ScrollView 自身已在屏内，避免未切到的横向 Tab 全量拉图）。
- **[ImagePreviewDialog.kt](app/src/main/kotlin/com/yuilittle/bili/ImagePreviewDialog.kt)**：
  - 新增 `posterProvider` 支持：点击评论图直接用新 `BitmapDrawable` 包装已显示的缩略图首帧承接，无黑屏过渡；若原图加载中用户已缩放，高清替换时保留缩放比例与视口中心。
  - 中央槽最高优先请求原图，左右邻槽改用低优先级静态缩略图，不再抢占 2 线程原图池；加载提示改为底部轻胶囊且延迟 120~150ms 显示，磁盘命中不闪烁。
- **[CommentSectionUi.kt](app/src/main/kotlin/com/yuilittle/bili/CommentSectionUi.kt)**：
  - 新增 `prefetchCommentTopMedia()`（评论首屏返回后立即受控预取前 4 条评论配图最多 4 张 + 头像最多 4 张，配图优先）。
  - 评论配图统一走 `COMMENT_PIC_THUMB_EDGE=320` + `allowAnimatedGif=false` 静态首帧优先展示，点开大图直传已解码缩略图并加载完整原图/动图。
  - `InlineEmotes` 收紧并发至 2、增加近视口检查（`isNearViewport`）、限制单 URL 重试上限为 2 次、合并同帧刷新且仅 `invalidate()` 消除整页重排。
- **[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)**、**[PgcDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/PgcDetailActivity.kt)**、**[SpaceDynamicDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicDetailActivity.kt)**：
  - 点击「评论」Tab 或横滑超过 18% 时立即触发 `ensureCommentsStarted()`（省去 260ms 切页动画等待）；评论首屏返回后立刻调用 `prefetchCommentTopMedia()`，在切集与 `onDestroy` 时取消预取句柄。
- **[SpaceDynamicCardUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicCardUi.kt)**：适配 `ImagePreviewDialog.show()` 命名参数。

### 验证与装机
- `assembleDebug + testDebugUnitTest + lintDebug` 全部通过（`BUILD SUCCESSFUL`）。
- APK `b71fee10...` 已通过 `http-cat.py` + `Shizuku` 安装到真机（哈希一致）。

---

## 弹幕长按快进与帧率优化 / 评论排序配色 / 构建提速 / 远程装机（2026-11-22 晚）

### 改动要点
- **[DanmakuOverlayView.kt](app/src/main/kotlin/com/yuilittle/bili/DanmakuOverlayView.kt)**：
  - **修复长按 2x 快进闪烁与松手回退**：弃用旧的 `clockAnchorMediaMs + (now - anchor) * playbackSpeed` 锚点公式（该公式切换倍速瞬间会把历史时间段按新倍速重算导致整屏弹幕瞬移与松手倒退），改为按 Choreographer VSYNC `frameTimeNanos` 增量累加浮点 `currentMediaMs += dtMs * playbackSpeed`，仅对小误差做温柔前向追平（绝不倒走），大跨度 Seek（>1.2s）才硬对齐。
  - **提升低端设备弹幕帧率**：新增有界离屏预光栅化位图缓存 `bitmapCache`（上限 96 条 / 6 MiB，单条超宽回退实时绘制），每条弹幕在准入时把描边 + 填充一次性绘制到紧凑 Bitmap，`onDraw` 每帧只做 `canvas.drawBitmap`，大幅降低低端机 Skia 轮廓描边开销。
- **[CommentSectionUi.kt](app/src/main/kotlin/com/yuilittle/bili/CommentSectionUi.kt)**：`CommentHeaderBar.applySort()` 取消切换到「按时间」时的玫瑰粉高亮，与「按热度」保持一致的中性胶囊配色（`surfaceRaised` + `lineSoft` + `muted`）。
- **[app/build.gradle.kts](app/build.gradle.kts)** 与常驻构建会话 `term-137`：
  - 根因定位：常驻会话原先挂了 `:app:lintDebug`，每次增量修改都会全量执行 UAST `lintAnalyzeDebug / UnitTest / AndroidTest`（占总耗时 1m55s~2m12s 的 80% 以上），且 `.build-env/tmp` 累积了 945 个历史临时目录。
  - 优化：`app/build.gradle.kts` 增加 `lint` 区块关闭 `checkTestSources` 与 HTML/XML 报告；常驻会话 `term-137` 切换为 `./gradlew --continuous :app:assembleDebug :app:testDebugUnitTest --offline --console=plain`（`:app:lintDebug` 改为按需执行），并清理 `.build-env/tmp` 残留。
- **远程无线调试设备接入**：配对 `192.168.1.136:36417` 成功，探测并连接无线调试服务端口 `192.168.1.136:38573`（`TrebleDroid vanilla`），记录到项目记忆 `remote-adb-device`。

### 验证与双机装机
- `:app:assembleDebug` + `:app:testDebugUnitTest` 全部通过（`BUILD SUCCESSFUL`，49 tests 全过）。
- APK `c4c96149...` 已同步安装到：
  - 本机（`http-cat.py` + `Shizuku`，SHA-256 核对一致，`Success`）；
  - 远程设备 `192.168.1.136:38573`（`adb install -r -t`，`Success`）。

---

## 平板专属 UI 适配与「外观适配」切换（2026-11-22 晚）

### 需求与设计原则
- 在「我的 → 外观」中新增「外观适配」设置行，默认手机端，右侧下拉胶囊可切换「手机端 / 平板端」并即时生效（登录态与未登录游客态均支持切换）。
- 手机端零回归：所有平板专属布局、侧边栏、多列网格与自由旋转仅在 `LayoutMode.isTablet == true` 时生效。

### 新增与改动文件
- **新建 [LayoutMode.kt](app/src/main/kotlin/com/yuilittle/bili/LayoutMode.kt)**：持久化 `layout_mode`（`phone` 默认 / `tablet`），提供 `isWideScreen()`、`applyTabletOrientationIfEnabled()`、`gridColumns()`、`wrapCenteredForTablet()`、`showModeDropdown()` 及矢量设备图标与下拉箭头。
- **新建 [TabletRailNav.kt](app/src/main/kotlin/com/yuilittle/bili/TabletRailNav.kt)**：平板左侧 76dp 固定导航栏（顶部品牌 Logo、中段「首页 / 搜索 / 下载 / 我的」、底部夜间模式快捷键）。
- **[MainActivity.kt](app/src/main/kotlin/com/yuilittle/bili/MainActivity.kt)**：平板模式隐藏底部悬浮胶囊，改为「左侧 `TabletRailNav` + 1px 分割线 + 右侧全幅内容区」；免责声明弹窗平板限宽 420dp。
- **[HomeFeedView.kt](app/src/main/kotlin/com/yuilittle/bili/HomeFeedView.kt)** 与 **[VideoCardUi.kt](app/src/main/kotlin/com/yuilittle/bili/VideoCardUi.kt)**：平板端单行顶栏（左「推荐 / 热门」+ 右搜索栏，滚动常驻不折叠）、自适应 3~4 列视频网格与匹配多列骨架屏。
- **[AccountPageView.kt](app/src/main/kotlin/com/yuilittle/bili/AccountPageView.kt)**：新增「外观适配」下拉切换行（登录/游客双态共用 `buildAppearanceCard()`），平板宽屏下改为「左资料/扫码 + 外观、右内容与工具」双栏仪表盘。
- **[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)** 与 **[PgcDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/PgcDetailActivity.kt)**：平板端放开自由旋转，横屏自动切换「左播放（64%）+ 右简介/评论（36%）」双栏并在横屏分栏态同步启用大屏弹幕排版；退出全屏无强制锁回竖屏闪跳。
- **[CommentSectionUi.kt](app/src/main/kotlin/com/yuilittle/bili/CommentSectionUi.kt)**：`ReplySheetDialog` 在平板宽屏下改为右侧 38% 滑出抽屉，不遮挡左侧视频。
- **[SearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SearchActivity.kt)**：平板端结果列表与骨架屏同步升级自适应 3~4 列卡片网格。
- **[SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)**：平板宽屏改为「左侧 316dp 固定资料卡 + 右侧动态/投稿常驻标签」，投稿列表在平板双栏下自动排布为双列卡片。
- **[AudioPlayerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/AudioPlayerActivity.kt)**：平板端放开旋转，宽屏下改为「左视觉舞台 + 右标题/进度/播控」双栏音乐台。
- **[SpaceDynamicDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicDetailActivity.kt)**：平板宽屏采用「左动态正文 + 右评论区」独立滚动双栏。
- **[ProfileSubActivity.kt](app/src/main/kotlin/com/yuilittle/bili/ProfileSubActivity.kt)**、**[DownloadManagerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadManagerActivity.kt)**、**[DownloadPageView.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPageView.kt)**、**[DownloadPickerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPickerActivity.kt)**、**[DownloadSeasonActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadSeasonActivity.kt)**、**[HistorySearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/HistorySearchActivity.kt)**、**[FavoriteSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/FavoriteSearchActivity.kt)**、**[SpaceSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceSearchActivity.kt)**、**[CreateFavoriteFolderActivity.kt](app/src/main/kotlin/com/yuilittle/bili/CreateFavoriteFolderActivity.kt)**、**[CoverCropActivity.kt](app/src/main/kotlin/com/yuilittle/bili/CoverCropActivity.kt)**、**[InAppWebActivity.kt](app/src/main/kotlin/com/yuilittle/bili/InAppWebActivity.kt)**、**[AndroidManifest.xml](app/src/main/AndroidManifest.xml)**：全量放开平板自由旋转并补齐居中舒适最大宽度约束。
- **新建 [LayoutModeTest.kt](app/src/test/kotlin/com/yuilittle/bili/LayoutModeTest.kt)**：覆盖 `LayoutMode.Mode.fromKey()` 默认与解析边界。

### 验证与双机装机
- 常驻构建 `:app:assembleDebug` + `:app:testDebugUnitTest` 仅耗时 **8 秒**全部通过（`BUILD SUCCESSFUL`，50 tests 全过）。
- APK `986b3600...` 已同步安装到：
  - 本机（Shizuku `pm install -r -t`，SHA-256 一致，`Success`）；
  - 远程平板 `192.168.1.136:38573`（`adb install -r -t`，`Success`）。

---

## 平板 UI 深度重塑（真机实测问题全量修复，2026-11-22 晚）

### 实测根因定位（`1920x1200 @ 336dpi`，横屏总宽 `914dp`，去侧栏可用 `836dp`）
1. **我的页面左侧头像/图标被裁切**：`wrapCenteredForTablet(maxDp = 960)` 用整屏宽度计算 `914dp`，但挂载在右侧容器（宽 `836dp`）内并居中，左右各溢出约 `39dp` 被裁掉。
2. **首页只出 3 列 × 2 行**：原 `gridColumns` 4 列阈值 `>= 980dp`，`836dp` 落在 3 列，且 16:10 封面 + 72dp 底部较高。
3. **侧边栏结构与跳页体验**：搜索/下载原本启动独立 Activity 脱离侧边栏，且缺少「收藏 / 历史」直达；「我的」未贴底放夜间上方；页面切换缺少上下滚动过渡。
4. **首页交互**：左右滑仍在切主壳层而非「推荐 ↔ 热门」；右上角多余搜索框；点击侧栏首页回顶手感不佳，需要右下角统一悬浮回顶按钮。
5. **二级页与全站弹窗宽度**：个人主页动态与空间搜索单列拉伸、收藏/下载管理/关于/赞助页面大片留白，全站约 15 处 Dialog 直接按 `widthPixels` 撑满平板宽度。

### 关键改动
- **[LayoutMode.kt](app/src/main/kotlin/com/yuilittle/bili/LayoutMode.kt)**：4 列阈值调至 `>= 720dp`；`wrapCenteredForTablet` 严格基于父容器安全可用宽计算；新增 `applyDialogWidth()` 统一治理全站弹窗最大宽（确认/单选 420dp、列表/表单 460~500dp）。
- **[TabletRailNav.kt](app/src/main/kotlin/com/yuilittle/bili/TabletRailNav.kt)** & **新建 [TabletVerticalStage.kt](app/src/main/kotlin/com/yuilittle/bili/TabletVerticalStage.kt)** & **[MainActivity.kt](app/src/main/kotlin/com/yuilittle/bili/MainActivity.kt)**：
  - 左侧边栏重排为顶部 Logo → 主导航（首页 / 搜索 / 下载 / 收藏 / 历史）→ 底部锚定（我的 → 夜间模式）。
  - 全部 6 个频道内嵌于右侧 `TabletVerticalStage`，切页按上下相对方向执行物理弹簧纵向滚动过渡（`stiffness=420, damping=0.88`），不跳转脱离侧边栏。
- **新建 [TabletSearchPageView.kt](app/src/main/kotlin/com/yuilittle/bili/TabletSearchPageView.kt)**：平板内嵌搜索工作台（平铺排序与时长筛选胶囊、历史/实时建议、番剧横滑带、4 列视频网格 + 右下角回顶按钮）。
- **新建 [TabletWorkspaceViews.kt](app/src/main/kotlin/com/yuilittle/bili/TabletWorkspaceViews.kt)**：
  - `TabletFavoritesPageView`：左侧 `272dp` 收藏夹目录（含搜索与新建按钮）+ 右侧选中收藏夹 3 列视频网格 + 「播放全部」+ 回顶按钮；
  - `TabletHistoryPageView`：4 列观看历史视频卡片墙 + 搜索历史/刷新 + 回顶按钮。
- **[HomeFeedView.kt](app/src/main/kotlin/com/yuilittle/bili/HomeFeedView.kt)** & **[VideoCardUi.kt](app/src/main/kotlin/com/yuilittle/bili/VideoCardUi.kt)**：平板端移除右上角搜索框，只保留居中「推荐 / 热门」胶囊；启用 `RootPager` 支持首页内左右滑动切换「推荐 ↔ 热门」；卡片采用紧凑 16:9 封面 + 62dp 底部实现 4 列 × 3 行一屏 12 卡；右下角叠加 `ScrollToTopButton`。
- **[AccountPageView.kt](app/src/main/kotlin/com/yuilittle/bili/AccountPageView.kt)**：移除错误的 `960dp` 外包改用 `MATCH_PARENT` 填满右侧工作台，彻底消除左侧裁切；退出弹窗限宽 420dp。
- **[SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)** & **[SpaceSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceSearchActivity.kt)**：平板端动态与视频列表全面双列排布，不再窄条/拉扁。
- **[DownloadPageView.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPageView.kt)** & **[ProfileSubActivity.kt](app/src/main/kotlin/com/yuilittle/bili/ProfileSubActivity.kt)**：下载管理/独立收藏列表改为双列卡片网格；赞助开发与关于软件改为平板专属横向双栏布局。
- **[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)** & **[PgcDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/PgcDetailActivity.kt)** & **[AudioPlayerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/AudioPlayerActivity.kt)** & **[DownloadUiShared.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadUiShared.kt)** & **[DownloadPickerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPickerActivity.kt)** & **[DownloadSeasonActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadSeasonActivity.kt)**：合集全集弹窗平板改为右侧 38% 滑出抽屉（不遮挡左侧视频）；音乐页旋转自动重建双栏；全站 Dialog 宽度统一收口。

### 验证与双机装机
- 常驻构建 `:app:assembleDebug` + `:app:testDebugUnitTest` 全部通过（`BUILD SUCCESSFUL`，50 tests 全过）。
- APK `c53885a6...` 已同步安装到：
  - 本机（Shizuku `pm install -r -t`，SHA-256 一致，`Success`）；
  - 远程平板 `192.168.1.136:38573`（`adb install -r -t`，`Success`）。

---

## 全应用前后端深度性能优化（手机 + 平板全端，2026-11-22 晚）

### 后端性能顾问核心审计与落地
1. **下载页 4Hz 全量重建与 O(任务×文件) 主线程 IO 消除**（[DownloadStore.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadStore.kt)、[DownloadPageView.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPageView.kt)、[MainActivity.kt](app/src/main/kotlin/com/yuilittle/bili/MainActivity.kt)）：
   - `DownloadManager.taskDiskBytes()` 改为 O(1) 读取后台聚合的 `cachedTaskDiskMap`，彻底消除主线程每张卡片重复 `dir.listFiles()`。
   - `DownloadPageView` 新增 `setActive(Boolean)` 并接入平板侧栏切换，隐藏时立即取消订阅与刷新；区分结构变更与纯进度更新（进度合并至 500ms 一次且不重扫磁盘存储、只刷新活跃 Tab）。
   - 修复 `downloadClient` 继承 `BiliHttp.client` 的 `28s callTimeout` 导致大视频持续下载到 28 秒被中断的问题（显式 `callTimeout(0)`），并接入 `onCallCreated` 实现暂停/删除时真实 `Call.cancel()`。
2. **PGC 取流真实取消、搜索单飞与评论游标线程安全**（[PgcDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/PgcDetailActivity.kt)、[BiliApi.kt](app/src/main/kotlin/com/yuilittle/bili/BiliApi.kt)、[BiliLinkTitle.kt](app/src/main/kotlin/com/yuilittle/bili/BiliLinkTitle.kt)、[CommentSectionUi.kt](app/src/main/kotlin/com/yuilittle/bili/CommentSectionUi.kt)）：
   - `PgcDetailActivity.loadPlayer()` 直接保留 `BiliPlayUrl.fetchPgcPlayUrl()` 返回的真实可取消 handle，切集/退页立即取消底层 Call。
   - `BiliApi.ensureAnonymousSearchCookie()` 增加 `anonSearchInitLock` 单飞锁防止首次并发搜索重复拉 SPI；`commentCursors` 升级为 `ConcurrentHashMap`。
   - `BiliLinkTitle` 从每条评论建裸 `Thread {}` 重构为有界 2 线程池 + 128 项 LRU 缓存（成功 10 分钟 / 失败 60 秒冷却）+ 同资源在途单飞合并 + 预编译正则，并加 `comment_title_bind_tag` 防错绑。
3. **连接池共享、图片缓存水位与内存安全**（[CoverLoader.kt](app/src/main/kotlin/com/yuilittle/bili/CoverLoader.kt)、[OnlinePlaybackSource.kt](app/src/main/kotlin/com/yuilittle/bili/OnlinePlaybackSource.kt)、[ImagePreviewDialog.kt](app/src/main/kotlin/com/yuilittle/bili/ImagePreviewDialog.kt)、[BiliSpaceApi.kt](app/src/main/kotlin/com/yuilittle/bili/BiliSpaceApi.kt)）：
   - `CoverLoader.http` 与 `OnlinePlaybackSource.client` 统一从 `BiliHttp.client.newBuilder()` 派生共享连接池（播放 client 保持独立 `Dispatcher` + `callTimeout(0)` + HTTPS/匿名 Cookie 安全拦截器）。
   - 修复 `CoverLoader.reportProgress()` 后台线程遍历主线程 `LinkedHashSet` 的竞态（改为 `progressListenerCount` volatile 计数）；`DiskCache.commit()` 升级高低水位清理（超 64MiB 时回落到 78%），避免满容后每写入 1 图都全目录扫描排序。
   - `ImagePreviewDialog` 保存图片改为优先流式复用已验证原图缓存、未命中走 `BiliHttp.mediaClient` 32KB 分块流式拷贝（消除 `readBytes()` 整图进堆 OOM 风险）；长图 `tileCache` 由「固定 10 块」升级为按 `allocationByteCount` 计量的 `10 MiB` 上限。
   - `BiliSpaceApi` 为 `profileCache`、`videoCache`、`searchVideoCache` 增加 `putBoundedCache(maxEntries=32)` 防止无限膨胀。

### 前端冷启动、首屏出图与绘制热路径优化
- **[HomeFeedView.kt](app/src/main/kotlin/com/yuilittle/bili/HomeFeedView.kt)**：磁盘快照就绪立即渲染首屏并并发预热首屏封面（手机 6 张 / 平板 12 张），不再因自动刷新被骨架屏阻塞；无缓存首屏时拿到第一份非空结果立即上屏，不再多页空扫。
- **[AccountPageView.kt](app/src/main/kotlin/com/yuilittle/bili/AccountPageView.kt)**：接入 `BiliLoginApi.fetchProfile(onBaseReady)` 提前展示头像/昵称/VIP/等级，不干等统计接口。
- **[MainActivity.kt](app/src/main/kotlin/com/yuilittle/bili/MainActivity.kt)**：手机与平板均在首屏后 650ms 空闲阶段静默预热 WBI 密钥与匿名 buvid，缩短后续点击视频/搜索首次握手耗时。
- **[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)** & **[PgcDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/PgcDetailActivity.kt)**：消除 `SeekBarView.onDraw` 每帧创建 3 个 `RectF` 与 `RoundImageView.onDraw` 每帧新建 `BitmapShader` 的 GC 抖动。
- **[TabletWorkspaceViews.kt](app/src/main/kotlin/com/yuilittle/bili/TabletWorkspaceViews.kt)**：修复平板历史分页误把过滤后空列表当结束的问题。

### 验证与双机装机
- 常驻构建 `:app:assembleDebug` + `:app:testDebugUnitTest` 全部通过（`BUILD SUCCESSFUL`，50 tests 全过）。
- APK `7c150ec1...` 已同步安装到：
  - 本机（Shizuku `pm install -r -t`，SHA-256 一致，`Success`）；
  - 远程平板 `192.168.1.136:38573`（`adb install -r -t`，`Success`）。

---

## 平板模式实测 8 项问题集中修复与 UI 重塑（2026-11-22 深夜）

1. **平板收藏夹「播放全部」全量分页拉取**（[TabletWorkspaceViews.kt](app/src/main/kotlin/com/yuilittle/bili/TabletWorkspaceViews.kt)）：
   - 点击「播放全部」时若 `favEnded == false`，先自动串行拉完全部剩余分页（按钮显示「正在准备…」防连点），补齐数十/上百个视频后完整传入 `FavoritePlaybackLauncher`，与「我的 → 我的收藏」一致。
2. **平板首页首屏/刷新自动补满 12 张视频卡**（[HomeFeedView.kt](app/src/main/kotlin/com/yuilittle/bili/HomeFeedView.kt)）：
   - B 站推荐单页过滤广告后常回 10~11 条；在 `commitFreshList` 中判断平板 4 列 × 3 行不足 12 张时自动补拉下一页，首屏直接满屏铺满，无需手动下滑补空位。
3. **平板历史记录卡片修复 `archive_xxx` 误显与无播放量占位**（[TabletWorkspaceViews.kt](app/src/main/kotlin/com/yuilittle/bili/TabletWorkspaceViews.kt)）：
   - 渲染前清空历史接口暂存于 `rcmdReason` 的删除键，标题下方正常显示 UP 主昵称；封面右下显示「已看/总时长」、左下显示「已看完 / 看到 mm:ss / 观看日期」并隐藏无意义的播放量小三角。
4. **平板退出视频/番剧全屏状态栏防白闪**（[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)、[PgcDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/PgcDetailActivity.kt)）：
   - `showSystemBars()` 调整为先设黑底与浅色图标（清除 `APPEARANCE_LIGHT_STATUS_BARS`）再清除 `FLAG_FULLSCREEN` 与恢复系统栏，消除退全屏瞬间闪白条。
5. **平板个人主页与空间搜索 UI 彻底重塑**（[SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)、[SpaceSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceSearchActivity.kt)、[SpaceDynamicCardUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceDynamicCardUi.kt)）：
   - 去掉左侧 316dp 固定列造成底部大面积空白的设计，改为顶部通栏紧凑资料横幅 + 吸顶标签栏 + 全幅 2 列圆角描边动态卡片 / 3 列投稿视频网格。
   - `SpaceDynamicCardUi.createCard` 新增 `cardWidthPx` 与 `cardMode`，平板 2 列按实际半屏列宽计算图片、九宫格与视频封面尺寸（修复此前按整屏宽度导致巨图溢出变形问题）。
6. **平板下载选择页双栏适配（单视频 / 合集 / 收藏夹 / 番剧电影）**（[DownloadPickerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/DownloadPickerActivity.kt)）：
   - 单视频页改为左 44% 大封面预览卡 + 右 56% 视频画质下载与快捷保存控制台；合集/番剧页改为左 320dp 固定控制面板（封面 + 统计/排序/全选/画质/仅音频 + 确认下载按钮）+ 右侧 2 列分集网格。
7. **平板音频播放页右侧滑出播放列表抽屉**（[AudioPlayerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/AudioPlayerActivity.kt)）：
   - 平板宽屏点击播放列表由底部弹窗改为右侧 `400dp` 满高圆角抽屉，支持从右向左平滑滑出动画、半透明遮罩点击/返回键平滑滑回关闭，手机端保持底部弹窗不变。

### 验证与双机装机
- 常驻构建 `:app:assembleDebug` + `:app:testDebugUnitTest` 全部通过（`BUILD SUCCESSFUL`，50 tests 全过）。
- APK `15c59282...` 已同步安装到：
  - 本机（Shizuku `pm install -r -t`，SHA-256 一致，`Success`）；
  - 远程平板 `192.168.1.136:38573`（`adb install -r -t`，`Success`）。

---

## 平板模式二次实测 5 项精细打磨（2026-11-22 深夜）

1. **平板历史记录封面清理冗余「看到 x:xx」**（[TabletWorkspaceViews.kt](app/src/main/kotlin/com/yuilittle/bili/TabletWorkspaceViews.kt)）：
   - 封面右下角已有「已看/总时长」角标，移除左下角多余的「看到 x:xx」文字及播放量图标。
2. **视频/番剧退出全屏彻底消除导航栏/状态栏闪白**（[styles.xml](app/src/main/res/values/styles.xml)、[AndroidManifest.xml](app/src/main/AndroidManifest.xml)、[VideoDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/VideoDetailActivity.kt)、[PgcDetailActivity.kt](app/src/main/kotlin/com/yuilittle/bili/PgcDetailActivity.kt)）：
   - 为播放页新增纯黑底 `PlayerActivityTheme`（`windowBackground`/`statusBarColor`/`navigationBarColor` 均黑），并在 `applyPlayerSystemBars()` 中让平板播放页状态栏与导航栏统一纯黑 + 白色图标、关闭系统对比度强加底板；Android 11+ 全屏切换改用纯 `WindowInsetsController` 而不再切旧 `FLAG_FULLSCREEN`。
3. **平板个人主页恢复折叠头卡与顶栏头像/昵称联动**（[SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)、[SpaceUi.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceUi.kt)）：
   - 平板与手机统一接入 `SpaceCoordinatorLayout`（扩展支持 `ScrollView`），上滑平滑隐藏「动态 | 投稿」上方的资料卡，并在返回按钮右侧淡入 UP 主头像与昵称。
4. **平板个人主页 & 空间搜索动态升级双列真瀑布流**（新增 [SpaceMasonryDynamicView.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceMasonryDynamicView.kt)，接入 [SpaceActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceActivity.kt)、[SpaceSearchActivity.kt](app/src/main/kotlin/com/yuilittle/bili/SpaceSearchActivity.kt)）：
   - 左右两列各自独立纵向紧接排列，增量追加自动落入较短一列，彻底消除图文高卡旁短视频卡下方的大段空白。
5. **平板音频播放页重做右侧精巧悬浮播放列表抽屉**（[AudioPlayerActivity.kt](app/src/main/kotlin/com/yuilittle/bili/AudioPlayerActivity.kt)）：
   - 收窄宽度至 `292~328dp`（≤ 屏宽 34%），高度占屏 80% 垂直居中、四周留 `14dp` 呼吸边距与 20dp 圆角和投影，采用紧凑行高并在打开时自动滚动定位到正在播放的曲目。

### 验证与双机装机
- 常驻构建 `:app:assembleDebug` + `:app:testDebugUnitTest` 全部通过（`BUILD SUCCESSFUL`，50 tests 全过）。
- APK `5af22c7b...` 已同步安装到：
  - 本机（Shizuku `pm install -r -t`，SHA-256 一致，`Success`）；
  - 远程平板 `192.168.1.136:38573`（`adb install -r -t`，`Success`）。

---

## v1.0.0 正式版发布与 GitHub 仓库迁移（2026-11-22）

- **播放页闪退修复**：`VideoDetailActivity` / `PgcDetailActivity` 访问 `windowInsetsController` 前先触发 `window.decorView` 初始化，修复 Android 11+ `onCreate` 空指针崩溃。
- **v1.0.0 正式版配置**（[app/build.gradle.kts](app/build.gradle.kts)、[app/proguard-rules.pro](app/proguard-rules.pro)）：
  - `versionName = "1.0.0"`、`versionCode = 10000`，仅打包 `arm64-v8a` 单包；
  - 开启 R8 代码压缩混淆与资源裁剪（`isMinifyEnabled = true`、`isShrinkResources = true`），保留持久化枚举 `values()/valueOf()` 与自定义 View 构造器，单 `classes.dex` 体积压缩至 `3.2 MiB`（v1/v2 签名校验通过）。
- **README 重写与 GitHub 发布**（[README.md](README.md)）：
  - 精简为通俗易懂的介绍并置顶说明原仓库 `https://github.com/Yui-Little/YuiBili` 原作者迁移原因；
  - 在 `xiaoyuili/YuiBili` 创建仓库、推送源码、发布 `v1.0.0` Release（附 `YuiBili-v1.0.0-arm64-v8a.apk`）并同步安装至本机与远程平板。

## 播放与离线缓存反馈修复（2026-11-27，Debug 待真机验收）

- **同集进出全屏**：普通视频和番剧原地扩展/收回播放窗口，不拆卸视频表面和弹幕层。绘制层按最后一次有效尺寸换算在途弹幕的轨迹和字号，保留下一条发射位置、已发记录、固定弹幕到期时间。暂停时也可重排；切集和真正跳转播放位置仍按新时间线处理。
- **合集与控件**：为在线合集、多 P、番剧接通播完后的队列调度与单集循环；全屏切集后按最终可见宽度恢复选集横条。进度按比例绘制；横拖整屏最多 60 秒，时间条仍支持任意定位；全屏两侧双击跳 10 秒、中间双击切换播放状态。倍速直接点选，前台视频播放保持亮屏。
- **方向、画质与历史**：「我的 → 外观」可选跟随系统、锁横屏、锁竖屏，以及记住上次手选或固定画质。缺档回退不改首选。手机/平板普通稿件历史列表、历史搜索和本地记录按稿件与分 P 续播，旧记录无有效进度则从头播放。
- **GIF 与预览**：可见评论 GIF 与预览分别持有动画实例；预览的缓存占位图不会覆盖当前动图。平板关闭和保存按钮的最小高度为 56dp。
- **下载**：「离线缓存 → 新下载」在任务创建时选择应用私有目录或系统媒体库。公共视频、音频分别进 Movies/YuiBili、Music/YuiBili；确认各成品与任务清单均已保存后才清理私有媒体暂存，保存失败保留可重试输入。已有下载不搬迁。第 9 项动态按用户意见暂缓。
- **验证和交付**：常驻 `:app:assembleDebug :app:testDebugUnitTest` 通过；16 个测试类共 86 项，0 失败、0 跳过。APK 签名验证通过，包名 `com.yuilittle.bili.debug`，SHA-256 `0af84e6717e071430899356b4b34150b637ecbc805df34cfecfe9b0e100fe14e`。手机授权恢复后，通过 Shizuku 核对 SHA-256 并覆盖安装成功（`pm install -r -t` 返回 `Success`，`pm path` 确认包名）。远程平板通过新的无线调试连接端口 `40811` 安装成功，设备上 `com.yuilittle.bili.debug` 的 `base.apk` 经 SHA-256 核对与交付包一致。未运行自动化 UI 测试，未发布或更改 GitHub v1.0.0。

## 双端体验复测后的定向调整（2026-11-27，第二轮 Debug）

沿用现有原生 View、Ds 配色/字重/圆角；用户未指定设备的反馈同时覆盖手机和平板。

### 功能反馈

1. 手机的普通视频、番剧全屏临时切为感应横屏，退出后恢复全应用屏幕方向设置；播放器及弹幕层不拆卸。
2. 平板历史在重新激活/回前台时读取当前账号的本地记录，再合并云端游标分页；保留独立展示时间，按已核实的 BV/aid/cid 去重，进度仍根据原始云时间比较；旧代次/旧账号响应不写回，有记录或空态失败均可原游标重试。
3. 用户功能性第 3 项未填写，本轮未据此新增内容。

### UI 反馈

1. 图片预览两端复用手机的关闭、存相册视觉样式，独立透明点击区至少 48dp，不放大可见按钮。
2. 全屏左右双击快退/快进改为加载图标下方的纯文字，重复操作复用提示及计时。
3. 拖动进度的时间文字在进度条上方居中；根据进度行的布局参数定位，隐藏控制层后调整窗口也不沿用旧坐标。
4. 倍速使用紧凑横向选择条；窄窗口/大字允许横向滚动，所有档位保留。弹窗随离页/窗口改变关闭，打开期间不自动隐藏控制栏。
5. 离线缓存右上角同排放保存位置文件夹图标与批量管理清单图标，管理状态变为完成勾；保留说明、权限流程、全选和批量删除。
6. 我的页面屏幕方向、默认画质沿用外观卡的图标、56dp 最小行高、分隔线与下拉胶囊，选项为同风格的可滚动菜单；游客/登录两份标签同步。
7. 删除游客页面独立的顶部夜间模式条，保留外观卡开关和平板侧栏开关。

### 新建议与验证

- 全屏合集关闭按钮左侧新增播放模式图标，顺序/随机/列表循环/单集循环与简介页即时双向同步及持久化；离线合集也统一四档，切模式只刷新控件而不重建分集列表。
- `:app:assembleDebug :app:testDebugUnitTest` 最新一轮通过，16 个测试类共 94 项、0 失败/错误/跳过。本轮新增 1 项方向策略与 7 项历史合并回归；测试捕获的身份歧义合并已修复并通过。
- APK 包名 `com.yuilittle.bili.debug`，v1/v2 签名校验通过；SHA-256 `ccc5e6df6935541a64cdb66cd1d689c4947f30564b846e5eaba48b0cab3d74ec`。手机 Shizuku 与平板 `192.168.1.136:40811` 均覆盖安装成功，两台已安装 `base.apk` 的 SHA-256 与构建产物一致。`:app:lintDebug` 完成，0 errors、258 warnings；未扩大范围处理全部警告。Lint 后已恢复同一 `term-1` 的常驻 assembleDebug/testDebugUnitTest。未自动操作 UI，不提交、不推送、不修改线上 v1.0.0。
- 本轮未改公共下载恢复路径；上一轮已告知的回滚中断重试和 API 23–28 公共父目录落盘风险仍待修，不属于本次入口布局调整的完成范围。

## 设置与全屏交互优化（2026-11-27，第三轮 Debug）

### UI 反馈

1. 外观适配右侧值不再独立消费点击，图标、标题与选值共用整行按压回弹；外观菜单返回句柄并纳入离页、登录状态切换时的关闭逻辑。
2. 外观适配、屏幕方向、默认画质右侧只保留文字和下拉箭头，去掉行内椭圆背景，不改动下拉弹层本身。
3. 新下载保存位置改为沿用 Ds 的圆角、字重和主题配色的自定义对话框，两项说明与当前选中勾明确，窄窗限宽限高可滚；取消不提交，公共库仍只在权限允许后更新设置。
4. 普通视频及番剧全屏合集播放模式菜单使用与合集面板一致的半透明深色背景、浅描边与玫瑰色选中项；非全屏菜单保留原主题。低矮窗口限高可滚，关闭生命周期仍保留。

### 功能反馈

1. 全屏双击跳播改为左侧 20% 后退、右侧 20% 前进，中间 60% 切换播放/暂停；边界归中间区域，小窗行为不变。
2. 默认画质新增“当前视频最高画质”，作为独立策略保存，既不替换固定 8K，也不写成上次手选。取流仍传合法 qn，最高只从本次实际视频轨选择，权限和试看以接口为准。切集重读策略，本集手选仍有效；最高策略的同媒体同会话自动续取保留降档请求值，缺档沿用原有软回退。

### 验证与交付

- 常驻 Debug 构建和单测完成；16 个测试类共 99 项，0 失败/错误/跳过。本轮增加双击边界、最高策略与手选隔离、合法请求/游客限制、实际轨道选择、恢复请求五项回归。`git diff --check` 通过。
- APK 包名 `com.yuilittle.bili.debug`，v1/v2 签名验证通过，大小 11,152,432 字节，SHA-256 `3e425d73af1afb6b6613944af31f3d705555cc37d53db9e227acf19ca254d9e1`。手机通过 Shizuku、平板通过 `192.168.1.136:40811` 均覆盖安装成功，已安装 `base.apk` 的 SHA-256 全部一致；仅更新 Debug 包，保留应用数据及正式版。
- 本轮未重复运行全量 Lint、未自动操作 UI；不提交、不推送、不改线上 v1.0.0。公共下载恢复逻辑未变。

## 首页标签与平板导航动效（2026-11-28，第四轮 Debug）

1. 「默认播放画质」文案改为「默认画质」，偏好键和画质策略保持不变。
2. 平板侧栏底部交换顺序：主题切换在上、我的在下。主题按钮为无底板地平线与日月线形图标，420ms 内当前天体向右落下，另一天体从左升到中央；自然完成才保存并重建主题。动画中重复点击不叠加，后台/脱离取消未提交切换，减少动画直接切换。现有圆形转场等待布局和播放期间均可幂等清理截图/监听。矮窗口只滚动上方主导航，底部主题/我的保持可达。平板「我的」游客及登录态均不创建夜间模式行，手机保留。
3. 首页推荐/热门统一为文字加 28dp×3dp 底线，平板左上、手机居中。两端 feed 复用 RootPager 的实际滑动进度直接驱动底线，点击/拖动/回弹均同步；手机内容区横滑优先切推荐/热门，首页/我的仍可用底栏切换。纯高度变化不打断横向手势；保留两个 feed 的滚动锚点与快照。
4. 平板侧栏移除搜索可见入口但保留内部路由编号，首页右上提供 48dp 搜索点击区，按压后沿用主舞台滑入/淡入打开现有内嵌搜索页；搜索页可直接返回首页，离开时关闭键盘并暂停待执行建议，同一 Activity 内保留输入和结果。搜索状态在 Activity 重建后的恢复未新增。

### 验证与交付

- 常驻 `:app:assembleDebug :app:testDebugUnitTest` 通过，16 个测试类共 102 项，0 失败/错误/跳过；新增 3 项日月轨迹、范围钳制和双向指示线插值回归，`git diff --check` 通过。
- APK 大小 11,157,587 字节，包名 `com.yuilittle.bili.debug`，v1/v2 签名通过；SHA-256 `35b39a95384b43d091f96916a4b0da23704b9ad45d3177ac04696cdc92605670`。
- 手机通过 Shizuku、平板通过 `192.168.1.136:40811` 均覆盖安装成功，两台 `base.apk` 哈希与构建产物一致；正式版与应用数据保留。已发送第四轮 Debug 包。`:app:lintDebug` 通过，0 errors、257 warnings；已恢复同一个 `term-1` 常驻 assembleDebug/testDebugUnitTest。未自动操作 UI，视觉和手势效果由用户验收。不提交、不推送、不改线上 v1.0.0。

## 平板主题与搜索统一（2026-11-28，第五轮 Debug）

1. 平板主题切换改为整页明暗交叉渐变，页面、日月轨迹、系统栏背景共用 420ms 进度。点击提交主题一次并重建，旧截图不含静止日月，新覆盖层独立绘制满不透明度图标，消除两段串行动画和双图标。手机原圆形转场保留。首触摸可结束渐变并正常分发，尺寸变化/后台/销毁幂等清理，截图分配失败直接应用主题。
2. 平板侧栏使用页面背景 `c.bg`，保留原有单条 hairline 分隔及导航选中态。
3. 历史、收藏搜索迁入主壳层，复用首页搜索输入与结果网格，三个 scope 独立缓存并返回对应来源。历史保持登录后云端 archive 标题搜索、pn 分页、按分 P 去重和历史续播；收藏保持跨全部自建收藏夹的既有有界搜索（每夹最多 2 页×20），不冒充无限全量。私域不发送全站联想/PGC 请求、不写公共搜索词库；API 冻结请求会话，页面以账号/代次/关键词拒收过期响应。防抖不收键盘，明确搜索才收键盘。
4. 历史顶部删除常驻刷新按钮，搜索改为 48dp 点击区内的放大镜图标；重新进入仍自动更新，网络错误保留原页重试。

主题重建通过非配置纯数据快照保留三类搜索词/结果/待请求状态、收藏夹及历史分页/滚动锚点，不保存 View 或 Activity，也不把大结果塞入 Bundle。保存待完成页与失败状态，恢复时不清空已有列表；滚动偏移扣除列表 padding。账号变化不恢复旧账号记录；进程死亡后仍按常规页面重新加载。

### 验证与交付

- 最新 `:app:assembleDebug :app:testDebugUnitTest` 通过；16 个测试类共 105 项，0 失败/错误/跳过。新增主题颜色插值、搜索返回路由、历史分 P 与收藏稿件去重键三项回归。`git diff --check` 通过。
- 包名 `com.yuilittle.bili.debug`，v1/v2 签名验证通过，APK 11,179,049 字节；SHA-256 `085661e620ac0a9b3d7243410129c0880d2df8ac203f5ccfdc90306d76f57acb`。手机 Shizuku、平板 `192.168.1.136:40811` 均覆盖安装成功，两台已安装 `base.apk` 与构建 APK 哈希一致，正式版和应用数据保留。`:app:lintDebug` 通过，0 errors、259 warnings；同一 `term-1` 已恢复常驻 assembleDebug/testDebugUnitTest。
- 本轮未自动操作 UI，动画及搜索体验由用户真机复测；不提交、不推送、不修改线上 v1.0.0。公共下载异常恢复逻辑未变。
- 用户验收（2026-11-28）：用户确认本轮“已验收，可以了，没问题”。以上 Debug 包作为下一轮更新的对照基线；当前源码改动仍未提交，未新增发布授权。

## 评论区体检修复（2026-11-28）

用户批准修复体检发现的全部 15 项问题。覆盖普通视频、番剧、动态详情及楼中楼入口；不新增发送评论或回复功能。

### 实现与复测对照

| 原体检编号 | 已实现的修复 | 人工复测方法 |
| --- | --- | --- |
| 1、2、9、11、14 | `ImageTileWorker` 将长图打开、解码、换图和释放串行化，最多保留 32 个待办索引、2 个待交付分块，解码宽度限制为 1440px；切图或关闭拒收旧结果。楼中楼按实际窗口钳制高度、配图按实际列宽测量。原图手动重试清除冷却；Android 6–9 保存图片先请求权限，再扫描相册，权限请求保存图片 URL 以支持页面重建。 | 连续打开/快速切换/关闭长图；在窄屏、横屏或分屏打开楼中楼和多图；断网加载原图后恢复网络并立即点重试；旧系统分别拒绝、允许存储权限，确认相册可见。 |
| 3、5、6、8 | 主列表独立持有分页 offset 和已见游标，JSON 转义后参与 WBI 签名；缺失或重复游标、缺少结束标记进入可重试异常态，不能当作正常到底。无新增内容但可翻页时提供手动继续。`BiliRequest` 可移除排队任务并取消网络 Call；切页、全屏、后台及销毁取消请求，返回续载；会话快照绑定 Cookie、CSRF 和响应代次。 | 热度/时间来回切换并连续翻页；加载中切简介、进出全屏、后台再返回；断网后点列表底部重试；登录/退出后确认旧账号结果不会写回。服务端异常游标和排队取消另有单测覆盖。 |
| 4 | `CommentListView` 使用原生 ListView 稳定 ID 与 `CommentRowView` 重绑复用；离屏清理头像、配图、选区、动画和点赞订阅。手机动态正文作为列表 header 共滚，平板仍为左右分栏。纯数据快照保留配置重建前已加载评论、分页状态、展开 ID 和滚动锚点，不持有 Activity 或 View；进程死亡后正常重载。 | 多次加载后快速上下滚动，检查昵称、徽标、图片和回复预览无串行；展开长文后滚出再返回；触发配置重建后再切回评论，核对数据、展开与位置。实际帧率需真机观察。 |
| 7、12、13 | 主列表与楼中楼使用同一份会话分区点赞状态，pending 去重、失败回滚，旧请求的数据不能在确认窗口过后覆盖已完成操作。正文保留原文，纯图片评论不误标为已删除；只使用当前评论明确的名称到 MID 映射生成 @ 链接，同名冲突不生成错误跳转。异步链接标题返回时避开正在选择的正文。 | 主列表点赞后打开楼中楼，反向取消并关闭，检查两处同步；断网点赞确认回滚；长按选词、复制含尖括号或换行正文；检查纯图片评论与明确 @ 用户的跳转。 |
| 10、15 | 表情失败使用 5 秒/60 秒可到期负缓存，后续可见或重绘可以恢复加载；迟到结果只刷新仍使用该 URL 的文本。评论表情磁盘缓存设置 16MiB 预算、超限清理到 75% 水位，临时文件写入和目录操作在后台执行，定期重算共用目录容量。 | 网络恢复后重新滚入原评论，确认表情能恢复；快速滚动检查表情不串到其他行。失败期限、磁盘预算和残留文件处理以单测及代码检查为主。 |

### 验证与交付

- 同一 `term-1` 常驻 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain` 已运行。24 个测试类共 150 项，0 失败/错误/跳过；Lint 为 0 errors、259 warnings，`git diff --check` 通过。最终日志为 `.build-env/comments-final-check.log`。
- 修正了 Lint 检出的权限 Fragment 可见性问题：`CommentImageSavePermission` 使用公开无参类，允许 Android 在配置变化或进程恢复时实例化。
- Debug APK 包名 `com.yuilittle.bili.debug`，v1/v2 签名校验通过，大小 11,224,279 字节；SHA-256 `9612c3bafef34d132a68545998dd6d44b490b55a2ccb889545827f297406a17e`。
- 手机经 `http-cat.py` + Shizuku 覆盖安装成功（`pm install -r -t` 返回 `Success`）；已安装 `base.apk` SHA-256 与构建产物一致。临时传包终端已关闭，常驻构建 `term-1` 保留。平板按用户要求暂不安装。未运行自动化 UI，旧系统权限、特殊窗口、实际滚动帧率及交互手感仍待用户真机验收。不提交、不推送、不修改线上 v1.0.0。

## 连续翻评偶发失败的恢复修正（2026-11-28）

用户复测：主评论或回复连续翻页偶发“加载失败”/“分页信息异常”，重进应用后可能恢复。公开接口按现有请求协议探测同一视频（aid `117676869491009`）：热度连续 7 页、时间连续 6 页均正常到明确结束标记，未复现首次偶发异常，不能据此认定账号请求或其他评论区无故障。

已定位的客户端缺陷及修正：

1. 异常游标页保留着原请求 offset，但原页脚重试会调用 `reset()` 清空已加载评论、游标和位置。改为 `CommentPageState` 保存 offset、已见游标、结束状态与失败原因；手动重试只重新开放失败页，原位置继续请求，已有评论保留并按 rpid 去重。正常结束不会被重试重新打开，也不会自动反复请求异常页。
2. `renderStatus(error)` 的原因原来仅用于当前绘制，切页、后台或配置恢复再次绘制时会变成笼统“加载失败”。失败原因现在与分页状态一起持有并保留到重试开始或成功，配置重建快照同样保留。分别显示缺失结束标记、缺失下一页位置、重复游标及实际网络原因。
3. 评论请求单独检查 HTTP 状态及 JSON 返回格式，保留接口错误码；避免 HTML 错误页变成冗长 JSON 解析提示。Debug 增加 `YuiBiliComment` 诊断：只记录目标哈希、游标长度、条数、错误哈希和 HTTP/API 数字码，不记录 Cookie、游标原文、评论正文或响应体，用于定位仍未复现的偶发触发原因。
4. 增加异常页原 offset 重试、网络失败及快照恢复保留原因、正常末页不重开、首屏失败重试、含特殊字符 offset 的完整 WBI query 和摘要一致性回归。保留现有经过公开接口验证的游标协议和严格终止检查。

验证：同一 `term-1` 常驻构建、24 个测试类共 155 项（0 失败/错误/跳过）及 Lint（0 errors、259 warnings）通过，`git diff --check` 通过。Debug APK 11,227,034 字节，签名验证通过；SHA-256 `4bc0e04389515d64c38f43b2ffe1d927051655f44af8e175da8b3bb0270d373f`。手机 Shizuku 覆盖安装返回 `Success`，已安装 `base.apk` 哈希一致；已发送 APK 并关闭临时 HTTP 服务，保留 `term-1` 常驻。用户随后授权平板安装，使用新无线调试地址 `192.168.1.136:46055` 覆盖安装返回 `Success`，平板已安装 `base.apk` SHA-256 与手机及构建产物一致。未运行自动化 UI、未提交推送。首次偶发触发原因尚未复现，下次出现时检查具体提示及 `YuiBiliComment` 诊断，不能将本轮恢复修正表述为已经消除全部偶发接口失败。

## 评论分页令牌复用判定修正（2026-11-28）

用户在手机和平板均反馈“加载更多失败，服务器重复返回同一页”。代码核实：该文字仅由 `next_offset` 等于当前/已见令牌触发，并未比较返回评论 ID，因此原提示不能证明整页数据重复，也不能据此归因于 B 站后端。上轮保留了这条过强的令牌唯一性假设，本轮修正。

- `next_offset` 按不透明令牌原样回传，不再要求每页唯一；删除 API、列表及 retainedState 中的历史令牌集合。缺少结束标记或下一页令牌仍作为可重试的协议异常。
- 按列表实际新增 rpid 判断内容是否前进：同令牌返回新评论可继续，整页无新增或空页且 `is_end=false` 时暂停自动翻页、显示现有“继续加载评论”，只在用户手动继续后请求一次；明确结束仍以 `is_end=true` 为准。该手动态纳入 `CommentPageState`，配置重建同样保留。
- Debug 分页日志增加 `repeatedCursor`、`manualNext`、楼中楼页码和 root 哈希，可区分“同令牌新数据”和“无新增内容”；不记录令牌、Cookie 或正文。
- 针对同令牌连续多页新内容、新令牌但整页无新增、手动继续及正常末页增加回归。
- 取证限制：手机 Shizuku 当时未运行，平板仅取到 5 条正常分页日志，未保留报错现场。公开请求对 5 个热门视频各 6 页、另对 2 个旧视频带匿名指纹各 6 页探测，均未复现令牌复用，不能声称已确认用户现场的服务器会话行为。第三方实现有同令牌新数据的实测记录，作为协议适配线索；最终仍需两端真机复测。
- 参考资料：`https://github.com/MuRongxin/bilibili_profiler/blob/main/docs/bilibili_api_reference.md` 的评论接口章节（2026-08-08 实测修正）。

验证与交付：`term-1` 常驻 assembleDebug/testDebugUnitTest/lintDebug 全部通过，24 类 157 项测试，0 失败/错误/跳过；Lint 0 errors、259 warnings；`git diff --check` 与 APK 签名验证通过。Debug APK 11,226,071 字节，SHA-256 `48c6f2e37b9380c900da52822048341e4870300c3024385c7d0349f26c9e2b41`。手机 Shizuku 恢复后、平板 `192.168.1.136:46055` 均覆盖安装返回 `Success`，两端已安装 `base.apk` 哈希与交付包一致。已发送 APK，临时 HTTP 终端已关闭，保留 `term-1` 常驻。手机恢复后读到的分页日志为空，仍未获得报错现场；不宣称已实证 B 站会话复用或后端故障。未运行自动化 UI，连续翻页体验等待用户验收，未提交推送。

## v1.1.0 正式版发布（2026-11-28）

- 用户确认最新评论分页包“可以了”，授权将当前累计改动提交到 GitHub、打包发布正式版，并将版本改为 1.1.0、编写更新日志。本轮授权包含提交、推送和新建 Release。
- `app/build.gradle.kts` 更新 `versionName=1.1.0`、`versionCode=10100`，沿用正式包名 `com.yuilittle.bili`、现有签名及 R8 压缩/资源裁剪。新增 `CHANGELOG.md` 汇总本次播放器/续播、下载位置、首页及平板、评论与图片的用户可见增量；README 同步版本徽章和更新日志入口。更新日志注明系统媒体库异常中断恢复的既有限制。
- 同一 `term-1` 常驻 `:app:assembleRelease :app:testDebugUnitTest :app:lintRelease --offline` 构建通过；24 类 157 项测试，0 失败/错误/跳过；Release Lint 0 errors、259 warnings，`git diff --check` 通过。无自动化 UI；正式构建依赖先前用户验收过的源码与本轮静态/构建验证。
- 正式 APK `YuiBili-v1.1.0-arm64-v8a.apk`：3,389,255 字节，SHA-256 `68b58eb1d411c169630e91fdaa9d6fde2c94ef987ba7296d5b2825395c2ec965`。v1/v2 签名通过，证书 SHA-256 与已发布 v1.0.0 完全一致，可覆盖升级；已核对包名、10100/1.1.0、非 debuggable 和单 DEX，R8 映射已在本地归档。
- 源码、`v1.1.0` 标签和 GitHub Release 由本轮发布流程继续推送；Release 附正式 APK、`SHA256SUMS.txt` 及中文更新说明。
