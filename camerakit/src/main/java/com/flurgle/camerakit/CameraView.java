package com.flurgle.camerakit;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.hardware.display.DisplayManagerCompat;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.flurgle.camerakit.CameraKit.Constants.FACING_BACK;
import static com.flurgle.camerakit.CameraKit.Constants.FACING_FRONT;
import static com.flurgle.camerakit.CameraKit.Constants.FLASH_AUTO;
import static com.flurgle.camerakit.CameraKit.Constants.FLASH_OFF;
import static com.flurgle.camerakit.CameraKit.Constants.FLASH_ON;
import static com.flurgle.camerakit.CameraKit.Constants.METHOD_STANDARD;

public class CameraView extends FrameLayout {

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

    @Permissions
    private int mPermissions;

    @VideoQuality
    private int mVideoQuality;

    private int mJpegQuality;
    private boolean mCropOutput;
    private boolean mAdjustViewBounds;

    private CameraListenerMiddleWare mCameraListener;
    private DisplayOrientationDetector mDisplayOrientationDetector;

    private CameraImpl mCameraImpl;
    private PreviewImpl mPreviewImpl;

    private Handler mMainHandler = new Handler();

    private Handler mCameraHandler;

    public CameraView(@NonNull Context context) {
        this(context, null);
    }

    @SuppressWarnings("all")
    public CameraView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mCameraHandler = CameraHandlerProvider.getHandler();
        if (attrs != null) {
            TypedArray a = context.getTheme().obtainStyledAttributes(
                    attrs,
                    R.styleable.CameraView,
                    0, 0);

            try {
                mFacing = a.getInteger(R.styleable.CameraView_ckFacing, CameraKit.Defaults.DEFAULT_FACING);
                mFlash = a.getInteger(R.styleable.CameraView_ckFlash, CameraKit.Defaults.DEFAULT_FLASH);
                mFocus = a.getInteger(R.styleable.CameraView_ckFocus, CameraKit.Defaults.DEFAULT_FOCUS);
                mMethod = a.getInteger(R.styleable.CameraView_ckMethod, CameraKit.Defaults.DEFAULT_METHOD);
                mZoom = a.getInteger(R.styleable.CameraView_ckZoom, CameraKit.Defaults.DEFAULT_ZOOM);
                mPermissions = a.getInteger(R.styleable.CameraView_ckPermissions, CameraKit.Defaults.DEFAULT_PERMISSIONS);
                mVideoQuality = a.getInteger(R.styleable.CameraView_ckVideoQuality, CameraKit.Defaults.DEFAULT_VIDEO_QUALITY);
                mJpegQuality = a.getInteger(R.styleable.CameraView_ckJpegQuality, CameraKit.Defaults.DEFAULT_JPEG_QUALITY);
                mCropOutput = a.getBoolean(R.styleable.CameraView_ckCropOutput, CameraKit.Defaults.DEFAULT_CROP_OUTPUT);
                mAdjustViewBounds = a.getBoolean(R.styleable.CameraView_android_adjustViewBounds, CameraKit.Defaults.DEFAULT_ADJUST_VIEW_BOUNDS);
            } finally {
                a.recycle();
            }
        }

        mCameraListener = new CameraListenerMiddleWare();

        mPreviewImpl = new TextureViewPreview(context, this);
        mCameraImpl = new Camera1(mCameraListener, mPreviewImpl, mCameraHandler);

        setFacing(mFacing);
        setFlash(mFlash);
        setFocus(mFocus);
        setMethod(mMethod);
        setZoom(mZoom);
        setJpegQuality(mJpegQuality);
        setPermissions(mPermissions);
        setVideoQuality(mVideoQuality);

        mDisplayOrientationDetector = new DisplayOrientationDetector(context) {
            @Override
            public void onDisplayOrientationChanged(int displayOrientation) {
                mCameraImpl.setDisplayOrientation(displayOrientation);
                mPreviewImpl.setDisplayOrientation(displayOrientation);
            }
        };

