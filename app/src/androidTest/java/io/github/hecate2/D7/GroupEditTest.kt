package io.github.hecate2.D7

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onIdle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.groups.GroupsActivity
import io.github.hecate2.D7.ui.result.ResultActivity
import io.github.hecate2.D7.util.Format
import io.github.hecate2.D7.util.Zones
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「编辑照片组」的仪器测试（模拟器运行，无需真机）：结果页元信息旁的「编辑」与照片组列表的
 * 长按菜单进的是同一个对话框，改的是同一份记录。
 *
 * 另外守着这个对话框的一条设计约定：界面上不放「时区」字样——输入框用数值样例当占位符，
 * 两个快捷键只画图标（按下去会填成什么写在 contentDescription 里）。术语一多就得翻八种
 * 语言，而这里全是数字，八种语言共用一份。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GroupEditTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private lateinit var groupId: String
    private var scene: ActivityScenario<*>? = null

    @Before
    fun seed() {
        TestSupport.deleteAllGroups(repository)
        val group = repository.createGroup(
            "在线测试组", 31.23, 121.47, 0.0, "Asia/Shanghai",
        )
        groupId = group.id
        repository.appendPoint(
            groupId,
            Region.EXTERNAL,
            PointRecord(az = 180.0, el = 10.0),
            viaHorizon = false,
        )
    }

    @After
    fun tearDown() {
        scene?.close()
        TestSupport.deleteAllGroups(repository)
    }

    private fun launchResult() {
        val intent = Intent(context, ResultActivity::class.java).putExtra(Extras.GROUP_ID, groupId)
        scene = ActivityScenario.launch<ResultActivity>(intent)
    }

    private fun launchGroups() {
        scene = ActivityScenario.launch<GroupsActivity>(GroupsActivity::class.java)
    }

    private fun openEditorFromResult() {
        launchResult()
        onView(withId(R.id.editGroup)).perform(scrollTo(), click())
        onView(withText(R.string.edit_group_title)).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    private fun openEditorFromGroups() {
        launchGroups()
        val name = repository.get(groupId)!!.name
        onView(withText(name)).perform(longClick())
        onView(withText(R.string.menu_edit)).inRoot(isPlatformPopup()).perform(click())
        onView(withText(R.string.edit_group_title)).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    private fun confirm() {
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())
        onIdle()
    }



    /** 进焦点即全选：从别处点回组名 / 经纬度，文字整段被选中，覆盖输入不必先长按全选。 */
    @Test
    fun editor_selectsAllWhenAFieldGainsFocus() {
        openEditorFromResult()
        onView(withId(R.id.latInput)).inRoot(isDialog())
            .perform(click(), closeSoftKeyboard())
        onView(withId(R.id.latInput)).inRoot(isDialog()).check(matches(TestSupport.selectedAll()))
        // 焦点移到别的框再点回来，走的是「重新获得焦点」那条路
        onView(withId(R.id.zoneInput)).inRoot(isDialog())
            .perform(click(), closeSoftKeyboard())
        onView(withId(R.id.latInput)).inRoot(isDialog())
            .perform(click(), closeSoftKeyboard())
        onView(withId(R.id.latInput)).inRoot(isDialog()).check(matches(TestSupport.selectedAll()))
        onView(withId(R.id.nameInput)).inRoot(isDialog())
            .perform(click(), closeSoftKeyboard())
        onView(withId(R.id.nameInput)).inRoot(isDialog()).check(matches(TestSupport.selectedAll()))
    }

    /**
     * 已经全选之后再点同一个框，**不再全选**，而是把光标落到点击处。
     * 整段覆盖与「改一两个字符」两种意图都得满足，第二次点击就是在表达后一种。
     */
    @Test
    fun editor_tappingAnAlreadySelectedFieldPlacesTheCaret() {
        openEditorFromResult()
        onView(withId(R.id.latInput)).inRoot(isDialog())
            .perform(click(), closeSoftKeyboard())
        onView(withId(R.id.latInput)).inRoot(isDialog()).check(matches(TestSupport.selectedAll()))
        onView(withId(R.id.latInput)).inRoot(isDialog())
            .perform(click(), closeSoftKeyboard())
        onView(withId(R.id.latInput)).inRoot(isDialog()).check(matches(TestSupport.noSelection()))
    }

    /**
     * 时区框**不**全选：`Asia/Kolkata` 这类值常要改的是后半截，一进焦点就全选反而得先取消选区。
     * 点一下只应落一个光标。
     */
    @Test
    fun editor_zoneFieldDoesNotSelectAllOnTap() {
        openEditorFromResult()
        onView(withId(R.id.zoneInput)).inRoot(isDialog())
            .perform(replaceText("Asia/Kolkata"), closeSoftKeyboard())
        onView(withId(R.id.zoneInput)).inRoot(isDialog())
            .perform(click(), closeSoftKeyboard())
        onView(withId(R.id.zoneInput)).inRoot(isDialog()).check(matches(TestSupport.noSelection()))
        // 文字没被全选，也就没被替换掉
        onView(withId(R.id.zoneInput)).inRoot(isDialog()).check(matches(withText("Asia/Kolkata")))
    }

    /** 经纬度右侧的半球后缀跟着输入的正负走。 */
    @Test
    fun editor_hemisphereSuffixFollowsTheSign() {
        openEditorFromResult()
        onView(withId(R.id.lonSuffix)).inRoot(isDialog())
            .check(matches(withText(Format.longitudeSuffix(121.47))))
        onView(withId(R.id.latSuffix)).inRoot(isDialog())
            .check(matches(withText(Format.latitudeSuffix(31.23))))

        onView(withId(R.id.lonInput)).inRoot(isDialog())
            .perform(replaceText("-74.006"), closeSoftKeyboard())
        onView(withId(R.id.latInput)).inRoot(isDialog())
            .perform(replaceText("-33.87"), closeSoftKeyboard())
        onView(withId(R.id.lonSuffix)).inRoot(isDialog()).check(matches(withText("°W")))
        onView(withId(R.id.latSuffix)).inRoot(isDialog()).check(matches(withText("°S")))
    }

    @Test
    fun resultEditor_prefillsCurrentRecord() {
        openEditorFromResult()
        onView(withId(R.id.nameInput)).inRoot(isDialog())
            .check(matches(withText("在线测试组")))
        onView(withId(R.id.lonInput)).inRoot(isDialog()).check(matches(withText("121.470000")))
        onView(withId(R.id.latInput)).inRoot(isDialog()).check(matches(withText("31.230000")))
        onView(withId(R.id.zoneInput)).inRoot(isDialog()).check(matches(withText("Asia/Shanghai")))
    }

    /** 改经纬度与时区后落库，并且结果页元信息那一行跟着换掉。 */
    @Test
    fun resultEditor_changesCoordinatesAndZone() {
        openEditorFromResult()
        onView(withId(R.id.lonInput)).inRoot(isDialog())
            .perform(replaceText("-74.006"), closeSoftKeyboard())
        onView(withId(R.id.latInput)).inRoot(isDialog())
            .perform(replaceText("40.7128"), closeSoftKeyboard())
        onView(withId(R.id.zoneInput)).inRoot(isDialog())
            .perform(replaceText("America/New_York"), closeSoftKeyboard())
        confirm()

        val group = TestSupport.waitFor {
            repository.get(groupId)?.takeIf { it.zoneId == "America/New_York" }
        }
        assertEquals(-74.006, group.lon, 1e-9)
        assertEquals(40.7128, group.lat, 1e-9)
        // 元信息沿用组记录里的时区显示，不必重进页面；地点是经度在前、纬度在后
        onView(withId(R.id.resultMeta)).check(matches(withText(containsString("74.01°W 40.71°N"))))
        onView(withId(R.id.resultMeta)).check(matches(withText(containsString("America/New_York"))))
    }

    /** 「用本机时区」图标键：填进去的就是手机当前时区。 */
    @Test
    fun resultEditor_deviceShortcutFillsSystemZone() {
        openEditorFromResult()
        onView(withId(R.id.zoneInput)).inRoot(isDialog())
            .perform(replaceText("Europe/Berlin"), closeSoftKeyboard())
        onView(withId(R.id.zoneDeviceButton)).inRoot(isDialog()).perform(click())
        onView(withId(R.id.zoneInput)).inRoot(isDialog())
            .check(matches(withText(Zones.system())))
        confirm()
        TestSupport.waitFor {
            repository.get(groupId)?.takeIf { it.zoneId == Zones.system() }
        }
    }

    /** 「按经度推算」图标键读的是输入框里**当前**的经度，不是打开对话框时的旧值。 */
    @Test
    fun resultEditor_longitudeShortcutReadsTheEditedLongitude() {
        openEditorFromResult()
        onView(withId(R.id.lonInput)).inRoot(isDialog())
            .perform(replaceText("-74.006"), closeSoftKeyboard())
        onView(withId(R.id.zoneLongitudeButton)).inRoot(isDialog()).perform(click())
        onView(withId(R.id.zoneInput)).inRoot(isDialog())
            .check(matches(withText(Zones.fromLongitude(-74.006))))
        confirm()
        TestSupport.waitFor {
            repository.get(groupId)?.takeIf { it.zoneId == Zones.fromLongitude(-74.006) }
        }
    }

    /**
     * 界面上与时区相关的字样只有数值样例，两个快捷键只画图标。
     * 图标说不出会填成什么，所以把「按下去写进输入框的那个值」放进 contentDescription。
     *
     * 样例值特意选带半小时的时区：偏移量可以有分钟这件事，看一眼就知道。
     */
    @Test
    fun zoneRow_usesNumbersAndIconsInsteadOfTranslatedWords() {
        openEditorFromResult()
        onView(withId(R.id.zoneInput)).inRoot(isDialog())
            .check(matches(withHint(R.string.label_timezone_hint)))
        assertEquals("+05:30", context.getString(R.string.label_timezone_hint))
        onView(withId(R.id.zoneDeviceButton)).inRoot(isDialog())
            .check(matches(withContentDescription(Zones.system())))
        onView(withId(R.id.zoneLongitudeButton)).inRoot(isDialog())
            .check(matches(withContentDescription(Zones.fromLongitude(121.47))))
    }

    @Test
    fun resultEditor_rejectsBadZoneAndBadLatitudeWithoutWriting() {
        openEditorFromResult()
        val before = repository.get(groupId)!!

        onView(withId(R.id.zoneInput)).inRoot(isDialog())
            .perform(replaceText("北京"), closeSoftKeyboard())
        confirm()
        onView(withText(R.string.edit_group_title)).inRoot(isDialog()).check(matches(isDisplayed()))
        assertEquals(before.zoneId, repository.get(groupId)!!.zoneId)

        onView(withId(R.id.zoneInput)).inRoot(isDialog())
            .perform(replaceText("Asia/Shanghai"), closeSoftKeyboard())
        onView(withId(R.id.latInput)).inRoot(isDialog())
            .perform(replaceText("999"), closeSoftKeyboard())
        confirm()
        onView(withText(R.string.edit_group_title)).inRoot(isDialog()).check(matches(isDisplayed()))
        assertEquals(before.lat, repository.get(groupId)!!.lat, 1e-9)
        assertEquals(before.lon, repository.get(groupId)!!.lon, 1e-9)
        // 组名也没被顺手写进去
        assertEquals(before.name, repository.get(groupId)!!.name)
    }

    /** 列表页长按菜单的「编辑」进的是同一个对话框，改名照旧管用。 */
    @Test
    fun groupsList_longPressEditsNameAndCoordinates() {
        openEditorFromGroups()
        onView(withId(R.id.nameInput)).inRoot(isDialog()).perform(replaceText("客厅阳台"))
        onView(withId(R.id.lonInput)).inRoot(isDialog())
            .perform(replaceText("2.3522"), closeSoftKeyboard())
        confirm()

        val group = TestSupport.waitFor {
            repository.get(groupId)?.takeIf { it.name == "客厅阳台" }
        }
        assertEquals(2.3522, group.lon, 1e-9)
        onView(withText("客厅阳台")).check(matches(isDisplayed()))
    }

    /** 空组名仍被拦下，且经纬度不会被顺手写进去。 */
    @Test
    fun groupsList_emptyNameKeepsDialogOpen() {
        openEditorFromGroups()
        onView(withId(R.id.lonInput)).inRoot(isDialog())
            .perform(replaceText("-74.006"), closeSoftKeyboard())
        onView(withId(R.id.nameInput)).inRoot(isDialog()).perform(replaceText(""))
        confirm()
        onView(withText(R.string.edit_group_title)).inRoot(isDialog()).check(matches(isDisplayed()))
        val group = repository.get(groupId)!!
        assertEquals("在线测试组", group.name)
        assertEquals(121.47, group.lon, 1e-9)
    }
}
