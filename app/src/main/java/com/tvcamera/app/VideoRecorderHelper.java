package com.tvcamera.app;

import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.IOException;

/**
 * 录像辅助类 - 封装 MediaRecorder 逻辑
 *
 * 支持：
 * - H.264 视频 + AAC 音频，MP4 封装
 * - 音频源自动降级：优先 USB 麦克风，失败则静音录像
 * - 存储空间检测
 * - 与 Camera2 的 Surface 集成
 */
public class VideoRecorderHelper {
    private static final String TAG = "VideoRecorderHelper";

    public interface RecordingCallback {
        void onRecordingStarted();
        void onRecordingStopped(String filePath);
        void onRecordingError(String message);
    }

    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private String currentFilePath;
    private RecordingCallback callback;

    public void setCallback(RecordingCallback callback) {
        this.callback = callback;
    }

    public boolean isRecording() {
        return isRecording;
    }

    /**
     * 获取存储剩余空间（MB）
     */
    public static long getAvailableStorageMB() {
        try {
            File path = Environment.getExternalStorageDirectory();
            StatFs stat = new StatFs(path.getPath());
            return stat.getAvailableBlocksLong() * stat.getBlockSizeLong() / (1024 * 1024);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 检查存储空间是否足够（至少 100MB）
     */
    public static boolean hasEnoughStorage() {
        long available = getAvailableStorageMB();
        return available < 0 || available > 100;
    }

    /**
     * 准备并启动录像
     *
     * @param outputPath 输出文件路径
     * @param previewSize 预览分辨率
     * @param cameraId 摄像头 ID（用于选择合适的 CamcorderProfile）
     */
    public void startRecording(String outputPath, Size previewSize, String cameraId) {
        if (isRecording) {
            Log.w(TAG, "已在录像中");
            return;
        }

        if (!hasEnoughStorage()) {
            if (callback != null) callback.onRecordingError("存储空间不足，无法录像");
            return;
        }

        try {
            mediaRecorder = new MediaRecorder();

            // 视频源：Surface（与 Camera2 集成）
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

            // 音频源：尝试设置，失败则静音录像
            boolean hasAudio = false;
            try {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
                hasAudio = true;
                Log.i(TAG, "音频源设置成功（CAMCORDER）");
            } catch (Exception e) {
                Log.w(TAG, "音频源 CAMCORDER 不可用，尝试 MIC");
                try {
                    mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    hasAudio = true;
                    Log.i(TAG, "音频源设置成功（MIC）");
                } catch (Exception e2) {
                    Log.w(TAG, "音频源不可用，将录制静音视频");
                    // 释放并重建（因为 setAudioSource 失败后状态不一致）
                    mediaRecorder.release();
                    mediaRecorder = new MediaRecorder();
                    mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                }
            }

            // 输出格式
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            // 视频编码：H.264
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(previewSize.getWidth(), previewSize.getHeight());
            mediaRecorder.setVideoEncodingBitRate(8_000_000); // 8 Mbps
            mediaRecorder.setVideoFrameRate(30);

            // 音频编码：AAC（如果有音频）
            if (hasAudio) {
                try {
                    mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    mediaRecorder.setAudioEncodingBitRate(128_000); // 128 kbps
                    mediaRecorder.setAudioSamplingRate(44100);
                } catch (Exception e) {
                    Log.w(TAG, "音频编码设置失败，降级为静音", e);
                    // 重建无音频的 recorder
                    mediaRecorder.release();
                    mediaRecorder = new MediaRecorder();
                    mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                    mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                    mediaRecorder.setVideoSize(previewSize.getWidth(), previewSize.getHeight());
                    mediaRecorder.setVideoEncodingBitRate(8_000_000);
                    mediaRecorder.setVideoFrameRate(30);
                }
            }

            // 输出文件
            currentFilePath = outputPath;
            mediaRecorder.setOutputFile(outputPath);

            // 最大文件大小（4GB，FAT32 限制）
            mediaRecorder.setMaxFileSize(4L * 1024 * 1024 * 1024);

            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            if (callback != null) callback.onRecordingStarted();
            Log.i(TAG, "录像已启动: " + outputPath);

        } catch (IOException e) {
            Log.e(TAG, "录像准备失败", e);
            release();
            if (callback != null) callback.onRecordingError("录像启动失败: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.e(TAG, "录像启动失败", e);
            release();
            if (callback != null) callback.onRecordingError("录像启动失败: " + e.getMessage());
        }
    }

    /**
     * 获取 MediaRecorder 的 Surface（用于 Camera2 的预览/录像会话）
     */
    public android.view.Surface getInputSurface() {
        if (mediaRecorder != null) {
            return mediaRecorder.getSurface();
        }
        return null;
    }

    /**
     * 停止录像
     */
    public void stopRecording() {
        if (!isRecording || mediaRecorder == null) return;

        try {
            mediaRecorder.stop();
            Log.i(TAG, "录像已停止: " + currentFilePath);
            if (callback != null) callback.onRecordingStopped(currentFilePath);
        } catch (RuntimeException e) {
            Log.e(TAG, "停止录像失败（可能文件过短）", e);
            // stop() 失败时文件可能损坏，删除它
            if (currentFilePath != null) {
                new File(currentFilePath).delete();
            }
            if (callback != null) callback.onRecordingError("录像保存失败");
        } finally {
            release();
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        isRecording = false;
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
            } catch (Exception e) {
                Log.e(TAG, "释放 MediaRecorder 异常", e);
            }
            mediaRecorder = null;
        }
    }
}
