package com.danmo.guide.ui.settings
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.danmo.guide.R
/**
 * 设置界面活动容器，用于承载设置Fragment
 * Settings activity container for hosting settings fragment
 */
class SettingsActivity : AppCompatActivity() {
    // 生命周期方法：创建活动 / Lifecycle method: create activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置布局文件 / Set content view
        setContentView(R.layout.activity_settings)
        // 初始化设置Fragment / Initialize settings fragment
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, SettingsFragment()) // 替换Fragment容器 / Replace fragment container
            .commit()  // 提交事务 / Commit transaction
    }
}