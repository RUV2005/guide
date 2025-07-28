package com.danmo.guide.feature.init

import com.amap.api.location.AMapLocation

object InitManager {

    var voskReady: Boolean = false

    var cachedLocation: AMapLocation? = null
        private set
}