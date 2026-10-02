package io.github.hecate2.sevend.ui.groups

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.hecate2.sevend.databinding.ActivityGroupsBinding

/**
 * 照片组管理（应用入口）。
 * 骨架阶段占位实现，后续提交填充卡片列表、建组与长按菜单。
 */
class GroupsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupsBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}