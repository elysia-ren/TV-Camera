package com.tvcamera.app;

import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

/**
 * 画质优化核心类 - 基于 OpenCV 的实时图像处理
 *
 * 处理链：白平衡 → CLAHE → 亮度 → 降噪 → 锐化
 *
 * 设计原则：
 * - 预分配 Mat 对象，避免每帧创建/销毁
 * - CLAHE 对象缓存复用
 * - 720p 以上跳过降噪和锐化（太重）
 * - 处理失败自动跳过，不影响预览
 */
public class ImageProcessor {
    private static final String TAG = "ImageProcessor";

    private static boolean opencvInitialized = false;

    // 预分配的 Mat 对象（复用）
    private Mat yuvMat;
    private Mat rgbMat;
    private Mat processedMat;
    private Mat tempMat;
    private Mat claheMat;

    // 缓存的 CLAHE 对象（避免每帧创建）
    private CLAHE claheInstance;

    private QualityPreferences prefs;

    // FPS 统计
    private long frameCount = 0;
    private long fpsStartTime = 0;
    private float currentFps = 0;

    private Bitmap outputBitmap;

    public static boolean initOpenCV() {
        if (opencvInitialized) return true;
        try {
            opencvInitialized = OpenCVLoader.initDebug();
            Log.i(opencvInitialized ? TAG : TAG,
                    opencvInitialized ? "OpenCV 初始化成功: " + Core.VERSION : "OpenCV 初始化失败");
        } catch (Exception e) {
            Log.e(TAG, "OpenCV 初始化异常", e);
        }
        return opencvInitialized;
    }

    public static boolean isOpenCvReady() {
        return opencvInitialized;
    }

    public ImageProcessor(QualityPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * 预分配 Mat 对象
     */
    public void prepare(int width, int height) {
        release();
        yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
        rgbMat = new Mat(height, width, CvType.CV_8UC3);
        processedMat = new Mat(height, width, CvType.CV_8UC3);
        tempMat = new Mat(height, width, CvType.CV_8UC3);
        claheMat = new Mat();
        claheInstance = Imgproc.createCLAHE(2.0, new Size(8, 8));
        outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Log.i(TAG, "Mat 已分配: " + width + "x" + height);
    }

    /**
     * 处理一帧 YUV 数据
     *
     * @param nv21Data NV21 格式帧数据
     * @return 处理后的 Bitmap，优化关闭返回 null
     */
    public Bitmap processFrame(byte[] nv21Data, int width, int height) {
        if (!opencvInitialized || !prefs.isEnabled()) return null;
        if (nv21Data == null) return null;

        long startTime = System.nanoTime();

        try {
            if (yuvMat == null || yuvMat.rows() != height + height / 2) {
                prepare(width, height);
            }

            yuvMat.put(0, 0, nv21Data);
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21);
            rgbMat.copyTo(processedMat);

            boolean isHeavyAllowed = (width <= 1280 && height <= 720);

            if (prefs.isWhiteBalance()) applyWhiteBalance(processedMat);
            if (prefs.isClahe()) applyClahe(processedMat);
            if (prefs.isBrightness()) applyBrightness(processedMat);
            if (prefs.isDenoise() && isHeavyAllowed) applyDenoise(processedMat);
            if (prefs.isSharpen() && isHeavyAllowed) applySharpen(processedMat);

            Utils.matToBitmap(processedMat, outputBitmap);
            updateFps();

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            if (elapsed > 40) Log.w(TAG, "帧处理耗时: " + elapsed + "ms");

            return outputBitmap;
        } catch (Exception e) {
            Log.e(TAG, "帧处理失败", e);
            return null;
        }
    }

    // ==================== 画质优化算法 ====================

    /** 白平衡 - 灰度世界算法 */
    private void applyWhiteBalance(Mat src) {
        try {
            java.util.List<Mat> channels = new java.util.ArrayList<>();
            Core.split(src, channels);

            double avgR = Core.mean(channels.get(0)).val[0];
            double avgG = Core.mean(channels.get(1)).val[0];
            double avgB = Core.mean(channels.get(2)).val[0];
            double avg = (avgR + avgG + avgB) / 3.0;

            if (avgR > 0) Core.multiply(channels.get(0), new org.opencv.core.Scalar(avg / avgR), channels.get(0));
            if (avgG > 0) Core.multiply(channels.get(1), new org.opencv.core.Scalar(avg / avgG), channels.get(1));
            if (avgB > 0) Core.multiply(channels.get(2), new org.opencv.core.Scalar(avg / avgB), channels.get(2));

            Core.merge(channels, src);
            for (Mat ch : channels) ch.release();
        } catch (Exception e) {
            Log.w(TAG, "白平衡失败", e);
        }
    }

