package com.flurgle.camerakit;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * Created on 09.06.17.
 *
 * @author Sergey Vasilenko (vasilenko.sn@gmail.com)
 */

public class CameraHandlerProvider {

	private HandlerThread mHandlerThread = new HandlerThread("Camera");
	{
		mHandlerThread.start();
	}
	private Handler mCameraHandler = new Handler(mHandlerThread.getLooper());

	private static CameraHandlerProvider instance;

	public static Handler getHandler() {
		if (instance == null) {
			instance = new CameraHandlerProvider();
		}
		return instance.mCameraHandler;
	}
}
