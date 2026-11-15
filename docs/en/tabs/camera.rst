Camera
======

.. clip:: camera

Live images from camera topics, up to four at once.

* Works with ``sensor_msgs/Image`` and ``sensor_msgs/CompressedImage`` (JPEG/PNG), with
  ``compressedDepth`` for topics whose name contains *depth*, and with **H.264 / H.265 video**:
  ``foxglove_msgs/CompressedVideo`` (``.../foxglove``) and ``ffmpeg_image_transport_msgs/FFMPEGPacket``
  (``.../ffmpeg``), which take a fraction of the WiFi a JPEG camera needs. The phone decodes them in
  hardware; the PC with ``ffmpeg`` (``sudo apt install ffmpeg``). The RViz *Image* and *Camera*
  displays take them too.
* Raw encodings: ``rgb8``, ``bgr8``, ``rgba8``, ``bgra8``, ``mono8``, ``mono16``, ``8UC1/3/4``,
  ``16UC1``, ``32FC1``, ``yuv422`` (``uyvy``) and ``yuyv``.
* **Search** finds the image topics on the network (it also runs by itself when ROS 2 connects).
  If yours is not listed, type its name at the end of the list and tap **Add**.
* Tap topics in the list to add or remove them. One camera fills the tab, two are stacked and three
  or four share a 2×2 grid.
* Each image shows its topic, resolution, encoding and frame rate.
* **Annotations**: if there is a ``visualization_msgs/ImageMarker`` or
  ``foxglove_msgs/ImageAnnotations`` topic next to the image (in its namespace), its boxes, circles,
  lines and texts are drawn on the image by themselves. The eye button on the image shows or hides
  them.

When you create the tab you can give the topic straight away (on the phone the field suggests the
image topics it finds). Each camera subscribes with *Best effort* and keeps only the latest image, so
it never falls behind.

In a :doc:`dashboard` widget the images fill the whole widget, and the button at the top right opens
the topic list.

.. container:: vm-clear

   Tip: put several cameras side by side in a :doc:`dashboard`.
