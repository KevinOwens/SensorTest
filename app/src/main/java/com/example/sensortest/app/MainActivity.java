package com.example.sensortest.app;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.gson.JsonObject;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;
import com.koushikdutta.ion.ProgressCallback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;

public class MainActivity extends Activity implements ScanManager.ScanListener, SensorEventListener {
    private static final int REQUEST_ENABLE_BT = 1;

    public static final int LOC_UPDATE_INTERVAL_IN_SECONDS = 10; // started as 5, changed by RP
    private static final long LOC_UPDATE_INTERVAL = 1000 * LOC_UPDATE_INTERVAL_IN_SECONDS;
    private static final int LOC_FASTEST_INTERVAL_IN_SECONDS = 10; // started as 1, changed by RP
    private static final long LOC_FASTEST_INTERVAL = 1000 * LOC_FASTEST_INTERVAL_IN_SECONDS;
    private final static int CONNECTION_FAILURE_RESOLUTION_REQUEST = 9000;
    private final static int WRITING_PERIOD = 1000 * LOC_UPDATE_INTERVAL_IN_SECONDS;
    public static final String ROOT_PATH = Environment.getExternalStorageDirectory().getPath() +
            "/ricardo/";

    private TextView mStatus;

    private LocationClient mLocationClient;
    private MyLocationListener mLocationListener = new MyLocationListener();
    // These settings are the same as the settings for the map. They will in fact give you updates at
    // the maximal rates currently possible.
    private static final LocationRequest REQUEST = LocationRequest.create()
            .setInterval(LOC_UPDATE_INTERVAL)
            .setFastestInterval(LOC_FASTEST_INTERVAL)
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

    private SensorManager mSensorManager;
    private Sensor mSensorAcc;
    private float mX;
    private float mY;
    private float mZ;
    private long mTimestamp;

    private String mImei;
    private double mLat = 0, mLng = 0;
    private float mAccuracy = 0;
    private String mLastFileName;
    private BluetoothManager mBluetoothManager;
    private HashMap<String, Integer> mNameMap = new HashMap<String, Integer>();

