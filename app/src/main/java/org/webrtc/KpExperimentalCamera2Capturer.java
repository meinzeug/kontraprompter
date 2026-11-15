// EXPERIMENTAL DERIVATIVE; NOT ENABLED IN THE APP. See third-party notices.
/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import androidx.annotation.Nullable;

public class KpExperimentalCamera2Capturer extends CameraCapturer {
  @Nullable private final CameraManager cameraManager;
  private final int minimumFps;

  public KpExperimentalCamera2Capturer(Context context, String cameraName, CameraEventsHandler eventsHandler, int minimumFps) {
    super(cameraName, eventsHandler, new Camera2Enumerator(context));

    this.minimumFps = minimumFps;
    cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
  }

  @Override
  protected void createCameraSession(CameraSession.CreateSessionCallback createSessionCallback,
      CameraSession.Events events, Context applicationContext,
      SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height,
      int framerate) {
    KpExperimentalCamera2Session.create(createSessionCallback, events, applicationContext, cameraManager,
        surfaceTextureHelper, cameraName, width, height, framerate, minimumFps);
  }
}
