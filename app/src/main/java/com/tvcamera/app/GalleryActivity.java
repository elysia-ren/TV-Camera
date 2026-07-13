package com.tvcamera.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置图库 - 照片和视频查看器
 *
 * 功能：
 * - 网格布局显示缩略图
 * - 照片/视频混排，视频显示时长和播放图标
 * - 全屏查看照片
 * - 遥控器操作
 */
public class GalleryActivity extends Activity {
    private static final String TAG = "GalleryActivity";

    private RecyclerView thumbnailGrid;
    private ViewFlipper fullScreenFlipper;
    private TextView photoInfo;
    private TextView emptyText;

    // 媒体文件信息
    private static class MediaItem {
        Uri uri;
        String name;
        long size;
        long dateModified;
        boolean isVideo;
        long duration; // 视频时长（毫秒）

        MediaItem(Uri uri, String name, long size, long dateModified, boolean isVideo, long duration) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.dateModified = dateModified;
            this.isVideo = isVideo;
            this.duration = duration;
        }
    }

    private List<MediaItem> mediaItems = new ArrayList<>();
    private boolean isFullScreen = false;
    private int currentFullScreenIndex = 0;
    private boolean initialLoadDone = false;
    private ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        thumbnailGrid = findViewById(R.id.thumbnail_grid);
        fullScreenFlipper = findViewById(R.id.fullscreen_flipper);
        photoInfo = findViewById(R.id.photo_info);
        emptyText = findViewById(R.id.empty_text);

        thumbnailGrid.setLayoutManager(new GridLayoutManager(this, 4));
        thumbnailGrid.setHasFixedSize(true);

        loadMedia();
        initialLoadDone = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (initialLoadDone) loadMedia();
    }

    @Override
    protected void onDestroy() {
        thumbnailExecutor.shutdownNow();
        recycleFlipperBitmaps();
        super.onDestroy();
    }

    private void loadMedia() {
        mediaItems.clear();
        recycleFlipperBitmaps();
        fullScreenFlipper.removeAllViews();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadFromMediaStore();
        } else {
            loadFromFileSystem();
        }

        if (mediaItems.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            thumbnailGrid.setVisibility(View.GONE);
            photoInfo.setText("暂无照片和视频");
            return;
        }

        emptyText.setVisibility(View.GONE);
        thumbnailGrid.setVisibility(View.VISIBLE);

        MediaAdapter adapter = new MediaAdapter();
        thumbnailGrid.setAdapter(adapter);
        updateInfo(0);
    }

    /** Android 10+：从 MediaStore 加载照片和视频 */
    private void loadFromMediaStore() {
        ContentResolver resolver = getContentResolver();

        // 查询照片
        String[] photoProjection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED
        };
        String photoSelection = MediaStore.Images.Media.RELATIVE_PATH + " = ?";
        String[] photoArgs = {Environment.DIRECTORY_DCIM + "/TVCamera"};

        try (Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                photoProjection, photoSelection, photoArgs,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String name = cursor.getString(nameCol);
                    if (name != null && name.startsWith("TVCamera")) {
                        Uri uri = Uri.withAppendedPath(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                        mediaItems.add(new MediaItem(uri, name,
                                cursor.getLong(sizeCol), cursor.getLong(dateCol), false, 0));
                    }
                }
            }
        }

        // 查询视频
        String[] videoProjection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DURATION
        };
        String videoSelection = MediaStore.Video.Media.RELATIVE_PATH + " = ?";
        String[] videoArgs = {Environment.DIRECTORY_DCIM + "/TVCamera"};

        try (Cursor cursor = resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection, videoSelection, videoArgs,
                MediaStore.Video.Media.DATE_MODIFIED + " DESC")) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
                int durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String name = cursor.getString(nameCol);
                    if (name != null && name.startsWith("TVCamera")) {
                        Uri uri = Uri.withAppendedPath(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                        mediaItems.add(new MediaItem(uri, name,
                                cursor.getLong(sizeCol), cursor.getLong(dateCol),
                                true, cursor.getLong(durCol)));
                    }
                }
            }
        }

        // 按时间排序
        mediaItems.sort((a, b) -> Long.compare(b.dateModified, a.dateModified));
    }

    /** Android 9 及以下：从文件系统加载 */
    private void loadFromFileSystem() {
        File photoDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "TVCamera");
        if (!photoDir.exists()) return;

        File[] files = photoDir.listFiles();
        if (files == null) return;

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        for (File file : files) {
            String name = file.getName().toLowerCase();
            boolean isVideo = name.endsWith(".mp4");
            boolean isPhoto = name.endsWith(".jpg") || name.endsWith(".jpeg");
            if (isVideo || isPhoto) {
                Uri uri = Uri.fromFile(file);
                mediaItems.add(new MediaItem(uri, file.getName(),
                        file.length(), file.lastModified() / 1000, isVideo, 0));
            }
        }
    }

    private void updateInfo(int position) {
        if (position >= 0 && position < mediaItems.size()) {
            MediaItem item = mediaItems.get(position);
            String type = item.isVideo ? "🎬 视频" : "📷 照片";
            String sizeStr = (item.size / 1024) + "KB";
            if (item.size > 1024 * 1024) sizeStr = String.format("%.1fMB", item.size / 1048576.0);
            photoInfo.setText(type + " | " + item.name + " | " + sizeStr +
                    " | " + (position + 1) + "/" + mediaItems.size());
        }
    }

    private String formatDuration(long ms) {
        int secs = (int) (ms / 1000);
        int mins = secs / 60;
        secs = secs % 60;
        return String.format("%02d:%02d", mins, secs);
    }

    // ==================== 全屏查看 ====================

    private void showFullScreen(int position) {
        // 视频暂不支持全屏播放
        if (mediaItems.get(position).isVideo) {
            Toast.makeText(this, "暂不支持视频播放", Toast.LENGTH_SHORT).show();
            return;
        }
        isFullScreen = true;
        currentFullScreenIndex = position;
        fullScreenFlipper.setVisibility(View.VISIBLE);
        thumbnailGrid.setVisibility(View.GONE);

        thumbnailGrid.clearFocus();
        fullScreenFlipper.setFocusable(true);
        fullScreenFlipper.setFocusableInTouchMode(true);
        fullScreenFlipper.requestFocus();

        rebuildFlipperFor(position);
        updateFullScreenInfo();
    }

    private void rebuildFlipperFor(int centerIndex) {
        recycleFlipperBitmaps();
        fullScreenFlipper.removeAllViews();

        int count = mediaItems.size();
        int start = Math.max(0, centerIndex - 1);
        int end = Math.min(count - 1, centerIndex + 1);

        for (int i = start; i <= end; i++) {
            if (mediaItems.get(i).isVideo) continue; // 跳过视频

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            Bitmap bitmap = decodeSampledBitmap(mediaItems.get(i).uri, 1920, 1080);
            iv.setImageBitmap(bitmap);
            fullScreenFlipper.addView(iv);
        }

        fullScreenFlipper.setDisplayedChild(Math.min(centerIndex - start, fullScreenFlipper.getChildCount() - 1));
    }

    private void updateFullScreenInfo() {
        MediaItem item = mediaItems.get(currentFullScreenIndex);
        photoInfo.setText("全屏 | " + item.name +
                " | " + (currentFullScreenIndex + 1) + "/" + mediaItems.size() +
                " | 左右切换 | 返回退出");
    }

    private void exitFullScreen() {
        isFullScreen = false;
        recycleFlipperBitmaps();
        fullScreenFlipper.removeAllViews();
        fullScreenFlipper.setVisibility(View.GONE);
        thumbnailGrid.setVisibility(View.VISIBLE);
        thumbnailGrid.requestFocus();
    }

    private void recycleFlipperBitmaps() {
        for (int i = 0; i < fullScreenFlipper.getChildCount(); i++) {
            View child = fullScreenFlipper.getChildAt(i);
            if (child instanceof ImageView) {
                ImageView iv = (ImageView) child;
                if (iv.getDrawable() instanceof BitmapDrawable) {
                    Bitmap bm = ((BitmapDrawable) iv.getDrawable()).getBitmap();
                    if (bm != null && !bm.isRecycled()) bm.recycle();
                }
                iv.setImageDrawable(null);
            }
        }
    }

    // ==================== 位图解码 ====================

    private Bitmap decodeSampledBitmap(Uri uri, int reqWidth, int reqHeight) {
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
            Log.e(TAG, "解码失败: " + uri, e);
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfH = height / 2;
            int halfW = width / 2;
            while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    // ==================== 按键处理 ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int count = mediaItems.size();
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
                    View focused = thumbnailGrid.getFocusedChild();
                    int pos = focused != null ?
                            thumbnailGrid.getChildAdapterPosition(focused) : -1;
                    if (pos >= 0) showFullScreen(pos);
                    else if (count > 0) showFullScreen(0);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    finish();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ==================== 适配器 ====================

    private class MediaAdapter extends RecyclerView.Adapter<MediaViewHolder> {
        @Override
        public MediaViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(GalleryActivity.this)
                    .inflate(R.layout.item_gallery, parent, false);
            return new MediaViewHolder(view);
        }

        @Override
        public void onBindViewHolder(MediaViewHolder holder, int position) {
            MediaItem item = mediaItems.get(position);

            holder.fileName.setText(item.name);
            holder.videoBadge.setVisibility(item.isVideo ? View.VISIBLE : View.GONE);
            if (item.isVideo && item.duration > 0) {
                holder.videoDuration.setText(formatDuration(item.duration));
            }

            // 聚焦动画
            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start();
                    v.setElevation(12f);
                    updateInfo(holder.getBindingAdapterPosition());
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                    v.setElevation(0f);
                }
            });

            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) showFullScreen(pos);
            });

            // 异步加载缩略图
            holder.thumbnail.setImageBitmap(null);
            final int pos = position;
            thumbnailExecutor.execute(() -> {
                Bitmap thumb = null;
                if (item.isVideo) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            thumb = getContentResolver().loadThumbnail(
                                    item.uri, new Size(320, 180), null);
                        } else {
                            // Android 9：使用文件路径（loadFromFileSystem 返回 file:// URI）
                            String path = item.uri.getPath();
                            if (path != null) {
                                thumb = ThumbnailUtils.createVideoThumbnail(
                                        path, android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "视频缩略图获取失败", e);
                    }
                } else {
                    thumb = decodeSampledBitmap(item.uri, 320, 180);
                }

                final Bitmap finalThumb = thumb;
                holder.thumbnail.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        if (finalThumb != null && !finalThumb.isRecycled()) finalThumb.recycle();
                        return;
                    }
                    int adapterPos = holder.getBindingAdapterPosition();
                    if (adapterPos == pos && adapterPos != RecyclerView.NO_POSITION) {
                        holder.thumbnail.setImageBitmap(finalThumb);
                    } else {
                        if (finalThumb != null && !finalThumb.isRecycled()) finalThumb.recycle();
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return mediaItems.size();
        }
    }

    private static class MediaViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail;
        LinearLayout videoBadge;
        TextView videoDuration;
        TextView fileName;

        MediaViewHolder(View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.thumbnail);
            videoBadge = itemView.findViewById(R.id.video_badge);
            videoDuration = itemView.findViewById(R.id.video_duration);
            fileName = itemView.findViewById(R.id.file_name);
        }
    }
}
