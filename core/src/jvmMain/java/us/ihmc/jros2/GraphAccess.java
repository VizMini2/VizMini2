// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package us.ihmc.jros2;

import java.lang.reflect.Field;

import us.ihmc.fastddsjava.natives.fastddsjavaGraph;

/**
 * Access to the jros2 native pointers needed by the fastddsjavaGraph functions (added in
 * the VizMini2/jros2 fork, see tools/jros2-16k): a node's participant, a publisher's DataWriter
 * and the native SampleInfo. It lives in the jros2 package to read the protected fields; the
 * DataWriter is private and is read via reflection (review when updating jros2).
 */
public final class GraphAccess
{
   private static volatile Field dataWriterField;

   private GraphAccess()
   {
   }

   /** Live endpoints in the domain seen by the node's participant ("W|R \t topic \t type \t guid"). */
   public static String[] discoveredEndpoints(ROS2Node node)
   {
      node.closeLock.readLock().lock();
      try
      {
         if (node.closed)
         {
            return new String[0];
         }
         return fastddsjavaGraph.participantGetDiscoveredEndpoints(node.fastddsParticipant);
      }
      finally
      {
         node.closeLock.readLock().unlock();
      }
   }

   /** Live participants: "guidPrefixHex \t name \t ip1,ip2" (unicast IPs, no loopback). */
   public static String[] discoveredParticipantAddresses(ROS2Node node)
   {
      node.closeLock.readLock().lock();
      try
      {
         if (node.closed)
         {
            return new String[0];
         }
         return fastddsjavaGraph.participantGetDiscoveredParticipantAddresses(node.fastddsParticipant);
      }
      finally
      {
         node.closeLock.readLock().unlock();
      }
   }

   /** Names of the live DDS participants in the domain. */
   public static String[] discoveredParticipants(ROS2Node node)
   {
      node.closeLock.readLock().lock();
      try
      {
         if (node.closed)
         {
            return new String[0];
         }
         return fastddsjavaGraph.participantGetDiscoveredParticipantNames(node.fastddsParticipant);
      }
      finally
      {
         node.closeLock.readLock().unlock();
      }
   }

   /** Identity (24 bytes) of the last sample written by the publisher; false if it has not written yet. */
   public static boolean lastSampleIdentity(ROS2Publisher<?> publisher, byte[] identityOut) throws ReflectiveOperationException
   {
      Field f = dataWriterField;
      if (f == null)
      {
         f = ROS2Publisher.class.getDeclaredField("fastddsDataWriter");
         f.setAccessible(true);
         dataWriterField = f;
      }
      return fastddsjavaGraph.dataWriterGetLastSampleIdentity(f.getLong(publisher), identityOut);
   }

   /** For a service response, the identity of the request it answers. */
   public static void relatedSampleIdentity(SampleInfo info, byte[] identityOut)
   {
      fastddsjavaGraph.sampleInfoRelatedSampleIdentity(info.fastddsSampleInfo, identityOut);
   }
}
