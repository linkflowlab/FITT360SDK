package com.linkflow.fitt360sdk.activity;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.linkflow.fitt360sdk.R;
import com.linkflow.fitt360sdk.adapter.MainRecyclerAdapter;
import com.linkflow.fitt360sdk.dialog.RTMPStreamerDialog;
import com.linkflow.fitt360sdk.dialog.USBTetheringDialog;
import com.linkflow.fitt360sdk.service.RTMPStreamService;

import app.library.linkflow.ConnectManager;
import app.library.linkflow.connect.ConnectHelper;
import app.library.linkflow.manager.NeckbandRestApiClient;
import app.library.linkflow.manager.model.PhotoModel;
import app.library.linkflow.manager.model.RecordModel;
import app.library.linkflow.manager.model.TemperModel;
import app.library.linkflow.manager.neckband.ConnectStateManage;
import app.library.linkflow.rtmp.RTSPToRTMPConverter;

import static com.linkflow.fitt360sdk.adapter.MainRecyclerAdapter.ID.ID_GALLERY;
import static com.linkflow.fitt360sdk.adapter.MainRecyclerAdapter.ID.ID_SETTING;

public class MainActivity extends BaseActivity implements MainRecyclerAdapter.ItemClickListener, PhotoModel.Listener {
    public static final String ACTION_START_RTMP = "start_rtmp", ACTION_STOP_RTMP = "stop_rtmp";
    private static final String USB_STATE_CHANGE_ACTION = "android.hardware.usb.action.USB_STATE";
    private static final int PERMISSION_CALLBACK = 366;

    private RTSPToRTMPConverter mRSToRMConverter;
    private RecordModel mRecordModel;
    private PhotoModel mPhotoModel;

    private MainRecyclerAdapter mAdapter;

    private long mStartedRecordTime, mReceivedUsbStateChangedTime;
    private RTMPStreamerDialog mRTMPStreamerDialog;
    private USBTetheringDialog mUSBTetheringDialog;
    private boolean mRTMPStreamMute;

