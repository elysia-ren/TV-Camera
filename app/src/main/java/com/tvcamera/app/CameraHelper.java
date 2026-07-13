package com.tvcamera.app;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 相机辅助工具类
 * 负责枚举摄像头设备、查询支持的分辨率
 */
public class CameraHelper {
    private static final String TAG = "CameraHelper";

    /**
     * 获取所有可用摄像头ID列表
     * 包括内置和外接USB摄像头
     */
    public static List<String> getCameraIds(Context context) {
        List<String> cameraIds = new ArrayList<>();
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : manager.getCameraIdList()) {
                cameraIds.add(id);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "枚举摄像头失败", e);
        }
        return cameraIds;
    }

    /**
     * 获取摄像头信息描述
     */
    public static String getCameraDescription(Context context, String cameraId) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);

            String facingStr;
            if (facing == null) {
                facingStr = "未知";
            } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                facingStr = "前置";
            } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                facingStr = "后置";
            } else if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                facingStr = "外接(USB)";
            } else {
                facingStr = "未知(" + facing + ")";
            }

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            String resolutionInfo = "";
            if (map != null) {
                // 优先查 YUV_420_888，不支持则查 JPEG，与 CameraActivity 格式选择逻辑一致
                Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                String fmt = "YUV";
                if (sizes == null || sizes.length == 0) {
                    sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
                    fmt = "JPEG";
                }
                if (sizes != null && sizes.length > 0) {
                    Size best = chooseBestSize(sizes);
                    resolutionInfo = " | " + fmt + " | 最佳: " + best.getWidth() + "x" + best.getHeight();
                }
            }

            return "摄像头 " + cameraId + " [" + facingStr + "]" + resolutionInfo;

        } catch (CameraAccessException e) {
            return "摄像头 " + cameraId + " [信息获取失败]";
        }
    }

    /**
     * 获取指定摄像头支持的分辨率列表（优先1080p）
     */
    public static Size[] getSupportedSizes(Context context, String cameraId) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                // 优先 YUV_420_888，不支持则查 JPEG
                Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                if (sizes == null || sizes.length == 0) {
                    sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
                }
                if (sizes != null && sizes.length > 0) {
                    // 按面积降序排列
                    List<Size> sizeList = new ArrayList<>();
                    Collections.addAll(sizeList, sizes);
                    Collections.sort(sizeList, new Comparator<Size>() {
                        @Override
                        public int compare(Size a, Size b) {
                            return b.getWidth() * b.getHeight() - a.getWidth() * a.getHeight();
                        }
                    });
                    return sizeList.toArray(new Size[0]);
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "获取分辨率失败", e);
        }
        return new Size[0];
    }

    /**
     * 选择最佳分辨率：按面积取最大值
     */
    public static Size chooseBestSize(Size[] sizes) {
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

    /**
     * 检查是否支持指定分辨率
     */
    public static boolean isSizeSupported(Context context, String cameraId, int width, int height) {
        Size[] sizes = getSupportedSizes(context, cameraId);
        for (Size size : sizes) {
            if (size.getWidth() == width && size.getHeight() == height) {
                return true;
            }
        }
        return false;
    }
}
