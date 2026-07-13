package com.tvcamera.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 画质优化设置 - SharedPreferences 持久化
 *
 * 保存用户对各项画质优化的开关和强度设置
 */
public class QualityPreferences {
    private static final String PREF_NAME = "quality_prefs";

    private static final String KEY_ENABLED = "quality_enabled";
    private static final String KEY_WHITE_BALANCE = "white_balance";
    private static final String KEY_CLAHE = "clahe";
    private static final String KEY_BRIGHTNESS = "brightness";
    private static final String KEY_DENOISE = "denoise";
    private static final String KEY_DENOISE_LEVEL = "denoise_level"; // 0=低, 1=中, 2=高
    private static final String KEY_SHARPEN = "sharpen";
    private static final String KEY_SHARPEN_LEVEL = "sharpen_level"; // 0=低, 1=中, 2=高
    private static final String KEY_SAVE_OPTIMIZED = "save_optimized";
    private static final String KEY_RECORD_OPTIMIZED = "record_optimized";
    private static final String KEY_SHOW_FPS = "show_fps";

    private final SharedPreferences prefs;

    public QualityPreferences(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ===== 总开关 =====
    public boolean isEnabled() { return prefs.getBoolean(KEY_ENABLED, true); }
    public void setEnabled(boolean v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply(); }

    // ===== 各项优化开关 =====
    public boolean isWhiteBalance() { return prefs.getBoolean(KEY_WHITE_BALANCE, true); }
    public void setWhiteBalance(boolean v) { prefs.edit().putBoolean(KEY_WHITE_BALANCE, v).apply(); }

    public boolean isClahe() { return prefs.getBoolean(KEY_CLAHE, true); }
    public void setClahe(boolean v) { prefs.edit().putBoolean(KEY_CLAHE, v).apply(); }

    public boolean isBrightness() { return prefs.getBoolean(KEY_BRIGHTNESS, true); }
    public void setBrightness(boolean v) { prefs.edit().putBoolean(KEY_BRIGHTNESS, v).apply(); }

    public boolean isDenoise() { return prefs.getBoolean(KEY_DENOISE, true); }
    public void setDenoise(boolean v) { prefs.edit().putBoolean(KEY_DENOISE, v).apply(); }

    public int getDenoiseLevel() { return prefs.getInt(KEY_DENOISE_LEVEL, 0); }
    public void setDenoiseLevel(int v) { prefs.edit().putInt(KEY_DENOISE_LEVEL, v).apply(); }

    public boolean isSharpen() { return prefs.getBoolean(KEY_SHARPEN, true); }
    public void setSharpen(boolean v) { prefs.edit().putBoolean(KEY_SHARPEN, v).apply(); }

    public int getSharpenLevel() { return prefs.getInt(KEY_SHARPEN_LEVEL, 0); }
    public void setSharpenLevel(int v) { prefs.edit().putInt(KEY_SHARPEN_LEVEL, v).apply(); }

    // ===== 录制/保存选项 =====
    public boolean isSaveOptimized() { return prefs.getBoolean(KEY_SAVE_OPTIMIZED, false); }
    public void setSaveOptimized(boolean v) { prefs.edit().putBoolean(KEY_SAVE_OPTIMIZED, v).apply(); }

    public boolean isRecordOptimized() { return prefs.getBoolean(KEY_RECORD_OPTIMIZED, true); }
    public void setRecordOptimized(boolean v) { prefs.edit().putBoolean(KEY_RECORD_OPTIMIZED, v).apply(); }

    // ===== 调试 =====
    public boolean isShowFps() { return prefs.getBoolean(KEY_SHOW_FPS, false); }
    public void setShowFps(boolean v) { prefs.edit().putBoolean(KEY_SHOW_FPS, v).apply(); }
}
