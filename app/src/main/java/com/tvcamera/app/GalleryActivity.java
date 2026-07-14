package com.tvcamera.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置图库 - 照片和视频查看器
 * 直接从磁盘读取，不依赖 MediaStore
 */
public class GalleryActivity extends Activity {
    private static final String TAG = "GalleryActivity";

    private RecyclerView thumbnailGrid;
    private ViewFlipper fullScreenFlipper;
    private TextView photoInfo;
    private TextView emptyText;

    private List<File> photoFiles = new ArrayList<>();
    private boolean isFullScreen = false;
    private int currentFullScreenIndex = 0;
    private boolean initialLoadDone = false;
    private boolean settingsOpened = false;
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

        initialLoadDone = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Environment.isExternalStorageManager()) {
                settingsOpened = false;
                loadPhotos();
            } else if (!settingsOpened) {
                settingsOpened = true;
                emptyText.setVisibility(View.VISIBLE);
                emptyText.setText("需要「所有文件访问权限」\n请前往设置开启后返回");
                thumbnailGrid.setVisibility(View.GONE);
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        } else {
            loadPhotos();
        }
    }

    @Override
    protected void onDestroy() {
        thumbnailExecutor.shutdownNow();
        recycleFlipperBitmaps();
        super.onDestroy();
    }

    private void loadPhotos() {
        photoFiles.clear();
        recycleFlipperBitmaps();
        fullScreenFlipper.removeAllViews();

        File photoDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "TVCamera");
        if (photoDir.exists()) {
            File[] files = photoDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".mp4");
            });
            if (files != null) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                photoFiles.addAll(Arrays.asList(files));
            }
        }

        if (photoFiles.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText("暂无照片和视频\n\n去拍一张吧！");
            thumbnailGrid.setVisibility(View.GONE);
            photoInfo.setText("");
            return;
        }

        emptyText.setVisibility(View.GONE);
        thumbnailGrid.setVisibility(View.VISIBLE);

        ThumbnailAdapter adapter = new ThumbnailAdapter();
        thumbnailGrid.setAdapter(adapter);
        updateInfo(0);
    }

    private String getName(int pos) {
        return photoFiles.get(pos).getName();
    }

    private boolean isVideo(int pos) {
        return getName(pos).toLowerCase().endsWith(".mp4");
    }

    private void updateInfo(int position) {
        if (position >= 0 && position < photoFiles.size()) {
            String type = isVideo(position) ? "🎬 视频" : "📷 照片";
            File f = photoFiles.get(position);
            String sizeStr = (f.length() / 1024) + "KB";
            if (f.length() > 1024 * 1024) sizeStr = String.format("%.1fMB", f.length() / 1048576.0);
            photoInfo.setText(type + " | " + getName(position) + " | " + sizeStr +
                    " | " + (position + 1) + "/" + photoFiles.size());
        }
    }

    // ==================== 全屏查看 ====================

    private void showFullScreen(int position) {
        if (isVideo(position)) {
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

        int count = photoFiles.size();
        int start = Math.max(0, centerIndex - 1);
        int end = Math.min(count - 1, centerIndex + 1);

        for (int i = start; i <= end; i++) {
            if (isVideo(i)) continue;

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            Bitmap bitmap = BitmapFactory.decodeFile(photoFiles.get(i).getAbsolutePath(), opts);
            iv.setImageBitmap(bitmap);
            fullScreenFlipper.addView(iv);
        }

        fullScreenFlipper.setDisplayedChild(Math.min(centerIndex - start, fullScreenFlipper.getChildCount() - 1));
    }

    private void updateFullScreenInfo() {
        photoInfo.setText("全屏 | " + getName(currentFullScreenIndex) +
                " | " + (currentFullScreenIndex + 1) + "/" + photoFiles.size() +
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

    // ==================== 按键处理 ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int count = photoFiles.size();
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
                Bitmap thumb = null;
                try {
                    if (isVideo(pos)) {
                        thumb = ThumbnailUtils.createVideoThumbnail(
                                photoFiles.get(pos).getAbsolutePath(),
                                android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
                    } else {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 4;
                        thumb = BitmapFactory.decodeFile(photoFiles.get(pos).getAbsolutePath(), opts);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "缩略图加载失败", e);
                }
                final Bitmap finalThumb = thumb;
                iv.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        if (finalThumb != null && !finalThumb.isRecycled()) finalThumb.recycle();
                        return;
                    }
                    int adapterPos = holder.getBindingAdapterPosition();
                    if (adapterPos == pos && adapterPos != RecyclerView.NO_POSITION) {
                        iv.setImageBitmap(finalThumb);
                    } else {
                        if (finalThumb != null && !finalThumb.isRecycled()) finalThumb.recycle();
                    }
                });
            });

            holder.itemView.setOnClickListener(v -> {
                int adapterPos = holder.getBindingAdapterPosition();
                if (adapterPos != RecyclerView.NO_POSITION) showFullScreen(adapterPos);
            });
            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.setAlpha(hasFocus ? 1.0f : 0.7f);
                if (hasFocus) {
                    int adapterPos = holder.getBindingAdapterPosition();
                    if (adapterPos != RecyclerView.NO_POSITION) updateInfo(adapterPos);
                }
            });
        }

        @Override
        public int getItemCount() {
            return photoFiles.size();
        }
    }

    private static class ThumbnailViewHolder extends RecyclerView.ViewHolder {
        ThumbnailViewHolder(View itemView) { super(itemView); }
    }
}
