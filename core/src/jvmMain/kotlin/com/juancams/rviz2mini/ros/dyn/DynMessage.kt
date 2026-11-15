// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros.dyn

import us.ihmc.fastddsjava.cdr.CDRBuffer
import us.ihmc.jros2.ROS2Message

/**
 * jros2 message whose structure is known at runtime (see [DynTypes]). jros2 creates
 * instances with the empty constructor, so the structure is looked up by class.
 */
abstract class DynMessage<T : DynMessage<T>> : ROS2Message<T> {

    val spec: MsgSpec = DynTypes.specFor(javaClass)

    @Volatile var value: MsgValue = DynCodec.defaultValue(spec)

    override fun set(other: T) { value = DynCodec.copyMsg(other.value) }

    // jros2 sizes the buffer with this before serializing: it must not fall short.
    override fun calculateSizeBytes(currentAlignment: Int): Int = DynCodec.size(spec, value, currentAlignment) + 8

    override fun serialize(buffer: CDRBuffer) = DynCodec.write(spec, value, buffer)

    override fun deserialize(buffer: CDRBuffer) { value = DynCodec.read(spec, buffer) }

    override fun toString() = "${spec.name} ${DynCodec.format(spec, value)}"
}

/**
 * Assigns a [DynSlots] class to each DDS type. The assignment lasts for the whole process lifetime
 * (jros2 registers the type by its name and may have live readers/writers with it).
 */
object DynTypes {

    private val byDdsName = HashMap<String, Class<*>>()
    private val specs = HashMap<Class<*>, MsgSpec>()
    private var next = 0

    /**
     * jros2 class for `spec`. If the type already had a class, its structure is updated. The variant with the
     * Cyclone header has its own class with the same DDS type name: jros2 registers the type by name and serializes with
     * the reader's or writer's class, so both coexist.
     */
    @Synchronized
    fun classFor(spec: MsgSpec): Class<out DynMessage<*>> {
        val key = if (spec.cycloneHeader) spec.ddsName + "#cyclone" else spec.ddsName
        val existing = byDdsName[key]
        if (existing != null) {
            specs[existing] = spec
            @Suppress("UNCHECKED_CAST")
            return existing as Class<out DynMessage<*>>
        }
        if (next >= DynSlots.ALL.size) throw IllegalStateException("Too many different dynamic types (${DynSlots.ALL.size}); restart the app")
        val cls = DynSlots.ALL[next++]
        cls.getField("name").set(null, spec.ddsName)
        byDdsName[key] = cls
        specs[cls] = spec
        @Suppress("UNCHECKED_CAST")
        return cls as Class<out DynMessage<*>>
    }

    @Synchronized
    fun specFor(cls: Class<*>): MsgSpec = specs[cls] ?: throw IllegalStateException("${cls.name} has no assigned type")

    /** New message of `spec` with the given value. */
    fun newMessage(spec: MsgSpec, value: MsgValue): DynMessage<*> =
        (classFor(spec).getDeclaredConstructor().newInstance() as DynMessage<*>).also { it.value = value }
}
