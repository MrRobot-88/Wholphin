# Native playback module provenance

Wholphin Android TV bundles local native playback modules so builds do not depend on private package credentials.

## Wholphin Extensions v0.2.4

Source: `damontecres/wholphin-extensions`
Tag: `v0.2.4`
Commit: `cbb4af14c8e61845440ef2281096fc05e8976027`
GitHub Actions run: `34638349445`
Artifact: `wholphin-mpv-aar` (`10277914540`)

Bundled files:

- `app/libs/lib-decoder-av1-release.aar`
  - SHA-256: `249F7DE33835F319A0D5A5AFC50C79D1215CED2CD7409AA117478E5CCF86A53B`
  - Provides Media3 dav1d AV1 software decoding fallback.
- `app/libs/wholphin-mpv-release.aar`
  - SHA-256: `5A3853DC64F2938BBFBB635F92261D0A9F4587F60FC82C31B0A69A39D5DA6432`
  - Provides the optional MPV playback backend.

The upstream `wholphin-extensions` repository is distributed under GNU GPL v2. Included native components remain subject to their respective upstream licenses.

The custom FFmpeg audio fallback build is documented separately in `NOTICE-FFMPEG-AUDIO.md`.
