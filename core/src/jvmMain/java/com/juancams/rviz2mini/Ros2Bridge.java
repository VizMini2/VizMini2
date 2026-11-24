// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini;

import geometry_msgs.Quaternion;
import geometry_msgs.TransformStamped;
import geometry_msgs.Vector3;
import tf2_msgs.TFMessage;
import us.ihmc.jros2.ROS2MessageReader;
import us.ihmc.jros2.ROS2Node;
import us.ihmc.jros2.ROS2QoSProfile;
import us.ihmc.jros2.ROS2Subscription;
import us.ihmc.jros2.ROS2Topic;

/**
 * Subscription to /tf and /tf_static with jros2's tf2_msgs/TFMessage (included since jros2 1.5;
 * with 1.2 it had to be deserialized by hand).
 */
public class Ros2Bridge {

    public interface TransformCallback {
        void onTransformReceived(String parentId, String childId, double tx, double ty, double tz,
                                 double qx, double qy, double qz, double qw, boolean isStatic, long stampNs);
    }

    /** /tf_static is published "latched": TRANSIENT_LOCAL must be requested to receive what was already published. */
    public static ROS2QoSProfile qosFor(boolean isStatic) {
        ROS2QoSProfile qos = new ROS2QoSProfile();
        qos.reliability(ROS2QoSProfile.Reliability.RELIABLE);
        qos.history(ROS2QoSProfile.History.KEEP_LAST);
        qos.depth(100);
        qos.durability(isStatic ? ROS2QoSProfile.Durability.TRANSIENT_LOCAL : ROS2QoSProfile.Durability.VOLATILE);
        return qos;
    }

    public static ROS2Subscription<TFMessage> setupTFSubscription(ROS2Node node, String topicName, boolean isStatic, TransformCallback callback) {
        ROS2Topic<TFMessage> topic = new ROS2Topic<>(topicName, TFMessage.class);
        // Each subscription is always served from the same DDS thread: we can reuse the message.
        TFMessage msg = new TFMessage();
        return node.createSubscription(topic, reader -> handleMessage(reader, msg, isStatic, callback), qosFor(isStatic));
    }

    /**
     * Called from the native DDS thread: no exception may escape from here, because it would reach
     * native code and crash the app.
     */
    private static void handleMessage(ROS2MessageReader<TFMessage> reader, TFMessage msg, boolean isStatic, TransformCallback callback) {
        try {
            if (!reader.read(msg)) return;
            for (int i = 0; i < msg.getTransforms().size(); i++) {
                TransformStamped t = msg.getTransforms().get(i);
                String child = t.getChildFrameIdAsString();
                if (child == null || child.isEmpty()) continue;
                Vector3 p = t.getTransform().getTranslation();
                Quaternion q = t.getTransform().getRotation();
                callback.onTransformReceived(
                        t.getHeader().getFrameIdAsString(), child,
                        p.getX(), p.getY(), p.getZ(), q.getX(), q.getY(), q.getZ(), q.getW(), isStatic,
                        t.getHeader().getStamp().getSec() * 1_000_000_000L + (t.getHeader().getStamp().getNanosec() & 0xFFFFFFFFL)
                );
            }
        } catch (Throwable e) {
            com.juancams.rviz2mini.platform.Log.e("VizMini2", "Error procesando " + (isStatic ? "/tf_static" : "/tf"), e);
        }
    }
}
