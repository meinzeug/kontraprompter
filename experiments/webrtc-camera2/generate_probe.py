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
session = replace_once(session, '  private boolean firstFrameReported;\n', '  private boolean firstFrameReported;\n  private boolean startupReported;\n')
session = replace_once(session, '  @Nullable private CameraCaptureSession captureSession;\n',
    '  @Nullable private CameraCaptureSession captureSession;\n  @Nullable private CameraDevice lastClosedCamera;\n'
    '  @Nullable private CameraCaptureSession lastClosedSession;\n')
session = replace_once(session, '      int framerate) {\n    new KpExperimentalCamera2Session', '      int framerate, int minimumFps) {\n    new KpExperimentalCamera2Session')
session = replace_once(session, '        cameraId, width, height, framerate);\n', '        cameraId, width, height, framerate, minimumFps);\n')
session = replace_once(session, '      int width, int height, int framerate) {\n    Logging.d', '      int width, int height, int framerate, int minimumFps) {\n    Logging.d')
session = replace_once(session, '    this.framerate = framerate;\n', '    this.framerate = framerate;\n    this.minimumFps = minimumFps;\n')
session = replace_once(session, '    final CaptureFormat.FramerateRange bestFpsRange =\n        CameraEnumerationAndroid.getClosestSupportedFramerateRange(framerateRanges, framerate);',
    '    final CaptureFormat.FramerateRange bestFpsRange = KpFixedFpsSelector.select(framerateRanges, minimumFps);\n'
    '    if (bestFpsRange == null) {\n      reportError("Feste Bildrate wird von dieser Kamera nicht unterstützt.");\n      return;\n    }')
session = replace_once(session, '    fpsUnitFactor = Camera2Enumerator.getFpsUnitFactor(fpsRanges);',
    '    if (fpsRanges == null || fpsRanges.length == 0) {\n      reportError("Camera has no available frame-rate ranges.");\n      return;\n    }\n'
    '    fpsUnitFactor = Camera2Enumerator.getFpsUnitFactor(fpsRanges);')
old_start_condition = '(captureSession == null) && (state != SessionState.STOPPED)'
if session.count(old_start_condition) != 2:
    raise SystemExit('upstream start-condition patch context mismatch')
session = session.replace(old_start_condition, '!startupReported && (state != SessionState.STOPPED)')
session = replace_once(session, '''    public void onDisconnected(CameraDevice camera) {
      checkIsOnCameraThread();
      final boolean startFailure = !startupReported && (state != SessionState.STOPPED);
      state = SessionState.STOPPED;
      stopInternal();
      if (startFailure) {
        callback.onFailure(FailureType.DISCONNECTED, "Camera disconnected / evicted.");
      } else {
        events.onCameraDisconnected(KpExperimentalCamera2Session.this);
      }
    }''', '''    public void onDisconnected(CameraDevice camera) {
      checkIsOnCameraThread();
      KpCameraDisconnect.dispatch(state == SessionState.STOPPED, startupReported,
          () -> closeLateCamera(camera), () -> adoptCamera(camera), () -> state = SessionState.STOPPED,
          KpExperimentalCamera2Session.this::stopInternal,
          () -> callback.onFailure(FailureType.DISCONNECTED, "Camera disconnected / evicted."),
          () -> events.onCameraDisconnected(KpExperimentalCamera2Session.this));
    }''')
session = replace_once(session, '''    public void onError(CameraDevice camera, int errorCode) {
      checkIsOnCameraThread();
      reportError(getErrorDescription(errorCode));''', '''    public void onError(CameraDevice camera, int errorCode) {
      checkIsOnCameraThread();
      if (state == SessionState.STOPPED) { closeLateCamera(camera); return; }
      adoptCamera(camera);
      reportError(getErrorDescription(errorCode));''')
session = replace_once(session, '      callback.onDone(KpExperimentalCamera2Session.this);',
    '      startupReported = true;\n      callback.onDone(KpExperimentalCamera2Session.this);')
session = replace_once(session, '    public void onOpened(CameraDevice camera) {\n      checkIsOnCameraThread();',
    '    public void onOpened(CameraDevice camera) {\n      checkIsOnCameraThread();\n'
    '      if (state == SessionState.STOPPED) { closeLateCamera(camera); return; }')
session = replace_once(session, '      surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);\n'
    '      surface = new Surface(surfaceTextureHelper.getSurfaceTexture());\n      try {',
    '      try {\n        surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);\n'
    '        surface = new Surface(surfaceTextureHelper.getSurfaceTexture());')
session = replace_once(session, '      } catch (CameraAccessException e) {\n        reportError("Failed to create capture session. " + e);',
    '      } catch (CameraAccessException | RuntimeException e) {\n        reportError("Failed to create capture session. " + e);')
session = replace_once(session, '    public void onConfigureFailed(CameraCaptureSession session) {\n      checkIsOnCameraThread();\n      session.close();',
    '    public void onConfigureFailed(CameraCaptureSession session) {\n      checkIsOnCameraThread();\n'
    '      if (state == SessionState.STOPPED) { closeLateSession(session); return; }\n'
    '      if (captureSession == null) captureSession = session;\n'
    '      else if (captureSession != session) closeLateSession(session);')
session = replace_once(session, '    public void onConfigured(CameraCaptureSession session) {\n      checkIsOnCameraThread();',
    '    public void onConfigured(CameraCaptureSession session) {\n      checkIsOnCameraThread();\n'
    '      if (state == SessionState.STOPPED) { closeLateSession(session); return; }')
