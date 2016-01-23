package com.example.sensortest.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by cnbuff410 on 11/8/14.
 */
public class ScanManager {
    /**
     * Member variables
     */
    private static List<ScanListener> sListeners = new ArrayList<ScanListener>();
    private static BluetoothAdapter sBluetoothAdapter;
    private static boolean sScanning = false;

    private static BluetoothAdapter.LeScanCallback sLeScanCallback = new BluetoothAdapter
            .LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            for (ScanListener l : sListeners) {
                l.onDevicesScanned(device, rssi, scanRecord);
            }
        }
    };

    public interface ScanListener {
        public void onScanStarted();

        public void onScanStopped();

        /**
         * The callback is fired each time the valid temperature data was obtained from remote BLE sensor
         */
        public void onDevicesScanned(final BluetoothDevice device, final int rssi,
                                     final byte[] scanRecord);
    }

    public static void setAdapter(BluetoothAdapter adapter) {
        sBluetoothAdapter = adapter;
    }

    public static void addListener(final ScanListener l) {
        if (!sListeners.contains(l)) {
            sListeners.add(l);
        }
    }

    public static void removeListener(final ScanListener l) {
        sListeners.remove(l);
    }

    /**
     * Starts scanning for temperature data. Call {@link #stopScan()} when done to save the power.
     */
    public static void startScan() {
        // Stops scanning after a pre-defined scan period.
        if (sBluetoothAdapter == null) return;
        if (sScanning) return;

        sBluetoothAdapter.startLeScan(
                sLeScanCallback
        );
        sScanning = true;
        for (ScanListener l : sListeners)
            l.onScanStarted();
    }

    /**
     * Stops scanning for temperature data from BLE sensors.
     */
    public static void stopScan() {
        if (sBluetoothAdapter == null) {
            return;
        }
        sBluetoothAdapter.stopLeScan(sLeScanCallback);
        for (ScanListener l : sListeners)
            l.onScanStopped();
        sScanning = false;
    }
}
