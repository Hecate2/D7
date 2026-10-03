package io.github.hecate2.D7.ui.capture

/**
 * 瞄准合法性判定（纯函数，便于单测覆盖边界）。
 *
 * 仰角需求域是 0..90，但低于 0 的读数不代表「平视而略低」，而是视线落在地面。
 * 这类读数若照旧入库，会被 [coerceIn] 夹成 0，装成一条「贴地的矮墙」——
 * 数据里看不出这个点本来是错的，事后也无法复原，所以必须在入口拦下。
 */
object AimGuard {

    /**
     * 允许记录的最低仰角（度）。
     *
     * 不取 0：远处的矮楼楼顶常在 3 度以内（3 度约等于 2 米高），
     * 卡 0 会误伤这类合法数据。取 -3 度既能放过矮楼顶，
     * 又能拦住真正瞄到地面的操作——竖持时手腕自然下垂约 -20 度。
     */
    const val MIN_EL_DEG = -3.0

    /** 常驻警告条的开启阈值（度），比 [MIN_EL_DEG] 高 2 度，起预警作用。 */
    const val WARN_ON_DEG = -1.0

    /** 警告条的关闭阈值（度），比开启时高 2 度形成滞回，姿态抖动时红条不闪。 */
    const val WARN_OFF_DEG = 1.0

    /** 瞄准仰角是否允许记录。 */
    fun canCapture(elDeg: Double): Boolean = elDeg >= MIN_EL_DEG

    /**
     * 瞄准警告条当前应否显示。[wasShown] 为上一帧状态，用于实现滞回：
     * 已显示时要用更宽松的 [WARN_OFF_DEG] 才关闭，未显示时用 [WARN_ON_DEG] 才开启。
     */
    fun shouldWarn(elDeg: Double, wasShown: Boolean): Boolean =
        if (wasShown) elDeg < WARN_OFF_DEG else elDeg < WARN_ON_DEG
}
