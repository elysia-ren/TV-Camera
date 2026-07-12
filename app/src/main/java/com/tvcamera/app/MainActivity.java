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
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面 - 摄像头选择
 * 显示所有可用摄像头，支持遥控器选择
 */
public class MainActivity extends Activity {
    private ListView cameraListView;
    private Button galleryButton;
    private TextView emptyHint;
    private List<CameraItem> cameraItems = new ArrayList<>();

    // 防止重复启动 CameraActivity
    private volatile boolean isStartingCamera = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraListView = findViewById(R.id.camera_list);
        galleryButton = findViewById(R.id.btn_gallery);
        emptyHint = findViewById(R.id.empty_hint);

        enumerateCameras();

        galleryButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, GalleryActivity.class)));

        // ListView 点击事件（触摸模式 + 遥控器确认键）
        cameraListView.setOnItemClickListener((parent, view, position, id) -> {
            openCamera(position);
        });

        cameraListView.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isStartingCamera = false;
        enumerateCameras();
    }

    private void enumerateCameras() {
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
            Toast.makeText(this, "未检测到摄像头", Toast.LENGTH_LONG).show();
            return;
        }

        emptyHint.setVisibility(View.GONE);
        cameraListView.setVisibility(View.VISIBLE);

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

    /**
     * 遥控器按键处理
     * 作为 onItemClickListener 的备用方案：
     * 某些 TV 系统的 ListView 对 DPAD_CENTER 的处理不一致，
     * 直接在 onKeyDown 中捕获确保一定能触发。
     */
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
