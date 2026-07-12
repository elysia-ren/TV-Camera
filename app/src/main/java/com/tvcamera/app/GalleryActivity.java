package com.tvcamera.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置图库 - 照片查看器
 *
 * 照片加载策略：
 * - Android 10+：通过 MediaStore 查询，用 Uri + ContentResolver 直接解码（兼容 Scoped Storage）
 * - Android 9 及以下：直接读取文件系统
 *
 * 保证与 CameraActivity 的保存路径一致
 */
public class GalleryActivity extends Activity {
    private static final String TAG = "GalleryActivity";

    private RecyclerView thumbnailList;
    private ViewFlipper fullScreenFlipper;
    private TextView photoInfo;
    private TextView emptyText;

    // Android 10+：使用 Uri 列表（通过 ContentResolver 读取）
    private List<Uri> photoUris = new ArrayList<>();
    // Android 9 及以下：使用 File 列表（直接读文件）
    private List<File> photoFiles = new ArrayList<>();
    // 标记当前使用的模式
    private boolean useUriMode = false;

    private boolean isFullScreen = false;
    private int currentFullScreenIndex = 0;
    private boolean initialLoadDone = false;

    private ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        thumbnailList = findViewById(R.id.thumbnail_list);
        fullScreenFlipper = findViewById(R.id.fullscreen_flipper);
        photoInfo = findViewById(R.id.photo_info);
        emptyText = findViewById(R.id.empty_text);

        thumbnailList.setLayoutManager(new LinearLayoutManager(
                this, LinearLayoutManager.HORIZONTAL, false));
        thumbnailList.setHasFixedSize(true);

        AlphaAnimation fadeIn = new AlphaAnimation(0.0f, 1.0f);
        fadeIn.setDuration(200);
        AlphaAnimation fadeOut = new AlphaAnimation(1.0f, 0.0f);
        fadeOut.setDuration(200);
        fullScreenFlipper.setInAnimation(fadeIn);
        fullScreenFlipper.setOutAnimation(fadeOut);

