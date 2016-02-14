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


public class FPVTutorialActivity extends Activity implements SurfaceTextureListener, OnClickListener, SensorEventListener {

    private static final String TAG = FPVTutorialActivity.class.getName();
    private static final String MYTAG = "self";
    private static final int INTERVAL_LOG = 300;
    private static long mLastTime = 0l;

    protected CameraReceivedVideoDataCallback mReceivedVideoDataCallBack = null;
    protected DJIOnReceivedVideoCallback mOnReceivedVideoCallback = null;

    private DJIBaseProduct mProduct = null;
    private DJICamera mCamera = null;
    // Codec for video live view
    protected DJICodecManager mCodecManager = null;

    protected TextView mConnectStatusTextView;
    //Video Preview
    protected TextureView mVideoSurface = null;
    private Button captureAction, recordAction, captureMode, devButton;
    private TextView viewTimer;
    private int i = 0;
    private int TIME = 1000;

    //Gimbal control
    private DJIGimbal gimbal;
    private boolean followMode = false;

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
    private int yawMax = 300;
    private int yawMin = -300;
    private int rollMax = 20;
    private int rollMin = -20;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fpvtutorial);

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

        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(FPVTutorialApplication.FLAG_CONNECTION_CHANGE);
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

        unregisterReceiver(mReceiver);

        super.onDestroy();
    }

    private void initUI() {
        mConnectStatusTextView = (TextView) findViewById(R.id.ConnectStatusTextView);
        // init mVideoSurface
        mVideoSurface = (TextureView)findViewById(R.id.video_previewer_surface);

        viewTimer = (TextView) findViewById(R.id.timer);
        captureAction = (Button) findViewById(R.id.button1);
        recordAction = (Button) findViewById(R.id.button2);
        captureMode = (Button) findViewById(R.id.button3);
        devButton = (Button) findViewById(R.id.button4);

        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }
        captureAction.setOnClickListener(this);
        recordAction.setOnClickListener(this);
        captureMode.setOnClickListener(this);
        devButton.setOnClickListener(this);
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

    private void initPreviewer() {
        try {
            mProduct = FPVTutorialApplication.getProductInstance();
        } catch (Exception exception) {
            mProduct = null;
        }

        if (null == mProduct || !mProduct.isConnected()) {
            mCamera = null;
            showToast(getString(R.string.disconnected));
        } else {
            try {
                gimbal = mProduct.getGimbal();
            } catch ( Exception e ) {
                gimbal = null;
            }

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
            mProduct = FPVTutorialApplication.getProductInstance();
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
        DJIBaseProduct product = FPVTutorialApplication.getProductInstance();
        if (product != null) {

            if(product.isConnected()) {
                //The product is connected
                mConnectStatusTextView.setText(FPVTutorialApplication.getProductInstance().getModel() + " Connected");
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
                Toast.makeText(FPVTutorialActivity.this, msg, Toast.LENGTH_SHORT).show();
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

                if ( followMode ) {
                    slowdownCounter --;
                    if ( slowdownCounter <= 0 ) {
                        mapGimbalDirection();
                        slowdownCounter = 5;
                        Log.v("Orientation", "a:" + (int)azimuth + " p:" + (int)pitch + " r:" + (int)roll);
                    }
                }

                //Log.v("Orientation", "y:" + (int)yaw + " d:" + (int)azimuthDelta + " a:" + (int)azimuth);

            }
        }
    }





    @Override
    public void onClick(View v) {


        try {
            mProduct = FPVTutorialApplication.getProductInstance();
        } catch (Exception exception) {
            mProduct = null;
        }

        if (null == mProduct || !mProduct.isConnected()) {
            mCamera = null;
            showToast(getString(R.string.disconnected));
        } else {

            switch (v.getId()) {
                case R.id.button1: {
                    captureAction();
                    break;
                }
                case R.id.button2: {
                    recordAction();
                    break;
                }
                case R.id.button3: {
                    stopRecord();
                    break;
                }
                case R.id.button4: {
                    devFunction();
                    break;
                }
                default:
                    break;
            }
        }
        switch (v.getId()) {
            case R.id.button1:{
                dev1();
                break;
            }
            case R.id.button2:{
                dev2();
                break;
            }
            case R.id.button3:{
                dev3();
                break;
            }
            case R.id.button4:{
                dev4();
                break;
            }
            default:
                break;
        }
    }
    // function for taking photo
    private void captureAction(){

        CameraMode cameraMode = CameraMode.ShootPhoto;

        mCamera = mProduct.getCamera();

        mCamera.setCameraMode(cameraMode, new DJICompletionCallback(){

            @Override
            public void onResult(DJIError error)
            {

                if (error == null) {
                    CameraShootPhotoMode photoMode = CameraShootPhotoMode.Single; // Set the camera capture mode as Single mode

                    mCamera.startShootPhoto(photoMode, new DJICompletionCallback(){

                        @Override
                        public void onResult(DJIError error)
                        {
                            if (error == null) {
                                showToast("take photo: success");
                            }else {
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
        CameraMode cameraMode = CameraMode.RecordVideo;

        mCamera = mProduct.getCamera();

        mCamera.setCameraMode(cameraMode, new DJICompletionCallback(){

            @Override
            public void onResult(DJIError error)
            {
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
        djiPitch.angle = -pitch;
        djiRoll.angle = -roll;
        djiYaw.angle = yaw;
        gimbal.rotateGimbalByAngle(
                DJIGimbal.DJIGimbalRotateAngleMode.AbsoluteAngle,
                djiPitch, djiRoll, djiYaw,
                null
        );
    }

    private void devFunction() {
        followMode = !followMode;
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
        if ( gimbal == null ) {
            Log.e(MYTAG, "No gimbal");
            return;
        }
        DJIGimbal.DJIGimbalConstraints constraints = gimbal.getDjiGimbalConstraints();
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
