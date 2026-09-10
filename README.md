# 道通天穹 → JASmart 网关

工程只保留四条业务主线：

```text
SkyCC 第三方登录 → WebSocket 接收 OSD → 属性转换 → JASmart MQTT 上报
                                      └→ 飞机上下线 → JASmart 子设备拓扑

JASmart live START → media_server 获取 RTMP 推流地址 → SkyCC start → 回复处理状态
JASmart live STOP → SkyCC stop → 回复处理状态
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
- `LiveCommandHandler`：请求上层推流地址并下发 start，STOP 下发 stop。

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
- 请求中的 `protocol` 是前端播放协议；设备推流协议使用配置中的 RTMP，播放地址由上层媒体服务提供。

START 流程：

1. 校验设备身份，从视频目录解析前端传入的完整 `videoId`。
2. 保留原请求的 `tid/bid`，调用同一设备的 `media_server`：
   `{"protocol":"RTMP","videoId":"<streamId>","type":"ACTIVE"}`。
3. 把返回的推流地址作为 `url` 下发给 SkyCC `/live/streams/start`。
4. 成功后只回复 `{"code":"OK","message":"OK"}`，不返回道通播放地址。

START 不查询 capacity、不复用道通已有流，也不自动切流或先停后开；重复 START 仍执行上述链路。
道通拒绝开流时返回真实错误，不把已有流视为本次下发成功。

STOP 根据完整 `videoId` 直接调用 SkyCC stop，不请求 media_server，不依赖本地开流记录。
未开播或设备离线按停流成功处理；其他失败统一走 JASmart `reply.error(...)`。

## 配置和运行

仓库提供 `src/main/resources/application-example.yml` 配置模板。首次拉取后复制为同目录的
`application.yml`，填写天穹和 JASmart 账号、设备 SN、上层平台设备实例 ID，再将 `enabled` 改为 `true`。
真实的 `application.yml` 已被 Git 忽略；本机已有的联调配置保留，不需要重新填写。

```bash
mvn clean package
java -jar target/autel-uav-gateway.jar
```

运行后会打印：SkyCC 登录及响应状态、REST 请求/响应、WebSocket 原始消息、OSD 转换结果、
JASmart 属性上报、子设备上下线、全量拓扑、`media_server` 请求/响应及直播结果。密码、token 和
WebSocket 鉴权头不会主动写入业务日志。

本工程使用的是 SkyCC 平台侧 HTTPS + WebSocket 接口。用户提供的道通 EMQX 地址和 MQTT
账号属于另一套设备侧 Cloud API 链路，本工程不使用；JASmart MQTT 是服务到上层平台的下行链路。

真实配置中的明文联调凭据仅保留本地，公共仓库只提交模板。上线时建议改为环境变量或密钥服务，
并把 JASmart `tcp://` 改为 broker 支持的 `ssl://`。

## 联调前需确认

- 飞机模型 `videoList[].videoId` 当前最大长度是 20，而道通实际 ID 通常超过 30；建议改为至少 64。
- 飞机模型 `videoTypes[].type` 最大长度是 10，`nightvision` 为 11；建议改为至少 16。
- V1.4 详细文档使用 `/api/manage/manage/api/v1/live/streams/*`，简版指导手册给出另一套直播路径；
  当前代码以 V1.4 详细接口为准，需要道通确认生产环境版本。
- 当前采用与 DJI 相同的申请推流地址后下发 start 的业务流程；机库 `video_type` 按联调要求使用 `wide`，
  完整 `video_id` 中的 `normal-0` 保留，飞机继续使用目录类型。机库 `wide`、自定义推流地址、已有直播的处理
  以及控制权要求仍待实机确认。不会通过自动抢占控制权处理失败。
- 两个物模型本身还有若干范围/单位冲突；代码不会猜测含义，具体停映射字段见属性映射说明。
