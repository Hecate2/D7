package io.github.hecate2.sevend.ui.result

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.hecate2.sevend.R
import io.github.hecate2.sevend.data.GroupRepository
import io.github.hecate2.sevend.databinding.ActivityPlaceholderBinding
import io.github.hecate2.sevend.ui.Extras
import io.github.hecate2.sevend.ui.capture.CaptureActivity

/**
 * 结果界面（占位骨架，后续提交实现计算、时间线、全年曲线与点列编辑）。
 */
class ResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPlaceholderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val groupId = intent.getStringExtra(Extras.GROUP_ID).orEmpty()
        val group = GroupRepository.get(this).get(groupId)

        binding.title.text = group?.name ?: ""
        binding.subtitle.text = getString(
            R.string.result_placeholder,
            group?.external?.size ?: 0,
            group?.ceiling?.size ?: 0,
        )
        binding.actionButton.text = getString(R.string.result_go_capture)
        binding.actionButton.setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, groupId))
        }
        binding.backButton.setOnClickListener { finish() }
    }
}