        final FocusMarkerLayout focusMarkerLayout = new FocusMarkerLayout(getContext());
        addView(focusMarkerLayout);
        focusMarkerLayout.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent motionEvent) {
                int action = motionEvent.getAction();
                if (motionEvent.getAction() == MotionEvent.ACTION_UP && mFocus == CameraKit.Constants.FOCUS_TAP_WITH_MARKER) {
                    focusMarkerLayout.focus(motionEvent.getX(), motionEvent.getY());
                }

                mPreviewImpl.getView().dispatchTouchEvent(motionEvent);
                return true;
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mDisplayOrientationDetector.enable(
                ViewCompat.isAttachedToWindow(this)
                        ? DisplayManagerCompat.getInstance(getContext()).getDisplay(Display.DEFAULT_DISPLAY)
                        : null
        );
    }

    @Override
    protected void onDetachedFromWindow() {
        mDisplayOrientationDetector.disable();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mAdjustViewBounds) {
            Size previewSize = getPreviewSize();
            if (getLayoutParams().width == LayoutParams.WRAP_CONTENT) {
                int height = MeasureSpec.getSize(heightMeasureSpec);
                float ratio = (float) height / (float) previewSize.getWidth();
                int width = (int) (previewSize.getHeight() * ratio);
                super.onMeasure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        heightMeasureSpec
                );
                return;
            } else if (getLayoutParams().height == LayoutParams.WRAP_CONTENT) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                float ratio = (float) width / (float) previewSize.getHeight();
                int height = (int) (previewSize.getWidth() * ratio);
                super.onMeasure(
                        widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                );
                return;
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

//	@Override
//	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
//		if (mAdjustViewBounds) {
//			Size previewSize = getPreviewSize();
//			if (previewSize != null) {
//				if (getLayoutParams().width == LayoutParams.WRAP_CONTENT) {
//					int height = MeasureSpec.getSize(heightMeasureSpec);
//					float ratio = (float) height / (float) previewSize.getWidth();
//					int width = (int) (previewSize.getHeight() * ratio);
//					super.onMeasure(
//							MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
//							heightMeasureSpec
//					);
//					return;
//				} else if (getLayoutParams().height == LayoutParams.WRAP_CONTENT) {
//					int width = MeasureSpec.getSize(widthMeasureSpec);
//					float ratio = (float) width / (float) previewSize.getHeight();
//					int height = (int) (previewSize.getWidth() * ratio);
//					super.onMeasure(
//							widthMeasureSpec,
//							MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
//					);
//					return;
//				}
//			} else {
//				super.onMeasure(widthMeasureSpec, heightMeasureSpec);
//				return;
//			}
//		}
//
//		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
//	}

    public void start() {
        int permissionCheck = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA);
        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCameraImpl.start();
                }
            });
        } else {
        }
    }

    public void stop() {
//        mCameraImpl.stop();
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.stop();
            }
        });
    }

    public void setFacing(@Facing final int facing) {
        if (mFacing == facing) {
            return;
        }
        this.mFacing = facing;

        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.setFacing(facing);
            }
        });
    }

    public void setFlash(@Flash final int flash) {
        this.mFlash = flash;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.setFlash(flash);
            }
        });
    }

    public void setFocus(@Focus int focus) {
        this.mFocus = focus;
        if (this.mFocus == CameraKit.Constants.FOCUS_TAP_WITH_MARKER) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCameraImpl.setFocus(CameraKit.Constants.FOCUS_TAP);
                }
            });
