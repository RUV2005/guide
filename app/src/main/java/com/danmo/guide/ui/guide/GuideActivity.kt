// GuideActivity.kt
package com.danmo.guide.ui.guide

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.danmo.guide.R
import com.danmo.guide.databinding.ActivityGuideBinding
import com.danmo.guide.ui.main.MainActivity

class GuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuideBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        // 检查是否已经设置过紧急联系人
        val emergencyNumber = prefs.getString("emergency_number", null)
        if (emergencyNumber != null) {
            binding.etEmergencyNumber.setText(emergencyNumber)
        }

        // 保存按钮点击事件
        binding.btnSave.setOnClickListener {
            val emergencyNumber = binding.etEmergencyNumber.text.toString().trim()
            if (emergencyNumber.isEmpty()) {
                Toast.makeText(this, "请输入紧急联系人电话", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 保存紧急联系人电话
            prefs.edit().putString("emergency_number", emergencyNumber).apply()

            // 标记为已引导
            prefs.edit().putBoolean("is_first_launch", false).apply()

            // 跳转到主界面
            startMainActivity()
        }

        // 跳过按钮点击事件
        binding.btnSkip.setOnClickListener {
            // 标记为已引导
            prefs.edit().putBoolean("is_first_launch", false).apply()

            // 跳转到主界面
            startMainActivity()
        }
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}