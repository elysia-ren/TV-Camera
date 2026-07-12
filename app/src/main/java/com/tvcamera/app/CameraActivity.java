package com.tvcamera.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 相机预览 + 拍照界面
 *
 * 架构设计（解决 JPEG 格式双 Surface 兼容问题）：
 * - 预览会话：TextureView + ImageReader 都加入 Session，但预览请求只输出到 TextureView
 * - 拍照：YUV 模式从 ImageReader 持续更新的帧中取最新；JPEG 模式用单次 capture 请求取一帧
 * - ImageReader 不参与预览流，只在拍照时工作，避免格式冲突导致 onConfigureFailed
 */
public class CameraActivity extends Activity {
    private static final String TAG = "CameraActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int REQUEST_STORAGE_PERMISSION = 101;

    private TextureView previewView;
    private TextView statusText;
    private Button captureButton;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private CaptureRequest.Builder previewBuilder;

    private String cameraId;
    private int requestedWidth = 1920;
    private int requestedHeight = 1080;
    private Size previewSize;
    private int chosenImageFormat = ImageFormat.YUV_420_888;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private volatile boolean isPreviewing = false;
    private volatile boolean isOpening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cameraId = getIntent().getStringExtra("camera_id");
        requestedWidth = getIntent().getIntExtra("width", 1920);
        requestedHeight = getIntent().getIntExtra("height", 1080);

        previewView = findViewById(R.id.preview_view);
        statusText = findViewById(R.id.status_text);
        captureButton = findViewById(R.id.btn_capture);

        captureButton.setOnClickListener(v -> takePhoto());
        previewView.setSurfaceTextureListener(surfaceTextureListener);

