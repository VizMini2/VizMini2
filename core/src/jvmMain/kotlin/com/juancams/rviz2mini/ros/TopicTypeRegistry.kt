// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros

/**
 * jros2 1.2.1 creates one DDS topic per (name, type) and never releases it during the node's lifetime.
 * If a reader/writer is created with the same name and ANOTHER type, Fast DDS does not create the topic and jros2
 * passes a null pointer to create_datareader: SIGSEGV and the app crashes (verified on the JVM).
 *
 * This registry remembers which type has been used with each name on the current node to avoid that.
 */
class TopicTypeRegistry {

    private val types = HashMap<String, String>()

    /** True if `name` has not been used or was used with this same type. */
    fun isCompatible(name: String, typeName: String): Boolean = types[name].let { it == null || it == typeName }

    fun typeOf(name: String): String? = types[name]

    fun register(name: String, typeName: String) {
        types.putIfAbsent(name, typeName)
    }

    fun clear() = types.clear()

    companion object {
        /**
         * Splits the requests into groups without repeated names (one group per temporary DDS node),
         * preserving order. This way the same name can be probed with several types without clashes.
         */
        fun <T> groupWithoutNameClashes(items: List<T>, nameOf: (T) -> String): List<List<T>> {
            val groups = ArrayList<MutableList<T>>()
            val namesPerGroup = ArrayList<MutableSet<String>>()
            for (item in items) {
                val name = nameOf(item)
                val i = namesPerGroup.indexOfFirst { name !in it }
                if (i >= 0) {
                    groups[i].add(item); namesPerGroup[i].add(name)
                } else {
                    groups.add(mutableListOf(item)); namesPerGroup.add(mutableSetOf(name))
                }
            }
            return groups
        }
    }
}
