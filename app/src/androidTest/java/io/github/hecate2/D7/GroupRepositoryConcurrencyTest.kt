package io.github.hecate2.D7

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.concurrent.CyclicBarrier

/**
 * 仓库并发写的仪器测试：拍照协程与界面/后台线程可能同时改同一组，
 * 「读当前列表 → 改一处 → 写回」不串行化就会静默丢点（实测 400 笔只剩 381 笔）。
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class GroupRepositoryConcurrencyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
    }

    @After
    fun tearDown() {
        TestSupport.deleteAllGroups(repository)
    }

    @Test
    fun concurrentAppends_keepEveryPoint() {
        val group = repository.createGroup("并发组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id)
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
}