        statusText.setText("正在打开摄像头 " + cameraId + "...");
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (previewView.isAvailable()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "停止后台线程失败", e);
            }
            backgroundThread = null;
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                    openCamera();
                }
                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {
                    configureTransform(w, h);
                }
                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }
                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    /** 打开摄像头（synchronized + 幂等保护） */
    private synchronized void openCamera() {
        if (isOpening || isPreviewing || cameraDevice != null) return;
        isOpening = true;

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                Toast.makeText(this, "无法获取摄像头配置", Toast.LENGTH_LONG).show();
                isOpening = false;
                finish();
                return;
            }

            chosenImageFormat = chooseImageFormat(map);
            boolean isYuv = (chosenImageFormat == ImageFormat.YUV_420_888);
            Log.i(TAG, "图像格式: " + (isYuv ? "YUV_420_888" : "JPEG"));

            Size[] outputSizes = map.getOutputSizes(chosenImageFormat);
            if (outputSizes != null && outputSizes.length > 0) {
                previewSize = chooseBestSize(outputSizes);
            } else {
                int fallback = isYuv ? ImageFormat.JPEG : ImageFormat.YUV_420_888;
                outputSizes = map.getOutputSizes(fallback);
                if (outputSizes != null && outputSizes.length > 0) {
                    chosenImageFormat = fallback;
                    previewSize = chooseBestSize(outputSizes);
                } else {
                    previewSize = new Size(requestedWidth, requestedHeight);
                }
            }
            Log.i(TAG, "预览分辨率: " + previewSize.getWidth() + "x" + previewSize.getHeight());

            // 创建 ImageReader（仅用于拍照，不参与预览流）
            imageReader = ImageReader.newInstance(
                    previewSize.getWidth(), previewSize.getHeight(),
                    chosenImageFormat, 2);
            imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);

            if (checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                isOpening = false;
                requestPermissions(
                        new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }

            manager.openCamera(cameraId, stateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "打开摄像头失败", e);
            isOpening = false;
            Toast.makeText(this, "打开摄像头失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int chooseImageFormat(StreamConfigurationMap map) {
        int[] formats = map.getOutputFormats();
        for (int fmt : formats) {
            if (fmt == ImageFormat.YUV_420_888) {
                Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (sizes != null && sizes.length > 0) return ImageFormat.YUV_420_888;
            }
        }
        for (int fmt : formats) {
            if (fmt == ImageFormat.JPEG) {
                Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                if (sizes != null && sizes.length > 0) {
                    Log.w(TAG, "不支持 YUV_420_888，降级 JPEG");
                    return ImageFormat.JPEG;
                }
            }
        }
        return ImageFormat.YUV_420_888;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            isOpening = false;
            createPreviewSession();
        }
        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            isOpening = false;
            isPreviewing = false;
            runOnUiThread(() -> {
                statusText.setText("摄像头已断开，3秒后返回...");
                Toast.makeText(CameraActivity.this, "摄像头已断开", Toast.LENGTH_SHORT).show();
                previewView.postDelayed(() -> finish(), 3000);
            });
        }
        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            isOpening = false;
            isPreviewing = false;
            runOnUiThread(() -> {
                statusText.setText("摄像头错误(" + error + ")，3秒后返回...");
                Toast.makeText(CameraActivity.this, "摄像头错误: " + error, Toast.LENGTH_LONG).show();
                previewView.postDelayed(() -> finish(), 3000);
            });
        }
    };

    /**
     * 创建预览会话
     *
     * 关键：ImageReader Surface 加入 Session（拍照时需要），
     * 但预览请求只输出到 TextureView，不输出到 ImageReader。
     * 这样 JPEG 格式的 ImageReader 不会干扰预览流。
     */
    private void createPreviewSession() {
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null) return;

            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface readerSurface = imageReader.getSurface();

            // 预览请求：只输出到 TextureView
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            // 注意：不添加 readerSurface 到预览请求

            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            // Session 包含两个 Surface（拍照时 ImageReader 需要）
            List<Surface> surfaces = Arrays.asList(previewSurface, readerSurface);
            cameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(
                                previewBuilder.build(), null, backgroundHandler);
                        isPreviewing = true;
                        runOnUiThread(() -> {
                            String fmt = (chosenImageFormat == ImageFormat.YUV_420_888)
                                    ? "YUV" : "JPEG";
                            statusText.setText("预览中 | " +
                                    previewSize.getWidth() + "x" + previewSize.getHeight() +
                                    " | " + fmt + " | 按OK键拍照");
                            configureTransform(previewView.getWidth(), previewView.getHeight());
                        });
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "启动预览失败", e);
                    }
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    // 修复：关闭摄像头，避免资源泄漏
                    closeCamera();
                    runOnUiThread(() -> {
                        Toast.makeText(CameraActivity.this,
                                "预览配置失败", Toast.LENGTH_LONG).show();
                        statusText.setText("预览配置失败，3秒后返回...");
                        previewView.postDelayed(() -> finish(), 3000);
                    });
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "创建预览会话失败", e);
        }
    }

    /**
     * ImageReader 默认回调（占位）
     * 预览不输出到 ImageReader，此回调仅在未被临时替换时生效
     * 实际拍照时 takePhoto() 会临时替换为带 CountDownLatch 的回调
     */
    private final ImageReader.OnImageAvailableListener imageAvailableListener = reader -> {
        Image image = reader.acquireLatestImage();
        if (image != null) image.close();
    };

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 拍照
     *
     * YUV 模式：从 imageReader 发起单次 capture 获取 YUV 帧 → NV21 → JPEG
     * JPEG 模式：从 imageReader 发起单次 capture 获取 JPEG 字节 → 直接保存
     */
    private void takePhoto() {
        if (!hasStoragePermission()) {
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
            return;
        }

        if (captureSession == null || imageReader == null) {
            Toast.makeText(this, "摄像头未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        captureButton.setEnabled(false);
        captureButton.setText("拍照中...");

        new Thread(() -> {
            try {
                byte[] jpegData;

                if (chosenImageFormat == ImageFormat.YUV_420_888) {
                    // YUV 模式：单次 capture 到 ImageReader 获取 YUV 帧
                    jpegData = captureYuvAndCompress();
                } else {
                    // JPEG 模式：单次 capture 到 ImageReader 获取 JPEG 字节
                    jpegData = captureJpeg();
                }

                if (jpegData == null) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "拍照失败：未获取到图像数据", Toast.LENGTH_SHORT).show());
                    return;
                }

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(new Date());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveWithMediaStore(jpegData, timestamp);
                } else {
                    saveToFile(jpegData, timestamp);
                }
            } catch (Exception e) {
                Log.e(TAG, "拍照失败", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "拍照失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                runOnUiThread(() -> {
                    captureButton.setEnabled(true);
                    captureButton.setText("📷 拍照");
                });
            }
        }).start();
    }

    /**
     * YUV 模式拍照：向 ImageReader 发起单次 capture，获取 YUV 帧后压缩为 JPEG
     */
    private byte[] captureYuvAndCompress() throws CameraAccessException, InterruptedException, IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        final byte[][] result = new byte[1][];
        final int[] dims = new int[2];

        // 临时替换 ImageReader 回调，用 CountDownLatch 等待单帧
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                byte[] nv21 = YuvToNv21Converter.yuv420ToNv21(image);
                result[0] = nv21;
                dims[0] = image.getWidth();
                dims[1] = image.getHeight();
            } finally {
                image.close();
                latch.countDown();
            }
        }, backgroundHandler);

        // 单次 capture 请求
        CaptureRequest.Builder captureBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.addTarget(imageReader.getSurface());
        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON);
        captureSession.capture(captureBuilder.build(), null, backgroundHandler);

        // 等待帧到达（最多 3 秒）
        boolean received = latch.await(3, TimeUnit.SECONDS);

        // 恢复原始回调
        imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);

        if (!received || result[0] == null) return null;

        // NV21 → JPEG
        YuvImage yuvImage = new YuvImage(result[0], ImageFormat.NV21, dims[0], dims[1], null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, dims[0], dims[1]), 95, baos);
        byte[] jpeg = baos.toByteArray();
        baos.close();
        return jpeg;
    }

    /**
     * JPEG 模式拍照：向 ImageReader 发起单次 capture，获取 JPEG 字节
     */
    private byte[] captureJpeg() throws CameraAccessException, InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final byte[][] result = new byte[1][];

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                result[0] = bytes;
            } finally {
                image.close();
                latch.countDown();
            }
        }, backgroundHandler);

        CaptureRequest.Builder captureBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.addTarget(imageReader.getSurface());
        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON);
        captureSession.capture(captureBuilder.build(), null, backgroundHandler);

        boolean received = latch.await(3, TimeUnit.SECONDS);

        imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);

        return (received && result[0] != null) ? result[0] : null;
    }

    private void saveToFile(byte[] jpegData, String timestamp) throws IOException {
        File photoDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "TVCamera");
        if (!photoDir.exists()) photoDir.mkdirs();

        File photoFile = new File(photoDir, "TVCamera_" + timestamp + ".jpg");
        FileOutputStream fos = new FileOutputStream(photoFile);
        fos.write(jpegData);
        fos.close();

        runOnUiThread(() -> Toast.makeText(this, "已保存: " + photoFile.getName(),
                Toast.LENGTH_SHORT).show());
    }

    private void saveWithMediaStore(byte[] jpegData, String timestamp) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "TVCamera_" + timestamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + "/TVCamera");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
            return;
        }

        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                getContentResolver().delete(uri, null, null);
                runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
                return;
            }
            os.write(jpegData);
            ContentValues update = new ContentValues();
            update.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, update, null, null);
            runOnUiThread(() -> Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            Log.e(TAG, "保存失败", e);
            getContentResolver().delete(uri, null, null);
            runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
        }
    }

    private void closeCamera() {
        isPreviewing = false;
        isOpening = false;
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewView == null || previewSize == null || viewWidth == 0 || viewHeight == 0) return;

        int bufW = previewSize.getWidth();
        int bufH = previewSize.getHeight();
        Matrix matrix = new Matrix();
        float scaleX = (float) viewWidth / bufW;
        float scaleY = (float) viewHeight / bufH;
        float scale = Math.max(scaleX, scaleY);
        float dx = (viewWidth - bufW * scale) / 2f;
        float dy = (viewHeight - bufH * scale) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        previewView.setTransform(matrix);
    }

    private Size chooseBestSize(Size[] sizes) {
        List<Size> sorted = new ArrayList<>(Arrays.asList(sizes));
        Collections.sort(sorted, (a, b) ->
                b.getWidth() * b.getHeight() - a.getWidth() * a.getHeight());
        for (Size s : sorted)
            if (s.getWidth() == 1920 && s.getHeight() == 1080) return s;
        for (Size s : sorted)
            if (s.getWidth() == 1280 && s.getHeight() == 720) return s;
        for (Size s : sorted)
            if (s.getWidth() == 640 && s.getHeight() == 480) return s;
        return sorted.get(0);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_CAMERA:
                takePhoto();
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            case KeyEvent.KEYCODE_MENU:
                startActivity(new Intent(this, GalleryActivity.class));
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                previewView.post(this::openCamera);
            } else {
                Toast.makeText(this, "需要摄像头权限", Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                takePhoto();
            } else {
                Toast.makeText(this, "需要存储权限才能保存照片", Toast.LENGTH_LONG).show();
            }
        }
    }
}