    /** CLAHE 对比度增强（LAB 空间 L 通道） */
    private void applyClahe(Mat src) {
        try {
            Imgproc.cvtColor(src, tempMat, Imgproc.COLOR_RGB2Lab);
            java.util.List<Mat> labChannels = new java.util.ArrayList<>();
            Core.split(tempMat, labChannels);

            if (claheInstance == null) {
                claheInstance = Imgproc.createCLAHE(2.0, new Size(8, 8));
            }
            claheInstance.apply(labChannels.get(0), claheMat);
            claheMat.copyTo(labChannels.get(0));

            Core.merge(labChannels, tempMat);
            Imgproc.cvtColor(tempMat, src, Imgproc.COLOR_Lab2RGB);
            for (Mat ch : labChannels) ch.release();
        } catch (Exception e) {
            Log.w(TAG, "CLAHE 失败", e);
        }
    }

    /** 亮度补偿 - 复用 processedMat 避免每帧分配 */
    private void applyBrightness(Mat src) {
        try {
            // convertTo 支持 src == dst（原地操作）
            src.convertTo(src, -1, 1.1, 10);
        } catch (Exception e) {
            Log.w(TAG, "亮度调整失败", e);
        }
    }

    /** 降噪 - 双边滤波 */
    private void applyDenoise(Mat src) {
        try {
            int level = prefs.getDenoiseLevel();
            int d;
            double sigmaColor, sigmaSpace;
            switch (level) {
                case 1: d = 9; sigmaColor = 75; sigmaSpace = 75; break;
                case 2: d = 15; sigmaColor = 100; sigmaSpace = 100; break;
                default: d = 5; sigmaColor = 50; sigmaSpace = 50;
            }
            // 用 tempMat 作为输出，避免分配新 Mat
            Imgproc.bilateralFilter(src, tempMat, d, sigmaColor, sigmaSpace);
            tempMat.copyTo(src);
        } catch (Exception e) {
            Log.w(TAG, "降噪失败", e);
        }
    }

    /** 锐化 - filter2D */
    private void applySharpen(Mat src) {
        try {
            int level = prefs.getSharpenLevel();
            float center;
            switch (level) {
                case 1: center = 1.5f; break;
                case 2: center = 2.0f; break;
                default: center = 1.2f;
            }
            org.opencv.core.Mat kernel = org.opencv.core.Mat.eye(3, 3, CvType.CV_32F);
            float[] kd = new float[9];
            kernel.get(0, 0, kd);
            float e = -0.1f;
            kd[0] = e; kd[1] = e; kd[2] = e;
            kd[3] = e; kd[4] = center; kd[5] = e;
            kd[6] = e; kd[7] = e; kd[8] = e;
            kernel.put(0, 0, kd);

            Imgproc.filter2D(src, tempMat, -1, kernel);
            tempMat.copyTo(src);
            kernel.release();
        } catch (Exception e) {
            Log.w(TAG, "锐化失败", e);
        }
    }

    // ==================== FPS ====================

    private void updateFps() {
        frameCount++;
        if (fpsStartTime == 0) fpsStartTime = System.currentTimeMillis();
        long elapsed = System.currentTimeMillis() - fpsStartTime;
        if (elapsed >= 1000) {
            currentFps = frameCount * 1000f / elapsed;
            frameCount = 0;
            fpsStartTime = System.currentTimeMillis();
        }
    }

    public float getCurrentFps() { return currentFps; }

    public Bitmap getOutputBitmap() { return outputBitmap; }

    public void release() {
        if (yuvMat != null) { yuvMat.release(); yuvMat = null; }
        if (rgbMat != null) { rgbMat.release(); rgbMat = null; }
        if (processedMat != null) { processedMat.release(); processedMat = null; }
        if (tempMat != null) { tempMat.release(); tempMat = null; }
        if (claheMat != null) { claheMat.release(); claheMat = null; }
        claheInstance = null;
        outputBitmap = null;
    }
}
