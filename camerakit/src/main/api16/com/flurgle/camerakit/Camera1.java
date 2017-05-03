package com.flurgle.camerakit;

import android.content.Context;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
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

    private int mCameraId;
    private Camera mCamera;
    private Camera.Parameters mCameraParameters;
    private Camera.CameraInfo mCameraInfo;
    private Size mPreviewSize;
    private Size mCaptureSize;
    private MediaRecorder mMediaRecorder;
    private File mVideoFile;

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

    private int mJpegQuality;

    private Handler mHandler;

    Camera1(CameraListener callback, PreviewImpl preview) {
        super(callback, preview);
        mHandler = new Handler(Looper.getMainLooper());
        preview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged() {
                if (mCamera != null) {
                    setupPreview();
                    adjustCameraParameters();
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
                    final List<String> modes = mCameraParameters.getSupportedFocusModes();
                    if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    } else {
                        setFocus(FOCUS_OFF);
                    }
                }
                break;

            case FOCUS_TAP:
                setFocus(FOCUS_CONTINUOUS);
                break;

            case FOCUS_OFF:
                if (mCameraParameters != null) {
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
                        new Thread(new ProcessStillTask(data, camera, mCameraInfo, new ProcessStillTask.OnStillProcessedListener() {
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
            mCamera.release();
            mCamera = null;
            mCameraParameters = null;
            mPreviewSize = null;
            mCaptureSize = null;
            mCameraListener.onCameraClosed();
        }
    }

    private int calculateCameraRotation(int rotation) {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (mCameraInfo.orientation + rotation) % 360) % 360;
        } else {
            return (mCameraInfo.orientation - rotation + 360) % 360;
        }
    }

    private void adjustCameraParameters() {
        mPreview.setTruePreviewSize(
                getPreviewResolution().getWidth(),
                getPreviewResolution().getHeight()
        );

        mCameraParameters.setPreviewSize(
                getPreviewResolution().getWidth(),
                getPreviewResolution().getHeight()
        );

        mCameraParameters.setPictureSize(
                getCaptureResolution().getWidth(),
                getCaptureResolution().getHeight()
        );

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
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_480P));

        File cacheDir = getCacheDir();
        mVideoFile = new File(cacheDir, createVideoFileName());
        mMediaRecorder.setOutputFile(mVideoFile.getAbsolutePath());

        mMediaRecorder.setMaxDuration(20000);
        mMediaRecorder.setMaxFileSize(5000000);
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

}
