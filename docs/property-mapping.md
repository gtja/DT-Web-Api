# 属性白名单映射

原则：只有“道通文档存在”且“与上层物模型语义、单位、枚举能安全对齐”的字段才上报。未知字段保留在道通 JSON 解析层，但不会透传到 JASmart。

## 机场（00000000001）

| 道通 `data` 路径 | JASmart 属性 | 处理 |
|---|---|---|
| `height` | `height` | 原值，m；按 001 当前范围只接收非负值 |
| `wind_speed` | `windSpeed` | 0 以上原值，m/s |
| `rainfall` | `rainfall` | 仅 0–3 |
| `supplement_light_state` | `supplementLightState` | 仅 0/1 |
| `emergency_stop_state` | `emergencyStopState` | 仅 0/1 |
| `environment_humidity` | `environmentHumidity` | 0–100，%RH |
| `environment_temperature` | `environmentTemperature` | 001 当前范围只接收非负值，°C |
| `humidity` | `humidity` | 0–100，%RH |
| `longitude` / `latitude` | 同名 | 按 001 当前范围仅 0–180 |
| `drone_in_dock` | `droneInDock`、`isInDock` | 仅 0/1，兼容模型重复字段 |
| `network_state.type` | `networkType` | 1→0（4G），2→1（以太网） |
| `cover_state` | `coverState` | 0→0，1→2，3→4；半开 2 丢弃 |
| `putter_state` | `putterState` | 0→0，1→2，3→4；半开 2 丢弃 |
| `air_conditioner.air_conditioner_state` | `airConditionerMode` | 仅精确的 0→0；准备态 8/9 不猜测 |
| `alternate_land_point.*` | `alternateLandPoint.*` | 经纬度 -180–180、安全高度 0–5000；4 个必填子字段齐全才上报 |
| `position_state.*` | `positionState.*` | 4 个必填子字段齐全才上报；quality 限 1–5，GPS/RTK 星数限 0–200 |
| `drone_charge_state.capacity_percent` | `droneChargeState.capacityPercent` | 原值 |
| `drone_charge_state.state` | `droneChargeState.state` | 0（在充电）→1；1（未充电）→0 |
| 直播状态 REST | `videoList` | 动态构建；机场的 `videoTypes` 按 struct 输出 |

未上报：示例中但无完整枚举/单位定义的 `alarm_state`、`mode_code`、`network_state.quality/rate`、`wireless_link`，以及存在温压尺度冲突的 `backup_battery`。`storage.total/used` 的道通单位未定义，而 001 要求 KB，确认单位前不猜测。

## 飞机（00000000002）

| 道通 `data` 路径 | JASmart 属性 | 处理 |
|---|---|---|
| `horizontal_speed` / `vertical_speed` | `horizontalSpeed` / `verticalSpeed` | 原值，m/s |
| `longitude` / `latitude` | 同名 | 按 002 当前范围仅接收非负值 |
| `elevation` | `elevation` | 相对起飞点高度，m |
| `attitude_pitch/roll` | `attitudePitch/Roll` | 原值 |
| `attitude_head` | `attitudeHead` | 四舍五入为 int |
| `home_distance` | `homeDistance` | 原值，m |
| `wind_speed` | `windSpeed` | 原值，m/s |
| `activation_time` | `activationTime` | 13 位毫秒时间戳除以 1000；秒值原样 |
| `night_lights_state` | `nightLightsState` | 仅 0/1 |
| `height_limit` | `heightLimit` | 仅 20–1500 m |
| `mode_code` | `modeCode` | 仅 0–16；17 起与 002 枚举冲突 |
| `distance_limit_status.state` | `distanceLimitStatus.state` | 仅 0/1 |
| `distance_limit_status.distance_limit` | `distanceLimitStatus.distanceLimit` | 仅文档范围 15–8000 m |
| `obstacle_avoidance.*` | `obstacleAvoidance.*` | 3 个必填子字段齐全才上报，值仅 0/1 |
| `position_state.*` | `positionState.*` | 4 个必填子字段齐全才上报；quality 限 1–5，GPS/RTK 星数限 0–10000 |
| 单一 `cameras[]` | `cameraMode`、`zoomFactor` | 多相机时不猜；zoom=1 因模型最小 2 而丢弃 |
| 单一 `payloads[]` + 同索引动态对象 | `cameraSn`、`gimbalPitch`、`gimbalYaw` | 只有唯一负载时映射 |
| 机巢 OSD `live_status[]` 中飞机活动码流的 `video_quality` | `videoQuality` | 按 `video_id` 的飞机 SN 分流；唯一活动码流时 0→0、1→1、2→3、3→4 |
| 直播状态 REST | `videoList` | 动态构建，`videoTypes` 为 array |

道通 `height` 是相对地球椭球面的绝对高度，002 的 `height` 是相对高度，因此明确不映射，也不会用 `elevation` 同时伪造两个高度字段。

002 的 `battery` struct 要求 `landingPower`，而天穹文档没有给出可信的同义字段。为避免发布不完整 struct，当前版本连同 `capacityPercent`、`remainFlightTime` 一起暂不上报；确认 `landingPower` 来源后再整体启用。

002 的 `totalFlightTime` 虽然与道通 `total_flight_time` 同为秒，但物模型把 int 的最大值写成 `1.17549e-038`，正常累计航时都会越界，因此当前暂不上报；修正模型上限后即可启用直映射。

飞机 `storage.total/used` 同样因道通文档没有声明单位、002 要求 KB 而暂不上报；确认单位后还需校验 `used <= total` 与 int 范围再整体启用。

所有上表中的 struct 都按物模型必填项做原子校验：任何一个必填子字段缺失、类型错误或越界，本帧不会发布该 struct，避免上层拒绝整个属性包。
