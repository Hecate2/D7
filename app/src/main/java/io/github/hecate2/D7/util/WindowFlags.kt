package io.github.hecate2.D7.util

import android.app.Activity
import android.view.WindowManager

/**
 * 前台期间禁止系统超时熄屏。
 *
 * 三个页面都要用户举着手机盯画面——采集时要连着按快门与长按，看结果时要比对时间轴，
 * 中途熄屏既打断操作又要重新解锁。FLAG_KEEP_SCREEN_ON 只在本窗口有焦点时生效，
 * 退到后台系统会自动恢复默认超时策略，所以不必再在 onPause/onResume 里配对清除。
 */
fun Activity.keepScreenOn() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}