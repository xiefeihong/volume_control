---
kind: external_dependency
name: Android 系统音频服务与蓝牙 AVRCP 绝对音量换算约束
slug: aosp-audio-avrcp
category: external_dependency
category_hints:
    - client_constraint
    - sdk_real_api
scope:
    - '**'
---

项目不直接调用 AOSP API，而是通过 Hook 系统框架间接影响其行为，因此以下约束是本项目必须遵守的外部事实：
- `AudioService.createStreamStates` 中 `MAX_STREAM_VOLUME[]` 是受保护的 `protected static int[]`，媒体默认 15 档，可被 `ro.config.media_vol_steps` 属性覆盖；修改该数组是唯一能改变系统音量阶数的 root 级手段。
- 蓝牙 AVRCP 绝对音量换算公式为 `absVolume = round(档位 × 127 / 系统最大档位)`，因此当系统媒体档位 >127 时必然出现相邻档位映射到同一耳机音量的情况，且第 1 档映射到 abs=1（≈0.8%），很多蓝牙耳机无法识别——这是“低档无声、相邻档相同”的根因。
- 本项目据此将媒体档位强制钳制在 ≤127（推荐 30~75），并在 UI 中展示 AVRCP 映射明细供用户确认无重复档位后再生效。