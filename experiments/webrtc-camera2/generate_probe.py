#!/usr/bin/env python3
"""Derive uniquely named build-only camera classes from the verified upstream blobs."""
import hashlib
import pathlib
import sys

base = pathlib.Path(__file__).resolve().parent
if len(sys.argv) != 2:
    raise SystemExit('usage: generate_probe.py OUTPUT_DIRECTORY')
target = pathlib.Path(sys.argv[1]) / 'org' / 'webrtc'
target.mkdir(parents=True, exist_ok=True)

def source(name, sha):
    data = (base / 'source' / (name + '.upstream')).read_bytes()
    if hashlib.sha256(data).hexdigest() != sha:
        raise SystemExit('upstream source hash mismatch: ' + name)
    return data.decode('utf-8')

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise SystemExit('upstream patch context mismatch')
    return text.replace(old, new, 1)

session = source('Camera2Session', '284153d75b99d5546f8704ad672ed65d17abd6f95e10d9df613e61c2dbb90cab')
session = session.replace('Camera2Session', 'KpExperimentalCamera2Session')
session = replace_once(session, '  private final int framerate;\n', '  private final int framerate;\n  private final int minimumFps;\n')
session = replace_once(session, '      int framerate) {\n    new KpExperimentalCamera2Session', '      int framerate, int minimumFps) {\n    new KpExperimentalCamera2Session')
session = replace_once(session, '        cameraId, width, height, framerate);\n', '        cameraId, width, height, framerate, minimumFps);\n')
session = replace_once(session, '      int width, int height, int framerate) {\n    Logging.d', '      int width, int height, int framerate, int minimumFps) {\n    Logging.d')
session = replace_once(session, '    this.framerate = framerate;\n', '    this.framerate = framerate;\n    this.minimumFps = minimumFps;\n')
session = replace_once(session, '    final CaptureFormat.FramerateRange bestFpsRange =\n        CameraEnumerationAndroid.getClosestSupportedFramerateRange(framerateRanges, framerate);',
    '    final CaptureFormat.FramerateRange bestFpsRange = KpFixedFpsSelector.select(framerateRanges, minimumFps,\n        CameraEnumerationAndroid.getClosestSupportedFramerateRange(framerateRanges, framerate));')

capturer = source('Camera2Capturer', '2a82b7d455ce6e944b6db858a4f456f9d680aed0b121dc13ea2a244ae0a6dbf5')
capturer = capturer.replace('Camera2Capturer', 'KpExperimentalCamera2Capturer').replace('Camera2Session.create', 'KpExperimentalCamera2Session.create')
capturer = replace_once(capturer, '  @Nullable private final CameraManager cameraManager;\n', '  @Nullable private final CameraManager cameraManager;\n  private final int minimumFps;\n')
capturer = replace_once(capturer, 'Context context, String cameraName, CameraEventsHandler eventsHandler) {', 'Context context, String cameraName, CameraEventsHandler eventsHandler, int minimumFps) {')
capturer = replace_once(capturer, '    cameraManager = (CameraManager)', '    this.minimumFps = minimumFps;\n    cameraManager = (CameraManager)')
capturer = replace_once(capturer, 'surfaceTextureHelper, cameraName, width, height, framerate);', 'surfaceTextureHelper, cameraName, width, height, framerate, minimumFps);')
marker = '// EXPERIMENTAL DERIVATIVE; NOT ENABLED IN THE APP. See third-party notices.\n'
(target / 'KpExperimentalCamera2Session.java').write_text(marker + session)
(target / 'KpExperimentalCamera2Capturer.java').write_text(marker + capturer)
