package com.danmo.guide.core.manager

import com.amap.api.location.AMapLocation

/**
 * 应用初始化状态管理
 * 用于在 SplashActivity 和 MainActivity 之间共享状态
 * 
 * 注意：这是一个简单的共享状态对象，主要用于启动阶段的临时状态传递
 * 正式运行时的状态应该由 InitializationManager 管理
 */
object InitState {
    var voskReady: Boolean = false
    var cachedLocation: AMapLocation? = null
}

