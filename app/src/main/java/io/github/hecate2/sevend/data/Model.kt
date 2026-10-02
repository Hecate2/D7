package io.github.hecate2.sevend.data

import android.content.Context
import io.github.hecate2.sevend.core.Angles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 拍摄分区：外部建筑区 / 自家天花板区。 */
enum class Region { EXTERNAL, CEILING }

/** 一个拍摄点。gapAfter 表示该点与拍摄序下一个点之间为经地平线推断段。 */
@Serializable
data class PointRecord(
    val az: Double,
    val el: Double,
    val gapAfter: Boolean = false,
    val photoUri: String? = null,
    val takenAt: Long = 0L,
)

/** 一组照片：一个站位（窗、阳台）的全部测量。 */
@Serializable
data class GroupRecord(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val altitude: Double = 0.0,
    val zoneId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val external: List<PointRecord> = emptyList(),
    val ceiling: List<PointRecord> = emptyList(),
) {
    fun regionList(region: Region): List<PointRecord> =
        if (region == Region.EXTERNAL) external else ceiling
}

/** 落盘结构。 */
@Serializable
data class Store(
    val version: Int = 1,
    val groups: List<GroupRecord> = emptyList(),
)

/**
 * 照片组仓库：全部组数据以 JSON 存放于 filesDir/groups.json，先写临时文件再原子改名。
 *
 * 组记录按值快照处理，任何修改都生成新记录并经 [groups] 重新发射，界面据此刷新。
 */
class GroupRepository private constructor(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _groups = MutableStateFlow<List<GroupRecord>>(emptyList())
    val groups: StateFlow<List<GroupRecord>> = _groups.asStateFlow()

    init {
        _groups.value = readStore().groups
    }

    fun get(id: String): GroupRecord? = _groups.value.firstOrNull { it.id == id }

    fun createGroup(
        name: String,
        lat: Double,
        lon: Double,
        altitude: Double,
        zoneId: String,
    ): GroupRecord {
        val now = System.currentTimeMillis()
        val group = GroupRecord(
            id = UUID.randomUUID().toString(),
            name = name,
            lat = lat,
            lon = lon,
            altitude = altitude,
            zoneId = zoneId,
            createdAt = now,
            updatedAt = now,
        )
        publish(_groups.value + group)
        return group
    }

    fun renameGroup(id: String, name: String) = update(id) { it.copy(name = name) }

    fun deleteGroup(id: String) {
        publish(_groups.value.filterNot { it.id == id })
    }

    fun deletePoint(groupId: String, region: Region, index: Int) = update(groupId) { group ->
        val list = group.regionList(region).toMutableList()
        if (index !in list.indices) return@update group
        list.removeAt(index)
        // 删除后前后两点改为直接连线，避免悬空的经地平线段
        if (index - 1 in list.indices) {
            list[index - 1] = list[index - 1].copy(gapAfter = false)
        }
        group.withRegion(region, list)
    }

    /**
     * 追加一个拍摄点，并同时决定它与上一点之间的连线模式：
     * viaHorizon 为 true（长按快门）则上一点的 gapAfter 置 true，否则清为直接连线。
     */
    fun appendPoint(
        groupId: String,
        region: Region,
        point: PointRecord,
        viaHorizon: Boolean,
    ) = update(groupId) { group ->
        val list = group.regionList(region).toMutableList()
        if (list.isNotEmpty()) {
            list[list.size - 1] = list[list.size - 1].copy(gapAfter = viaHorizon)
        }
        list.add(point.copy(gapAfter = false))
        group.withRegion(region, list)
    }

    /** 修改第 startIndex 个点与下一点之间的连线模式（经地平线或直接连线）。 */
    fun setSegmentViaHorizon(groupId: String, region: Region, startIndex: Int, viaHorizon: Boolean) =
        update(groupId) { group ->
            val list = group.regionList(region).toMutableList()
            if (startIndex !in list.indices) return@update group
            list[startIndex] = list[startIndex].copy(gapAfter = viaHorizon)
            group.withRegion(region, list)
        }

    /** 强行改写某点的角度（照片与拍摄时间保留）；方位规范化到 [0, 360)，仰角限制到 [0, 90]。 */
    fun updatePointAngles(groupId: String, region: Region, index: Int, az: Double, el: Double) =
        update(groupId) { group ->
            val list = group.regionList(region).toMutableList()
            if (index !in list.indices) return@update group
            list[index] = list[index].copy(
                az = Angles.normalize360(az),
                el = el.coerceIn(0.0, 90.0),
            )
            group.withRegion(region, list)
        }

    /** 整区替换点列（批量编辑用，是否保留照片由调用方决定）。 */
    fun setRegionPoints(groupId: String, region: Region, points: List<PointRecord>) =
        update(groupId) { group -> group.withRegion(region, points) }

    private fun update(id: String, transform: (GroupRecord) -> GroupRecord) {
        val current = _groups.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = transform(current[index]).copy(updatedAt = System.currentTimeMillis())
        publish(current.toMutableList().also { it[index] = updated })
    }

    private fun publish(groups: List<GroupRecord>) {
        _groups.value = groups
        persist(Store(groups = groups))
    }

    private fun GroupRecord.withRegion(region: Region, list: List<PointRecord>): GroupRecord =
        if (region == Region.EXTERNAL) copy(external = list) else copy(ceiling = list)

    private fun readStore(): Store {
        if (!file.exists()) return Store()
        return try {
            json.decodeFromString(Store.serializer(), file.readText())
        } catch (_: Exception) {
            Store()
        }
    }

    private fun persist(store: Store) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Store.serializer(), store))
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        @Volatile
        private var instance: GroupRepository? = null

        fun get(context: Context): GroupRepository =
            instance ?: synchronized(this) {
                instance ?: GroupRepository(File(context.filesDir, "groups.json"))
                    .also { instance = it }
            }
    }
}