// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package us.ihmc.jros2;

import java.lang.reflect.Field;

import us.ihmc.fastddsjava.natives.fastddsjavaGraph;

/**
 * Access to the jros2 native pointers needed by the fastddsjavaGraph functions (added in
 * the VizMini2/jros2 fork, see tools/jros2-16k): a node's participant, a publisher's DataWriter, a
 * subscription's DataReader and the native SampleInfo. It lives in the jros2 package to read the protected fields;
 * the DataWriter and the DataReader are private and are read via reflection (review when updating jros2).
 */
public final class GraphAccess
{
   private static volatile Field dataWriterField;
   private static volatile Field dataReaderField;
   /** False once the natives turn out not to have the related identity functions (natives older than the Java code). */
   private static volatile boolean relatedNatives = true;

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
      return fastddsjavaGraph.dataWriterGetLastSampleIdentity(dataWriter(publisher), identityOut);
   }

   /** For a service response, the identity of the request it answers. */
   public static void relatedSampleIdentity(SampleInfo info, byte[] identityOut)
   {
      fastddsjavaGraph.sampleInfoRelatedSampleIdentity(info.fastddsSampleInfo, identityOut);
   }

   /** The DDS identity (24 bytes) of a received sample: for a service request, what its reply must refer to. */
   public static void sampleIdentity(SampleInfo info, byte[] identityOut)
   {
      fastddsjavaGraph.sampleInfoSampleIdentity(info.fastddsSampleInfo, identityOut);
   }

   private static long dataWriter(ROS2Publisher<?> publisher) throws ReflectiveOperationException
   {
      Field f = dataWriterField;
      if (f == null)
      {
         f = ROS2Publisher.class.getDeclaredField("fastddsDataWriter");
         f.setAccessible(true);
         dataWriterField = f;
      }
      return f.getLong(publisher);
   }

   /**
    * The related_sample_identity (24 bytes) of the next sample the publisher writes, only that one; null clears it.
    * False if the natives do not have it (the sample then goes without).
    */
   public static boolean setNextRelatedSampleIdentity(ROS2Publisher<?> publisher, byte[] identity) throws ReflectiveOperationException
   {
      if (!relatedNatives)
         return false;
      try
      {
         fastddsjavaGraph.dataWriterSetNextRelatedSampleIdentity(dataWriter(publisher), identity);
         return true;
      }
      catch (UnsatisfiedLinkError e)
      {
         relatedNatives = false;
         return false;
      }
   }

   /** Whether the publisher is matched with the reader with that GUID (16 bytes); true if the natives cannot tell. */
   public static boolean isMatchedWith(ROS2Publisher<?> publisher, byte[] readerGuid) throws ReflectiveOperationException
   {
      if (!relatedNatives)
         return true;
      try
      {
         return fastddsjavaGraph.dataWriterIsMatchedWith(dataWriter(publisher), readerGuid);
      }
      catch (UnsatisfiedLinkError e)
      {
         relatedNatives = false;
         return true;
      }
   }

   /** The GUID (16 bytes) of the subscription's DataReader; false if the natives do not have it. */
   public static boolean readerGuid(ROS2Subscription<?> subscription, byte[] guidOut) throws ReflectiveOperationException
   {
      if (!relatedNatives)
         return false;
      Field f = dataReaderField;
      if (f == null)
      {
         f = ROS2Subscription.class.getDeclaredField("fastddsDataReader");
         f.setAccessible(true);
         dataReaderField = f;
      }
      try
      {
         fastddsjavaGraph.dataReaderGetGuid(f.getLong(subscription), guidOut);
         return true;
      }
      catch (UnsatisfiedLinkError e)
      {
         relatedNatives = false;
         return false;
      }
   }
}
