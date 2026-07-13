package com.tvcamera.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Size;

/**
 * 分辨率偏好设置 - 按摄像头分别记住用户选择的分辨率
 */
public class ResolutionPreferences {
    private static final String PREF_NAME = "resolution_prefs";
    private static final String KEY_PREFIX = "resolution_";

    private final SharedPreferences prefs;

    public ResolutionPreferences(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 保存指定摄像头的分辨率 */
    public void saveResolution(String cameraId, int width, int height) {
        prefs.edit().putString(KEY_PREFIX + cameraId, width + "x" + height).apply();
    }

    /** 获取指定摄像头的分辨率，未设置返回 null */
    public Size getResolution(String cameraId) {
        String val = prefs.getString(KEY_PREFIX + cameraId, null);
        if (val == null) return null;
        try {
            String[] parts = val.split("x");
            return new Size(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (Exception e) {
            return null;
        }
    }

    /** 清除所有分辨率记忆 */
    public void clear() {
        prefs.edit().clear().apply();
    }
}
