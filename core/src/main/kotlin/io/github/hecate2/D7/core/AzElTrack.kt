package io.github.hecate2.D7.core

/**
 * 一串世界方向（方位角、仰角）采样点，两条等长数组按下标配对。
 *
 * 与 `List<Pair<Double, Double>>` 表达的是同一件事，但叠加层每秒重绘数十次、每帧要过
 * 四条参考弧加若干条推断段（合计上千个采样），Pair 会逐帧拆装箱两次 double。求值、
 * 叠加层与导出图三处共用这一个类型，折线的几何语义也就跟着只有一份。
 */
class AzElTrack(val azimuths: DoubleArray, val elevations: DoubleArray) {

    init {
        require(azimuths.size == elevations.size) {
            "方位采样 ${azimuths.size} 个与仰角采样 ${elevations.size} 个对不上"
        }
    }

    val size: Int get() = azimuths.size
}
