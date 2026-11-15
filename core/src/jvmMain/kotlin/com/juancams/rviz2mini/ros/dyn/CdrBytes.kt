// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros.dyn

import us.ihmc.fastddsjava.cdr.CDRBuffer

/** A dynamic message to CDR (with its encapsulation header) and back: bags, foxglove_bridge and Zenoh. */
object CdrBytes {
    fun serialize(spec: MsgSpec, v: MsgValue): ByteArray {
        val buf = CDRBuffer()
        buf.ensureRemainingCapacity(CDRBuffer.PAYLOAD_HEADER.size + DynCodec.size(spec, v, 0) + 16)
        buf.writePayloadHeader()
        DynCodec.write(spec, v, buf)
        val bb = buf.getBufferUnsafe()
        val out = ByteArray(bb.position())
        bb.duplicate().apply { flip() }.get(out)
        return out
    }

    fun deserialize(spec: MsgSpec, data: ByteArray): MsgValue {
        val buf = CDRBuffer()
        buf.ensureRemainingCapacity(data.size + 8)
        buf.getBufferUnsafe().put(data)
        buf.rewind()
        buf.readPayloadHeader()
        return DynCodec.read(spec, buf)
    }
}
