package io.github.hecate2.D7.ui.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 瞄准合法性与警告滞回的边界单测。
 *
 * 这两条规则决定「用户的误操作会不会变成一条看起来正常的数据」：
 * 负仰角若被夹成 0 入库，事後无法分辨那是真平视还是瞄了地面；
 * 警告条若在阈值附近来回闪，用户会学会忽略它。两处都必须锁死。
 */
class AimGuardTest {

    @Test
    fun canCapture_rejectsBelowThreshold() {
        // 竖持时手腕自然下垂约 -20 度，常见误操作区间都应被拦下
        assertFalse("瞄向地面 20 度应拒绝", AimGuard.canCapture(-20.0))
        assertFalse("瞄向地面 5 度应拒绝", AimGuard.canCapture(-5.0))
    }

    @Test
    fun canCapture_acceptsHorizonAndLowBuildings() {
        // 0 度是平视；远处的矮楼顶常在 3 度以内，不能误伤
        assertTrue("平视应允许", AimGuard.canCapture(0.0))
        assertTrue("仰角 1 度应允许", AimGuard.canCapture(1.0))
        assertTrue("仰角 3 度（矮楼顶）应允许", AimGuard.canCapture(3.0))
        assertTrue("天顶应允许", AimGuard.canCapture(90.0))
    }

    @Test
    fun canCapture_thresholdIsInclusive() {
        // 恰好等于阈值应放行：判定用 >=，边界归合法一侧
        assertTrue("恰好 -3 度应放行", AimGuard.canCapture(AimGuard.MIN_EL_DEG))
        assertFalse("略低于阈值应拒绝", AimGuard.canCapture(AimGuard.MIN_EL_DEG - 0.01))
    }

    @Test
    fun shouldWarn_turnsOnBeforeCaptureIsBlocked() {
        // 预警应早于拦截：-2 度还允许拍照，但已经进入红条区间
        assertTrue("-2 度应已显示警告", AimGuard.shouldWarn(-2.0, wasShown = false))
        assertTrue("-2 度仍允许拍照", AimGuard.canCapture(-2.0))
    }

    @Test
    fun shouldWarn_hysteresisHoldsOnAcrossThreshold() {
        // 滞回的核心：从警戒下方缓慢上穿 0 度再回来，状态不得在 -1..+1 之间翻转，
        // 否则姿态轻微抖动会让红条不停闪烁，用户会学会忽略它
        var shown = false
        for (step in -20..20) {
            shown = AimGuard.shouldWarn(step * 0.5, shown)
        }
        assertFalse("最终抬到 +10 度，警告应关闭", shown)
        // 再缓慢下穿 0 度：必须一路保持关闭，直到 -1 度才重新亮起
        for (step in 20 downTo -20) {
            shown = AimGuard.shouldWarn(step * 0.5, shown)
        }
        assertTrue("下穿到 -10 度，警告应重新亮起", shown)
    }

    @Test
    fun shouldWarn_requiresHigherElevationToTurnOff() {
        // 已显示时，要抬过 +1 度才关闭：-1..+1 区间维持显示
        assertTrue("抬到 -1 度仍应保持显示", AimGuard.shouldWarn(-1.0, wasShown = true))
        assertTrue("抬到 +0.9 度仍应保持显示", AimGuard.shouldWarn(0.9, wasShown = true))
        assertFalse("抬到 +1 度才关闭", AimGuard.shouldWarn(1.0, wasShown = true))
    }
}