        loadPhotos();
        initialLoadDone = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (initialLoadDone) {
            loadPhotos();
        }
    }

    @Override
    protected void onDestroy() {
        thumbnailExecutor.shutdownNow();
        recycleFlipperBitmaps();
        super.onDestroy();
    }

    /** 获取照片总数 */
    private int getPhotoCount() {
        return useUriMode ? photoUris.size() : photoFiles.size();
    }

    /**
     * 加载照片列表
     * Android 10+：MediaStore 查询 + Uri 模式
     * Android 9 及以下：文件系统 + File 模式
     */
    private void loadPhotos() {
        photoUris.clear();
        photoFiles.clear();
        recycleFlipperBitmaps();
        fullScreenFlipper.removeAllViews();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            useUriMode = true;
            loadPhotosFromMediaStore();
        } else {
            useUriMode = false;
            loadPhotosFromFileSystem();
        }

        if (getPhotoCount() == 0) {
            emptyText.setVisibility(View.VISIBLE);
            thumbnailList.setVisibility(View.GONE);
            photoInfo.setText("暂无照片");
            return;
        }

        emptyText.setVisibility(View.GONE);
        thumbnailList.setVisibility(View.VISIBLE);

        ThumbnailAdapter adapter = new ThumbnailAdapter();
        thumbnailList.setAdapter(adapter);
        updatePhotoInfo(0);
    }

    /**
     * Android 10+：通过 MediaStore 查询公共 DCIM/TVCamera 中的照片
     * 直接存储 content Uri，不解码为 File 路径（兼容 Scoped Storage）
     */
    private void loadPhotosFromMediaStore() {
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED
        };

        // 精确匹配 DCIM/TVCamera 目录（避免匹配到 TVCamera2 等其他文件夹）
        String selection = MediaStore.Images.Media.RELATIVE_PATH + " = ?";
        String[] selectionArgs = {Environment.DIRECTORY_DCIM + "/TVCamera/"};
        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " DESC";

        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder)) {

            if (cursor == null) {
                Log.w(TAG, "MediaStore 查询返回 null");
                return;
            }

            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String name = cursor.getString(nameColumn);

                if (name != null && name.startsWith("TVCamera") &&
                        (name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg"))) {

                    Uri contentUri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                    photoUris.add(contentUri);
                }
            }

            Log.i(TAG, "MediaStore 查询到 " + photoUris.size() + " 张照片");

        } catch (Exception e) {
            Log.e(TAG, "MediaStore 查询失败", e);
        }
    }

    /** Android 9 及以下：直接读取文件系统 */
    private void loadPhotosFromFileSystem() {
        File photoDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "TVCamera");

        if (photoDir.exists()) {
            File[] files = photoDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            });
            if (files != null) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                photoFiles.addAll(Arrays.asList(files));
            }
        }
    }

    /** 获取指定位置的照片名称 */
    private String getPhotoName(int position) {
        if (useUriMode) {
            // 从 Uri 中提取文件名
            Uri uri = photoUris.get(position);
            String lastPath = uri.getLastPathSegment();
            return lastPath != null ? lastPath : "photo_" + position;
        } else {
            return photoFiles.get(position).getName();
        }
    }

    /** 获取指定位置的照片大小（KB） */
    private long getPhotoSizeKb(int position) {
        if (useUriMode) {
            // 通过 ContentResolver 获取大小
            try (Cursor cursor = getContentResolver().query(
                    photoUris.get(position),
                    new String[]{MediaStore.Images.Media.SIZE},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
                    return cursor.getLong(sizeCol) / 1024;
                }
            }
            return 0;
        } else {
            return photoFiles.get(position).length() / 1024;
        }
    }

    private void updatePhotoInfo(int position) {
        int count = getPhotoCount();
        if (position >= 0 && position < count) {
            photoInfo.setText(getPhotoName(position) + " | " + getPhotoSizeKb(position) + "KB" +
                    " | " + (position + 1) + "/" + count);
        }
    }

    private void showFullScreen(int position) {
        isFullScreen = true;
        currentFullScreenIndex = position;
        fullScreenFlipper.setVisibility(View.VISIBLE);
        thumbnailList.setVisibility(View.GONE);

        // 修复：清除 RecyclerView 焦点，防止隐藏状态下拦截按键事件
        thumbnailList.clearFocus();
        fullScreenFlipper.setFocusable(true);
        fullScreenFlipper.setFocusableInTouchMode(true);
        fullScreenFlipper.requestFocus();

        rebuildFlipperFor(position);
        updateFullScreenInfo();
    }

    private void rebuildFlipperFor(int centerIndex) {
        recycleFlipperBitmaps();
        fullScreenFlipper.removeAllViews();

        int count = getPhotoCount();
        int start = Math.max(0, centerIndex - 1);
        int end = Math.min(count - 1, centerIndex + 1);

        for (int i = start; i <= end; i++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            Bitmap bitmap = decodeSampledBitmap(i, 1920, 1080);
            iv.setImageBitmap(bitmap);
            fullScreenFlipper.addView(iv);
        }

        fullScreenFlipper.setDisplayedChild(centerIndex - start);
    }

    private void updateFullScreenInfo() {
        int count = getPhotoCount();
        photoInfo.setText("全屏 | " + getPhotoName(currentFullScreenIndex) +
                " | " + (currentFullScreenIndex + 1) + "/" + count +
                " | 左右切换 | 返回退出");
    }

    private void exitFullScreen() {
        isFullScreen = false;
        recycleFlipperBitmaps();
        fullScreenFlipper.removeAllViews();
        fullScreenFlipper.setVisibility(View.GONE);
        thumbnailList.setVisibility(View.VISIBLE);
        thumbnailList.requestFocus();
    }

    private void recycleFlipperBitmaps() {
        for (int i = 0; i < fullScreenFlipper.getChildCount(); i++) {
            View child = fullScreenFlipper.getChildAt(i);
            if (child instanceof ImageView) {
                ImageView iv = (ImageView) child;
                if (iv.getDrawable() instanceof BitmapDrawable) {
                    Bitmap bm = ((BitmapDrawable) iv.getDrawable()).getBitmap();
                    if (bm != null && !bm.isRecycled()) {
                        bm.recycle();
                    }
                }
                iv.setImageDrawable(null);
            }
        }
    }

    /**
     * 统一解码入口：根据模式选择 File 解码或 Uri 解码
     */
    private Bitmap decodeSampledBitmap(int position, int reqWidth, int reqHeight) {
        if (useUriMode) {
            return decodeSampledBitmapFromUri(photoUris.get(position), reqWidth, reqHeight);
        } else {
            return decodeSampledBitmapFromFile(photoFiles.get(position), reqWidth, reqHeight);
        }
    }

    /**
     * Android 10+：通过 ContentResolver + Uri 解码（兼容 Scoped Storage）
     * 不依赖文件路径，直接从 MediaStore 读取数据流
     */
    private Bitmap decodeSampledBitmapFromUri(Uri uri, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;

            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            BitmapFactory.decodeStream(is, null, options);
            is.close();

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;

            is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
            is.close();
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Uri 解码失败: " + uri, e);
            return null;
        }
    }

    /** Android 9 及以下：直接从文件解码 */
    private Bitmap decodeSampledBitmapFromFile(File file, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int count = getPhotoCount();
        if (isFullScreen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (currentFullScreenIndex > 0) {
                        currentFullScreenIndex--;
                        rebuildFlipperFor(currentFullScreenIndex);
                        updateFullScreenInfo();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (currentFullScreenIndex < count - 1) {
                        currentFullScreenIndex++;
                        rebuildFlipperFor(currentFullScreenIndex);
                        updateFullScreenInfo();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    exitFullScreen();
                    return true;
            }
        } else {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    View focused = thumbnailList.getFocusedChild();
                    int pos = focused != null ?
                            thumbnailList.getChildAdapterPosition(focused) : -1;
                    if (pos >= 0) {
                        showFullScreen(pos);
                    } else if (count > 0) {
                        showFullScreen(0);
                    }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    finish();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 缩略图适配器 - 异步加载 */
    private class ThumbnailAdapter extends RecyclerView.Adapter<ThumbnailViewHolder> {
        @Override
        public ThumbnailViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ImageView iv = new ImageView(GalleryActivity.this);
            iv.setLayoutParams(new RecyclerView.LayoutParams(320, 240));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setFocusable(true);
            iv.setFocusableInTouchMode(true);
            return new ThumbnailViewHolder(iv);
        }

        @Override
        public void onBindViewHolder(ThumbnailViewHolder holder, int position) {
            ImageView iv = (ImageView) holder.itemView;
            iv.setImageBitmap(null);

            final int pos = position;

            thumbnailExecutor.execute(() -> {
                Bitmap thumb = decodeSampledBitmap(pos, 320, 240);
                iv.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        if (thumb != null && !thumb.isRecycled()) thumb.recycle();
                        return;
                    }
                    int adapterPos = holder.getBindingAdapterPosition();
                    if (adapterPos == pos && adapterPos != RecyclerView.NO_POSITION) {
                        iv.setImageBitmap(thumb);
                    } else {
                        if (thumb != null && !thumb.isRecycled()) {
                            thumb.recycle();
                        }
                    }
                });
            });

            holder.itemView.setOnClickListener(v -> {
                int adapterPos = holder.getBindingAdapterPosition();
                if (adapterPos != RecyclerView.NO_POSITION) {
                    showFullScreen(adapterPos);
                }
            });
            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.setAlpha(hasFocus ? 1.0f : 0.7f);
                if (hasFocus) {
                    int adapterPos = holder.getBindingAdapterPosition();
                    if (adapterPos != RecyclerView.NO_POSITION) {
                        updatePhotoInfo(adapterPos);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return getPhotoCount();
        }
    }

    private static class ThumbnailViewHolder extends RecyclerView.ViewHolder {
        ThumbnailViewHolder(View itemView) {
            super(itemView);
        }
    }
}
