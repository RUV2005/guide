# 项目目录结构说明

## 优化后的目录结构

```
com.danmo.guide/
├── core/                    # 核心模块（可复用的通用组件）
│   ├── manager/            # 管理器模块（从 ui/main/manager 提升）
│   │   ├── CameraModeManager.kt
│   │   ├── InitializationManager.kt
│   │   ├── LocationWeatherManager.kt
│   │   ├── PermissionManager.kt
│   │   ├── SensorHandler.kt
│   │   ├── StreamManager.kt
│   │   ├── TtsServiceManager.kt
│   │   ├── UIManager.kt
│   │   └── VoiceCommandHandler.kt
│   └── service/            # 服务模块
│       └── TtsService.kt   # TTS 服务（从 feature/fall 移动）
│
├── feature/                 # 功能特性模块
│   ├── camera/             # 摄像头相关
│   ├── detection/          # 目标检测相关
│   ├── fall/               # 跌倒检测（TtsService 已移至 core/service）
│   ├── feedback/           # 反馈系统
│   ├── init/               # 初始化管理
│   ├── location/           # 定位服务
│   ├── performancemonitor/ # 性能监控
│   ├── powermode/          # 功耗模式
│   ├── vosk/               # 语音识别
│   └── weather/            # 天气服务
│
└── ui/                      # UI 界面模块
    ├── components/         # UI 组件（共享组件）
    │   └── OverlayView.kt  # 检测覆盖层视图
    ├── main/               # 主界面
    │   └── MainActivity.kt
    ├── read/               # 在线阅读界面
    ├── room/               # 室内场景描述
    │   ├── ArkViewModel.kt  # 从 feature/room 移动
    │   └── RoomActivity.kt
    ├── settings/           # 设置界面
    ├── splash/             # 启动界面
    ├── theme/              # 主题配置
    └── voicecall/          # 语音通话界面
```

## 优化说明

### 1. 创建 core 目录
- **core/manager/** - 存放所有管理器类，这些类可以被多个 Activity 复用
- **core/service/** - 存放服务类，如 TtsService

### 2. 目录调整
- ✅ `ui/main/manager/` → `core/manager/` - 提升管理器到核心层
- ✅ `feature/fall/TtsService.kt` → `core/service/TtsService.kt` - 服务类独立
- ✅ `feature/room/ArkViewModel.kt` → `ui/room/ArkViewModel.kt` - ViewModel 归属 UI 层
- ✅ `ui/main/OverlayView.kt` → `ui/components/OverlayView.kt` - 组件共享

### 3. 目录结构优势
- **清晰的层次**：core（核心）→ feature（功能）→ ui（界面）
- **易于复用**：core 模块可以被多个模块使用
- **职责明确**：每个目录都有明确的职责
- **便于维护**：相关文件集中管理

## 包命名规范

- `core.*` - 核心通用模块，可被多个模块复用
- `feature.*` - 功能特性模块，业务逻辑
- `ui.*` - 用户界面模块，Activity/Fragment/View

