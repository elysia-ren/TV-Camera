package com.tvcamera.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CameraActivity extends Activity {
    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int REQUEST_STORAGE_PERMISSION = 101;
    private static final long CONTROL_HIDE_DELAY = 4000;

    private TextureView previewView;
    private ImageView processedPreview;
    private Button captureButton;
    private Button recordButton;
    private Button qualityButton;
    private Button galleryButton;
    private LinearLayout controlBar;
    private TextView cameraLabel;
    private TextView resolutionLabel;
    private TextView storageLabel;
    private TextView recordingTime;
    private TextView fpsLabel;
    private View recordingDot;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private CaptureRequest.Builder previewBuilder;

    private String cameraId;
    private int requestedWidth = 1920;
    private int requestedHeight = 1080;
    private Size previewSize;
    private int chosenImageFormat = ImageFormat.YUV_420_888;

    private List<String> availableCameraIds = new ArrayList<>();
    private List<String> availableCameraNames = new ArrayList<>();

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Handler mainHandler = new Handler();

    private VideoRecorderHelper videoRecorder = new VideoRecorderHelper();
    private boolean isRecordingVideo = false;
    private long recordingStartTime = 0;
    private Runnable recordingTimerRunnable;

    private ImageProcessor imageProcessor;
    private QualityPreferences qualityPrefs;
    private boolean qualityPanelOpen = false;

    private Runnable hideControlRunnable;
    private boolean controlsVisible = true;
    private boolean previewStarted = false;

    private volatile boolean isPreviewing = false;
    private volatile boolean isOpening = false;
    private CameraPreferences preferences;
    private ResolutionPreferences resolutionPrefs;

    private Runnable fpsUpdateRunnable;
    private volatile CountDownLatch videoSaveLatch = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preferences = new CameraPreferences(this);
        resolutionPrefs = new ResolutionPreferences(this);
        qualityPrefs = new QualityPreferences(this);

        cameraId = getIntent().getStringExtra("camera_id");
        requestedWidth = getIntent().getIntExtra("width", 1920);
        requestedHeight = getIntent().getIntExtra("height", 1080);

        boolean opencvOk = ImageProcessor.initOpenCV();
        if (opencvOk) {
            imageProcessor = new ImageProcessor(qualityPrefs);
        }

        initViews();
        setupVideoRecorder();
        enumerateCameras();
        updateStorageDisplay();

        previewView.setSurfaceTextureListener(surfaceTextureListener);
    }

    private void initViews() {
        previewView = findViewById(R.id.preview_view);
        processedPreview = findViewById(R.id.processed_preview);
        captureButton = findViewById(R.id.btn_capture);
        recordButton = findViewById(R.id.btn_record);
        qualityButton = findViewById(R.id.btn_quality);
        galleryButton = findViewById(R.id.btn_gallery);
        controlBar = findViewById(R.id.control_bar);
        cameraLabel = findViewById(R.id.camera_label);
        resolutionLabel = findViewById(R.id.resolution_label);
        storageLabel = findViewById(R.id.storage_label);
        recordingTime = findViewById(R.id.recording_time);
        recordingDot = findViewById(R.id.recording_dot);
        fpsLabel = findViewById(R.id.fps_label);

        captureButton.setOnClickListener(v -> takePhoto());
        recordButton.setOnClickListener(v -> toggleRecording());
        qualityButton.setOnClickListener(v -> toggleQualityPanel());
        galleryButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, GalleryActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        });

        // 控制栏隐藏定时器（预览启动后才开始计时）
        hideControlRunnable = () -> hideControls();

        // FPS 更新
        fpsUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (imageProcessor != null && qualityPrefs.isShowFps()) {
                    float fps = imageProcessor.getCurrentFps();
                    fpsLabel.setText(String.format(Locale.getDefault(), "%.1f FPS", fps));
                    fpsLabel.setVisibility(View.VISIBLE);
                } else {
                    fpsLabel.setVisibility(View.GONE);
                }
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(fpsUpdateRunnable);

        // 确保面板初始状态正确
        hideControlRunnable = () -> hideControls();
    }

    // ==================== 控制栏 ====================

    /** 预览启动后调用，开始控制栏隐藏计时 */
    private void startHideTimer() {
        previewStarted = true;
        resetHideTimer();
    }

    private void resetHideTimer() {
        if (!previewStarted) return;
        mainHandler.removeCallbacks(hideControlRunnable);
        mainHandler.postDelayed(hideControlRunnable, CONTROL_HIDE_DELAY);
    }

    private void showControls() {
        if (!controlsVisible) {
            controlBar.setVisibility(View.VISIBLE);
            controlBar.animate().alpha(1.0f).setDuration(200).start();
            findViewById(R.id.info_overlay).setVisibility(View.VISIBLE);
            findViewById(R.id.info_overlay).animate().alpha(1.0f).setDuration(200).start();
            findViewById(R.id.storage_overlay).setVisibility(View.VISIBLE);
            findViewById(R.id.storage_overlay).animate().alpha(1.0f).setDuration(200).start();
            controlsVisible = true;
        }
        resetHideTimer();
    }

    private void hideControls() {
        if (controlsVisible && !isRecordingVideo && !qualityPanelOpen) {
            controlBar.animate().alpha(0.0f).setDuration(300).withEndAction(() -> {
                if (!controlsVisible) controlBar.setVisibility(View.INVISIBLE);
            }).start();
            findViewById(R.id.info_overlay).animate().alpha(0.0f).setDuration(300).withEndAction(() -> {
                if (!controlsVisible) findViewById(R.id.info_overlay).setVisibility(View.INVISIBLE);
            }).start();
            findViewById(R.id.storage_overlay).animate().alpha(0.0f).setDuration(300).withEndAction(() -> {
                if (!controlsVisible) findViewById(R.id.storage_overlay).setVisibility(View.INVISIBLE);
            }).start();
            controlsVisible = false;
        }
    }

    // ==================== 画质设置面板（动态创建，不遮挡布局） ====================

    private View dynamicQualityPanel = null;

    private void toggleQualityPanel() {
        if (qualityPanelOpen) closeQualityPanel();
        else openQualityPanel();
    }

    private void openQualityPanel() {
        qualityPanelOpen = true;

        // 动态创建面板
        FrameLayout root = findViewById(android.R.id.content);
        dynamicQualityPanel = getLayoutInflater().inflate(R.layout.panel_quality, root, false);
        root.addView(dynamicQualityPanel);

        setupQualityPanelItems();
        refreshQualityPanel();
        dynamicQualityPanel.findViewById(R.id.toggle_enabled).requestFocus();
    }

    private void closeQualityPanel() {
        qualityPanelOpen = false;
        if (dynamicQualityPanel != null) {
            FrameLayout root = findViewById(android.R.id.content);
            root.removeView(dynamicQualityPanel);
            dynamicQualityPanel = null;
        }
        qualityButton.requestFocus();
        resetHideTimer();
    }

    private void setupQualityPanelItems() {
        View panel = dynamicQualityPanel;
        if (panel == null) return;

        panel.findViewById(R.id.toggle_enabled).setOnClickListener(v -> {
            qualityPrefs.setEnabled(!qualityPrefs.isEnabled());
            refreshQualityPanel();
            // 切换优化开关后重建预览会话，切换 Camera2 输出目标
            closeQualityPanel();
            closeCamera();
            previewView.postDelayed(this::openCamera, 300);
        });
        panel.findViewById(R.id.toggle_white_balance).setOnClickListener(v -> {
            qualityPrefs.setWhiteBalance(!qualityPrefs.isWhiteBalance());
            refreshQualityPanel();
        });
        panel.findViewById(R.id.toggle_clahe).setOnClickListener(v -> {
            qualityPrefs.setClahe(!qualityPrefs.isClahe());
            refreshQualityPanel();
        });
        panel.findViewById(R.id.toggle_brightness).setOnClickListener(v -> {
            qualityPrefs.setBrightness(!qualityPrefs.isBrightness());
            refreshQualityPanel();
        });
        panel.findViewById(R.id.toggle_denoise).setOnClickListener(v -> {
            if (!qualityPrefs.isDenoise()) {
                qualityPrefs.setDenoise(true);
                qualityPrefs.setDenoiseLevel(0);
            } else if (qualityPrefs.getDenoiseLevel() < 2) {
                qualityPrefs.setDenoiseLevel(qualityPrefs.getDenoiseLevel() + 1);
            } else {
                qualityPrefs.setDenoise(false);
            }
            refreshQualityPanel();
        });
        panel.findViewById(R.id.toggle_sharpen).setOnClickListener(v -> {
            if (!qualityPrefs.isSharpen()) {
                qualityPrefs.setSharpen(true);
                qualityPrefs.setSharpenLevel(0);
            } else if (qualityPrefs.getSharpenLevel() < 2) {
                qualityPrefs.setSharpenLevel(qualityPrefs.getSharpenLevel() + 1);
            } else {
                qualityPrefs.setSharpen(false);
            }
            refreshQualityPanel();
        });
        panel.findViewById(R.id.toggle_save_optimized).setOnClickListener(v -> {
            qualityPrefs.setSaveOptimized(!qualityPrefs.isSaveOptimized());
            refreshQualityPanel();
        });
        panel.findViewById(R.id.toggle_record_optimized).setOnClickListener(v -> {
            qualityPrefs.setRecordOptimized(!qualityPrefs.isRecordOptimized());
            refreshQualityPanel();
        });
        panel.findViewById(R.id.toggle_fps).setOnClickListener(v -> {
            qualityPrefs.setShowFps(!qualityPrefs.isShowFps());
            refreshQualityPanel();
        });

        // 分辨率切换
        panel.findViewById(R.id.toggle_resolution).setOnClickListener(v -> {
            showResolutionDialog();
        });

        refreshQualityPanel();
    }

    private void refreshQualityPanel() {
        View panel = dynamicQualityPanel;
        if (panel == null) return;

        ((TextView) panel.findViewById(R.id.value_enabled)).setText(
                qualityPrefs.isEnabled() ? "✅ 开启" : "❌ 关闭");
        ((TextView) panel.findViewById(R.id.value_white_balance)).setText(
                qualityPrefs.isWhiteBalance() ? "✅" : "❌");
        ((TextView) panel.findViewById(R.id.value_clahe)).setText(
                qualityPrefs.isClahe() ? "✅" : "❌");
        ((TextView) panel.findViewById(R.id.value_brightness)).setText(
                qualityPrefs.isBrightness() ? "✅" : "❌");
        String[] levels = {"低", "中", "高"};
        ((TextView) panel.findViewById(R.id.value_denoise)).setText(
                qualityPrefs.isDenoise() ? "✅ " + levels[qualityPrefs.getDenoiseLevel()] : "❌");
        ((TextView) panel.findViewById(R.id.value_sharpen)).setText(
                qualityPrefs.isSharpen() ? "✅ " + levels[qualityPrefs.getSharpenLevel()] : "❌");
        ((TextView) panel.findViewById(R.id.value_save_optimized)).setText(
                qualityPrefs.isSaveOptimized() ? "✅" : "❌");
        ((TextView) panel.findViewById(R.id.value_record_optimized)).setText(
                qualityPrefs.isRecordOptimized() ? "✅" : "❌");
        ((TextView) panel.findViewById(R.id.value_fps)).setText(
                qualityPrefs.isShowFps() ? "✅" : "❌");

        // 分辨率显示
        Size currentRes = resolutionPrefs.getResolution(cameraId);
        if (currentRes != null && currentRes.getWidth() > 0 && currentRes.getHeight() > 0) {
            ((TextView) panel.findViewById(R.id.value_resolution)).setText(
                    currentRes.getWidth() + "x" + currentRes.getHeight());
        } else {
            ((TextView) panel.findViewById(R.id.value_resolution)).setText("自动");
        }
    }

    /** 弹出分辨率选择列表 */
    private void showResolutionDialog() {
        if (cameraId == null) return;
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            android.hardware.camera2.params.StreamConfigurationMap map =
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            Size[] sizes = map.getOutputSizes(chosenImageFormat);
            if (sizes == null || sizes.length == 0) return;

            // 按面积降序排列
            java.util.List<Size> sorted = new java.util.ArrayList<>(java.util.Arrays.asList(sizes));
            java.util.Collections.sort(sorted, (a, b) ->
                    Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));

            // 构建选项列表：自动 + 各分辨率
            java.util.List<String> labels = new java.util.ArrayList<>();
            labels.add("自动（最大分辨率）");
            for (Size s : sorted) {
                labels.add(s.getWidth() + "x" + s.getHeight());
            }

            // 当前选中项
            Size current = resolutionPrefs.getResolution(cameraId);
            int checked = 0; // 默认选中"自动"
            if (current != null && current.getWidth() > 0) {
                for (int i = 0; i < sorted.size(); i++) {
                    if (sorted.get(i).getWidth() == current.getWidth()
                            && sorted.get(i).getHeight() == current.getHeight()) {
                        checked = i + 1;
                        break;
                    }
                }
            }

            new AlertDialog.Builder(this)
                    .setTitle("选择分辨率")
                    .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                        if (which == 0) {
                            resolutionPrefs.saveResolution(cameraId, 0, 0);
                        } else {
                            Size chosen = sorted.get(which - 1);
                            resolutionPrefs.saveResolution(cameraId, chosen.getWidth(), chosen.getHeight());
                        }
                        dialog.dismiss();
                        // 延迟重新打开，等旧相机完全关闭
                        closeCamera();
                        previewView.postDelayed(() -> openCamera(), 300);
                        refreshQualityPanel();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (CameraAccessException e) {
            Log.e(TAG, "获取分辨率列表失败", e);
        }
    }

    // ==================== 录像 ====================

    private void setupVideoRecorder() {
        videoRecorder.setCallback(new VideoRecorderHelper.RecordingCallback() {
            @Override
            public void onRecordingStarted() {
                isRecordingVideo = true;
                runOnUiThread(() -> {
                    recordButton.setText("⏹ 停止");
                    captureButton.setEnabled(false);
                    captureButton.setAlpha(0.5f);
                    recordingDot.setVisibility(View.VISIBLE);
                    recordingTime.setVisibility(View.VISIBLE);
                    startRecordingTimer();
                    startBlinkingDot();
                });
            }

            @Override
            public void onRecordingStopped(String filePath) {
                isRecordingVideo = false;
                runOnUiThread(() -> {
                    recordButton.setText("🎥 录像");
                    captureButton.setEnabled(true);
                    captureButton.setAlpha(1.0f);
                    recordingDot.setVisibility(View.GONE);
                    recordingTime.setVisibility(View.GONE);
                    stopRecordingTimer();
                    updateStorageDisplay();
                });
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    final CountDownLatch latch = new CountDownLatch(1);
                    videoSaveLatch = latch;
                    new Thread(() -> {
                        try {
                            saveVideoToMediaStore(filePath);
                            runOnUiThread(() -> Toast.makeText(CameraActivity.this, "录像已保存", Toast.LENGTH_SHORT).show());
                        } finally {
                            latch.countDown();
                            videoSaveLatch = null;
                        }
                    }).start();
                } else {
                    runOnUiThread(() -> Toast.makeText(CameraActivity.this, "录像已保存", Toast.LENGTH_SHORT).show());
                }
            }

            @Override
            public void onRecordingError(String message) {
                isRecordingVideo = false;
                runOnUiThread(() -> {
                    recordButton.setText("🎥 录像");
                    captureButton.setEnabled(true);
                    captureButton.setAlpha(1.0f);
                    recordingDot.setVisibility(View.GONE);
                    recordingTime.setVisibility(View.GONE);
                    stopRecordingTimer();
                    Toast.makeText(CameraActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void enumerateCameras() {
        availableCameraIds.clear();
        availableCameraNames.clear();
        for (String id : CameraHelper.getCameraIds(this)) {
            availableCameraIds.add(id);
            availableCameraNames.add(CameraHelper.getCameraDescription(this, id));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        // 只有摄像头完全关闭时才重新打开
        if (previewView.isAvailable() && cameraDevice == null && !isOpening) {
            previewView.postDelayed(this::openCamera, 200);
        }
        updateStorageDisplay();
    }

    @Override
    protected void onPause() {
        if (isRecordingVideo) videoRecorder.stopRecording();
        // 等待视频保存完成（最多 5 秒）
        CountDownLatch latch = videoSaveLatch;
        if (latch != null) {
            try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        videoRecorder.release();
        if (imageProcessor != null) imageProcessor.release();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); } catch (InterruptedException e) { }
            backgroundThread = null;
        }
    }

    // ==================== 录像计时 ====================

    private void startRecordingTimer() {
        recordingStartTime = System.currentTimeMillis();
        recordingTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRecordingVideo) return;
                long elapsed = System.currentTimeMillis() - recordingStartTime;
                int secs = (int) (elapsed / 1000);
                recordingTime.setText(String.format(Locale.getDefault(),
                        "%02d:%02d", secs / 60, secs % 60));
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(recordingTimerRunnable);
    }

    private void stopRecordingTimer() {
        if (recordingTimerRunnable != null) mainHandler.removeCallbacks(recordingTimerRunnable);
    }

    private void startBlinkingDot() {
        Animation blink = AnimationUtils.loadAnimation(this, R.anim.fade_out);
        blink.setRepeatCount(Animation.INFINITE);
        blink.setRepeatMode(Animation.REVERSE);
        recordingDot.startAnimation(blink);
    }

    private void updateStorageDisplay() {
        long mb = VideoRecorderHelper.getAvailableStorageMB();
        if (mb >= 0) {
            storageLabel.setText(mb > 1024
                    ? String.format(Locale.getDefault(), "存储: %.1f GB", mb / 1024.0)
                    : "存储: " + mb + " MB");
        } else {
            storageLabel.setText("存储: --");
        }
    }

    // ==================== TextureView ====================

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                    openCamera();
                    applyPreviewTransform();
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { configureTransform(w, h); }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
            };

    // ==================== 打开摄像头 ====================

    private synchronized void openCamera() {
        if (isOpening || isPreviewing || cameraDevice != null) return;
        isOpening = true;

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { isOpening = false; finish(); return; }

            chosenImageFormat = chooseImageFormat(map);
            Size[] outputSizes = map.getOutputSizes(chosenImageFormat);
            if (outputSizes != null && outputSizes.length > 0) {
                // 优先使用用户选择的分辨率
                Size userChoice = resolutionPrefs.getResolution(cameraId);
                if (userChoice != null && userChoice.getWidth() > 0 && userChoice.getHeight() > 0) {
                    boolean found = false;
                    for (Size s : outputSizes) {
                        if (s.getWidth() == userChoice.getWidth() && s.getHeight() == userChoice.getHeight()) {
                            previewSize = s;
                            found = true;
                            break;
                        }
                    }
                    if (!found) previewSize = chooseBestSize(outputSizes);
                } else {
                    previewSize = chooseBestSize(outputSizes);
                }
            } else {
                previewSize = new Size(requestedWidth, requestedHeight);
            }

            if (imageProcessor != null) {
                imageProcessor.prepare(previewSize.getWidth(), previewSize.getHeight());
            }

            imageReader = ImageReader.newInstance(
                    previewSize.getWidth(), previewSize.getHeight(), chosenImageFormat, 2);
            imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);

            preferences.saveLastCameraId(cameraId);

            String fmt = (chosenImageFormat == ImageFormat.YUV_420_888) ? "YUV" : "JPEG";
            int idx = availableCameraIds.indexOf(cameraId);
            cameraLabel.setText(idx >= 0 && idx < availableCameraNames.size()
                    ? availableCameraNames.get(idx) : "摄像头 " + cameraId);
            resolutionLabel.setText(previewSize.getWidth() + "x" + previewSize.getHeight() + " | " + fmt);

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                isOpening = false;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "打开摄像头失败", e);
            isOpening = false;
        }
    }

    private int chooseImageFormat(StreamConfigurationMap map) {
        for (int fmt : map.getOutputFormats()) {
            if (fmt == ImageFormat.YUV_420_888) {
                Size[] s = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (s != null && s.length > 0) return ImageFormat.YUV_420_888;
            }
        }
        for (int fmt : map.getOutputFormats()) {
            if (fmt == ImageFormat.JPEG) {
                Size[] s = map.getOutputSizes(ImageFormat.JPEG);
                if (s != null && s.length > 0) return ImageFormat.JPEG;
            }
        }
        return ImageFormat.YUV_420_888;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            cameraDevice = camera; isOpening = false;
            // 等视图布局完成后再创建预览会话
            previewView.post(() -> createPreviewSession());
        }
        @Override public void onDisconnected(CameraDevice camera) {
            camera.close(); cameraDevice = null; isOpening = false; isPreviewing = false;
            runOnUiThread(() -> {
                Toast.makeText(CameraActivity.this, "摄像头已断开", Toast.LENGTH_SHORT).show();
                previewView.postDelayed(() -> finish(), 3000);
            });
        }
        @Override public void onError(CameraDevice camera, int error) {
            camera.close(); cameraDevice = null; isOpening = false; isPreviewing = false;
            runOnUiThread(() -> {
                Toast.makeText(CameraActivity.this, "摄像头错误: " + error, Toast.LENGTH_LONG).show();
                previewView.postDelayed(() -> finish(), 3000);
            });
        }
    };

    // ==================== 预览会话 ====================

    private void createPreviewSession() {
        // 确保视图已布局
        previewView.post(() -> {
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null) return;
            // 设置 buffer 为摄像头输出分辨率，让 configureTransform 矩阵做缩放
            if (previewSize != null) {
                texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            }
            Surface previewSurface = new Surface(texture);
            Surface readerSurface = imageReader.getSurface();

            // 统一用 ImageView 显示，隐藏 TextureView
            runOnUiThread(() -> {
                previewView.setVisibility(View.INVISIBLE);
                processedPreview.setVisibility(View.VISIBLE);
            });

            // 统一输出到 ImageReader，由 listener 渲染到 ImageView
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(readerSurface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            List<Surface> surfaces = Arrays.asList(previewSurface, readerSurface);
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
                        isPreviewing = true;
                        runOnUiThread(() -> startHideTimer());
                    } catch (CameraAccessException e) { Log.e(TAG, "启动预览失败", e); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    closeCamera();
                    runOnUiThread(() -> Toast.makeText(CameraActivity.this, "预览配置失败", Toast.LENGTH_LONG).show());
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) { Log.e(TAG, "创建预览会话失败", e); }
        }); // end previewView.post
    }

    // ==================== ImageReader 回调 ====================

    private final ImageReader.OnImageAvailableListener imageAvailableListener = reader -> {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            if (imageProcessor != null && qualityPrefs.isEnabled() && chosenImageFormat == ImageFormat.YUV_420_888) {
                // 优化开启：OpenCV 处理后渲染
                byte[] nv21 = YuvToNv21Converter.yuv420ToNv21(image);
                Bitmap processed = imageProcessor.processFrame(nv21, image.getWidth(), image.getHeight());
                if (processed != null) renderToImageView(processed);
            } else {
                // 优化关闭：直接用原始帧渲染
                Bitmap raw = imageToBitmap(image);
                if (raw != null) renderToImageView(raw);
            }
        } finally {
            image.close();
        }
    };

    /** Image → Bitmap（优化关闭时用） */
    private Bitmap imageToBitmap(Image image) {
        try {
            byte[] nv21 = YuvToNv21Converter.yuv420ToNv21(image);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, baos);
            byte[] jpeg = baos.toByteArray();
            baos.close();
            return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Exception e) {
            Log.w(TAG, "imageToBitmap失败", e);
            return null;
        }
    }

    private void renderToImageView(Bitmap bitmap) {
        if (bitmap == null) return;
        mainHandler.post(() -> {
            try {
                processedPreview.setImageBitmap(bitmap);
            } catch (Exception e) {
                Log.w(TAG, "ImageView渲染失败", e);
            }
        });
    }

    // ==================== 拍照 ====================

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void takePhoto() {
        if (isRecordingVideo) { Toast.makeText(this, "录像中无法拍照", Toast.LENGTH_SHORT).show(); return; }
        if (!hasStoragePermission()) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
            return;
        }
        if (captureSession == null || imageReader == null) {
            Toast.makeText(this, "摄像头未就绪，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }

        captureButton.setEnabled(false);
        captureButton.setText("拍照中...");

        new Thread(() -> {
            try {
                byte[] jpegData;
                if (chosenImageFormat == ImageFormat.YUV_420_888) jpegData = captureYuvAndCompress();
                else jpegData = captureJpeg();

                if (jpegData == null) {
                    runOnUiThread(() -> Toast.makeText(this, "拍照失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(jpegData, timestamp);
                else saveToFile(jpegData, timestamp);
            } catch (Exception e) {
                Log.e(TAG, "拍照失败", e);
                runOnUiThread(() -> Toast.makeText(this, "拍照失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                runOnUiThread(() -> { captureButton.setEnabled(true); captureButton.setText("📷 拍照"); updateStorageDisplay(); });
            }
        }).start();
    }

    private byte[] captureYuvAndCompress() throws CameraAccessException, InterruptedException, IOException {
        // 1. 停止预览，防止预览帧干扰拍照
        captureSession.stopRepeating();
        // 2. 同步刷新管线：发一个单拍请求，同步等它完成，确保残留帧全部消费
        final CountDownLatch flushLatch = new CountDownLatch(1);
        imageReader.setOnImageAvailableListener(reader -> {
            Image img = reader.acquireLatestImage();
            if (img != null) img.close();
            flushLatch.countDown();
        }, backgroundHandler);
        captureSession.capture(previewBuilder.build(), null, backgroundHandler);
        flushLatch.await(2, TimeUnit.SECONDS);
        Thread.sleep(50);

        // 3. 设置拍照 listener，此时不会有新帧到来
        final CountDownLatch latch = new CountDownLatch(1);
        final byte[][] result = new byte[1][];
        final int[] dims = new int[2];

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                result[0] = YuvToNv21Converter.yuv420ToNv21(image);
                dims[0] = image.getWidth(); dims[1] = image.getHeight();
            } finally { image.close(); latch.countDown(); }
        }, backgroundHandler);

        CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        b.addTarget(imageReader.getSurface());
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        captureSession.capture(b.build(), null, backgroundHandler);

        boolean received = latch.await(3, TimeUnit.SECONDS);
        imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);
        resumePreview();
        if (!received || result[0] == null) return null;

        // 判断是否开启“保存优化后图片”
        if (qualityPrefs.isSaveOptimized() && imageProcessor != null) {
            Bitmap optimized = imageProcessor.processFrameForCapture(result[0], dims[0], dims[1]);
            if (optimized != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                optimized.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                byte[] jpeg = baos.toByteArray();
                baos.close();
                optimized.recycle();
                return jpeg;
            }
        }
        return compressYuvToJpeg(result[0], dims[0], dims[1]);
    }

    private byte[] compressYuvToJpeg(byte[] nv21, int width, int height) throws IOException {
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, baos);
        byte[] jpeg = baos.toByteArray();
        baos.close();
        return jpeg;
    }

    private byte[] captureJpeg() throws CameraAccessException, InterruptedException {
        captureSession.stopRepeating();
        final CountDownLatch flushLatch = new CountDownLatch(1);
        imageReader.setOnImageAvailableListener(reader -> {
            Image img = reader.acquireLatestImage();
            if (img != null) img.close();
            flushLatch.countDown();
        }, backgroundHandler);
        captureSession.capture(previewBuilder.build(), null, backgroundHandler);
        flushLatch.await(2, TimeUnit.SECONDS);
        Thread.sleep(50);

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[][] result = new byte[1][];

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                result[0] = new byte[buffer.remaining()]; buffer.get(result[0]);
            } finally { image.close(); latch.countDown(); }
        }, backgroundHandler);

        CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        b.addTarget(imageReader.getSurface());
        captureSession.capture(b.build(), null, backgroundHandler);

        boolean received = latch.await(3, TimeUnit.SECONDS);
        imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);
        resumePreview();
        return (received && result[0] != null) ? result[0] : null;
    }

    /** 恢复预览 repeating request */
    private void resumePreview() {
        try {
            if (captureSession != null && previewBuilder != null) {
                captureSession.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "恢复预览失败", e);
        }
    }

    // ==================== 录像 ====================

    private void toggleRecording() { if (isRecordingVideo) stopRecording(); else startRecording(); }

    private void startRecording() {
        if (captureSession == null || cameraDevice == null) { Toast.makeText(this, "摄像头未就绪", Toast.LENGTH_SHORT).show(); return; }
        if (!hasStoragePermission()) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION); return;
        }
        if (!VideoRecorderHelper.hasEnoughStorage()) { Toast.makeText(this, "存储空间不足", Toast.LENGTH_LONG).show(); return; }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String outputPath;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File cacheDir = getExternalCacheDir(); if (cacheDir == null) cacheDir = getCacheDir();
            outputPath = new File(cacheDir, "TVCamera_" + timestamp + ".mp4").getAbsolutePath();
        } else {
            File photoDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "TVCamera");
            if (!photoDir.exists()) photoDir.mkdirs();
            outputPath = new File(photoDir, "TVCamera_" + timestamp + ".mp4").getAbsolutePath();
        }

        videoRecorder.startRecording(outputPath, previewSize, cameraId);
        Surface recorderSurface = videoRecorder.getInputSurface();
        if (recorderSurface != null) rebuildSessionForRecording(recorderSurface);
    }

    private void rebuildSessionForRecording(Surface recorderSurface) {
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null) return;
            if (previewSize != null) texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);

            if (captureSession != null) { captureSession.close(); captureSession = null; }

            Surface readerSurface = imageReader.getSurface();
            List<Surface> surfaces = Arrays.asList(previewSurface, recorderSurface, readerSurface);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder rb = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        rb.addTarget(previewSurface);
                        rb.addTarget(recorderSurface);
                        rb.addTarget(readerSurface); // 录像时也往 ImageReader 送帧，保持预览刷新
                        rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        rb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        session.setRepeatingRequest(rb.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) { Log.e(TAG, "录像会话失败", e); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "录像会话配置失败"); videoRecorder.stopRecording();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) { Log.e(TAG, "重建录像会话失败", e); videoRecorder.stopRecording(); }
    }

    private void stopRecording() {
        videoRecorder.stopRecording();
        rebuildSessionForPreview();
    }

    private void rebuildSessionForPreview() {
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null) return;
            if (previewSize != null) texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface readerSurface = imageReader.getSurface();

            if (captureSession != null) { captureSession.close(); captureSession = null; }

            // 统一用 ImageView
            runOnUiThread(() -> {
                previewView.setVisibility(View.INVISIBLE);
                processedPreview.setVisibility(View.VISIBLE);
            });

            List<Surface> surfaces = Arrays.asList(previewSurface, readerSurface);
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        previewBuilder.addTarget(readerSurface); // 统一输出到 ImageReader
                        previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) { Log.e(TAG, "恢复预览失败", e); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) { Log.e(TAG, "恢复预览失败"); }
            }, backgroundHandler);
        } catch (CameraAccessException e) { Log.e(TAG, "重建预览失败", e); }
    }

    // ==================== 文件保存 ====================

    private void saveToFile(byte[] jpegData, String timestamp) throws IOException {
        File photoDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "TVCamera");
        if (!photoDir.exists()) photoDir.mkdirs();
        File photoFile = new File(photoDir, "TVCamera_" + timestamp + ".jpg");
        FileOutputStream fos = new FileOutputStream(photoFile); fos.write(jpegData); fos.close();
        Uri fileUri = Uri.fromFile(photoFile);
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri));
        getContentResolver().notifyChange(fileUri, null);
        runOnUiThread(() -> Toast.makeText(this, "已保存: " + photoFile.getName(), Toast.LENGTH_SHORT).show());
    }

    private void saveWithMediaStore(byte[] jpegData, String timestamp) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "TVCamera_" + timestamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/TVCamera");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) { runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()); return; }

        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) { getContentResolver().delete(uri, null, null); return; }
            os.write(jpegData);
            ContentValues update = new ContentValues();
            update.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, update, null, null);
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
            getContentResolver().notifyChange(uri, null);
            runOnUiThread(() -> Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            getContentResolver().delete(uri, null, null);
            runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
        }
    }

    private void saveVideoToMediaStore(String cacheFilePath) {
        File cacheFile = new File(cacheFilePath);
        if (!cacheFile.exists() || cacheFile.length() == 0) return;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, cacheFile.getName());
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/TVCamera");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return;

        try (OutputStream os = getContentResolver().openOutputStream(uri);
             FileInputStream fis = new FileInputStream(cacheFile)) {
            if (os == null) { getContentResolver().delete(uri, null, null); return; }
            byte[] buf = new byte[8192]; int len;
            while ((len = fis.read(buf)) != -1) os.write(buf, 0, len);
            ContentValues update = new ContentValues();
            update.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, update, null, null);
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
            getContentResolver().notifyChange(uri, null);
        } catch (IOException e) {
            getContentResolver().delete(uri, null, null);
        } finally { cacheFile.delete(); }
    }

    // ==================== 摄像头切换 ====================

    private void showCameraSwitchDialog() {
        if (isRecordingVideo) { Toast.makeText(this, "录像中无法切换", Toast.LENGTH_SHORT).show(); return; }
        if (availableCameraIds.size() <= 1) { Toast.makeText(this, "只有一个摄像头", Toast.LENGTH_SHORT).show(); return; }

        new AlertDialog.Builder(this)
                .setTitle("切换摄像头")
                .setItems(availableCameraNames.toArray(new String[0]), (dialog, which) -> {
                    String newId = availableCameraIds.get(which);
                    if (!newId.equals(cameraId)) {
                        closeCamera(); cameraId = newId; preferences.saveLastCameraId(cameraId); openCamera();
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    // ==================== 资源管理 ====================

    private void closeCamera() {
        isPreviewing = false; isOpening = false;
        if (captureSession != null) { captureSession.close(); captureSession = null; }
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewView == null || previewSize == null || viewWidth == 0 || viewHeight == 0) return;
        Matrix matrix = new Matrix();
        float scaleX = (float) viewWidth / previewSize.getWidth();
        float scaleY = (float) viewHeight / previewSize.getHeight();
        // center-crop：填满屏幕，裁剪多余部分，无拉伸
        float scale = Math.max(scaleX, scaleY);
        float scaledW = previewSize.getWidth() * scale;
        float scaledH = previewSize.getHeight() * scale;
        float dx = (viewWidth - scaledW) / 2f;
        float dy = (viewHeight - scaledH) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        previewView.setTransform(matrix);
    }

    /** 在 UI 线程安全地应用预览变换 */
    private void applyPreviewTransform() {
        int w = previewView.getWidth();
        int h = previewView.getHeight();
        if (w > 0 && h > 0) {
            configureTransform(w, h);
        } else {
            previewView.post(() -> configureTransform(previewView.getWidth(), previewView.getHeight()));
        }
    }

    private Size chooseBestSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        Size largest = sizes[0];
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            long largestArea = (long) largest.getWidth() * largest.getHeight();
            if (area > largestArea) {
                largest = size;
            }
        }
        return largest;
    }

    // ==================== 按键 ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        showControls();

        if (qualityPanelOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { closeQualityPanel(); return true; }
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_CAMERA:
                takePhoto(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (isRecordingVideo) { stopRecording(); return true; }
                finish(); return true;
            case KeyEvent.KEYCODE_MENU:
                showCameraSwitchDialog(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                resetHideTimer(); break;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                previewView.post(this::openCamera);
            } else { Toast.makeText(this, "需要摄像头权限", Toast.LENGTH_LONG).show(); finish(); }
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 用户手动触发
            } else { Toast.makeText(this, "需要存储权限", Toast.LENGTH_LONG).show(); }
        }
    }
}