    /**
     * Tasks
     */
    private Handler mHandler = new Handler();
    private Runnable mWriteRecordTask = new Runnable() {
        @Override
        public void run() {
            try {
                JSONArray jsonArray = new JSONArray();
                for (String k : mNameMap.keySet()) {
                    JSONObject beacon = new JSONObject();
                    try {
                        beacon.put("bid", k);
                        beacon.put("r", mNameMap.get(k));
                        jsonArray.put(beacon);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

                JSONArray gpsArray = new JSONArray();
                try {
                    gpsArray.put(mLat);
                    gpsArray.put(mLng);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                Long timestamp = System.currentTimeMillis();
                JSONObject record = new JSONObject();
                try {
                    record.put("d", mImei);
                    record.put("t", timestamp);
                    record.put("p", gpsArray);
                    record.put("a", String.format("%.1f", mAccuracy));
                    record.put("accX", mX);
                    //record.put("accTS", mTimestamp);//RP commented out, don't need this.
                    record.put("b", jsonArray);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                final String filename = getCurrentTimestampForFile();
                if (mLastFileName != null && !mLastFileName.equals(filename)) {
                    Ion.with(getApplicationContext())
                            .load("http://reaccting.herokuapp.com/v1/samples")
                            .uploadProgressHandler(new ProgressCallback() {
                                @Override
                                public void onProgress(long uploaded, long total) {
                                    // Displays the progress bar for the first time.
                                }
                            })
                            .setTimeout(60 * 60 * 1000)
                            .setMultipartFile(mLastFileName, new File(ROOT_PATH + mLastFileName))
                            .asJsonObject()
                            .setCallback(new FutureCallback<JsonObject>() {
                                @Override
                                public void onCompleted(Exception e, JsonObject result) {
                                    Log.d("Kun", "Upload finished");
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            mStatus.setText("Uploaded file: " + filename);
                                        }
                                    });
                                }
                            });
                }
                mLastFileName = filename;
                BufferedWriter wFile = new BufferedWriter(new FileWriter(new File(ROOT_PATH,
                        filename), true));
                wFile.write(record.toString() + "\n");
                wFile.close();
            } catch (IOException e) {
                Log.e("Kun", "Could not write file " + e.getMessage());
            }
            mNameMap.clear();
            mHandler.postDelayed(this, WRITING_PERIOD);
        }
    };

    private Runnable mStopGpsTask = new Runnable() { //RP added 11/26/14
        @Override
        public void run() {
            stopMonitorLocation();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatus = (TextView) findViewById(R.id.status);

        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mImei = tm.getDeviceId();

        checkFolder();
        // Check BLE availability
        if (!isBleExist()) finish();
        if (!isBleEnabled()) showBleEnablingDialog();

        setUpLocationClientIfNeeded();
        ScanManager.setAdapter(mBluetoothManager.getAdapter());
        ScanManager.addListener(this);
        ScanManager.startScan();
        mHandler.postDelayed(mWriteRecordTask, WRITING_PERIOD);
        startMonitorLocation(); // Commented out by RP on 12/17/14 to stop GPS logging. Used again on 12/27/14.
        //mHandler.postDelayed(mStopGpsTask, 60000);//RP Added 11/26/14.  Stop searching after 30 seconds. RP changed to 45 on 6/25/15.// RP commented out on 11/15/

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensorAcc = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        mSensorManager.registerListener(this, mSensorAcc, SensorManager.SENSOR_DELAY_FASTEST);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mSensorManager.unregisterListener(this);
        mHandler.removeCallbacks(mWriteRecordTask);
        stopMonitorLocation();
        ScanManager.stopScan();
        //mHandler.removeCallbacks(mStopGpsTask);  //RP added 11/26.  // RP commented out on 11/15/15.//
    }

    private boolean isBleExist() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "Bluetooth 4.0 is not supported", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    protected boolean isBleEnabled() {
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter adapter = mBluetoothManager.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    protected void showBleEnablingDialog() {
        final Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
    }

    private void setUpLocationClientIfNeeded() {
        if (mLocationClient == null) {
            mLocationClient = new LocationClient(MainActivity.this, mLocationListener,
                    mLocationListener);
        }
    }

    public void startMonitorLocation() {
        mLocationClient.connect();
    }

    public void stopMonitorLocation() {
        if (mLocationClient != null) {
            mLocationClient.disconnect();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    public void onSensorChanged(SensorEvent event) { // RP commented out all these lines on 11/7/15
        Log.d("Kun", "x: " + event.values[0] + ", y: " + event.values[1] + ", z: " + event.values[2]);
        mX = event.values[0];
        mY = event.values[1];
        mZ = event.values[2];
        mTimestamp = event.timestamp;
    }

    @Override
    public void onScanStarted() {

    }

    @Override
    public void onScanStopped() {

    }

    @Override
    public void onDevicesScanned(final BluetoothDevice device, final int rssi, byte[] scanRecord) {
        mNameMap.put(device.getAddress(), rssi);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mStatus.setText("Scan device: " + device.getAddress() + "With rssi " + rssi );
            }
        });
    }

    public class MyLocationListener implements LocationListener,
            GooglePlayServicesClient.ConnectionCallbacks,
            GooglePlayServicesClient.OnConnectionFailedListener {

        @Override
        public void onLocationChanged(Location location) {
            mLat = location.getLatitude();
            mLng = location.getLongitude();
            mAccuracy = location.getAccuracy();
            //mHandler.removeCallbacks(mStopGpsTask); //RP added 11/26/14.  Removed by RP on 11/15/15
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mStatus.setText("Update GPS. lat: " + mLat + ", lng: " + mLng);
                }
            });
        }

        @Override
        public void onConnected(Bundle bundle) {
            mLocationClient.requestLocationUpdates(REQUEST, this);
            mStatus.setText("GPS connected");
        }

        @Override
        public void onDisconnected() {
            mLocationClient.removeLocationUpdates(this);
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            if (connectionResult.hasResolution()) {
                try {
                    // Start an Activity that tries to resolve the error
                    connectionResult.startResolutionForResult(MainActivity.this,
                            CONNECTION_FAILURE_RESOLUTION_REQUEST);
                } catch (IntentSender.SendIntentException e) {
                    // Log the error
                    e.printStackTrace();
                }
            } else {
                Log.d("Kun", connectionResult.getErrorCode() + "");
            }
        }
    }

    public static String getCurrentTimestampForFile() {
        Calendar calendar = Calendar.getInstance();
        Date now = calendar.getTime();
        Log.d("Kun", "now is " + now.toString());
        Timestamp currTime = new Timestamp(now.getTime());
        String fileTimestamp = new SimpleDateFormat("yyyy-MM-dd-kk").format(currTime);
        Log.d("Kun", "timestamp is " + fileTimestamp);
        return fileTimestamp;
    }

    public static boolean checkFolder() {
        File folder = new File(ROOT_PATH);
        if (!folder.exists()) {
            folder.mkdir();
        }
        if (!folder.canWrite()) {
            return false;
        }
        return true;
    }
}
