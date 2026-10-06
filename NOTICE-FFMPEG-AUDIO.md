# FFmpeg audio decoder notice

This fork includes a locally built Media3 FFmpeg audio decoder AAR at `app/libs/lib-decoder-ffmpeg-release.aar`.

Build provenance:
- Media3: 1.11.1, commit `8c6678b657ede1e7883fc164ef73ed483c7796c3`
- FFmpeg: n9.0, commit `d32b387f2b0a484599d4587d651891f0c63c4238`
- Android NDK: r28c / `28.2.13676358`
- AAR SHA-256: `954E074C4F9AE826DCA126CA44043FB9EA69F9E4FCAC81BE24EF5467AD5ED82F`

Enabled FFmpeg decoders:
`dca`, `ac3`, `eac3`, `mlp`, `truehd`, `flac`, `alac`, `pcm_mulaw`, `pcm_alaw`, `mp3`.

The FFmpeg build was configured with `CONFIG_GPL=0`, `CONFIG_NONFREE=0`, and `CONFIG_VERSION3=0`. FFmpeg licensing remains subject to the upstream LGPL 2.1-or-later terms and applicable component licenses. Media3 is licensed under the Apache License 2.0.

The AAR is used as the Media3 software audio extension so Wholphin can locally decode supported audio formats when hardware decode or passthrough is unavailable or when the user selects the software/extension renderer path.
