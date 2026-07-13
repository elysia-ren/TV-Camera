package com.tvcamera.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 摄像头偏好设置 - 记住用户选择
 *
 * 功能：
 * - 记住上次使用的摄像头 ID
 * - 单摄像头时自动进入预览
 * - 多摄像头时跳过选择列表，直接进入上次的摄像头
 */
public class CameraPreferences {
    private static final String PREF_NAME = "tvcamera_prefs";
    private static final String KEY_LAST_CAMERA_ID = "last_camera_id";
    private static final String KEY_HAS_USED = "has_used";

    private final SharedPreferences prefs;

    public CameraPreferences(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 保存上次使用的摄像头 ID */
    public void saveLastCameraId(String cameraId) {
        prefs.edit()
                .putString(KEY_LAST_CAMERA_ID, cameraId)
                .putBoolean(KEY_HAS_USED, true)
                .apply();
    }

    /** 获取上次使用的摄像头 ID，首次使用返回 null */
    public String getLastCameraId() {
        if (!prefs.getBoolean(KEY_HAS_USED, false)) {
            return null;
        }
        return prefs.getString(KEY_LAST_CAMERA_ID, null);
    }

    /** 是否曾经使用过（非首次） */
    public boolean hasUsedBefore() {
        return prefs.getBoolean(KEY_HAS_USED, false);
    }

    /** 清除记忆（用于重置） */
    public void clear() {
        prefs.edit().clear().apply();
    }
}
