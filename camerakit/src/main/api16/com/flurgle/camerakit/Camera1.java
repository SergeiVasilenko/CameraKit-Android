package com.flurgle.camerakit;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static com.flurgle.camerakit.CameraKit.Constants.FLASH_OFF;
import static com.flurgle.camerakit.CameraKit.Constants.FOCUS_CONTINUOUS;
import static com.flurgle.camerakit.CameraKit.Constants.FOCUS_OFF;
import static com.flurgle.camerakit.CameraKit.Constants.FOCUS_TAP;
import static com.flurgle.camerakit.CameraKit.Constants.METHOD_STANDARD;
import static com.flurgle.camerakit.CameraKit.Constants.METHOD_STILL;

@SuppressWarnings("deprecation")
public class Camera1 extends CameraImpl {

    private static final String TAG = "Camera1";

    private static final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.GERMAN);

    private static final int FOCUS_AREA_SIZE_DEFAULT = 300;
    private static final int FOCUS_METERING_AREA_WEIGHT_DEFAULT = 1000;
    private static final int DELAY_MILLIS_BEFORE_RESETTING_FOCUS = 3000;

    private int mCameraId;
    private Camera mCamera;
    private Camera.Parameters mCameraParameters;
    private Camera.CameraInfo mCameraInfo;
    private Size mPreviewSize;
    private Size mCaptureSize;
    private MediaRecorder mMediaRecorder;
    private File mVideoFile;
    private Camera.AutoFocusCallback mAutofocusCallback;

    private int mDisplayOrientation;

    @Facing
    private int mFacing;

    @Flash
    private int mFlash;

    @Focus
    private int mFocus;

    @Method
    private int mMethod;

    @Zoom
    private int mZoom;

    @VideoQuality
    private int mVideoQuality;

    private int mJpegQuality;

    private Handler mHandler;

    private boolean mIsAudioEnabled;

    Camera1(CameraListener callback, PreviewImpl preview, Handler handler) {
        super(callback, preview);
        // same looper but another task queue
        mHandler = new Handler(handler.getLooper());
        preview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged() {
                if (mCamera != null) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setupPreview();
                            adjustCameraParameters();
                        }
                    });
                }
            }
        });

        mCameraInfo = new Camera.CameraInfo();

    }

    // CameraImpl:

    @Override
    void start() {
        setFacing(mFacing);
        openCamera();
        if (mPreview.isReady()) setupPreview();
        mCamera.startPreview();
    }

    @Override
    void stop() {
        if (mCamera != null) mCamera.stopPreview();
        mHandler.removeCallbacksAndMessages(null);
        releaseCamera();
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        this.mDisplayOrientation = displayOrientation;
    }

    @Override
    void setFacing(@Facing int facing) {
        int internalFacing = new ConstantMapper.Facing(facing).map();
        if (internalFacing == -1) {
            return;
        }

        for (int i = 0, count = Camera.getNumberOfCameras(); i < count; i++) {
            Camera.getCameraInfo(i, mCameraInfo);
            if (mCameraInfo.facing == internalFacing) {
                mCameraId = i;
                mFacing = facing;
                break;
            }
        }

        if (mFacing == facing && isCameraOpened()) {
            stop();
            start();
        }
    }

    @Override
    void setFlash(@Flash final int flash) {
        Log.i(TAG, "setFlash: " + flash);
        if (mCameraParameters != null) {
            // reset parameters
            mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            mCamera.setParameters(mCameraParameters);

            // set flash. Delay needs to change FLASH_MODE_TORCH to FLASH_MODE_ON.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mCameraParameters != null) {
                        List<String> flashes = mCameraParameters.getSupportedFlashModes();
                        String internalFlash = new ConstantMapper.Flash(flash).map();
                        if (flashes != null && flashes.contains(internalFlash)) {
                            mCameraParameters.setFlashMode(internalFlash);
                            mFlash = flash;
                        } else {
                            String currentFlash = new ConstantMapper.Flash(mFlash).map();
                            if (flashes == null || !flashes.contains(currentFlash)) {
                                mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                                mFlash = FLASH_OFF;
                            }
                        }
                        mCamera.setParameters(mCameraParameters);
                    } else {
                        mFlash = flash;
                    }
                }
            }, 200);
        } else {
            mFlash = flash;
        }
    }

    @Override
    void setFocus(@Focus int focus) {
        this.mFocus = focus;
        switch (focus) {
            case FOCUS_CONTINUOUS:
                if (mCameraParameters != null) {
                    detachFocusTapListener();
                    final List<String> modes = mCameraParameters.getSupportedFocusModes();
                    if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    } else {
                        setFocus(FOCUS_OFF);
                    }
                }
                break;

            case FOCUS_TAP:
                if (mCameraParameters != null) {
                    attachFocusTapListener();
                    final List<String> modes = mCameraParameters.getSupportedFocusModes();
                    if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    }
                }
                break;

            case FOCUS_OFF:
                if (mCameraParameters != null) {
                    detachFocusTapListener();
                    final List<String> modes = mCameraParameters.getSupportedFocusModes();
                    if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
                    } else if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
                    } else {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    }
                }
                break;
        }
    }

    @Override
    void setMethod(@Method int method) {
        this.mMethod = method;
    }

    @Override
    void setZoom(@Zoom int zoom) {
        this.mZoom = zoom;
    }

    @Override
    void setVideoQuality(int videoQuality) {
        this.mVideoQuality = videoQuality;
    }

    @Override
    void setJpegQuality(int jpegQuality) {
        mJpegQuality = jpegQuality;
        if (mCameraParameters != null) {
            mCameraParameters.setJpegQuality(jpegQuality);
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    void captureImage() {
        switch (mMethod) {
            case METHOD_STANDARD:
                mCamera.takePicture(null, null, null, new Camera.PictureCallback() {
                    @Override
                    public void onPictureTaken(byte[] data, Camera camera) {
                        int cameraRotation = (calculateCameraRotation(mDisplayOrientation)
                                + (mFacing == CameraKit.Constants.FACING_FRONT ? 180 : 0)) % 360;
                        Log.d(TAG, "onPictureTaken: jpeg: before: " + data.length + ", after: " + data.length
                                + ", rotation: " + cameraRotation);
                        mCameraListener.onPictureTaken(data, cameraRotation);
                        camera.startPreview();
                    }
                });
                break;

            case METHOD_STILL:
                mCamera.setOneShotPreviewCallback(new Camera.PreviewCallback() {
                    @Override
                    public void onPreviewFrame(byte[] data, Camera camera) {
                        new Thread(new ProcessStillTask(data, camera, calculateCaptureRotation(), new ProcessStillTask.OnStillProcessedListener() {
                            @Override
                            public void onStillProcessed(final YuvImage yuv) {
                                mCameraListener.onPictureTaken(yuv);
                            }
                        })).start();
                    }
                });
                break;
        }
    }

    @Override
    void startVideo() {
        initMediaRecorder();
        prepareMediaRecorder();
        mMediaRecorder.start();
    }

    @Override
    void endVideo() {
        mMediaRecorder.stop();
        mMediaRecorder.release();
        mMediaRecorder = null;
        mCameraListener.onVideoTaken(mVideoFile);
    }

    @Override
    boolean isVideoRecording() {
        return mMediaRecorder != null;
    }

    @Override
    Size getCaptureResolution() {
        if (mCaptureSize == null && mCameraParameters != null) {
            TreeSet<Size> sizes = new TreeSet<>();
            for (Camera.Size size : mCameraParameters.getSupportedPictureSizes()) {
                sizes.add(new Size(size.width, size.height));
            }

            TreeSet<AspectRatio> aspectRatios = findCommonAspectRatios(
                    mCameraParameters.getSupportedPreviewSizes(),
                    mCameraParameters.getSupportedPictureSizes()
            );
            AspectRatio targetRatio = aspectRatios.size() > 0 ? aspectRatios.last() : null;

            Iterator<Size> descendingSizes = sizes.descendingIterator();
            Size size;
            while (descendingSizes.hasNext() && mCaptureSize == null) {
                size = descendingSizes.next();
                if (targetRatio == null || targetRatio.matches(size)) {
                    mCaptureSize = size;
                    break;
                }
            }
        }

        return mCaptureSize;
    }

    @Override
    Size getPreviewResolution() {
        if (mPreviewSize == null && mCameraParameters != null) {
            TreeSet<Size> sizes = new TreeSet<>();
            for (Camera.Size size : mCameraParameters.getSupportedPreviewSizes()) {
                sizes.add(new Size(size.width, size.height));
            }

            TreeSet<AspectRatio> aspectRatios = findCommonAspectRatios(
                    mCameraParameters.getSupportedPreviewSizes(),
                    mCameraParameters.getSupportedPictureSizes()
            );
            AspectRatio targetRatio = aspectRatios.size() > 0 ? aspectRatios.last() : null;

            Iterator<Size> descendingSizes = sizes.descendingIterator();
            Size size;
            while (descendingSizes.hasNext() && mPreviewSize == null) {
                size = descendingSizes.next();
                if (targetRatio == null || targetRatio.matches(size)) {
                    mPreviewSize = size;
                    break;
                }
            }
        } else {
            if (mCameraParameters == null) {
                Log.w(TAG, "getPreviewResolution: mCameraParameters is null");
            }
        }

        return mPreviewSize;
    }

    @Override
    boolean isCameraOpened() {
        return mCamera != null;
    }

    // Internal:

    private void openCamera() {
        if (mCamera != null) {
            releaseCamera();
        }

        mCamera = Camera.open(mCameraId);
        mCameraParameters = mCamera.getParameters();

        adjustCameraParameters();
        int rotation = calculateCameraRotation(mDisplayOrientation);
        mCamera.setDisplayOrientation(
                rotation
        );
//        mCamera.setDisplayOrientation(calculatePreviewRotation());

        mCameraListener.onCameraOpened();
    }

    private void setupPreview() {
        try {
            if (mPreview.getOutputClass() == SurfaceHolder.class) {
                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
            } else {
                mCamera.setPreviewTexture(mPreview.getSurfaceTexture());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            Log.d(TAG, "releaseCamera: ");
            mCamera.release();
            mCamera = null;
            mCameraParameters = null;
            mPreviewSize = null;
            mCaptureSize = null;
            mCameraListener.onCameraClosed();
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    private int calculateCameraRotation(int rotation) {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (mCameraInfo.orientation + rotation) % 360) % 360;
        } else {
            return (mCameraInfo.orientation - rotation + 360) % 360;
        }
    }

    private int calculatePreviewRotation() {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return ((mCameraInfo.orientation - mDisplayOrientation) + 360 + 180) % 360;
        } else {
            return (mCameraInfo.orientation - mDisplayOrientation + 360) % 360;
        }
    }

    private int calculateCaptureRotation() {
        int previewRotation = calculatePreviewRotation();
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            //Front is flipped
            return (previewRotation + 180 + 2*mDisplayOrientation + 720) %360;
        } else {
            return previewRotation;
        }
    }

    private void adjustCameraParameters() {
        boolean invertPreviewSizes = mDisplayOrientation%180 != 0;
        mPreview.setTruePreviewSize(
                invertPreviewSizes? getPreviewResolution().getHeight() : getPreviewResolution().getWidth(),
                invertPreviewSizes? getPreviewResolution().getWidth() : getPreviewResolution().getHeight()
        );

        mCameraParameters.setPreviewSize(
                getPreviewResolution().getWidth(),
                getPreviewResolution().getHeight()
        );

        mCameraParameters.setPictureSize(
                getCaptureResolution().getWidth(),
                getCaptureResolution().getHeight()
        );

//        int rotation = calculateCaptureRotation();
//        mCameraParameters.setRotation(rotation);

        mCameraParameters.setJpegQuality(mJpegQuality);

        int cameraRotation = (calculateCameraRotation(mDisplayOrientation)
                        + (mFacing == CameraKit.Constants.FACING_FRONT ? 180 : 0)) % 360;

        // it doesn't work on some devices (do not remove it - it is just for example, which doesn't work)
//        mCameraParameters.setRotation(
//                (calculateCameraRotation(mDisplayOrientation)
//                        + (mFacing == CameraKit.Constants.FACING_FRONT ? 180 : 0)) % 360
//        );

        Log.d(TAG, "adjustCameraParameters: setRotation: " + cameraRotation);

        setFocus(mFocus);
        setFlash(mFlash);

        mCamera.setParameters(mCameraParameters);
    }

    private TreeSet<AspectRatio> findCommonAspectRatios(List<Camera.Size> previewSizes, List<Camera.Size> captureSizes) {
        Set<AspectRatio> previewAspectRatios = new HashSet<>();
        for (Camera.Size size : previewSizes) {
            if (size.width >= CameraKit.Internal.screenHeight && size.height >= CameraKit.Internal.screenWidth) {
                previewAspectRatios.add(AspectRatio.of(size.width, size.height));
            }
        }

        Set<AspectRatio> captureAspectRatios = new HashSet<>();
        for (Camera.Size size : captureSizes) {
            captureAspectRatios.add(AspectRatio.of(size.width, size.height));
        }

        TreeSet<AspectRatio> output = new TreeSet<>();
        for (AspectRatio aspectRatio : previewAspectRatios) {
            if (captureAspectRatios.contains(aspectRatio)) {
                output.add(aspectRatio);
            }
        }

        return output;
    }

    private void initMediaRecorder() {
        mMediaRecorder = new MediaRecorder();
        mCamera.unlock();

        mMediaRecorder.setCamera(mCamera);

        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        boolean isAudioEnabled =
                ContextCompat.checkSelfPermission(mPreview.getView().getContext(),
                                                  Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (isAudioEnabled) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        }

        CamcorderProfile profile = getCamcorderProfile(mVideoQuality);

        mMediaRecorder.setOutputFormat(profile.fileFormat);
        mMediaRecorder.setVideoEncoder(profile.videoCodec);
        mMediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        mMediaRecorder.setVideoFrameRate(profile.videoFrameRate);
        mMediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        if (isAudioEnabled) {
            mMediaRecorder.setAudioChannels(profile.audioChannels);
            mMediaRecorder.setAudioEncoder(profile.audioCodec);
            mMediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
            mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);
        }


        File cacheDir = getCacheDir();
        mVideoFile = new File(cacheDir, createVideoFileName());
        mMediaRecorder.setOutputFile(mVideoFile.getAbsolutePath());
        mMediaRecorder.setOrientationHint(mCameraInfo.orientation);
    }

    private File getCacheDir() {
        File cacheDir;
        Context context = mPreview.getView().getContext();
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            cacheDir = context.getExternalCacheDir();
        } else {
            cacheDir = context.getCacheDir();
        }
        return cacheDir;
    }

    private String createVideoFileName() {
        return "video-" + mDateFormat.format(new Date()) + ".mp4";
    }

    private void prepareMediaRecorder() {
        try {
            mMediaRecorder.prepare();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private CamcorderProfile getCamcorderProfile(@VideoQuality int videoQuality) {
        CamcorderProfile camcorderProfile = null;
        switch (videoQuality) {
            case CameraKit.Constants.VIDEO_QUALITY_480P:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_480P)) {
                    camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_480P);
                } else {
                    return getCamcorderProfile(CameraKit.Constants.VIDEO_QUALITY_LOWEST);
                }
                break;

            case CameraKit.Constants.VIDEO_QUALITY_720P:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_720P)) {
                    camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_720P);
                } else {
                    return getCamcorderProfile(CameraKit.Constants.VIDEO_QUALITY_480P);
                }
                break;

            case CameraKit.Constants.VIDEO_QUALITY_1080P:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_1080P)) {
                    camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_1080P);
                } else {
                    return getCamcorderProfile(CameraKit.Constants.VIDEO_QUALITY_720P);
                }
                break;

            case CameraKit.Constants.VIDEO_QUALITY_2160P:
                try {
                    camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_2160P);
                } catch (Exception e) {
                    return getCamcorderProfile(CameraKit.Constants.VIDEO_QUALITY_HIGHEST);
                }
                break;

            case CameraKit.Constants.VIDEO_QUALITY_HIGHEST:
                camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_HIGH);
                break;

            case CameraKit.Constants.VIDEO_QUALITY_LOWEST:
                camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_LOW);
                break;
        }

        return camcorderProfile;
    }

    void setTapToAutofocusListener(Camera.AutoFocusCallback callback) {
        if (this.mFocus != FOCUS_TAP) {
            throw new IllegalArgumentException("Please set the camera to FOCUS_TAP.");
        }

        this.mAutofocusCallback = callback;
    }

    private int getFocusAreaSize() {
        return FOCUS_AREA_SIZE_DEFAULT;
    }

    private int getFocusMeteringAreaWeight() {
        return FOCUS_METERING_AREA_WEIGHT_DEFAULT;
    }

    private void detachFocusTapListener() {
        mPreview.getView().setOnTouchListener(null);
    }

    private void attachFocusTapListener() {
        mPreview.getView().setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (mCamera != null) {
                        Camera.Parameters parameters = mCamera.getParameters();
                        String focusMode = parameters.getFocusMode();
                        Rect rect = calculateFocusArea(event.getX(), event.getY());
                        List<Camera.Area> meteringAreas = new ArrayList<>();
                        meteringAreas.add(new Camera.Area(rect, getFocusMeteringAreaWeight()));
                        if (parameters.getMaxNumFocusAreas() != 0 && focusMode != null &&
                            (focusMode.equals(Camera.Parameters.FOCUS_MODE_AUTO) ||
                            focusMode.equals(Camera.Parameters.FOCUS_MODE_MACRO) ||
                            focusMode.equals(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ||
                            focusMode.equals(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
                        ) {
                            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                            parameters.setFocusAreas(meteringAreas);
                            if (parameters.getMaxNumMeteringAreas() > 0) {
                                parameters.setMeteringAreas(meteringAreas);
                            }
                            if(!parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                                return false; //cannot autoFocus
                            }
                            mCamera.setParameters(parameters);
                            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                                @Override
                                public void onAutoFocus(boolean success, Camera camera) {
                                    resetFocus(success, camera);
                                }
                            });
                        } else if (parameters.getMaxNumMeteringAreas() > 0) {
                            if(!parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                                return false; //cannot autoFocus
                            }
                            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                            parameters.setFocusAreas(meteringAreas);
                            parameters.setMeteringAreas(meteringAreas);

                            mCamera.setParameters(parameters);
                            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                                @Override
                                public void onAutoFocus(boolean success, Camera camera) {
                                    resetFocus(success, camera);
                                }
                            });
                        } else {
                            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                                @Override
                                public void onAutoFocus(boolean success, Camera camera) {
                                    if (mAutofocusCallback != null) {
                                        mAutofocusCallback.onAutoFocus(success, camera);
                                    }
                                }
                            });
                        }
                    }
                }
                return true;
            }
        });
    }

    private void resetFocus(final boolean success, final Camera camera) {
        mHandler.removeCallbacksAndMessages(null);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    camera.cancelAutoFocus();
                    Camera.Parameters params = camera.getParameters();
                    if (params.getFocusMode() != Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) {
                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                        params.setFocusAreas(null);
                        params.setMeteringAreas(null);
                        camera.setParameters(params);
                    }

                    if (mAutofocusCallback != null) {
                        mAutofocusCallback.onAutoFocus(success, camera);
                    }
                }
            }
        }, DELAY_MILLIS_BEFORE_RESETTING_FOCUS);
    }

    private Rect calculateFocusArea(float x, float y) {
        int centerX = clamp(Float.valueOf((x / mPreview.getView().getWidth()) * 2000 - 1000).intValue(), getFocusAreaSize());
        int centerY = clamp(Float.valueOf((y / mPreview.getView().getHeight()) * 2000 - 1000).intValue(), getFocusAreaSize());
        return new Rect(
                centerX - getFocusAreaSize() / 2,
                centerY - getFocusAreaSize() / 2,
                centerX + getFocusAreaSize() / 2,
                centerY + getFocusAreaSize() / 2
        );
    }

    private int clamp(int touchCoordinateInCameraReper, int focusAreaSize) {
        int result;
        if (Math.abs(touchCoordinateInCameraReper) + focusAreaSize / 2 > 1000) {
            if (touchCoordinateInCameraReper > 0) {
                result = 1000 - focusAreaSize / 2;
            } else {
                result = -1000 + focusAreaSize / 2;
            }
        } else {
            result = touchCoordinateInCameraReper - focusAreaSize / 2;
        }
        return result;
    }

}