session = replace_once(session, '      } catch (CameraAccessException e) {\n        reportError("Failed to start capture request. " + e);',
    '      } catch (CameraAccessException | RuntimeException e) {\n        reportError("Failed to start capture request. " + e);')
session = replace_once(session, '      surfaceTextureHelper.startListening((VideoFrame frame) -> {',
    '      try {\n      surfaceTextureHelper.startListening((VideoFrame frame) -> {')
session = replace_once(session, '      });\n      Logging.d(TAG, "Camera device successfully started.");',
    '      });\n      } catch (RuntimeException e) {\n        reportError("Failed to start camera frame listener. " + e);\n        return;\n      }\n'
    '      Logging.d(TAG, "Camera device successfully started.");')
session = replace_once(session, '  private void reportError(String error) {\n    checkIsOnCameraThread();',
    '  private void reportError(String error) {\n    checkIsOnCameraThread();\n    if (state == SessionState.STOPPED) return;')
session = replace_once(session, '    } catch (CameraAccessException | IllegalArgumentException e) {\n      reportError("getCameraCharacteristics(): " + e.getMessage());',
    '    } catch (CameraAccessException | RuntimeException e) {\n      reportError("getCameraCharacteristics(): " + e.getMessage());')
session = replace_once(session, '''    cameraOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    isCameraFrontFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
        == CameraMetadata.LENS_FACING_FRONT;

    findCaptureFormat();''', '''    try {
      cameraOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
      isCameraFrontFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
          == CameraMetadata.LENS_FACING_FRONT;
      findCaptureFormat();
    } catch (RuntimeException e) {
      reportError("Failed to inspect camera capabilities: " + e);
      return;
    }''')
session = replace_once(session, '''    surfaceTextureHelper.stopListening();

    if (captureSession != null) {
      captureSession.close();
      captureSession = null;
    }
    if (surface != null) {
      surface.release();
      surface = null;
    }
    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }

    Logging.d(TAG, "Stop done");''', '''    final CameraCaptureSession oldSession = captureSession;
    final Surface oldSurface = surface;
    final CameraDevice oldCamera = cameraDevice;
    captureSession = null; surface = null; cameraDevice = null;
    if (oldSession != null) lastClosedSession = oldSession;
    if (oldCamera != null) lastClosedCamera = oldCamera;
    boolean clean = KpCameraCleanup.shared().release(surfaceTextureHelper::stopListening,
        oldSession == null ? null : oldSession::close,
        oldSurface == null ? null : oldSurface::release,
        oldCamera == null ? null : oldCamera::close);
    if (!clean) Logging.e(TAG, "Camera cleanup uncertain; experimental restarts blocked.");
    Logging.d(TAG, "Stop done");''')
session = replace_once(session, '  private void reportError(String error) {', '''  private void adoptCamera(CameraDevice incoming) {
    cameraDevice = KpCameraOwnership.adopt(cameraDevice, incoming, this::closeLateCamera);
  }

  private void closeLateCamera(CameraDevice incoming) {
    if (incoming == null || incoming == lastClosedCamera) return;
    lastClosedCamera = incoming;
    KpCameraCleanup.shared().release(incoming::close);
  }

  private void closeLateSession(CameraCaptureSession incoming) {
    if (incoming == null || incoming == lastClosedSession) return;
    lastClosedSession = incoming;
    KpCameraCleanup.shared().release(incoming::close);
  }

  private void reportError(String error) {''')

capturer = source('Camera2Capturer', '2a82b7d455ce6e944b6db858a4f456f9d680aed0b121dc13ea2a244ae0a6dbf5')
capturer = capturer.replace('Camera2Capturer', 'KpExperimentalCamera2Capturer').replace('Camera2Session.create', 'KpExperimentalCamera2Session.create')
capturer = replace_once(capturer, '  @Nullable private final CameraManager cameraManager;\n', '  @Nullable private final CameraManager cameraManager;\n  private final int minimumFps;\n')
capturer = replace_once(capturer, 'Context context, String cameraName, CameraEventsHandler eventsHandler) {', 'Context context, String cameraName, CameraEventsHandler eventsHandler, int minimumFps) {')
capturer = replace_once(capturer, '    cameraManager = (CameraManager)', '    this.minimumFps = minimumFps;\n    cameraManager = (CameraManager)')
capturer = replace_once(capturer, 'surfaceTextureHelper, cameraName, width, height, framerate);', 'surfaceTextureHelper, cameraName, width, height, framerate, minimumFps);')
capturer = replace_once(capturer, '    KpExperimentalCamera2Session.create(createSessionCallback, events, applicationContext, cameraManager,',
    '    if (KpCameraCleanup.shared().isBlocked()) {\n'
    '      createSessionCallback.onFailure(CameraSession.FailureType.ERROR, "Kamerafreigabe ungeklärt. App neu starten.");\n'
    '      return;\n    }\n'
    '    KpExperimentalCamera2Session.create(createSessionCallback, events, applicationContext, cameraManager,')
marker = '// EXPERIMENTAL DERIVATIVE; NOT ENABLED IN THE APP. See third-party notices.\n'
(target / 'KpExperimentalCamera2Session.java').write_text(marker + session)
(target / 'KpExperimentalCamera2Capturer.java').write_text(marker + capturer)