    private BroadcastReceiver mRTMPBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(ACTION_STOP_RTMP)) {
                    if (intent.getIntExtra("close", -1) == 10) {
                        mAdapter.changeStreamingState(false);
                    }
                } else if (action.equals(ACTION_START_RTMP)) {
                    mAdapter.changeStreamingState(true);
                }
            }
        }
    };

    private BroadcastReceiver mUsbBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equalsIgnoreCase(USB_STATE_CHANGE_ACTION)) {
                    if(intent.getExtras().getBoolean("connected")) {
                        if (isUSBTetheringActive()) {
                            Log.e("main", "usb state change action - usb tethering activated");
                            mNeckbandManager.enableRndis(true);
                        } else {
                            mNeckbandManager.getConnectStateManage().setState(ConnectStateManage.STATE.STATE_NONE);
                            mUSBTetheringDialog.show(getSupportFragmentManager());
                        }
                    } else {
                        mUSBTetheringDialog.dismissAllowingStateLoss();
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        hideHeader();
        super.onCreate(savedInstanceState);
        setBodyView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_NETWORK_STATE}, PERMISSION_CALLBACK);
        }

        mRSToRMConverter = RTSPToRTMPConverter.getInstance();
        mRTMPStreamerDialog = new RTMPStreamerDialog();
        mRTMPStreamerDialog.setClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (v.getId() == R.id.base_dialog_agree) {
                    if (!mRSToRMConverter.isRTMPWorking()) {
                        Intent intent = new Intent(MainActivity.this, RTMPStreamService.class);
                        intent.setAction(RTMPStreamService.ACTION_START_RTMP_STREAM);
                        intent.putExtra("rtmp_url", mRTMPStreamerDialog.getRTMPUrl());
                        intent.putExtra("rtmp_bitrate_auto", mRTMPStreamerDialog.enableAutoBitrate());
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent);
                        } else {
                            startService(intent);
                        }
                    }
                    mRTMPStreamerDialog.dismissAllowingStateLoss();
                } else if (v.getId() == R.id.base_dialog_disagree) {
                    mRTMPStreamerDialog.dismissAllowingStateLoss();
                }
            }
        });

        mUSBTetheringDialog = new USBTetheringDialog();
        mUSBTetheringDialog.setClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (v.getId() == R.id.base_dialog_agree) {
                    Intent tetherSettings = new Intent();
                    tetherSettings.setClassName("com.android.settings", "com.android.settings.TetherSettings");
                    startActivity(tetherSettings);
                }
                mUSBTetheringDialog.dismissAllowingStateLoss();
            }
        });

        mRecordModel = mNeckbandManager.getRecordModel(this);
        mPhotoModel = mNeckbandManager.getPhotoModel(this);

        RecyclerView recycler = findViewById(R.id.recycler);
        LinearLayoutManager manager = new LinearLayoutManager(this);
        mAdapter = new MainRecyclerAdapter(this, this);
        recycler.setLayoutManager(manager);
        recycler.setAdapter(mAdapter);

        IntentFilter rtmpFilter = new IntentFilter();
        rtmpFilter.addAction(ACTION_START_RTMP);
        rtmpFilter.addAction(ACTION_STOP_RTMP);
        LocalBroadcastManager.getInstance(this).registerReceiver(mRTMPBroadcastReceiver, rtmpFilter);

        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(USB_STATE_CHANGE_ACTION);
        registerReceiver(mUsbBroadcastReceiver , usbFilter);

        ConnectManager.getInstance(getApplicationContext()).disconnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e("connect state", "called on resume - usb tethering : " + isUSBTetheringActive());
        if (mNeckbandManager.getConnectStateManage().isConnected()) {
            mRSToRMConverter = RTSPToRTMPConverter.getInstance();
            if (mRSToRMConverter.isRTMPWorking()) {

            }
        }
        if (isUSBTetheringActive()) {
            if (!mNeckbandManager.getConnectStateManage().isConnected()) {
                mNeckbandManager.enableRndis(true);
            }
            mUSBTetheringDialog.dismissAllowingStateLoss();
        } else if (!ConnectManager.getInstance(this).isConnected()) {
            mNeckbandManager.getConnectStateManage().setState(ConnectStateManage.STATE.STATE_NONE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRSToRMConverter.isRTMPWorking()) {
            mRSToRMConverter.stop();
            mRSToRMConverter.exit();
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mRTMPBroadcastReceiver);
        unregisterReceiver(mUsbBroadcastReceiver);
        ConnectHelper.getInstance().disconnect();
        ConnectHelper.getInstance().clearRegister(getApplicationContext());
        mNeckbandManager.getPreviewModel().activateRTSP(mNeckbandManager.getAccessToken(), false);
    }

    @Override
    public void clickedItem(int position) {
        MainRecyclerAdapter.Item item = mAdapter.getItem(position);
        if (item.mId == ID_SETTING) {
            Intent intent = new Intent(this, SettingActivity.class);
            startActivity(intent);
        } else if (item.mId == ID_GALLERY) {
            Intent intent = new Intent(this, GalleryActivity.class);
            startActivity(intent);
        } else if (mNeckbandManager.getConnectStateManage().isConnected()) {
            switch (item.mId) {
                case ID_RECORDING:
                    if (System.currentTimeMillis() - mStartedRecordTime >= 2000) {
                        mStartedRecordTime = System.currentTimeMillis();
                        boolean isRecording = !mNeckbandManager.isRecording();
                        mRecordModel.actionRecord(mNeckbandManager.getAccessToken(), isRecording);
                        mAdapter.changeRecordState(isRecording);
                    } else {
                        Toast.makeText(this, R.string.alert_record_safe, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case ID_TAKE_PHOTO: mPhotoModel.takePhoto(mNeckbandManager.getAccessToken()); break;
                case ID_PREVIEW:
                    if (!mNeckbandManager.isRecording()) {
                        Intent intent = new Intent(this, PreviewActivity.class);
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "recording...", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case ID_STREAMING:
                    if (!mRSToRMConverter.isRTMPWorking()) {
                        mRTMPStreamerDialog.show(getSupportFragmentManager());
                    } else {
                        Intent intent = new Intent(MainActivity.this, RTMPStreamService.class);
                        intent.setAction(RTMPStreamService.ACTION_CANCEL_RTMP_STREAM);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent);
                        } else {
                            startService(intent);
                        }
                        mRSToRMConverter.exit();
                        mAdapter.changeStreamingState(false);
                    }
                    break;
                case ID_STREAMING_MUTE:
                    mRTMPStreamMute = !mRTMPStreamMute;
                    mRSToRMConverter.setMute(mRTMPStreamMute);
                    mAdapter.changeStreamingMute(mRTMPStreamMute);
                    break;
                case ID_TEMPERATURE:
                    boolean enabled = !(mNeckbandManager.getSetManage().isNormalLimitEnable() || mNeckbandManager.getSetManage().isSafeLimitEnable());
                    mNeckbandManager.getSetManage().getTemperModel().setTemperLimitEnable(mNeckbandManager.getAccessToken(), TemperModel.Type.NORMAL, enabled);
                    mNeckbandManager.getSetManage().getTemperModel().setTemperLimitEnable(mNeckbandManager.getAccessToken(), TemperModel.Type.SAFE, enabled);
                    mAdapter.changeTemperatureState(enabled);
                    break;
            }
        } else {
            Toast.makeText(this, "please, check wifi direct.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void completedGetRecordState(boolean success, boolean isRecording) {
        super.completedGetRecordState(success, isRecording);
        mAdapter.changeRecordState(isRecording);

    }

    @Override
    public void completedTakePhoto(boolean success, String filename) {

    }

    @Override
    public void onConnectState(ConnectStateManage.STATE state) {
        super.onConnectState(state);
        Log.e("main", "on connect state - " + state);
        if (state == ConnectStateManage.STATE.STATE_DONE) {
            mAdapter.changeTemperatureState(mNeckbandManager.getSetManage().isNormalLimitEnable() || mNeckbandManager.getSetManage().isSafeLimitEnable());
        }
    }

    @Override
    public void recordState(boolean isPhysical, boolean isRecording) {
        mAdapter.changeRecordState(isRecording);
    }

    @Override
    public void connectedRndis(String rndisIp) {
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "rndis connected", Toast.LENGTH_SHORT).show();
            }
        });
        Log.e("main", "connected rndis");
        NeckbandRestApiClient.setBaseUrl(rndisIp);
        mNeckbandManager.connect("newwifi", "123456");
    }
}
