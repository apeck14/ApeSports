# Offline playback fixtures

These original synthetic fixtures contain a solid-color video and generated tone, with no external footage. They are included only in the test APK, never the app APK. `wide.mp4` is 16:9; `standard.mp4` is 4:3. Tests loop the 30-second clips. Both use AVC video and AAC audio to exercise real decoders without network or provider dependencies.

Regenerate with FFmpeg:

```sh
ffmpeg -y -f lavfi -i 'color=c=0x258348:s=320x180:r=30' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 30 -c:v libx264 -pix_fmt yuv420p -preset veryfast -c:a aac -b:a 32k -movflags +faststart wide.mp4
ffmpeg -y -f lavfi -i 'color=c=0x255c83:s=240x180:r=30' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 30 -c:v libx264 -pix_fmt yuv420p -preset veryfast -c:a aac -b:a 32k -movflags +faststart standard.mp4
```
