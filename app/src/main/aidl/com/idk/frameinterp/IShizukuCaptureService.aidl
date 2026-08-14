package com.idk.frameinterp;

import com.idk.frameinterp.IShizukuFrameCallback;

interface IShizukuCaptureService {
    void startCapture(int targetUid, int width, int height, int maxFps, IShizukuFrameCallback callback) = 1;
    void stopCapture() = 2;
    void destroy() = 16777114;
}
