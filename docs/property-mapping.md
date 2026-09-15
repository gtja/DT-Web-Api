# 属性转换核对清单

核对日期：2026-09-15。基础属性以用户提供的 001（45 项）和 002（74 项）原始物模型为输出约束；经用户要求，机库和飞机分别追加下述前端兼容字段。没有修改 Downloads 原文件或上层平台。下表逐项覆盖原模型全部 119 个顶层属性，不承诺设备未提供的数据也能有值。

## 来源与边界

- 《天穹平台 API 接口说明 V1.4.0》7.2/7.3（PDF 页 129～149）：SkyCC OSD 字段、基本枚举、单位。
- [道通龙鱼机巢属性](https://doc.autelrobotics.cn/cloud_api/cn/60/50/00/20/00/)：补充机巢工作状态、完整空调模式、工作架次和链路模式。
- [道通龙鱼飞机属性](https://doc.autelrobotics.cn/cloud_api/cn/60/50/00/10/10/)：补充负载索引、存储单位、风向有效性、红外枚举。
- [道通设备属性示例](https://doc.autelrobotics.cn/cloud_api/cn/60/50/00/20/00/)中的 battery.landing_power/return_home_power 仅用于字段真实出现时的条件映射，不能当作当前 SkyCC 必然提供的值。
- 数据仍来自 WS `osd_property`，不是用 capacity 获取飞参。capacity 只服务直播；本次没有修改直播启停、推流、videoId 或设备控制。

只有可信来源且符合上层类型、范围和枚举的值才发；缺字段、非法数值、NaN/Infinity、溢出不变成 0。struct 的必填项必须齐全，可选子字段则按实际存在值输出。每帧仍打印原始 WS、转换结果、JASmart 上报日志。被省略字段的原因可按本表对照原始帧查到。

## 机库前端兼容字段（原属性之外追加）

- `modeDisplay`：复用已经校验过的 `modeCode`，但输出字符串 `"0"`～`"4"`。前端信息卡以此字段查显示枚举，且有真值判空，所以不能把空闲态发成数字 `0`。原 `modeCode` 仍然是整数，控制逻辑不变。
- `temperature`：取机库 OSD 的 `temperature`，单位 °C，表示舱内温度；保留 0 和负值。`environmentTemperature` 仍取 `environment_temperature`，表示舱外温度，两者不混用。
- 只扩展机库，飞机转换器不添加这两个字段。原始值缺失或非法时不造默认值；日志随原有属性一起打印。
- “型号”读取设备元数据里的 `deviceTags[MODEL_NUMBER]`，不是 `properties`；需在上层设备标签中配置。仅在 MQTT 属性中增加 `modelNumber` 不能让现有页面显示型号。
- 这两个字段不在上传的 001 模型中。如果上层有严格的未知属性过滤，需要将它们加入机库模型或允许扩展透传；本次未修改上层平台。

## 机库 001

<!-- dock -->
| 上层属性 | 状态 | 取值及处理 |
|---|---|---|
| `airConditionerMode` | 已映射 | air_conditioner.air_conditioner_state：0～3 同值；4～9 退出/准备态不冒充工作模式 |
| `alarmState` | 待确认语义 | alarm_state 见示例，但未给声光报警开关的完整定义；不能把设备故障告警替代声光报警开关 |
| `firstPowerOn` | 无已确认来源/模型受限 | 没有首次开机时间来源；且毫秒时间戳不能放进 int32；不能使用本次启动时间 |
| `supplementLightState` | 已映射 | supplement_light_state，0/1 |
| `height` | 已映射/范围受限 | height，米；按原模型仅接受非负数 |
| `windSpeed` | 已映射 | wind_speed，SkyCC 米/秒，不再套用其他接口缩放 |
| `accTime` | 无已确认来源 | 未找到机巢累计运行秒数；不能用网关进程运行时长替代 |
| `activationTime` | 已映射 | activation_time，整数秒；毫秒时间戳 /1000，校验 int 范围 |
| `rainfall` | 已映射 | rainfall，0～3 |
| `compatibleStatus` | 待确认枚举 | compatible_status 实测存在，但不能仅凭 0/1 猜测固件一致性含义 |
| `networkType` | 已映射 | network_state.type：1→0（4G），2→1（以太网） |
| `alternateLandPoint` | 已映射 | alternate_land_point：经纬度、安全高度、是否配置；四项必填，完整且合法才上报 |
| `droneBatteryMaintenanceInfo` | 无已确认来源 | 电池维护状态及剩余维护时长无对应完整结构 |
| `emergencyStopState` | 已映射 | emergency_stop_state，0/1 |
| `batteryStoreMode` | 无已确认来源 | 未找到电池存储策略枚举 |
| `positionState` | 已映射/模型受限 | position_state：四项完整；isFixed 0～3，quality 1～5，GPS/RTK 0～200；quality=0 整组不发 |
| `putterState` | 已映射/枚举受限 | putter_state：0→0，1→2，3→4；半开不猜方向 |
| `environmentHumidity` | 已映射 | environment_humidity，0～100 |
| `workingVoltage` | 无已确认来源 | 机巢工作电压 mV 无确认字段；不能取飞机/电池槽电压冒充 |
| `coverState` | 已映射/枚举受限 | cover_state：0→0，1→2，3→4；半开不能确定开/关方向，不上报 |
| `environmentTemperature` | 已映射/范围受限 | environment_temperature；原模型不接受负温度 |
| `electricSupplyVoltage` | 无已确认来源 | 市电电压 V 无确认字段 |
| `droneInDock` | 已映射 | drone_in_dock，0/1；不根据子设备在线状态推断是否在舱 |
| `droneChargeState` | 已映射 | drone_charge_state：capacity_percent 0～100；state 0→1（充电）、1→0（空闲）；两项必填 |
| `storage` | 待确认单位 | 机巢 storage 单位未声明，旧示例和 SkyCC 当前数据数量级不同；不按大小猜 bytes/KB |
| `longitude` | 已映射/范围受限 | longitude；原模型只接受 0～180 |
| `latitude` | 已映射/范围受限 | latitude；原模型只接受 0～180，建议改为 -90～90 |
| `modeCode` | 已映射 | mode_code：0～4 同值；绝不使用 state（机巢作业步骤）代替 |
| `jobNumber` | 已映射 | work_sorties，整数 0～10000 |
| `backupBattery` | 待确认单位/模型冲突 | 文档写温度、电压 /100，但上层 voltage 的 unit=V、unitName=毫伏相互矛盾，暂不上报整组 |
| `workingCurrent` | 无已确认来源 | 机巢工作电流 mA 无确认字段；不使用电池槽电流 |
| `networkRate` | 待确认单位 | network_state.rate 实测为小数，但上层要求 0～10000 整数 b/s；不能直接截断 |
| `networkQuality` | 待确认枚举 | network_state.quality 完整等级含义未确认；不能把 SDR 的 0～5 等级直接塞进 0～2 |
| `humidity` | 已映射 | humidity，0～100；舱内温度 temperature 不等于湿度 |
| `backupBatteryVoltage` | 待确认源单位 | 目标明确 mV，源 voltage /100 后的单位需确认；不猜缩放倍率 |
| `videoList` | 已有映射，未改直播流程 | REST 直播通道目录；每项 videoId 保持业务值 live，videoTypes 为 struct |
| `droneFormatProcess` | 无 OSD 对应状态 | 需要格式化任务进度事件；不根据 storage.used 推断完成 |
| `deviceFormatProcess` | 无 OSD 对应状态 | 需要机巢格式化任务进度事件 |
| `droneOpenProcess` | 已映射 | sub_device.device_online_status：0→3（已关机）、1→2（已开机）；不伪造开机中/关机中 |
| `deviceRebootProcess` | 无 OSD 对应状态 | 需要重启过程事件；连接正常不等于刚刚完成重启 |
| `droneChargeStatusProcess` | 无 OSD 对应过程 | 充电/未充电不提供指令执行阶段；已有实际状态 droneChargeState |
| `isInDock` | 已映射 | drone_in_dock，0/1，兼容上层同义属性 |
| `airTransferEnable` | 无已确认来源 | 未找到上层空中传输功能同义开关 |
| `linkWorkMode` | 已映射 | wireless_link.link_workmode：0/1 同值；没有文档依据的 100/101 不生成 |
| `cameraPosition` | 无已确认来源 | 摄像头连接状态不代表内/外镜头；不从 videoId 数字后缀猜位置 |
<!-- end -->

## 飞机驾驶舱兼容字段（原属性之外追加）

从 `ja-dushu-front` 的驾驶舱 Header、Compass 实际读取字段确认；只由飞机 OSD 生成，不使用机库数据填充。实现集中在 `AircraftFrontendProperties`。

| 前端显示/字段 | 道通来源 | 转换 |
|---|---|---|
| 电量 `electricity` | battery.capacity_percent | 0～100；即使原 battery 因缺 landingPower 不能整体发送，仍可显示真实电量 |
| 电池弹窗 `cloudApiOsdData.battery` | battery 及 batteries[] | 白名单保留电量、剩余时长、真实返航/强降电量和电池序号/序列号/循环数；不透传整包、不用告警阈值冒充动态所需电量 |
| ASL `altitude` | height | 与现有 DJI 接入一样使用源绝对高；SkyCC 定义为椭球高，没有凭空转换为正高/平均海平面高度 |
| 姿态仪 `uavPitch/uavRoll/uavYaw` | attitude_pitch/roll/head | 原始角度 -180～180；uavYaw 保留小数，不使用已取整的 attitudeHead |
| FT `flyTime` | total_armed_time | 本次打桨统计秒数乘 1000，Long 毫秒；不是 total_flight_time 终身累计 |
| FD `flyDistance` | total_armed_distance | 本次打桨里程，米；不是累计里程或剩余任务距离 |
| GPS `satelliteNumber`、RTK `rtkSatelliteNumber` | position_state.gps_number/rtk_number | 独立取真实星数，quality=0 不再使头部星数一起消失；原 positionState 类型不改 |
| `signalMode/signalStrength` | lte_signal | 明确的 LTE 0～100 换成前端 0～5（除以 20）；若提供 lte_status，仅 3/4 表示连接时发送；无值不伪造满格 |
| `sdrStrength` | wireless_link.sdr_quality | 仅本飞机 sdr_link_state=1 且 quality 0～5 时发送；不复制机巢链路强度 |

H.S=`horizontalSpeed`、ALT=`height`、距 Home=`homeDistance`、云台方向=`gimbalYaw` 已按原转换上报，不追加同义假值。数值 0 按真实值保留，负绝对高/负姿态角不丢弃；FT=0 时页面显示“未起飞”是前端既定行为。

不能通过追加属性解决的项目：

- `AUTO` 来自上层设备 `getDeviceLinks` 的当前链路名称，不是 OSD 飞行模式；不覆盖链路选择或控制权。
- “设备已离线”依赖设备上下线/连接状态；不因有缓存属性就改成在线，不改变现有拓扑或直播流程。
- 飞机/机库型号来自 `MODEL_NUMBER` 标签，仍需要上层设备元数据配置。
- 未确认范围的 `fourth_generation_uavQuality` 不按数值大小猜 0～5 还是 0～100；缺少已确认信号字段时图标仍可能缺值。
- 原 `positionState.isFixed` 数字枚举与前端弹窗的字符串定义存在冲突；本次仅追加星数别名，不破坏原物模型字段类型。
- 原始坐标确为 0 时地图仍可能定位到 0,0；展示适配不能制造有效定位。

上层若严格过滤未知属性，需允许这些扩展透传或加入对应飞机模型；本次只修改网关代码，未调整前端/上层服务。

## 飞机 002

<!-- aircraft -->
| 上层属性 | 状态 | 取值及处理 |
|---|---|---|
| `modeCode` | 已映射/枚举受限 | mode_code 仅 0～16 同值；17 起含义与上层不同，不直接透传 |
| `gear` | 待确认枚举 | gear_level 的 0/1/2 与上层 A/P/NAV 等档位不是同一枚举 |
| `firmwareVersion` | 已映射 | firmware_version；或机巢 sub_device.firmware_version（校验绑定飞机 SN），最多 64 字 |
| `horizontalSpeed` | 已映射 | horizontal_speed，米/秒；保留真实 0 |
| `verticalSpeed` | 已映射 | vertical_speed，米/秒 |
| `longitude` | 已映射/范围受限 | longitude，保留源 0；原模型不接受负经度，不借用机巢经度 |
| `latitude` | 已映射/范围受限 | latitude，保留源 0；原模型不接受负纬度，不借用机巢纬度 |
| `height` | 已映射 | 取 elevation（相对起飞点高度），范围 -100～1000m；不能取道通绝对高度 height |
| `elevation` | 已映射 | elevation，相对起飞点高度，米；与上层 height 是同源兼容字段 |
| `attitudePitch` | 已映射 | attitude_pitch，角度 |
| `attitudeRoll` | 已映射 | attitude_roll，角度 |
| `attitudeHead` | 已映射 | attitude_head，-180～180 度，四舍五入到 int；防溢出 |
| `homeLongitude` | 无已确认来源 | 未找到 Home 点经度；不使用当前飞机坐标或机巢坐标 |
| `homeLatitude` | 无已确认来源 | 未找到 Home 点纬度；不使用当前坐标 |
| `homeHeight` | 无已确认来源 | 未找到 Home 点高度；不使用相对高/绝对高冒充 |
| `homeDistance` | 已映射 | home_distance，米 |
| `windSpeed` | 已映射 | wind_speed，SkyCC 单位沿用 PDF；若携带 wind_speed_valid，必须为 1 |
| `windDirection` | 已映射 | wind_direction，1～8；若携带 wind_direction_valid，必须为 1 |
| `controlSource` | 已映射 | control_source 字符串；不把数字来源类型转成伪标识 |
| `heightRestriction` | 待确认语义 | is_near_height_limit 是是否接近限高，上层字段描述是高度限制 0～100；不能默认是同义状态 |
| `distanceRestriction` | 待确认语义 | is_near_area_limit/是否接近限距并不等于上层距离限制值 |
| `totalFlightTime` | 模型阻挡 | total_flight_time 单位秒明确，但 int max=1.17549e-038，正常累计值无法上报 |
| `totalFlightSorties` | 已映射 | total_flight_sorties；字段缺失时用 fly_turns；接受 256.0，拒绝小数架次和 int 溢出 |
| `totalFlightDistance` | 来源/模型阻挡 | 未确认 SkyCC 累计里程来源；total_armed_distance 仅本次打桨里程，且上层 int max 错误 |
| `activationTime` | 已映射/源值待核实 | activation_time 毫秒转秒；实测该值随帧变化，需道通确认是否真正激活时间，不由网关改写 |
| `nightLightsState` | 已映射 | night_lights_state，0/1 |
| `heightLimit` | 已映射/模型受限 | height_limit；原模型只接受 20～1500m，实测 5000 不上报，不能截断成 1500 |
| `distanceLimitStatus` | 已映射/模型受限 | distance_limit_status，state 0/1、distance_limit 15～8000m；实测 120000 导致整组不发 |
| `obstacleAvoidance` | 已映射 | obstacle_avoidance，horizon/upside/downside 三项 0/1，完整才发送 |
| `battery` | 条件映射/缺必填来源 | capacity_percent、remain_flight_time、landing_power；仅三项真实且完整时发送。当前 SkyCC 缺 landing_power，不拿告警阈值替代 |
| `storage` | 已映射 | 飞机文档确认 KB；优先明确启用的 sdcard_storage，否则 storage；非负 int、used≤total，禁止溢出 |
| `positionState` | 已映射/模型受限 | 四项必填；isFixed 0～3、quality 1～5、GPS/RTK 0～10000；实测 quality=0 导致整组不发 |
| `videoList` | 已有映射，未改直播流程 | REST 直播通道目录；videoId 保持 live，videoTypes 为 array；道通请求仍使用真实通道 |
| `controlTag` | 无已确认来源 | 未找到控制标签；不由控制源字段推导 |
| `cameraMode` | 已映射 | 唯一 cameras[].camera_mode 0/1；多相机不猜，101 不强行转换 |
| `zoomFactor` | 已映射/模型受限 | 唯一 cameras[].zoom_factor；原模型最小值 2，因此源 1 倍不上报 |
| `lostAction` | 条件映射 | rc_lost_action 数值或 .state，0～2；未确认同义的 out_of_control_action 不猜 |
| `laserDistance` | 条件映射 | laser_distance_data.laser_distance；laser_distance_is_valid=1 才发送，0～10000m |
| `disconnectTime` | 无已确认来源 | 未找到设备同义失联时长；网关 WS 断线时长不是飞机失联时长 |
| `healthInfo` | 无 OSD 同义结构 | HMS 告警需独立处理；不能把一个 alarm_status 数字当作完整健康信息 |
| `videoQuality` | 已有映射 | 机巢 live_status 中唯一活动飞机码流；0→0、1→1、2→3、3→4 |
| `enableAR` | 上层业务属性 | 无道通 OSD 对应字段 |
| `ar` | 上层业务属性 | 无道通 OSD 对应字段 |
| `audioPlayMode` | 无已确认来源 | 当前负载未提供音频播放模式 |
| `lightStatus` | 无已确认来源 | 当前负载未提供上层照明灯模式 0～8 |
| `lightStrength` | 无已确认来源 | 当前负载未提供已确认单位/范围的照明强度 |
| `lightFollow` | 无已确认来源 | 无已确认的照明随动开关 |
| `returnhomePower` | 条件映射 | battery.return_home_power 0～100；当前 SkyCC 无此值，不能使用用户设置的 low_battery_warning_threshold |
| `remainFlightDistance` | 待确认语义 | remain_distance 不能确认是剩余续航距离还是任务剩余航程 |
| `zoomExposureValue` | 待确认镜头归属 | 仅 exposure_compensation 不能区分广角/变焦镜头；不复制给两者 |
| `zoomFocusMode` | 部分枚举映射 | 唯一 cameras[].zoom_focus_mode：2（MF）→0；道通 AF 未区分 AFS/AFC，不猜 |
| `deviceModel` | 模型不支持 | 上层枚举仅含 DJI 机型，不能把龙鱼写成其中一种；需增加道通机型 |
| `wideExposureMode` | 待确认枚举 | iso_mode/shutter_mode 不能无歧义组合成上层四种曝光模式 |
| `zoomExposureMode` | 待确认枚举 | 同上；没有确认的同义镜头曝光模式 |
| `wideExposureValue` | 待确认镜头归属 | exposure_compensation 没有明确广角镜头归属 |
| `gimbalPitch` | 已映射 | 唯一负载动态对象的 gimbal_pitch，-180～180；支持 cameras 指向及独立动态对象 |
| `gimbalYaw` | 已映射 | 唯一负载动态对象的 gimbal_yaw，-180～180；索引冲突/多负载不混合 |
| `wideShutterSpeed` | 类型/枚举冲突 | 源为分数形式时间字符串；上层 int 0～60 是另一套定义，禁止 1/30→0 |
| `zoomShutterSpeed` | 类型/枚举冲突 | 同上；需上层快门枚举表 |
| `irMeteringMode` | 已映射 | 唯一 cameras[].ir_metering_mode：0→CLOSE，1→POINT，2→AREA |
| `irMeteringPoint` | 已映射 | 唯一相机 ir_metering_point.x/y，0～1，按上层可选子字段输出 |
| `irMeteringArea` | 已映射/模型受限 | 区域 x/y/宽高 0～1、平均/最高温 0～100；最低温上限在原模型误写为 1，超范围温度不发 |
| `supplementLighState` | 无已确认来源 | 保留模型拼写；不将机身夜航灯状态当作相机补光灯 |
| `laserLatitude` | 无已确认有效测距目标坐标 | 当前激光结构未提供目标纬度及完整有效性定义 |
| `laserLongitude` | 无已确认有效测距目标坐标 | 当前激光结构未提供目标经度及完整有效性定义 |
| `laserAltitude` | 无已确认有效测距目标坐标 | 当前激光结构未提供目标高度及基准定义 |
| `thermalCurrentPaletteStyle` | 已转换枚举 | 唯一动态负载：0→0、4→6、5→11；高德 30→0、31→1、32→5、33→13、34→6、35→11、36→8、38→3、39→2；其余无等价定义不发 |
| `recordAudioFiles` | 无已确认来源 | 无录音文件清单数据 |
| `currentSelectedRecordAudioFile` | 无已确认来源 | 无录音文件选择状态 |
| `recordAudioInfos` | 无已确认来源 | 无录音文件详情数据 |
| `cameraSn` | 条件映射 | 唯一 payloads[].sn，最长 1024；只有 cameras 索引不能生成相机序列号 |
| `thermalGainMode` | 已映射 | 唯一动态负载 thermal_gain_mode：1/2 同值；文档未定义的 0/3 不生成 |
| `stealthState` | 无已确认来源 | 无隐身模式状态 |
| `videoResolution` | 待确认枚举 | video_format_settings 是 MOV/MP4/TIFF 容器，不是 1080P/4K；video_resolution 枚举未定义 |
<!-- end -->

## 需要上层物模型先调整的项目

目前保持上传原模型约束，不先发送平台不接受的数据：

1. 飞机 battery.landingPower 改为非必填，才可在当前 SkyCC 缺失该值时报告电量及剩余时长。
2. 两端 positionState.quality 增加 0（建议标记为“未提供有效档位”，不要简单认定未定位）。实际 quality=0 也可能伴随非零星数，不能改成 1 掩盖。
3. totalFlightTime、totalFlightDistance 的 int 上限改为合理整数（例如 2147483647）；累计里程仍需确认来源。
4. 飞机 heightLimit、distanceLimitStatus.distanceLimit 的范围应覆盖龙鱼实际能力（目前原始帧为 5000m、120000m）。
5. zoomFactor 最小值改为 1；坐标允许负值（经度 -180～180、纬度 -90～90）；高度、环境温度不能只接受非负值。
6. irMeteringArea.minTemperaturePoint.temperature 的 0～1 并非温度范围，应按设备测温能力设定；平均/最高温度限制也需一并检查。
7. backupBattery.voltage 的 V/mV 定义统一；firstPowerOn 的毫秒时间戳改为 long/合适类型；storage 的 int 最大值不能写成 2147483648。
8. 飞机 deviceModel 增加 Autel 机型；videoList.videoTypes[].type 长度 10 无法容纳 nightvision；modeCode 17 起必须扩充枚举或另行约定归一规则。

上述调整需要导入上层且同步修改转换范围，不能只改测试里的快照。现有源字段即便在文档存在，也不意味着每台设备每帧一定返回。

## 回归验证

- `src/test/resources/thing-model/` 保存两份原模型的 properties 快照，不包含服务定义或账号。
- `src/test/resources/telemetry/` 是依据真实报文字段形状、补充文档边界用例整理的无凭据测试数据，不是完整原始抓包。
- 单测覆盖工作状态全枚举、空调过渡态、充电枚举、0 坐标、负载索引缺失/冲突、多相机、温度范围、存储与整数溢出、有效性标志、固件 SN 隔离。
- `PropertyModelContractTest` 校验测试输出的属性标识、类型、范围、文本长度及必填项；在原始模型之外分别允许 `dock-frontend-extensions.json` / `aircraft-frontend-extensions.json` 中明确列出的字段，其他未知字段仍拒绝，并确保基础清单恰好覆盖 45+74 项。
