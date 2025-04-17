package com.danmo.guide.ui.splash
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import com.danmo.guide.R
import com.danmo.guide.ui.main.MainActivity
/**
 * 启动屏活动，展示品牌标识并进行初始化
 * Splash activity showing brand identity and performing initialization
 */
@SuppressLint("CustomSplashScreen") // 禁用自定义启动屏的Lint警告 / Disable custom splash screen lint warning
class SplashActivity : AppCompatActivity() {
    companion object {
        private const val SPLASH_DELAY = 2000L // 启动屏展示时长（毫秒） / Splash display duration in milliseconds
    }
    // 生命周期方法：创建活动 / Lifecycle method: create activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash) // 设置布局文件 / Set layout
        // 延时跳转主界面 / Delayed transition to main interface
        Handler(Looper.getMainLooper()).postDelayed({
            navigateToMain()
        }, SPLASH_DELAY)
    }
    /**
     * 导航到主活动（带转场动画）
     * Navigate to main activity with transition animation
     */
    private fun navigateToMain() {
        // 创建淡入淡出动画效果 / Create fade-in and fade-out animation
        val options = ActivityOptionsCompat.makeCustomAnimation(
            this,
            android.R.anim.fade_in,  // 进入动画（系统默认淡入） / Enter animation (system fade-in)
            android.R.anim.fade_out   // 退出动画（系统默认淡出） / Exit animation (system fade-out)
        )
        // 配置跳转意图 / Configure navigation intent
        Intent(this, MainActivity::class.java).apply {
            // 清空返回栈 / Clear back stack
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // 启动主活动（带动画参数） / Launch main activity with animation
            startActivity(this, options.toBundle())
        }
    }
}