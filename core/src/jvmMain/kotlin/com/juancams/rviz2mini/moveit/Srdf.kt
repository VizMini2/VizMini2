// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.moveit

import com.juancams.rviz2mini.rviz.geom.UrdfModel
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What is needed from the MoveIt SRDF (`/robot_description_semantic`): the planning groups and their named
 * states ("ready", "open"...). Each group's joints are obtained with the URDF ([groupJoints]): a group can
 * be given by chain (base → tip), by links, by joints or by other groups.
 */
class Srdf(val robot: String, val groups: List<Group>, val states: List<GroupState>) {

    class Group(val name: String, val joints: List<String>, val links: List<String>, val chains: List<Pair<String, String>>, val subgroups: List<String>)
    class GroupState(val group: String, val name: String, val values: Map<String, Double>)

    fun group(name: String) = groups.firstOrNull { it.name == name }
    fun statesOf(group: String) = states.filter { it.group == group }

    /** A group's tip (that of its chain, or that of a subgroup with a chain), or null. */
    fun tipLink(name: String, seen: Set<String> = emptySet()): String? {
        val g = group(name) ?: return null
        if (name in seen) return null
        g.chains.lastOrNull()?.let { return it.second }
        for (s in g.subgroups) tipLink(s, seen + name)?.let { return it }
        return null
    }

    /** A group's moving joints, in URDF order (no fixed or mimic ones). */
    fun groupJoints(name: String, urdf: UrdfModel, seen: Set<String> = emptySet()): List<String> {
        val g = group(name) ?: return emptyList()
        if (name in seen) return emptyList()
        val byChild = urdf.joints.associateBy { it.child }
        val out = LinkedHashSet<String>()
        out += g.joints
        for (l in g.links) byChild[l]?.let { out += it.name }
        for ((base, tip) in g.chains) {
            // From the tip toward the base: the parent joint of each link.
            var link = tip
            val chain = ArrayList<String>()
            var guard = 0
            while (link != base && guard++ < 512) {
                val j = byChild[link] ?: break
                chain += j.name
                link = j.parent
            }
            out += chain.reversed()
        }
        for (s in g.subgroups) out += groupJoints(s, urdf, seen + name)
        val movable = urdf.joints.filter { it.movable }.map { it.name }
        return movable.filter { it in out }
    }

    companion object {
        fun parse(xml: String): Srdf {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val root = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
            fun Element.kids(tag: String): List<Element> {
                val out = ArrayList<Element>()
                val n = childNodes
                for (i in 0 until n.length) (n.item(i) as? Element)?.takeIf { it.tagName == tag }?.let { out += it }
                return out
            }
            val groups = root.kids("group").map { g ->
                Group(g.getAttribute("name"), g.kids("joint").map { it.getAttribute("name") }, g.kids("link").map { it.getAttribute("name") },
                    g.kids("chain").map { it.getAttribute("base_link") to it.getAttribute("tip_link") }, g.kids("group").map { it.getAttribute("name") })
            }
            val states = root.kids("group_state").map { s ->
                GroupState(s.getAttribute("group"), s.getAttribute("name"),
                    s.kids("joint").mapNotNull { j -> j.getAttribute("value").trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()?.let { j.getAttribute("name") to it } }.toMap())
            }
            return Srdf(root.getAttribute("name"), groups, states)
        }
    }
}
