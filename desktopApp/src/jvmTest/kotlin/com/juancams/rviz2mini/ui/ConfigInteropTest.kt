// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui

import com.juancams.rviz2mini.dashboard.DashboardGrid
import com.juancams.rviz2mini.plot.PanelKind
import com.juancams.rviz2mini.plot.PanelSpec
import com.juancams.rviz2mini.plot.PlotLayout
import com.juancams.rviz2mini.plot.SeriesSpec
import com.juancams.rviz2mini.plot.SeriesTransform
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.rviz.rvizfile.RvizImporter
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.ui.interop.ConfigFormat
import com.juancams.rviz2mini.ui.interop.ConfigInterop
import com.juancams.rviz2mini.ui.interop.FoxgloveLayout
import com.juancams.rviz2mini.ui.interop.PlotJugglerLayout
import com.juancams.rviz2mini.ui.interop.TopicTypes
import com.juancams.rviz2mini.ui.screens.CAMERA_KEY_TOPIC
import com.juancams.rviz2mini.ui.screens.DASHBOARD_KEY_LAYOUT
import com.juancams.rviz2mini.ui.screens.DashboardTemplates
import com.juancams.rviz2mini.ui.screens.MESSAGE_KEY_TOPIC
import com.juancams.rviz2mini.ui.screens.PLOT_KEY_LAYOUT
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Importing and exporting other tools' files: PlotJuggler layouts (the structure of real ones: docks of plots, curves
 * named "/topic/field" with ".N" array indices, XY plots, Lua custom series), Foxglove layouts (the exported form, the
 * default layout of Lichtblick, and the SDK's programmatic form) and RViz 2 files, with round trips.
 */
class ConfigInteropTest {
    private val types = mapOf(
        "/odom" to "nav_msgs/msg/Odometry", "/scan" to "sensor_msgs/msg/LaserScan", "/camera/image_raw" to "sensor_msgs/msg/Image",
        "/map" to "nav_msgs/msg/OccupancyGrid", "/robot/battery" to "sensor_msgs/msg/BatteryState",
    )
    private var savedLookup = TopicTypes.lookup
    private var savedAll = TopicTypes.all

    @Before fun setup() {
        UiTestSetup.install()
        savedLookup = TopicTypes.lookup; savedAll = TopicTypes.all
        TopicTypes.lookup = { types[it] }
        TopicTypes.all = { types.keys.toList() }
    }

    @After fun restore() { TopicTypes.lookup = savedLookup; TopicTypes.all = savedAll }

    private fun layoutOf(t: TabSpec) = PlotLayout.fromJson(t.settings[PLOT_KEY_LAYOUT])

    // A layout as PlotJuggler 3 saves it (trimmed: the plugins it does not use are left out).
    private val pjLayout = """<?xml version='1.0' encoding='UTF-8'?>
<root>
 <tabbed_widget name="Main Window" parent="main_window">
  <Tab tab_name="Robot" containers="1">
   <Container>
    <DockSplitter sizes="0.333;0.333;0.333" count="3" orientation="-">
     <DockArea name="...">
      <plot flip_y="false" style="Lines" flip_x="false" mode="TimeSeries">
       <range top="1" bottom="0" left="0" right="10"/>
       <limitY/>
       <curve name="/odom/pose/pose/position/x" color="#1f77b4"/>
       <curve name="/scan/ranges.3" color="#d62728"/>
      </plot>
     </DockArea>
     <DockArea name="...">
      <plot flip_y="false" style="Lines" flip_x="false" mode="XYPlot">
       <range top="1" bottom="0" left="0" right="1"/>
       <limitY/>
       <curve name="/odom/pose/pose/position/[x;y]" curve_x="/odom/pose/pose/position/x" curve_y="/odom/pose/pose/position/y" color="#1ac938"/>
      </plot>
     </DockArea>
     <DockArea name="...">
      <plot flip_y="false" style="Lines" flip_x="false" mode="TimeSeries">
       <range top="1" bottom="0" left="0" right="10"/>
       <limitY/>
       <curve name="speed" color="#ff7f0e"/>
      </plot>
     </DockArea>
    </DockSplitter>
   </Container>
  </Tab>
  <currentTabIndex index="0"/>
 </tabbed_widget>
 <use_relative_time_offset enabled="1"/>
 <Plugins>
  <plugin ID="ROS2 Topic Subscriber">
   <use_header_stamp value="false"/>
   <selected_topics value="/odom;/scan"/>
  </plugin>
 </Plugins>
 <previouslyLoaded_Streamer name="ROS2 Topic Subscriber"/>
 <customMathEquations>
  <snippet name="speed">
   <global>count = 0</global>
   <function>return math.sqrt(value*value + v1*v1)</function>
   <linked_source>/odom/twist/twist/linear/x</linked_source>
   <additional_sources>
    <v1>/odom/twist/twist/linear/y</v1>
   </additional_sources>
  </snippet>
 </customMathEquations>
 <snippets/>
</root>
"""

    @Test fun detectsEachFormat() {
        assertEquals(ConfigFormat.PLOTJUGGLER, ConfigInterop.detect(pjLayout))
        assertEquals(ConfigFormat.FOXGLOVE, ConfigInterop.detect(lichtblickDefault))
        assertEquals(ConfigFormat.FOXGLOVE, ConfigInterop.detect(sdkLayout))
        assertEquals(ConfigFormat.RVIZ, ConfigInterop.detect(rvizFile()))
        val tab = TabSpec.create(TabType.TELEOP, "Joy")
        assertEquals(ConfigFormat.VIZMINI2, ConfigInterop.detect(ConfigInterop.export(tab, listOf(tab), ConfigFormat.VIZMINI2).text))
        assertEquals(null, ConfigInterop.detect("hello"))
    }

    @Test fun plotJugglerLayoutBecomesAPlotTab() {
        val r = ConfigInterop.import(pjLayout, "robot.xml")
        assertEquals(ConfigFormat.PLOTJUGGLER, r.format)
        val tab = r.tabs.single()
        assertEquals(TabType.PLOT, tab.type)
        val panels = layoutOf(tab).panels
        assertEquals(listOf(PanelKind.TIME, PanelKind.XY, PanelKind.TIME), panels.map { it.kind })
        val first = panels[0].series
        assertEquals(listOf("/odom" to "pose.pose.position.x", "/scan" to "ranges[3]"), first.map { it.topic to it.field })
        assertEquals("sensor_msgs/msg/LaserScan", first[1].type)
        assertEquals(listOf("pose.pose.position.x", "pose.pose.position.y"), panels[1].series.map { it.field })
        // The custom series: its inputs come along, and the Lua is the same function(time, value, v1).
        val speed = panels[2].series.last()
        assertEquals("speed", speed.label); assertTrue(speed.isScript)
        assertEquals("return math.sqrt(value*value + v1*v1)", speed.script); assertEquals("count = 0", speed.scriptGlobals)
        assertEquals(listOf("/odom/twist/twist/linear/x", "/odom/twist/twist/linear/y"), speed.inputList)
        assertEquals(listOf("twist.twist.linear.x", "twist.twist.linear.y"), panels[2].series.dropLast(1).map { it.field })
        assertTrue(r.notes.toString(), r.notes.isEmpty())
    }

    @Test fun plotJugglerRoundTrip() {
        val layout = PlotLayout(listOf(
            PanelSpec(series = listOf(SeriesSpec(topic = "/odom", type = "nav_msgs/msg/Odometry", field = "twist.twist.linear.x", label = "vx"),
                SeriesSpec(topic = "/scan", type = "sensor_msgs/msg/LaserScan", field = "ranges[2]", label = "r2"),
                SeriesSpec(label = "double", script = "return 2*value", inputs = "vx"))),
            PanelSpec(kind = PanelKind.XY, series = listOf(SeriesSpec(topic = "/odom", field = "pose.pose.position.x"), SeriesSpec(topic = "/odom", field = "pose.pose.position.y"))),
        ))
        val tab = TabSpec.create(TabType.PLOT, "Speeds").withSetting(PLOT_KEY_LAYOUT, layout.toJson())
        val out = ConfigInterop.export(tab, listOf(tab), ConfigFormat.PLOTJUGGLER)
        assertTrue(out.text, "<curve name=\"/scan/ranges.2\"" in out.text && "curve_x=\"/odom/pose/pose/position/x\"" in out.text)
        assertTrue(out.text, "<selected_topics value=\"/odom;/scan\"/>" in out.text && "<snippet name=\"double\">" in out.text)
        assertTrue(out.notes.toString(), out.notes.isEmpty())
        // Back: the same topics, fields, kinds and script.
        val back = layoutOf(ConfigInterop.import(out.text).tabs.single()).panels
        assertEquals(listOf(PanelKind.TIME, PanelKind.XY), back.map { it.kind })
        assertTrue(back[0].series.map { it.topic to it.field }.containsAll(listOf("/odom" to "twist.twist.linear.x", "/scan" to "ranges[2]")))
        assertEquals("return 2*value", back[0].series.first { it.isScript }.script)
        assertEquals(listOf("pose.pose.position.x", "pose.pose.position.y"), back[1].series.map { it.field })
    }

    @Test fun plotJugglerNames() {
        val notes = ArrayList<String>()
        assertEquals("/robot/battery" to "percentage", PlotJugglerLayout.splitName("/robot/battery/percentage", types.keys, notes))
        assertEquals("/diag" to "status[2].values[0].value", PlotJugglerLayout.splitName("/diag/status.2/values.0/value", emptyList(), notes))
        assertEquals(1, notes.size) // "/diag" was a guess
        assertEquals("/diag/status.2/values.0/value", PlotJugglerLayout.joinName("/diag", "status[2].values[0].value"))
        assertEquals("/scan/ranges.-1", PlotJugglerLayout.joinName("/scan", "ranges[-1]"))
    }

    // Lichtblick's default layout (packages/suite-base/.../defaultLayout.ts), with a Plot added.
    private val lichtblickDefault = """{
      "configById": {
        "3D!18i6zy7": {"layers": {"845139cb": {"visible": true, "label": "Grid", "instanceId": "845139cb", "layerId": "foxglove.Grid"}},
                       "topics": {"/scan": {"visible": true}, "/map": {"visible": true}, "/odom": {"visible": false}}, "followTf": "base_link"},
        "RawMessagesVirtual!os6rgs": {"topicPath": "/odom.pose.pose"},
        "Image!3mnp456": {"imageMode": {"imageTopic": "/camera/image_raw"}},
        "Plot!p1": {"paths": [{"value": "/odom.twist.twist.linear.x.@derivative", "enabled": true, "timestampMethod": "receiveTime", "color": "#ff7043"},
                              {"value": "/robot/battery.percentage", "enabled": false, "timestampMethod": "receiveTime"}], "xAxisVal": "timestamp"}
      },
      "globalVariables": {}, "userNodes": {}, "playbackConfig": {"speed": 1},
      "layout": {"first": "3D!18i6zy7", "second": {"first": "Image!3mnp456", "second": {"first": "RawMessagesVirtual!os6rgs", "second": "Plot!p1", "direction": "column"},
                 "direction": "column", "splitPercentage": 30}, "direction": "row", "splitPercentage": 70}
    }"""

    @Test fun foxgloveLayoutBecomesADashboard() {
        val r = ConfigInterop.import(lichtblickDefault, "default.json")
        val dash = r.tabs.first()
        assertEquals(TabType.DASHBOARD, dash.type)
        val widgets = r.tabs.drop(1).associateBy { it.type }
        assertEquals(setOf(TabType.RVIZ, TabType.CAMERA, TabType.MESSAGE, TabType.PLOT), widgets.keys)
        val rviz = widgets.getValue(TabType.RVIZ)
        val displays = DisplayList.fromJson(rviz.settings["displays"])!!
        assertEquals(listOf(DisplayType.GRID, DisplayType.TF, DisplayType.LASER_SCAN, DisplayType.MAP), displays.map { it.type })
        assertEquals("base_link", rviz.settings["fixedFrame"])
        assertEquals("/camera/image_raw", widgets.getValue(TabType.CAMERA).settings[CAMERA_KEY_TOPIC])
        assertEquals("/odom", widgets.getValue(TabType.MESSAGE).settings[MESSAGE_KEY_TOPIC])
        val s = layoutOf(widgets.getValue(TabType.PLOT)).allSeries.single()
        assertEquals("/odom" to "twist.twist.linear.x", s.topic to s.field); assertEquals(SeriesTransform.DERIVATIVE, s.transform)
        // Where the splits put them: the 3D view on the left 70 %, the image on top of the right column.
        val items = DashboardGrid.fromJson(dash.settings[DASHBOARD_KEY_LAYOUT]).associateBy { it.id }
        val r3d = items.getValue(rviz.id); val rimg = items.getValue(widgets.getValue(TabType.CAMERA).id)
        assertEquals(0.0, r3d.x, 0.0); assertEquals(8.0, r3d.w, 1.0)
        assertTrue(rimg.x >= 8 && rimg.y == 0.0)
    }

    // The SDK's programmatic form (foxglove.layouts: Layout(content=SplitContainer(...)) serialized as it does).
    private val sdkLayout = """{"version": 1, "variables": null, "userScripts": null, "content": {"type": "split", "direction": "row", "items": [
        {"proportion": 2, "content": {"type": "panel", "panelType": "ThreeDee", "config": {"topics": {"/scan": {"visible": true}}, "followTf": "odom"}, "title": null}},
        {"proportion": 1, "content": {"type": "tabs", "selectedTabIndex": 0, "tabs": [
          {"title": "Plot View", "content": {"type": "stack", "panels": [
            {"panel": {"type": "panel", "panelType": "Plot", "config": {"paths": [{"value": "/odom.pose.pose.position.x", "enabled": true, "timestampMethod": "receiveTime"}],
                       "xAxisVal": "custom", "xAxisPath": {"value": "/odom.pose.pose.position.y", "enabled": true}}, "title": "Path"}, "size": 0.6},
            {"panel": {"type": "panel", "panelType": "Teleop", "config": {"topic": "/cmd_vel"}, "title": null}, "size": 0.4}]}},
          {"title": "Image View", "content": {"type": "panel", "panelType": "Image", "config": {}, "title": null}}]}}]}}"""

    @Test fun foxgloveSdkLayout() {
        val r = ConfigInterop.import(sdkLayout)
        val widgets = r.tabs.filter { it.dashboardId == r.tabs.first().id }
        assertEquals(listOf(TabType.RVIZ, TabType.PLOT, TabType.TELEOP), widgets.map { it.type })
        // The Foxglove tab that was not open comes too, as a tab of its own.
        val other = r.tabs.last()
        assertEquals(TabType.CAMERA, other.type); assertEquals("Layout · Image View", other.name)
        val xy = layoutOf(widgets[1]).panels.single()
        assertEquals(PanelKind.XY, xy.kind)
        assertEquals(listOf("pose.pose.position.y", "pose.pose.position.x"), xy.series.map { it.field })
        assertEquals("Path", widgets[1].name)
        val items = DashboardGrid.fromJson(r.tabs.first().settings[DASHBOARD_KEY_LAYOUT]).associateBy { it.id }
        assertEquals(8.0, items.getValue(widgets[0].id).w, 0.0)
        assertTrue(items.getValue(widgets[2].id).y > items.getValue(widgets[1].id).y)
    }

    /** As in Husarion's ROSbot layouts: Publish buttons, some inside a Foxglove Tab panel with other tabs. */
    private val publishLayout = """{"configById": {
        "Publish!a": {"topicName": "/led/left", "datatype": "std_msgs/msg/Bool", "buttonText": "LED1 Turn ON", "buttonColor": "#00A871", "value": "{\n  \"data\": true\n}"},
        "Publish!b": {"topicName": "/cmd_vel", "datatype": "geometry_msgs/Twist", "buttonText": "Test Motors", "buttonColor": "#a80000",
                      "value": "{\"linear\": {\"x\": 1.0}, \"angular\": {\"z\": -0.5}}", "foxglovePanelTitle": "Test Motors"},
        "Plot!p": {"paths": [{"value": "/battery.voltage", "enabled": true}]},
        "Teleop!t": {"topic": "/cmd_vel"},
        "Tab!x": {"activeTabIdx": 0, "tabs": [{"title": "Plots", "layout": "Plot!p"}, {"title": "Production", "layout": {"first": "Publish!b", "second": "Teleop!t", "direction": "row"}}]}},
      "layout": {"first": "Publish!a", "second": "Tab!x", "direction": "row"}}"""

    @Test fun foxglovePublishPanelsBecomeButtonsAndEveryTabComes() {
        val r = ConfigInterop.import(publishLayout, "rosbot.json")
        val main = r.tabs.first()
        assertEquals(TabType.DASHBOARD, main.type); assertEquals("rosbot", main.name)
        val led = r.tabs.filter { it.dashboardId == main.id }.first { it.type == TabType.BUTTONS }
        val b = com.juancams.rviz2mini.buttons.ButtonList.fromJson(led.settings[com.juancams.rviz2mini.ui.screens.BUTTONS_KEY]).single()
        assertEquals("LED1 Turn ON", b.label); assertEquals("/led/left", b.target); assertEquals("std_msgs/msg/Bool", b.type)
        assertEquals(true, JSONObject(b.values).getBoolean("data"))
        assertEquals(2, b.color)  // green
        // The "Production" tab: a dashboard of its own, with the motors button (a ROS 1 style type, normalized) and the teleop.
        val prod = r.tabs.first { it.name == "rosbot · Production" }
        val inProd = r.tabs.filter { it.dashboardId == prod.id }
        assertEquals(setOf(TabType.BUTTONS, TabType.TELEOP), inProd.map { it.type }.toSet())
        val motors = com.juancams.rviz2mini.buttons.ButtonList.fromJson(inProd.first { it.type == TabType.BUTTONS }.settings[com.juancams.rviz2mini.ui.screens.BUTTONS_KEY]).single()
        assertEquals("geometry_msgs/msg/Twist", motors.type); assertEquals(-0.5, JSONObject(motors.values).getJSONObject("angular").getDouble("z"), 0.0)
        assertEquals(3, motors.color)  // red
        // No widget covers another, even where Foxglove's panels are smaller than a widget can be.
        for (d in r.tabs.filter { it.type == TabType.DASHBOARD }) {
            val items = DashboardGrid.fromJson(d.settings[DASHBOARD_KEY_LAYOUT])
            assertTrue(d.name, items.none { a -> items.any { b -> a.overlaps(b) } })
        }
        // And back to Foxglove: the button is a Publish panel again.
        val out = JSONObject(ConfigInterop.export(prod, r.tabs, ConfigFormat.FOXGLOVE).text).getJSONObject("configById")
        val pub = out.keys().asSequence().first { it.startsWith("Publish!") }.let { out.getJSONObject(it) }
        assertEquals("/cmd_vel", pub.getString("topicName")); assertEquals("Test Motors", pub.getString("buttonText"))
        assertEquals(1.0, JSONObject(pub.getString("value")).getJSONObject("linear").getDouble("x"), 0.0)
    }

    @Test fun foxgloveRoundTripOfTheShowcase() {
        val tabs = DashboardTemplates.build(DashboardTemplates.Template.SHOWCASE)
        val out = ConfigInterop.export(tabs.first(), tabs, ConfigFormat.FOXGLOVE)
        val json = JSONObject(out.text)
        val ids = json.getJSONObject("configById").keys().asSequence().map { it.substringBefore('!') }.toList()
        assertTrue(ids.toString(), ids.containsAll(listOf("3D", "Image", "Plot", "Teleop", "DiagnosticSummary", "TopicGraph", "Gauge")))
        // TF tree has no Foxglove panel: said.
        assertTrue(out.notes.toString(), out.notes.any { "TF tree" in it })
        val back = ConfigInterop.import(out.text).tabs
        val kinds = back.drop(1).map { it.type }.toSet()
        assertTrue(kinds.toString(), kinds.containsAll(setOf(TabType.RVIZ, TabType.CAMERA, TabType.PLOT, TabType.TELEOP, TabType.DIAGNOSTICS, TabType.NODE_GRAPH, TabType.INDICATORS)))
        // The XY panel (x, y of the odometry) stays XY.
        assertTrue(back.filter { it.type == TabType.PLOT }.flatMap { layoutOf(it).panels }.any { it.kind == PanelKind.XY })
    }

    @Test fun foxglovePaths() {
        val notes = ArrayList<String>()
        val p = FoxgloveLayout.parsePath("/diagnostics.status[:]{name==\"motor\"}.level", notes)!!
        assertEquals("/diagnostics", p.topic); assertEquals("status[0]{name==\"motor\"}.level", p.field)
        assertEquals(1, notes.size)
        assertEquals(180 / Math.PI, FoxgloveLayout.parsePath("/imu.angular_velocity.z.@rad2deg", notes)!!.scale, 1e-9)
        assertEquals("/diagnostics.status{name==\"motor\"}.level", FoxgloveLayout.formatPath("/diagnostics", "status{name==motor}.level"))
        assertEquals("/odom", FoxgloveLayout.formatPath("/odom", ""))
    }

    private fun rvizFile() = listOf(File("../core/src/jvmTest/resources/rviz/nav2_like.rviz"), File("core/src/jvmTest/resources/rviz/nav2_like.rviz")).first { it.exists() }.readText()

    @Test fun rvizFileBecomesAnRvizTabAndBack() {
        val r = ConfigInterop.import(rvizFile(), "nav2_like.rviz")
        val tab = r.tabs.single()
        assertEquals(TabType.RVIZ, tab.type); assertEquals("nav2_like", tab.name)
        val displays = DisplayList.fromJson(tab.settings["displays"])!!
        assertTrue(displays.size >= 3)
        val out = ConfigInterop.export(tab, listOf(tab), ConfigFormat.RVIZ)
        val again = RvizImporter.import(out.text)
        assertEquals(displays.map { it.type }, again.displays.map { it.type })
        assertNotNull(again.fixedFrame)
        assertEquals(listOf(ConfigFormat.VIZMINI2, ConfigFormat.RVIZ, ConfigFormat.FOXGLOVE), ConfigInterop.exportFormats(tab, listOf(tab)))
    }

    /** With VIZMINI2_INTEROP_OUT=dir: the showcase dashboard exported for each tool, to open them in the real ones. */
    @Test fun writeSamples() {
        val dir = System.getenv("VIZMINI2_INTEROP_OUT")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        dir.mkdirs()
        val tabs = DashboardTemplates.build(DashboardTemplates.Template.SHOWCASE)
        for (f in listOf(ConfigFormat.FOXGLOVE, ConfigFormat.PLOTJUGGLER)) {
            val r = ConfigInterop.export(tabs.first(), tabs, f)
            File(dir, "showcase." + if (f == ConfigFormat.PLOTJUGGLER) "plotjuggler.xml" else "foxglove.json").writeText(r.text)
        }
        val plot = TabSpec.create(TabType.PLOT, "Robot").withSetting(PLOT_KEY_LAYOUT, PlotLayout(listOf(
            PanelSpec(series = listOf(SeriesSpec(topic = "/odom", field = "twist.twist.linear.x", label = "vx"), SeriesSpec(topic = "/scan", field = "ranges[2]", label = "r2"),
                SeriesSpec(label = "double_vx", script = "return 2*value", inputs = "vx"))),
            PanelSpec(kind = PanelKind.XY, series = listOf(SeriesSpec(topic = "/odom", field = "pose.pose.position.x"), SeriesSpec(topic = "/odom", field = "pose.pose.position.y"))))).toJson())
        File(dir, "robot.plotjuggler.xml").writeText(ConfigInterop.export(plot, listOf(plot), ConfigFormat.PLOTJUGGLER).text)
        // And files from the real tools (VIZMINI2_INTEROP_IN: a folder): what each one becomes, in import-summary.txt.
        val inDir = System.getenv("VIZMINI2_INTEROP_IN")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        File(dir, "import-summary.txt").printWriter().use { w ->
            for (f in inDir.listFiles().orEmpty().sortedBy { it.name }) {
                val r = runCatching { ConfigInterop.import(f.readText(), f.name) }.getOrElse { w.println("${f.name}: ERROR ${it.message}"); continue }
                w.println("${f.name}: ${r.format} -> " + r.tabs.joinToString { t ->
                    t.type.name + (if (t.type == TabType.PLOT) layoutOf(t).panels.joinToString(prefix = "[", postfix = "]") { p -> p.kind.name + p.series.map { it.topic + ":" + it.field } } else "")
                })
                r.notes.forEach { w.println("   note: $it") }
            }
        }
    }
}
