package com.idk.frameinterp;

import android.hardware.HardwareBuffer;

interface IShizukuFrameCallback {
    void onFrame(in HardwareBuffer buffer, long timestampNs);
    void onError(String message);
}
