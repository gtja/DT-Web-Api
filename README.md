# 道通天穹 → JASmart 网关

工程只保留四条业务主线：

```text
SkyCC 第三方登录 → WebSocket 接收 OSD → 属性转换 → JASmart MQTT 上报
                                      └→ 飞机上下线 → JASmart 子设备拓扑

JASmart live START → media_server 获取 RTMP 推流地址 → capacity 获取道通播放地址
                  → 本服务启动 FFmpeg 拉取道通视频并转推 → 回复处理状态
JASmart live STOP → 停止本服务的 FFmpeg 进程 → 回复处理状态
```

机场使用产品 `00000000001`，作为 JASmart 网关设备；飞机使用产品
`00000000002`，通过 `addSubDevice` 挂在机场下面。

## 代码入口

- `GatewayRuntime`：启动 JASmart、SkyCC WebSocket 和视频目录刷新。
- `AutelTokenProvider`：RSA 加密并登录 SkyCC，缓存 token。
- `AutelWebSocketClient`：接收 WebSocket 消息，断线后自动重连。
- `AutelMessageRouter`：把 OSD、上下线和 `update_topo` 分发给 JASmart。
- `DockPropertyMapper` / `AircraftPropertyMapper`：只转换附件文档与物模型能对齐的字段。
- `JASmartGatewayManager`：属性上报、飞机子设备拓扑、`media_server` 调用。
- `LiveCommandHandler`：处理 MQTT 直播请求，取得推流地址和道通源地址。
- `FfmpegStreamRelay`：启动、复用、停止 FFmpeg 转推进程，记录输出和异常。

OSD 不再经过缓存、TTL 或批量合并：收到一帧、转换一帧、上报一帧。字段白名单和枚举转换见
[属性映射说明](docs/property-mapping.md)。

## live 能力

机场和飞机共用同一个 `live` 实现。前端请求示例：

```json
{
  "action": "START",
  "protocol": "WS_FLV",
  "streamId": "上层媒体流 ID",
  "videoId": "从 videoList 取得的完整道通通道 ID"
}
```

- `action` 必须是 `START` 或 `STOP`。
- `videoId` 必须传 `videoList` 中的完整 ID，不再用 `live` 猜测默认镜头。
- `streamId` 只在 START 使用；为空时向 `media_server` 传 `live`。
- 请求中的 `protocol` 是前端播放协议；本服务向媒体服务器推送 RTMP，播放地址仍由上层媒体服务提供。
- 同时播放不同视频通道时使用不同 `streamId`，避免两个通道争用同一个推流地址。

START 流程：

1. 校验 `videoId` 属于当前机场或飞机。
2. 保留原请求的 `tid/bid`，调用同一设备的 `media_server`：
   `{"protocol":"RTMP","videoId":"<streamId>","type":"ACTIVE"}`。
3. 同一通道已向该地址正常转推时直接回复成功；否则查询 SkyCC `capacity`，查找该完整
   `video_id` 且 `active=true` 的通道，从 `live_streams` 取得播放地址（优先 RTMP，其次 RTSP、HTTP-FLV）。
4. Java 通过 `ProcessBuilder` 启动 FFmpeg，将道通源流转推到步骤 2 的地址，不经过 shell。
5. 检测到 FFmpeg 输出视频帧后只回复 `{"code":"OK","message":"OK"}`，不返回道通播放地址。
   此状态表示转推进程开始输出，不等同于前端已收到画面。

直播链路不再调用 SkyCC `start/switch/stop`，也不会申请设备控制权或改变设备的推流目的地。
道通目标通道必须已经有直播源；未开播时返回明确错误，需要先在天穹开启直播。不会将 capacity
顶层的设备推流 `url` 误当作播放地址，也不会把另一个镜头的视频作为当前通道返回。

STOP 只停止本服务中对应 `videoId` 的 FFmpeg，不停止道通直播，不请求 media_server。没有本地
转推进程时也返回成功。服务正常退出时会清理全部 FFmpeg 进程。启动失败、无输出帧超时等错误
统一走 JASmart `reply.error(...)`；启动后的意外退出打印错误日志，下次 START 会重新取得源地址并启动，
当前不做后台自动重连。

## 配置和运行

仓库提供 `src/main/resources/application-example.yml` 配置模板。首次拉取后复制为同目录的
`application.yml`，填写天穹和 JASmart 账号、设备 SN、上层平台设备实例 ID，再将 `enabled` 改为 `true`。
真实的 `application.yml` 已被 Git 忽略；本机已有的联调配置保留，不需要重新填写。

运行服务器需要安装 FFmpeg，并能够访问道通播放地址和上层 RTMP 地址。
开启转码时需包含 `libx264`。`autel.gateway.live` 配置：

```yaml
ffmpeg-path: ffmpeg       # 可执行文件路径；本机已配置 /opt/homebrew/bin/ffmpeg
transcode: false          # false: 复制视频编码；true: 转为 H.264。始终禁用音频
relay-start-timeout: 20s  # 等待首个输出视频帧的最长时间
relay-io-timeout: 15s     # FFmpeg 网络读写超时
```

先使用 `transcode: false` 降低 CPU 开销；如果源编码不被媒体服务器或播放器支持，再改为 `true`。
当前按已验证命令 `ffmpeg -i <源地址> -vcodec copy -an -f flv <推流地址>` 转推，只保留视频，
不转发或转码音频。Java 另外添加日志、进度和超时参数，用于判断启动成功及清理进程。
视频经过本服务，需为每路流准备相应的入站、出站带宽；
开启转码会额外消耗 CPU。前端请求超时应覆盖 media_server、capacity 和 FFmpeg 启动所需的时间。

```bash
mvn clean package
java -jar target/autel-uav-gateway.jar
```

运行后会打印：SkyCC 登录及响应状态、REST 请求/响应、WebSocket 原始消息、OSD 转换结果、
JASmart 属性上报、子设备上下线、全量拓扑、`media_server` 请求/响应、FFmpeg 启停/输出及直播结果。
FFmpeg 每半秒的进度仅在 DEBUG 输出。播放/推流 URL 可能包含鉴权参数，联调日志请勿公开传播。

本工程使用的是 SkyCC 平台侧 HTTPS + WebSocket 接口。用户提供的道通 EMQX 地址和 MQTT
账号属于另一套设备侧 Cloud API 链路，本工程不使用；JASmart MQTT 是服务到上层平台的下行链路。

真实配置中的明文联调凭据仅保留本地，公共仓库只提交模板。上线时建议改为环境变量或密钥服务，
并把 JASmart `tcp://` 改为 broker 支持的 `ssl://`。

## 联调前需确认

- 飞机模型 `videoList[].videoId` 当前最大长度是 20，而道通实际 ID 通常超过 30；建议改为至少 64。
- 飞机模型 `videoTypes[].type` 最大长度是 10，`nightvision` 为 11；建议改为至少 16。
- V1.4 详细文档使用 `/api/manage/manage/api/v1/live/streams/*`，简版指导手册给出另一套直播路径；
  当前代码以 V1.4 详细接口为准，需要道通确认生产环境版本。
- 当前由服务器转推，不再给机库下发 `video_type`。`videoId` 中的 `normal-0` 等内容是道通通道标识，
  原样用于匹配源地址，不需要替换成 `wide`。
- 两个物模型本身还有若干范围/单位冲突；代码不会猜测含义，具体停映射字段见属性映射说明。

可选的本地转推冒烟测试（需安装 FFmpeg，不连接真实设备）：

```bash
mvn -Dtest=FfmpegRelaySmokeTest -Dffmpeg.smoke=true test
```
