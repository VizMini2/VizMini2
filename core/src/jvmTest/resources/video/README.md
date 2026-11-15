Test videos (100x60, solid #3366CC, 10 frames, a key frame every 5, parameter sets repeated and an access unit
delimiter before each frame), made with:

    ffmpeg -f lavfi -i "color=c=0x3366CC:s=100x60:r=10" -frames:v 10 -c:v libx264 -preset ultrafast -tune zerolatency \
      -pix_fmt yuv420p -x264-params "keyint=5:min-keyint=5:repeat-headers=1:aud=1" -f h264 blue_100x60.h264
    ffmpeg -f lavfi -i "color=c=0x3366CC:s=100x60:r=10" -frames:v 10 -c:v libx265 -preset ultrafast -tune zerolatency \
      -pix_fmt yuv420p -x265-params "keyint=5:min-keyint=5:repeat-headers=1:aud=1" -f hevc blue_100x60.h265
