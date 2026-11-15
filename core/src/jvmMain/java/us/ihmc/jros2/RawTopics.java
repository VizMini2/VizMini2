// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package us.ihmc.jros2;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import us.ihmc.fastddsjava.natives.fastddsjava;
import us.ihmc.fastddsjava.profiles.ProfilesXML;
import us.ihmc.fastddsjava.profiles.gen.TopicProfileType;

/**
 * jros2 1.5.1 always prepends "rt" to the DDS topic name, so it cannot create the service
 * topics ("rq/xRequest", "rr/xReply"). This replicates ROS2Node.getOrCreateTopicData with an
 * arbitrary DDS name and registers it in the node under the name of `topic`: a later createPublisher /
 * createSubscription with that same ROS2Topic reuses it. The node deletes it on
 * close like any other topic.
 *
 * It lives in the jros2 package to be able to use TopicData and the node's protected fields; the
 * private ones are read via reflection (tested with jros2 1.5.1; review when updating jros2).
 */
public final class RawTopics
{
   private static final AtomicLong profileCounter = new AtomicLong();

   private RawTopics()
   {
   }

   @SuppressWarnings("unchecked")
   public static <T extends ROS2Message<T>> void bind(ROS2Node node, ROS2Topic<T> topic, String ddsTopicName) throws Exception
   {
      Field mapField = ROS2Node.class.getDeclaredField("topicDataByNameAndType");
      Field typesField = ROS2Node.class.getDeclaredField("typeRegistrationsByName");
      Field lockField = ROS2Node.class.getDeclaredField("typeRegistrationLock");
      Class<?> registrationClass = Class.forName("us.ihmc.jros2.ROS2Node$TypeRegistration");
      Constructor<?> registrationConstructor = registrationClass.getDeclaredConstructor(long.class, long.class);
      Field wrapperField = registrationClass.getDeclaredField("fastddsTopicDataWrapperType");
      Field typeSupportField = registrationClass.getDeclaredField("fastddsTypeSupport");
      for (java.lang.reflect.AccessibleObject o : new java.lang.reflect.AccessibleObject[] {mapField, typesField, lockField, registrationConstructor,
                                                                                             wrapperField, typeSupportField})
      {
         o.setAccessible(true);
      }

      String topicName = topic.getName();
      String typeName = ROS2Message.getNameFromMessageClass(topic.getType());

      node.closeLock.readLock().lock();
      try
      {
         if (node.closed)
         {
            throw new IllegalStateException("Node closed");
         }

         Map<String, Map<String, TopicData>> topicData = (Map<String, Map<String, TopicData>>) mapField.get(node);
         Map<String, Object> registrations = (Map<String, Object>) typesField.get(node);
         Object registrationLock = lockField.get(null);

         synchronized (topicData)
         {
            Map<String, TopicData> byType = topicData.get(topicName);
            if (byType == null)
            {
               byType = new HashMap<>();
               topicData.put(topicName, byType);
            }
            if (byType.containsKey(typeName))
            {
               return;
            }

            ProfilesXML profilesXML = new ProfilesXML();
            TopicProfileType topicProfile = new TopicProfileType();
            String profileName = "t_raw_" + profileCounter.getAndIncrement();
            topicProfile.setProfileName(profileName);
            profilesXML.addTopicProfile(topicProfile);
            synchronized (ProfilesXML.getLoadLock())
            {
               profilesXML.load();
            }

            Object registration = registrations.get(typeName);
            if (registration == null)
            {
               synchronized (registrationLock)
               {
                  registration = registrations.get(typeName);
                  if (registration == null)
                  {
                     long wrapper = fastddsjava.createTopicDataWrapperType(typeName, fastddsjava.CDR_LE);
                     long typeSupport = fastddsjava.createTypesupport(wrapper);
                     fastddsjava.registerType(node.fastddsParticipant, typeSupport);
                     registration = registrationConstructor.newInstance(wrapper, typeSupport);
                     registrations.put(typeName, registration);
                  }
               }
            }

            long wrapper = wrapperField.getLong(registration);
            long typeSupport = typeSupportField.getLong(registration);
            long fastddsTopic = fastddsjava.createTopic(node.fastddsParticipant, wrapper, ddsTopicName, profileName);
            if (fastddsTopic == 0)
            {
               throw new IllegalStateException("Fast DDS could not create topic " + ddsTopicName + " (" + typeName + ")");
            }
            byType.put(typeName, new TopicData(wrapper, typeSupport, fastddsTopic));
         }
      }
      finally
      {
         node.closeLock.readLock().unlock();
      }
   }
}
