package com.tvcamera.app;

import android.media.Image;
import java.nio.ByteBuffer;

/**
 * 将 Camera2 API 的 YUV_420_888 Image 转换为 NV21 字节数组
 * NV21 格式: Y 平面 + 交错的 VU 数据 (YYYYYYYY VUVU)
 *
 * 关键：U/V 平面的 rowStride 和 pixelStride 可能不一致，
 * 必须分别独立处理，不能假设两者相同。
 */
public class YuvToNv21Converter {

    /**
     * 从 ByteBuffer 读取 U 或 V 字节
     * pixelStride=1 时直接读取；pixelStride=2 时取交错数据的第一个字节
     * pixelStride>2 属于非标格式，降级取首字节并记录警告
     */
    private static byte getUV(ByteBuffer buffer, int rowOffset, int col, int pixelStride) {
        return buffer.get(rowOffset + col * pixelStride);
    }

    /**
     * 将 YUV_420_888 Image 转换为 NV21 byte[]
     *
     * 修复要点：
     * 1. Y 平面也考虑 yPixelStride（某些设备 Y 平面 stride=2）
     * 2. U/V 平面分别独立获取 rowStride 和 pixelStride
     * 3. 使用绝对位置读取 get(index)，不修改 buffer position
     * 4. 通过 getUV() 辅助方法统一处理 pixelStride=1/2 的情况
     */
    public static byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];

        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];

        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();

        int yRowStride = yPlane.getRowStride();
        int yPixelStride = yPlane.getPixelStride();

        // 检测非标格式并记录日志
        int uvPixelStrideU = uPlane.getPixelStride();
        int uvPixelStrideV = vPlane.getPixelStride();
        if (uvPixelStrideU > 2 || uvPixelStrideV > 2) {
            android.util.Log.w("YuvToNv21",
                    "非标准 pixelStride: U=" + uvPixelStrideU + " V=" + uvPixelStrideV
                    + "，可能存在颜色异常");
        }

        // ========== 拷贝 Y 平面 ===========
        int yOffset = 0;
        for (int row = 0; row < height; row++) {
            int rowOffset = row * yRowStride;
            for (int col = 0; col < width; col++) {
                nv21[yOffset++] = yBuffer.get(rowOffset + col * yPixelStride);
            }
        }

        // ========== 拷贝 UV 平面（分别独立处理） ===========
        int uvRowStrideU = uPlane.getRowStride();
        int uvRowStrideV = vPlane.getRowStride();

        int uvHeight = height / 2;
        int uvWidth = width / 2;
        int nv21Offset = width * height;

        for (int row = 0; row < uvHeight; row++) {
            int rowOffsetU = row * uvRowStrideU;
            int rowOffsetV = row * uvRowStrideV;
            for (int col = 0; col < uvWidth; col++) {
                // NV21 格式: V 在前, U 在后
                nv21[nv21Offset++] = getUV(vBuffer, rowOffsetV, col, uvPixelStrideV);
                nv21[nv21Offset++] = getUV(uBuffer, rowOffsetU, col, uvPixelStrideU);
            }
        }

        return nv21;
    }

    /**
     * 将 YUYV 数据转换为 NV21（用于某些 USB 摄像头直接输出 YUYV 的场景）
     */
    public static byte[] yuyvToNv21(byte[] yuyv, int width, int height) {
        byte[] nv21 = new byte[width * height * 3 / 2];
        int ySize = width * height;
        int nv21Offset = ySize;

        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j += 2) {
                int yuyvIndex = (i * width + j) * 2;
                if (yuyvIndex + 3 >= yuyv.length) break;

                byte y0 = yuyv[yuyvIndex];
                byte u = yuyv[yuyvIndex + 1];
                byte y1 = yuyv[yuyvIndex + 2];
                byte v = yuyv[yuyvIndex + 3];

                int yIndex = i * width + j;
                nv21[yIndex] = y0;
                if (j + 1 < width) {
                    nv21[yIndex + 1] = y1;
                }

                if (i % 2 == 0 && j % 2 == 0) {
                    nv21[nv21Offset++] = v;
                    nv21[nv21Offset++] = u;
                }
            }
        }
        return nv21;
    }
}
