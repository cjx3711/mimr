package com.dji.fpvtutorial;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import dji.sdk.AirLink.DJILBAirLink.DJIOnReceivedVideoCallback;
import dji.sdk.Battery.DJIBattery;
import dji.sdk.Camera.DJICamera;
import dji.sdk.Camera.DJICamera.CameraReceivedVideoDataCallback;
import dji.sdk.Codec.DJICodecManager;
import dji.sdk.Gimbal.DJIGimbal;
import dji.sdk.Products.DJIAircraft;
import dji.sdk.base.DJIBaseComponent.DJICompletionCallback;
import dji.sdk.base.DJIBaseProduct;
import dji.sdk.base.DJIBaseProduct.Model;
import dji.sdk.base.DJIError;
import dji.sdk.Camera.DJICameraSettingsDef.CameraMode;
import dji.sdk.Camera.DJICameraSettingsDef.CameraShootPhotoMode;


public class CameraActivity extends Activity implements SurfaceTextureListener, OnClickListener, SensorEventListener {

    private static final String TAG = CameraActivity.class.getName();
    private static final String MYTAG = "self";
    private static final int INTERVAL_LOG = 300;
    private static long mLastTime = 0l;

    protected CameraReceivedVideoDataCallback mReceivedVideoDataCallBack = null;
    protected DJIOnReceivedVideoCallback mOnReceivedVideoCallback = null;

    private DJIBaseProduct mProduct = null;
    private DJICamera mCamera = null;
    private DJIBattery mBattery = null;
    // Codec for video live view
    protected DJICodecManager mCodecManager = null;

    protected TextView mConnectStatusTextView, mBatteryTextView;
    //Video Preview
    protected TextureView mVideoSurface = null;
    private Button captureButton, recordButton, stopRecordButton, followButton, resetButton;
    private Button lPButton, lYButton, lRButton;
    private TextView viewTimer;
    private int i = 0;
    private int TIME = 1000;

    //Gimbal control
    private DJIGimbal mGimbal;
    private boolean followMode = false;
    private boolean recording = false;

    //Phone stuff
    public static double roll = 0;
    public static double pitch = 0;
    public static double yaw = 0;
    public static double azimuth = 0;
    public static double azimuthPrev = 0;
    public static double azimuthDelta = 0;
    public static int slowdownCounter = 0;

    public static SensorManager mSensorManager;
    public static Sensor accelerometer;
    public static Sensor magnetometer;

    public static float[] mAccelerometer = null;
    public static float[] mGeomagnetic = null;
    private DJIGimbal.DJIGimbalAngleRotation djiPitch;
    private DJIGimbal.DJIGimbalAngleRotation djiRoll;
    private DJIGimbal.DJIGimbalAngleRotation djiYaw;
    private PowerManager.WakeLock wakeLock;
    private int pitchMax = 89;
    private int pitchMin = -20;
    private int yawMax = 180;
    private int yawMin = -180;
    private int rollMax = 20;
    private int rollMin = -20;

    private boolean lockedYaw = false, lockedPitch = false, lockedRoll = false;


    public CameraActivity() {
        mStringBuffer = new StringBuffer();
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        initUI();

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataCallBack = new CameraReceivedVideoDataCallback() {

            @Override
            public void onResult(byte[] videoBuffer, int size) {
                if(mCodecManager != null){
                    // Send the raw H264 video data to codec manager for decoding
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }else {
                    Log.e(TAG, "mCodecManager is null");
                }
            }
        };

        // The callback for receiving the raw video data from Airlink
        mOnReceivedVideoCallback = new DJIOnReceivedVideoCallback() {
            @Override
            public void onResult(byte[] videoBuffer, int size) {
                if(mCodecManager != null){
                    // Send the raw H264 video data to codec manager for decoding
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }
            }
        };

        //EventBus.getDefault().register(this);

        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(MimrApplication.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);



        //Sensor stuff
        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);


