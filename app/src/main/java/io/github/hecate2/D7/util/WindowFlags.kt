package io.github.hecate2.D7.util

import android.app.Activity
import android.view.WindowManager

/**
 * 按用户的「屏幕常亮」偏好设置本窗口。
 *
 * 三个页面都要用户举着手机盯画面——采集时要连着按快门与长按，看结果时要比对时间轴——
 * 所以默认开着；关掉时清掉标志，交回系统默认的超时策略。标志只在本窗口有焦点时生效，
 * 退到后台系统会自动恢复默认，不必在 onPause/onResume 里配对清除。
 */
fun Activity.applyKeepScreenOn(on: Boolean) {
    if (on) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
