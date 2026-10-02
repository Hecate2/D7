package io.github.hecate2.sevend.ui.capture

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import io.github.hecate2.sevend.R
import io.github.hecate2.sevend.data.GroupRepository
import io.github.hecate2.sevend.databinding.ActivityPlaceholderBinding
import io.github.hecate2.sevend.ui.Extras

/**
 * 采集界面（占位骨架，后续提交实现相机、传感器与叠加层）。
 */
class CaptureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPlaceholderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val group = GroupRepository.get(this).get(intent.getStringExtra(Extras.GROUP_ID).orEmpty())
        binding.title.text = group?.name ?: ""
        binding.subtitle.setText(R.string.capture_placeholder)
        binding.actionButton.isVisible = false
        binding.backButton.setOnClickListener { finish() }
    }
}