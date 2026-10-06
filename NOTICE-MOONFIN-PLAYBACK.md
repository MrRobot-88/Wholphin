# Moonfin playback code attribution

Parts of this Wholphin fork's Android playback compatibility layer are adapted from:

- Project: Moonfin-Core
- Repository: https://github.com/Moonfin-Client/Moonfin-Core
- Donor commit: `36ed696f1d02ba240b459e953d98965ae514648c`
- License: GNU General Public License v2.0 (GPL-2.0)

Adapted/reused components include playback policy and codec compatibility work such as:

- Android Media3 audio passthrough policy logic
- HDMI / ARC / eARC audio route capability and IEC 61937 carrier probing
- App-side IEC 61937 audio packing/output for AC-3, E-AC-3, DTS, DTS-HD and TrueHD
- DTS-HD to DTS core fallback / DTS core extraction
- dav1d AV1 Media3 extension classes/native libraries used for software AV1 decoding
- Dolby Vision Profile 7 compatibility code and native libdovi integration (when present in this branch)

Wholphin itself is distributed under GPL-2.0. Source changes derived from Moonfin remain under the same compatible GPL-2.0 terms.

The Moonfin project and its contributors are not responsible for modifications made in this fork.