//            mCameraImpl.setFocus(CameraKit.Constants.FOCUS_TAP);
            return;
        }

        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.setFocus(mFocus);
            }
        });

    }

    public void setMethod(@Method int method) {
        this.mMethod = method;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.setMethod(mMethod);
            }
        });

    }

    public void setZoom(@Zoom int zoom) {
        this.mZoom = zoom;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.setZoom(mZoom);
            }
        });

    }

    public void setPermissions(@Permissions int permissions) {
        this.mPermissions = permissions;
    }

    public void setVideoQuality(@VideoQuality int videoQuality) {
        this.mVideoQuality = videoQuality;
        mCameraImpl.setVideoQuality(mVideoQuality);
    }

    public void setJpegQuality(final int jpegQuality) {
        this.mJpegQuality = jpegQuality;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.setJpegQuality(jpegQuality);
            }
        });
    }

    public void setCropOutput(boolean cropOutput) {
        this.mCropOutput = cropOutput;
    }

    @Facing
    public int toggleFacing() {
        switch (mFacing) {
            case FACING_BACK:
                setFacing(FACING_FRONT);
                break;

            case FACING_FRONT:
                setFacing(FACING_BACK);
                break;
        }

        return mFacing;
    }

    @Flash
    public int toggleFlash() {
        switch (mFlash) {
            case FLASH_OFF:
                setFlash(FLASH_ON);
                break;

            case FLASH_ON:
                setFlash(FLASH_AUTO);
                break;

            case FLASH_AUTO:
                setFlash(FLASH_OFF);
                break;
        }

        return mFlash;
    }

    public void setCameraListener(CameraListener cameraListener) {
        this.mCameraListener.setCameraListener(cameraListener);
    }

    public void captureImage() {
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.captureImage();
            }
        });
    }

    public void startRecordingVideo() {
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.startVideo();
            }
        });
    }

    public void stopRecordingVideo() {
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.endVideo();
            }
        });
    }

    public boolean isVideoRecording() {
        return mCameraImpl.isVideoRecording();
    }

    public Size getPreviewSize() {
        return mCameraImpl != null ? mCameraImpl.getPreviewResolution() : null;
    }

    public Size getCaptureSize() {
        return mCameraImpl != null ? mCameraImpl.getCaptureResolution() : null;
    }

    private void requestPermissions(boolean requestCamera, boolean requestAudio) {
        Activity activity = null;
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                activity = (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }

        List<String> permissions = new ArrayList<>();
        if (requestCamera) permissions.add(Manifest.permission.CAMERA);
        if (requestAudio) permissions.add(Manifest.permission.RECORD_AUDIO);

        if (activity != null) {
            ActivityCompat.requestPermissions(
                    activity,
                    permissions.toArray(new String[permissions.size()]),
                    CameraKit.Constants.PERMISSION_REQUEST_CAMERA);
        }
    }

    public void destroy() {
        mCameraHandler.removeCallbacksAndMessages(null);
        mMainHandler.removeCallbacksAndMessages(null);
    }

    private class CameraListenerMiddleWare extends CameraListener {

        private CameraListener mCameraListener;

        private void notifyCameraOpened() {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    getCameraListener().onCameraOpened();
                }
            });
        }

        private void notifyCameraClosed() {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    getCameraListener().onCameraClosed();
                }
            });
        }

        @Override
        public void onCameraOpened() {
            notifyCameraOpened();
        }

        @Override
        public void onCameraClosed() {
            notifyCameraClosed();
        }

        @Override
        public void onPictureTaken(final byte[] jpeg, final int rotation) {
            if (mCropOutput) {
                final int width = mMethod == METHOD_STANDARD ? mCameraImpl.getCaptureResolution().getWidth() : mCameraImpl.getPreviewResolution().getWidth();
                final int height = mMethod == METHOD_STANDARD ? mCameraImpl.getCaptureResolution().getHeight() : mCameraImpl.getPreviewResolution().getHeight();
                final AspectRatio outputRatio = AspectRatio.of(getWidth(), getHeight());
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        getCameraListener().onPictureTaken(new CenterCrop(jpeg, outputRatio, mJpegQuality).getJpeg(), rotation);
                    }
                });

            } else {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        getCameraListener().onPictureTaken(jpeg, rotation);
                    }
                });
            }
        }

        @Override
        public void onPictureTaken(final YuvImage yuv) {
            if (mCropOutput) {
                final AspectRatio outputRatio = AspectRatio.of(getWidth(), getHeight());
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        getCameraListener().onPictureTaken(new CenterCrop(yuv, outputRatio, mJpegQuality).getJpeg(), 0);
                    }
                });
            } else {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                yuv.compressToJpeg(new Rect(0, 0, yuv.getWidth(), yuv.getHeight()), mJpegQuality, out);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        getCameraListener().onPictureTaken(out.toByteArray(), 0);
                    }
                });
            }
        }

        @Override
        public void onVideoTaken(final File video) {
            super.onVideoTaken(video);
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    getCameraListener().onVideoTaken(video);
                }
            });
        }

        public void setCameraListener(@Nullable CameraListener cameraListener) {
            this.mCameraListener = cameraListener;
        }

        @NonNull
        public CameraListener getCameraListener() {
            if (mCameraListener == null) {
                mCameraListener = new CameraListener() {
                };
            }
            return mCameraListener;
        }

    }

}