        djiPitch = new DJIGimbal.DJIGimbalAngleRotation(true, 0, DJIGimbal.DJIGimbalRotateDirection.Clockwise);
        djiRoll = new DJIGimbal.DJIGimbalAngleRotation(true, 0, DJIGimbal.DJIGimbalRotateDirection.Clockwise);
        djiYaw = new DJIGimbal.DJIGimbalAngleRotation(true, 0, DJIGimbal.DJIGimbalRotateDirection.Clockwise);

    }


    @Override
    public void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();
        initPreviewer();
        updateTitleBar();
        if(mVideoSurface == null) {
            Log.e(TAG, "mVideoSurface is null");
        }

        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        mSensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);

        PowerManager powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "My Lock");
        wakeLock.acquire();
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        uninitPreviewer();
        super.onPause();


        mSensorManager.unregisterListener(this, accelerometer);
        mSensorManager.unregisterListener(this, magnetometer);
        wakeLock.release();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
    }

    public void onReturn(View view){
        Log.e(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        uninitPreviewer();

        //EventBus.getDefault().unregister(this);
        unregisterReceiver(mReceiver);

        super.onDestroy();
    }

    private void initUI() {
        mConnectStatusTextView = (TextView) findViewById(R.id.ConnectStatusTextView);
        mBatteryTextView = (TextView) findViewById(R.id.battery_percentage);
        // init mVideoSurface
        mVideoSurface = (TextureView)findViewById(R.id.video_previewer_surface);

        viewTimer = (TextView) findViewById(R.id.timer);
        captureButton = (Button) findViewById(R.id.capture_btn);
        recordButton = (Button) findViewById(R.id.record_btn);
        stopRecordButton = (Button) findViewById(R.id.stop_record_btn);
        followButton = (Button) findViewById(R.id.follow_btn);
        resetButton = (Button) findViewById(R.id.reset_btn);
        stopRecordButton.setVisibility(View.GONE);
        resetButton.setVisibility(View.GONE);

        lPButton = (Button) findViewById(R.id.lock_pitch);
        lRButton = (Button) findViewById(R.id.lock_roll);
        lYButton = (Button) findViewById(R.id.lock_yaw);

        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }
        captureButton.setOnClickListener(this);
        recordButton.setOnClickListener(this);
        stopRecordButton.setOnClickListener(this);
        resetButton.setOnClickListener(this);
        followButton.setOnClickListener(this);
        lPButton.setOnClickListener(this);
        lRButton.setOnClickListener(this);
        lYButton.setOnClickListener(this);
    }

    private Handler handlerTimer = new Handler();
    Runnable runnable = new Runnable(){
        @Override
        public void run() {
            // handler自带方法实现定时器
            try {
                handlerTimer.postDelayed(this, TIME);
                viewTimer.setText(Integer.toString(i++));

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    protected static final int CHANGE_TEXT_VIEW = 0;

    protected StringBuffer mStringBuffer;
    protected Handler mHandler = new Handler(new Handler.Callback() {

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case CHANGE_TEXT_VIEW :
                    mBatteryTextView.setText(mStringBuffer.toString());
                    break;

                default:
                    break;
            }
            return false;
        }
    });

    private void initPreviewer() {
        try {
            mProduct = MimrApplication.getProductInstance();
        } catch (Exception exception) {
            mProduct = null;
        }

        if (null == mProduct || !mProduct.isConnected()) {
            mCamera = null;
            showToast(getString(R.string.disconnected));
        } else {
            try {
                mGimbal = mProduct.getGimbal();
            } catch ( Exception e ) {
                mGimbal = null;
            }
            try {
                mBattery = mProduct.getBattery();
            } catch ( Exception e ) {
                mBattery = null;
            }

            mBattery.setBatteryStateUpdateCallback(
                    new DJIBattery.DJIBatteryStateUpdateCallback() {
                        @Override
                        public void onResult(DJIBattery.DJIBatteryState djiBatteryState) {
                            mStringBuffer.delete(0, mStringBuffer.length());

                            mStringBuffer.append(djiBatteryState.getBatteryEnergyRemainingPercent()).
                                    append("% ");
                            mStringBuffer.
                                    append(djiBatteryState.getCurrentVoltage()).append("mV ");
                            mStringBuffer.
                                    append(djiBatteryState.getCurrentCurrent()).append("mA");

                            mHandler.sendEmptyMessage(CHANGE_TEXT_VIEW);
                        }
                    }
            );

//            if ( mGimbal != null ) {
//                mGimbal.setGimbalSmoothTrackDeadbandOnAxis(DJIGimbal.DJIGimbalSmoothTrackAxis.Yaw, 90, null);
//                mGimbal.setGimbalSmoothTrackDeadbandOnAxis(DJIGimbal.DJIGimbalSmoothTrackAxis.Pitch, 90, null);
//            }

//
//            mGimbal.getGimbalSmoothTrackDeadbandOnAxis(DJIGimbal.DJIGimbalSmoothTrackAxis.Yaw,
//                    new DJIBaseComponent.DJICompletionCallbackWith<Integer>() {
//                        @Override
//                        public void onSuccess(Integer integer) {
//                            Log.i(MYTAG, "Yaw Deadpan: " + integer);
//                        }
//                        @Override
//                        public void onFailure(DJIError djiError) {}
//                    });
//            mGimbal.getGimbalSmoothTrackDeadbandOnAxis(DJIGimbal.DJIGimbalSmoothTrackAxis.Pitch,
//                    new DJIBaseComponent.DJICompletionCallbackWith<Integer>() {
//                        @Override
//                        public void onSuccess(Integer integer) {
//                            Log.i(MYTAG, "Pitch Deadpan: " + integer);
//                        }
//                        @Override
//                        public void onFailure(DJIError djiError) {}
//                    });
//
//            mGimbal.getGimbalSmoothTrackAccelerationOnAxis(DJIGimbal.DJIGimbalSmoothTrackAxis.Pitch,
//                    new DJIBaseComponent.DJICompletionCallbackWith<Integer>() {
//                        @Override
//                        public void onSuccess(Integer integer) {
//                            Log.i(MYTAG, "Pitch Acceleration: " + integer);
//                        }
//
//                        @Override
//                        public void onFailure(DJIError djiError) {
//                        }
//                    });
//
//            mGimbal.getGimbalSmoothTrackAccelerationOnAxis(DJIGimbal.DJIGimbalSmoothTrackAxis.Yaw,
//                    new DJIBaseComponent.DJICompletionCallbackWith<Integer>() {
//                        @Override
//                        public void onSuccess(Integer integer) {
//                            Log.i(MYTAG, "Yaw Acceleration: " + integer);
//                        }
//
//                        @Override
//                        public void onFailure(DJIError djiError) {
//                        }
//                    });
//
//            mGimbal.getGimbalSmoothTrackSpeedOnAxis(DJIGimbal.DJIGimbalSmoothTrackAxis.Pitch,
//                    new DJIBaseComponent.DJICompletionCallbackWith<Integer>() {
//                        @Override
//                        public void onSuccess(Integer integer) {
//                            Log.i(MYTAG, "Pitch Speed: " + integer);
//                        }
//
//                        @Override
//                        public void onFailure(DJIError djiError) {
//                        }
//                    });
//            mGimbal.getGimbalSmoothTrackSpeedOnAxis(DJIGimbal.DJIGimbalSmoothTrackAxis.Yaw,
//                    new DJIBaseComponent.DJICompletionCallbackWith<Integer>() {
//                        @Override
//                        public void onSuccess(Integer integer) {
//                            Log.i(MYTAG, "Yaw Speed: " + integer);
//                        }
//
//                        @Override
//                        public void onFailure(DJIError djiError) {
//                        }
//                    });



            if (null != mVideoSurface) {
                mVideoSurface.setSurfaceTextureListener(this);
            }

            if (!mProduct.getModel().equals(Model.UnknownAircraft)) {
                mCamera = mProduct.getCamera();
                if (mCamera != null){
                    // Set the callback
                    mCamera.setDJICameraReceivedVideoDataCallback(mReceivedVideoDataCallBack);

                }
            } else {
                if (null != mProduct.getAirLink()) {
                    if (null != mProduct.getAirLink().getLBAirLink()) {
                        // Set the callback
                        mProduct.getAirLink().getLBAirLink().setDJIOnReceivedVideoCallback(mOnReceivedVideoCallback);
                    }
                }
            }
        }
    }

    private void uninitPreviewer() {
        try {
            mProduct = MimrApplication.getProductInstance();
        } catch (Exception exception) {
            mProduct = null;
        }

        if (null == mProduct || !mProduct.isConnected()) {
            mCamera = null;
            showToast(getString(R.string.disconnected));
        } else {
            if (!mProduct.getModel().equals(Model.UnknownAircraft)) {
                mCamera = mProduct.getCamera();
                if (mCamera != null){
                    // Set the callback
                    mCamera.setDJICameraReceivedVideoDataCallback(null);

                }
            } else {
                if (null != mProduct.getAirLink()) {
                    if (null != mProduct.getAirLink().getLBAirLink()) {
                        // Set the callback
                        mProduct.getAirLink().getLBAirLink().setDJIOnReceivedVideoCallback(null);
                    }
                }
            }
        }
    }

    //
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.e(TAG,"onSurfaceTextureAvailable");
        if (mCodecManager == null) {
            Log.e(TAG, "mCodecManager is null 2");
            mCodecManager = new DJICodecManager(this, surface, width, height);
        }
    }

    //
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.e(TAG,"onSurfaceTextureSizeChanged");
    }

    //
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.e(TAG,"onSurfaceTextureDestroyed");
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }

        return false;
    }

    //
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        Log.e(TAG, "onSurfaceTextureUpdated");
    }

    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            updateTitleBar();
            onProductChange();
        }

    };


    private void updateTitleBar() {
        if(mConnectStatusTextView == null) return;
        boolean ret = false;
        DJIBaseProduct product = MimrApplication.getProductInstance();
        if (product != null) {

            if(product.isConnected()) {
                //The product is connected
                mConnectStatusTextView.setText(MimrApplication.getProductInstance().getModel() + " Connected");
                ret = true;
            } else {

                if(product instanceof DJIAircraft) {
                    DJIAircraft aircraft = (DJIAircraft)product;
                    if(aircraft.getRemoteController() != null && aircraft.getRemoteController().isConnected()) {
                        // The product is not connected, but the remote controller is connected
                        mConnectStatusTextView.setText("only RC Connected");
                        ret = true;
                    }
                }
            }
        }

        if(!ret) {
            // The product or the remote controller are not connected.
            mConnectStatusTextView.setText("Disconnected");
        }
    }

    protected void onProductChange() {
        initPreviewer();

    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            final long current = System.currentTimeMillis();
            if (current - mLastTime < INTERVAL_LOG) {
                Log.d("", "click double");
                mLastTime = 0;
            } else {
                mLastTime = current;
                Log.d("", "click single");
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(CameraActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }


    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // onSensorChanged gets called for each sensor so we have to remember the values
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            mAccelerometer = event.values;
        }

        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            mGeomagnetic = event.values;
        }

        if (mAccelerometer != null && mGeomagnetic != null) {
            float R[] = new float[9];
            float I[] = new float[9];
            boolean success = SensorManager.getRotationMatrix(R, I, mAccelerometer, mGeomagnetic);

            if (success) {
                float orientation[] = new float[3];
                SensorManager.getOrientation(R, orientation);
                // at this point, orientation contains the azimuth(direction), pitch and roll values.
                azimuth = 180 * orientation[0] / Math.PI;
                roll = 180 * orientation[1] / Math.PI;
                pitch = 180 * orientation[2] / Math.PI - 90;

                azimuthDelta = azimuth - azimuthPrev;
                if ( azimuthDelta > 270 ) azimuthDelta -= 360;
                if ( azimuthDelta < -270 ) azimuthDelta += 360;
                azimuthPrev = azimuth;

                if (followMode) yaw += azimuthDelta;
                if ( yaw < yawMin ) yaw = yawMin;
                if ( yaw > yawMax ) yaw = yawMax;
                if ( roll < rollMin ) roll = rollMin;
                if ( roll > rollMax ) roll = rollMax;
                if ( pitch < pitchMin ) pitch = pitchMin;
                if ( pitch > pitchMax ) pitch = pitchMax;

                roll = lockedRoll ? 0 : roll;
                yaw = lockedYaw ? 0 : yaw;
                pitch = lockedPitch ? 0 : pitch;

                if ( followMode ) {
                    slowdownCounter --;
                    if ( slowdownCounter <= 0 ) {
                        mapGimbalDirection();
                        slowdownCounter = 5;
                        //Log.v("Orientation", "a:" + (int)azimuth + " p:" + (int)pitch + " r:" + (int)roll);
                    }
                }

                //Log.v("Orientation", "y:" + (int)yaw + " d:" + (int)azimuthDelta + " a:" + (int)azimuth);

            }
        }
    }





    @Override
    public void onClick(View v) {
        try {
            mProduct = MimrApplication.getProductInstance();
        } catch (Exception exception) {
            mProduct = null;
        }

        if (null == mProduct || !mProduct.isConnected()) {
            mCamera = null;
            showToast(getString(R.string.disconnected));
        } else {

            switch (v.getId()) {
                case R.id.capture_btn: {
                    captureAction();
                    break;
                }
                case R.id.record_btn: {
                    recordAction();
                    break;
                }
                case R.id.stop_record_btn: {
                    stopRecord();
                    break;
                }
                case R.id.reset_btn: {
                    resetView();
                    break;
                }
                case R.id.follow_btn: {
                    toggleFollow();
                    break;
                }
                case R.id.lock_pitch: {
                    toggleLockPitch();
                    break;
                }
                case R.id.lock_roll: {
                    toggleLockRoll();
                    break;
                }
                case R.id.lock_yaw: {
                    toggleLockYaw();
                    break;
                }
                default:
                    break;
            }
        }
        switch (v.getId()) {
            case R.id.capture_btn:{
                dev1();
                break;
            }
            case R.id.record_btn:{
                dev2();
                break;
            }
            case R.id.stop_record_btn:{
                dev3();
                break;
            }
            case R.id.follow_btn:{
                dev4();
                break;
            }
            default:
                break;
        }
    }

    private void toggleLockPitch() {
        lockedPitch = !lockedPitch;
        if ( lockedPitch ) {
            showToast("Pitch locked");
            lPButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_locked, 0, 0, 0);
        }
        else {
            showToast("Pitch unlocked");
            lPButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_unlocked, 0, 0, 0);
        }
    }
    private void toggleLockRoll() {
        lockedRoll = !lockedRoll;
        if ( lockedRoll ) {
            showToast("Roll locked");
            lRButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_locked, 0, 0, 0);
        }
        else {
            showToast("Roll unlocked");
            lRButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_unlocked, 0, 0, 0);
        }
    }
    private void toggleLockYaw() {
        lockedYaw = !lockedYaw;
        if ( lockedYaw ) {
            showToast("Yaw locked");
            lYButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_locked, 0, 0, 0);
        }
        else {
            showToast("Yaw unlocked");
            lYButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_unlocked, 0, 0, 0);
        }
    }

    private void resetView() {
        yaw = 0;
    }
    private void updateRecordButtons(boolean recording) {
        this.recording = recording;
        if ( !recording ) {
            captureButton.setVisibility(View.VISIBLE);
            recordButton.setVisibility(View.VISIBLE);
            stopRecordButton.setVisibility(View.GONE);
        } else {
            captureButton.setVisibility(View.GONE);
            recordButton.setVisibility(View.GONE);
            stopRecordButton.setVisibility(View.VISIBLE);
        }
    }
    // function for taking photo
    private void captureAction(){
        CameraMode cameraMode = CameraMode.ShootPhoto;
        mCamera = mProduct.getCamera();

        mCamera.setCameraMode(cameraMode, new DJICompletionCallback(){
            @Override
            public void onResult(DJIError error) {
                if (error == null) {
                    CameraShootPhotoMode photoMode = CameraShootPhotoMode.Single; // Set the camera capture mode as Single mode
                    mCamera.startShootPhoto(photoMode, new DJICompletionCallback() {
                        @Override
                        public void onResult(DJIError error) {
                            if (error == null) {
                                showToast("take photo: success");
                            } else {
                                showToast(error.getDescription());
                            }
                        }
                    }); // Execute the startShootPhoto API
                } else {
                    showToast(error.getDescription());
                }
            }
        });

    }
    // function for starting recording
    private void recordAction(){
        updateRecordButtons(true);
        CameraMode cameraMode = CameraMode.RecordVideo;
        mCamera = mProduct.getCamera();
        mCamera.setCameraMode(cameraMode, new DJICompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                if (error == null) {
                    mCamera.startRecordVideo(new DJICompletionCallback() {

                        @Override
                        public void onResult(DJIError error) {
                            if (error == null) {
                                showToast("Record video: success");
                                handlerTimer.postDelayed(runnable, TIME); // Start the timer for recording
                            } else {
                                showToast(error.getDescription());
                            }
                        }
                    }); // Execute the startShootPhoto API
                } else {
                    showToast(error.getDescription());
                }
            }
        });

    }
    // function for stopping recording
    private void stopRecord(){
        updateRecordButtons(false);
        mCamera = mProduct.getCamera();
        mCamera.stopRecordVideo(new DJICompletionCallback() {

            @Override
            public void onResult(DJIError error) {
                if (error == null) {
                    showToast("Stop recording: success");
                } else {
                    showToast(error.getDescription());
                }
                handlerTimer.removeCallbacks(runnable); // Start the timer for recording
                i = 0; // Reset the timer for recording
            }

        });
    }

    private void mapGimbalDirection () {
        setGimbalDirection((float) pitch, (float) roll, (float) yaw);
    }
    private void setGimbalDirection ( float pitch, float roll, float yaw ) {
        djiPitch.angle =  -pitch;
        djiRoll.angle = -roll;
        djiYaw.angle = yaw;
        mGimbal.rotateGimbalByAngle(
                DJIGimbal.DJIGimbalRotateAngleMode.AbsoluteAngle,
                djiPitch, djiRoll, djiYaw,
                null
        );
    }

    private void toggleFollow() {
        followMode = !followMode;
        if ( followMode ) {
            resetButton.setVisibility(View.VISIBLE);
            followButton.setText("Stop Follow");
        } else {
            resetButton.setVisibility(View.GONE);
            followButton.setText("Start Follow");
        }
        yaw = 0;
        mapGimbalDirection();
    }

    private void dev1() {

    }
    private void dev2() {

    }
    private void dev3() {

    }
    private void dev4() {
        if ( mGimbal == null ) {
            Log.e(MYTAG, "No gimbal");
            return;
        }
        DJIGimbal.DJIGimbalConstraints constraints = mGimbal.getDjiGimbalConstraints();
        if ( constraints == null ) {
            Log.e(MYTAG, "No Constraints");
            return;
        }
        pitchMax = (int) constraints.getPitchStopMax();
        pitchMin = (int) constraints.getPitchStopMin();
        yawMax = (int) constraints.getYawStopMax();
        yawMin = (int) constraints.getYawStopMin();
        rollMax = (int) constraints.getRollStopMax();
        rollMin = (int) constraints.getRollStopMin();

        Log.d(MYTAG, "Pitch: " + pitchMin + " - " + pitchMax);
        Log.d(MYTAG, "Roll : " + rollMin + " - " + rollMax);
        Log.d(MYTAG, "Yaw  : " + yawMin + " - " + yawMax);
    }
}
