package com.tvcamera.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Size;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面 - 摄像头选择
 *
 * 功能：
 * - 首次使用：单摄像头自动进入预览，多摄像头显示选择列表
 * - 再次使用：直接进入上次选择的摄像头（跳过选择）
 * - 摄像头卡片式布局，聚焦缩放动画
 */
public class MainActivity extends Activity {
    private ListView cameraListView;
    private Button galleryButton;
    private TextView emptyHint;
    private TextView statusText;
    private List<CameraItem> cameraItems = new ArrayList<>();
    private CameraPreferences preferences;
    private volatile boolean isStartingCamera = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = new CameraPreferences(this);

        cameraListView = findViewById(R.id.camera_list);
        galleryButton = findViewById(R.id.btn_gallery);
        emptyHint = findViewById(R.id.empty_hint);
        statusText = findViewById(R.id.status_text);

        galleryButton.setOnClickListener(v ->
                startActivity(new Intent(this, GalleryActivity.class)));

        cameraListView.setOnItemClickListener((parent, view, position, id) -> {
            openCamera(position);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        isStartingCamera = false;
        enumerateAndAutoEnter();
    }

    /**
     * 枚举摄像头并判断是否自动进入
     */
    private void enumerateAndAutoEnter() {
        List<String> ids = CameraHelper.getCameraIds(this);
        cameraItems.clear();

        for (String id : ids) {
            String desc = CameraHelper.getCameraDescription(this, id);
            Size[] sizes = CameraHelper.getSupportedSizes(this, id);
            Size bestSize = sizes.length > 0 ? CameraHelper.chooseBestSize(sizes) : new Size(0, 0);
            cameraItems.add(new CameraItem(id, desc, bestSize));
        }

        if (cameraItems.isEmpty()) {
            emptyHint.setVisibility(View.VISIBLE);
            cameraListView.setVisibility(View.GONE);
            statusText.setText("未检测到摄像头");
            return;
        }

        emptyHint.setVisibility(View.GONE);
        cameraListView.setVisibility(View.VISIBLE);

        // ===== 自动进入逻辑 =====
        // 1. 只有一个摄像头 → 直接进入
        if (cameraItems.size() == 1) {
            statusText.setText("已自动选择摄像头");
            openCamera(0);
            return;
        }

        // 2. 有上次使用的摄像头 → 直接进入
        String lastId = preferences.getLastCameraId();
        if (lastId != null) {
            for (int i = 0; i < cameraItems.size(); i++) {
                if (cameraItems.get(i).id.equals(lastId)) {
                    statusText.setText("已恢复上次选择");
                    openCamera(i);
                    return;
                }
            }
        }

        // 3. 多个摄像头，无记忆 → 显示选择列表
        statusText.setText("选择摄像头（" + cameraItems.size() + " 个可用）");
        populateList();
    }

    private void populateList() {
        ArrayAdapter<CameraItem> adapter = new ArrayAdapter<CameraItem>(
                this, R.layout.item_camera, R.id.camera_name, cameraItems) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                CameraItem item = getItem(position);
                if (item != null) {
                    TextView nameView = view.findViewById(R.id.camera_name);
                    TextView sizeView = view.findViewById(R.id.camera_size);
                    nameView.setText(item.description);
                    if (item.bestSize.getWidth() > 0) {
                        sizeView.setText("分辨率: " + item.bestSize.getWidth() + "x" + item.bestSize.getHeight());
                    } else {
                        sizeView.setText("分辨率: 未知");
                    }
                }
                return view;
            }
        };

        cameraListView.setAdapter(adapter);
        cameraListView.requestFocus();
    }

    private void openCamera(int position) {
        if (isStartingCamera) return;
        if (position < 0 || position >= cameraItems.size()) return;

        isStartingCamera = true;
        CameraItem item = cameraItems.get(position);
        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra("camera_id", item.id);
        intent.putExtra("width", item.bestSize.getWidth());
        intent.putExtra("height", item.bestSize.getHeight());
        startActivity(intent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (cameraItems.isEmpty()) return true;
            int pos = cameraListView.getSelectedItemPosition();
            if (pos >= 0 && pos < cameraItems.size()) {
                openCamera(pos);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    static class CameraItem {
        String id;
        String description;
        Size bestSize;

        CameraItem(String id, String description, Size bestSize) {
            this.id = id;
            this.description = description;
            this.bestSize = bestSize;
        }
    }
}
