package io.github.hecate2.D7.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CyclicBarrier

/**
 * 仓库并发写的**纯 JVM** 单测（不需要设备，`:app:testDebugUnitTest` 里跑，秒级）。
 *
 * 拍照协程与界面/后台线程可能同时改同一组，「读当前列表 → 改一处 → 写回」不串行化就会
 * 静默丢点（去掉锁后实测 300 笔只剩 281 笔）。仪器测试里另有一份同名用例，那条走真机
 * 文件系统；本条守的是同一段逻辑本身，让回归在 `./gradlew test` 阶段就暴露。
 */
class GroupRepositoryConcurrencyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun repository(name: String = "groups.json"): GroupRepository =
        GroupRepository(File(temp.root, name))

    @Test
    fun concurrentAppends_keepEveryPoint() {
        val repository = repository()
        val group = repository.createGroup("并发组", 31.23, 121.47, 0.0, "Asia/Shanghai")
        val rounds = 150
        // 每个来回两线程同时写，逼出读改写竞态
        val barrier = CyclicBarrier(2)

        fun appendLoop(tag: Int) {
            repeat(rounds) { i ->
                barrier.await()
                repository.appendPoint(
                    group.id,
                    Region.EXTERNAL,
                    PointRecord(az = (tag * 10_000 + i).toDouble(), el = 10.0),
                    viaHorizon = false,
                )
            }
        }

        val worker = Thread { appendLoop(1) }
        worker.start()
        appendLoop(2)
        worker.join()

        assertEquals(rounds * 2, repository.get(group.id)!!.external.size)
    }

    /** 建组也必须进锁：否则整组会被并发的另一次发布整个覆盖掉。 */
    @Test
    fun concurrentCreates_keepEveryGroup() {
        val repository = repository()
        val groups = 40
        val barrier = CyclicBarrier(2)

        fun createLoop() = repeat(groups) { i ->
            barrier.await()
            repository.createGroup("组$i", 31.0 + i, 121.0, 0.0, "Asia/Shanghai")
        }

        val worker = Thread { createLoop() }
        worker.start()
        createLoop()
        worker.join()

        assertEquals(groups * 2, repository.groups.value.size)
    }

    /**
     * 落盘走单线程写队列，是异步的；轮询到磁盘内容追上内存态再断言。
     *
     * gapAfter 记在「与新点相连的那个旧点」上，所以第 i 次追加带viaHorizon 时改的是第 i-1 个点：
     * 本例 [false, true, false, true, false] 的连法落在盘上应读回 [true, false, true, false, false]。
     */
    @Test
    fun appends_arePersistedToDisk() {
        val file = File(temp.root, "groups.json")
        val repository = GroupRepository(file)
        val group = repository.createGroup("落盘组", 39.9, 116.4, 0.0, "Asia/Shanghai")
        repeat(5) { i ->
            repository.appendPoint(
                group.id,
                Region.EXTERNAL,
                PointRecord(az = 170.0 + i, el = 12.0 + i),
                viaHorizon = i % 2 == 1,
            )
        }

        // 每次新建仓库都重新读盘，直到写队列把最后一个点落完
        val deadline = System.currentTimeMillis() + 5_000
        var restored: GroupRecord? = null
        while (System.currentTimeMillis() < deadline) {
            val candidate = GroupRepository(file).get(group.id)
            if (candidate != null && candidate.external.size == 5) {
                restored = candidate
                break
            }
            Thread.sleep(20)
        }
        assertEquals("落盘组", restored?.name)
        assertEquals(
            listOf(true, false, true, false, false),
            restored?.external?.map { it.gapAfter },
        )
    }

    /**
     * 删中间那个点：左右两点之间要改为直接连线，否则留下一个没有端点的经地平线段。
     * gapAfter 记在「与新点相连的那个旧点」上，故删 p1 后 p0 与 p2 都应为 false。
     */
    @Test
    fun deletePoint_clearsTheDanglingGapFlag() {
        val repository = repository()
        val group = repository.createGroup("删点组", 39.9, 116.4, 0.0, "Asia/Shanghai")
        repeat(3) { i ->
            repository.appendPoint(
                group.id,
                Region.EXTERNAL,
                PointRecord(az = 170.0 + i, el = 10.0),
                viaHorizon = true,
            )
        }
        assertEquals(listOf(true, true, false), repository.get(group.id)!!.external.map { it.gapAfter })

        repository.deletePoint(group.id, Region.EXTERNAL, 1)
        val points = repository.get(group.id)!!.external
        assertEquals(2, points.size)
        assertEquals(listOf(false, false), points.map { it.gapAfter })
    }
}