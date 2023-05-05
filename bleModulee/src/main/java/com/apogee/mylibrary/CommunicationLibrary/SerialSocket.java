package com.apogee.mylibrary.CommunicationLibrary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import com.apogee.mylibrary.R;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

/**
 * wrap BLE communication into socket like class
 * - connect, disconnect and write as methods,
 * - read + status is returned by SerialListener
 */
@SuppressLint("MissingPermission")

 public class SerialSocket extends BluetoothGattCallback {

    /**
     * delegate device specific behaviour to inner class
     * Class that provides the value for property and handles its changes
     */
    private static class DeviceDelegate {
        boolean connectCharacteristics(BluetoothGattService s) {
            return true;
        }

        // following methods only overwritten for Telit devices
        void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { /*nop*/ }

        void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {/*nop*/ }

        void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { /*nop*/ }

        boolean canWrite() {
            return true;
        }

        void disconnect() {/*nop*/ }
    }

    // Services in form of UUID

    private static final UUID BLUETOOTH_LE_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID BLUETOOTH_LE_NRF_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID BLUETOOTH_LE_NRF_CHAR_RW2 = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"); // read on microbit, write on adafruit
    private static final UUID BLUETOOTH_LE_NRF_CHAR_RW3 = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    private static final int MAX_MTU = 512; // BLE standard does not limit, some BLE 4.2 devices support 251, various source say that Android has max 512
    private static final int DEFAULT_MTU = 23; // Default MTU value for android
    private static final String TAG = "SerialSocket";

    private final ArrayList<byte[]> writeBuffer;
    private final IntentFilter pairingIntentFilter; // Declares the capability of its parent component
    private final BroadcastReceiver pairingBroadcastReceiver; // for (pairing) It occur when the device starts or when message received
    private final BroadcastReceiver disconnectBroadcastReceiver;  // for (disconnect) It occur when the device starts or when message received
    private final Context context;
    private SerialListener listener; // Calling the serialListener interface
    private DeviceDelegate delegate; // Class that provides the value for property and handles its changes
    private BluetoothDevice device; // Represents a remote Bluetooth device.
    private BluetoothGatt gatt; // class provides Bluetooth GATT functionality to enable communication with Bluetooth Smart or Smart Ready devices.
    private BluetoothGattCharacteristic readCharacteristic, writeCharacteristic; // characteristic is a basic data element used to construct a GATT service

    // Boolean values
    private boolean writePending;
    private boolean canceled;
    private boolean connected;
    private int payloadSize = DEFAULT_MTU - 3;

    /**
     * The code starts by declaring a BluetoothSocket object.
     *  The code starts by declaring a BluetoothSocket object.
     * The code is for a Bluetooth device that has been paired with another.
     * The code is used to create a BroadcastReceiver that will receive the broadcast purpose when the Bluetooth device changes its state,
     * such as going into pairing mode.
     */

    public SerialSocket(Context context, BluetoothDevice device) {
        if (context instanceof Activity)
            throw new InvalidParameterException("expected non UI context");
        this.context = context;
        this.device = device;
        writeBuffer = new ArrayList<>();  //ArrayList to hold the data that will be sent from the client to the server.
        pairingIntentFilter = new IntentFilter();
        pairingIntentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        pairingIntentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onPairingBroadcastReceive(context, intent);
            }
        };
        disconnectBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (listener != null)
                    listener.onSerialIoError(new IOException("background disconnect"));
                disconnect(); // disconnect now, else would be queued until UI re-attached
            }
        };
    }
    /**
     *  The code is trying to get the name of the device.
     *  The code attempts to return the name of the device if it exists,
     * otherwise it will return the address of the device.
     */
    String getName() {
        return device.getName() != null ? device.getName() : device.getAddress();
    }
    /**
     * The code starts by creating a listener variable.
     * The code then calls disconnect() on the listener, which will stop listening for data and errors from the device.
     * Next, it creates a new instance of GattDevice with null as its constructor arguments.
     */
    void disconnect() {
        Log.d(TAG, "disconnect");
        listener = null; // ignore remaining data and errors
        device = null;
        canceled = true;
        synchronized (writeBuffer) {
            writePending = false;
            writeBuffer.clear();
        }
        readCharacteristic = null;
        writeCharacteristic = null;
        if (delegate != null)
            delegate.disconnect();
        if (gatt != null) {
            Log.d(TAG, "gatt.disconnect");
            gatt.disconnect();
            Log.d(TAG, "gatt.close");
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
            connected = false;
        }
        try {
            context.unregisterReceiver(pairingBroadcastReceiver);
        } catch (Exception ignored) {
        }
        try {
            context.unregisterReceiver(disconnectBroadcastReceiver);
        } catch (Exception ignored) {
        }
    }

    /**
     * The code starts by creating a listener variable.
     * connect-success and most connect-errors are returned asynchronously to listener
     * The code then calls connect() on the listener, which will be listening for data and errors from the device.
     */
    void connect(SerialListener listener) throws IOException {
        if (connected || gatt != null)
            throw new IOException("already connected");
        canceled = false;
        this.listener = listener;
        context.registerReceiver(disconnectBroadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_DISCONNECT));
        Log.d(TAG, "connect " + device);
        context.registerReceiver(pairingBroadcastReceiver, pairingIntentFilter);
        if (Build.VERSION.SDK_INT < 23) {
            Log.d(TAG, "connectGatt");
            gatt = device.connectGatt(context, false, this);
        } else {
            Log.d(TAG, "connectGatt,LE");
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
        }
        if (gatt == null)
            throw new IOException("connectGatt failed");
        // continues asynchronously in onPairingBroadcastReceive() and onConnectionStateChange()
    }
    /**
     * Called for the intents
     * Used to broadcast PAIRING REQUEST
     * Also Indicates the change in the bond state of a remote device. For example, if a device is bonded (paired).
     */
    private void onPairingBroadcastReceive(Context context, Intent intent) {
        // for ARM Mbed, Microbit, ... use pairing from Android bluetooth settings
        // for HM10-clone, ... pairing is initiated here
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || !device.equals(this.device))
            return;
        switch (intent.getAction()) {
            case BluetoothDevice.ACTION_PAIRING_REQUEST:
                final int pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                Log.d(TAG, "pairing request " + pairingVariant);
                onSerialConnectError(new IOException(context.getString(R.string.pairing_request)));
                // pairing dialog brings app to background (onPause), but it is still partly visible (no onStop), so there is no automatic disconnect()
                break;
            case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                Log.d(TAG, "bond state " + previousBondState + "->" + bondState);
                break;
            default:
                Log.d(TAG, "unknown broadcast " + intent.getAction());
                break;
        }
    }

    /**
     *
     * Callback indicating when GATT client has connected/disconnected to/from a remote GATT server.
     *
     * @param gatt GATT client
     * @param status Status of the connect or disconnect operation. {@link
     * BluetoothGatt#GATT_SUCCESS} if the operation succeeds.
     * @param newState Returns the new connection state. Can be one of {@link
     * BluetoothProfile#STATE_DISCONNECTED} or {@link BluetoothProfile#STATE_CONNECTED}
     */

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        // status directly taken from gat_api.h, e.g. 133=0x85=GATT_ERROR ~= timeout
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG, "connect status " + status + ", discoverServices");
            if (!gatt.discoverServices())
                onSerialConnectError(new IOException("discoverServices failed"));
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (connected)
                onSerialIoError(new IOException("gatt status " + status));
            else
                onSerialConnectError(new IOException("gatt status " + status));
        } else {
            Log.d(TAG, "unknown connect state " + newState + " " + status);
        }
        // continues asynchronously in onServicesDiscovered()
    }

    /**
     * Callback invoked when the list of remote services, characteristics and descriptors
     * for the remote device have been updated, ie new services have been discovered.
     *
     * @param gatt GATT client invoked {@link BluetoothGatt#discoverServices}
     * @param status {@link BluetoothGatt#GATT_SUCCESS} if the remote device has been explored
     * successfully.
     */

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        Log.d(TAG, "servicesDiscovered, status " + status);
        if (canceled)
            return;
        connectCharacteristics1(gatt);
    }
    // The code is trying to write a characteristic with the BluetoothGatt.writeProperties method.

    private void connectCharacteristics1(BluetoothGatt gatt) {
        boolean sync = true;
        writePending = false;
        for (BluetoothGattService gattService : gatt.getServices()) {

            if (gattService.getUuid().equals(BLUETOOTH_LE_NRF_SERVICE))
                delegate = new NrfDelegate();


            if (delegate != null) {
                sync = delegate.connectCharacteristics(gattService);
                break;
            }
        }
        if (canceled)
            return;
        if (delegate == null || readCharacteristic == null || writeCharacteristic == null) {
            for (BluetoothGattService gattService : gatt.getServices()) {
                Log.d(TAG, "service " + gattService.getUuid());
                for (BluetoothGattCharacteristic characteristic : gattService.getCharacteristics())
                    Log.d(TAG, "characteristic " + characteristic.getUuid());
            }
            onSerialConnectError(new IOException("no serial profile found"));
            return;
        }
        if (sync)
            connectCharacteristics2(gatt);
    }

    private void connectCharacteristics2(BluetoothGatt gatt) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "request max MTU");
            if (!gatt.requestMtu(MAX_MTU))
                onSerialConnectError(new IOException("request MTU failed"));
            // continues asynchronously in onMtuChanged
        } else {
            connectCharacteristics3(gatt);
        }
    }

    /**
     *Callback indicating the MTU for a given device connection has changed. This callback is triggered in response
     * to the BluetoothGatt#requestMtu function, or in response to a connection event.
     *
     * @param gatt GATT client invoked {@link BluetoothGatt#requestMtu}
     * @param mtu The new MTU size
     * @param status {@link BluetoothGatt#GATT_SUCCESS} if the MTU has been changed successfully
     */

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        Log.d(TAG, "mtu size " + mtu + ", status=" + status);
        if (status == BluetoothGatt.GATT_SUCCESS) {
            payloadSize = mtu - 3;
            Log.d(TAG, "payload size " + payloadSize);
        }
        connectCharacteristics3(gatt);
    }

    private void connectCharacteristics3(BluetoothGatt gatt) {
        int writeProperties = writeCharacteristic.getProperties();
        if ((writeProperties & (BluetoothGattCharacteristic.PROPERTY_WRITE +     // Microbit,HM10-clone have WRITE
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) { // HM10,TI uart,Telit have only WRITE_NO_RESPONSE
            onSerialConnectError(new IOException("write characteristic not writable"));
            return;
        }
        if (!gatt.setCharacteristicNotification(readCharacteristic, true)) {
            onSerialConnectError(new IOException("no notification for read characteristic"));
            return;
        }
        BluetoothGattDescriptor readDescriptor = readCharacteristic.getDescriptor(BLUETOOTH_LE_CCCD);
        if (readDescriptor == null) {
            onSerialConnectError(new IOException("no CCCD descriptor for read characteristic"));
            return;
        }
        int readProperties = readCharacteristic.getProperties();
        if ((readProperties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            Log.d(TAG, "enable read indication");
            readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        } else if ((readProperties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            Log.d(TAG, "enable read notification");
            readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            onSerialConnectError(new IOException("no indication/notification for read characteristic (" + readProperties + ")"));
            return;
        }
        Log.d(TAG, "writing read characteristic descriptor");
        if (!gatt.writeDescriptor(readDescriptor)) {
            onSerialConnectError(new IOException("read characteristic CCCD descriptor not writable"));
        }
        // continues asynchronously in onDescriptorWrite()
    }

    /**
     *
     * Callback indicating the result of a descriptor write operation.
     *
     * @param gatt GATT client invoked {@link BluetoothGatt#writeDescriptor}
     * @param descriptor Descriptor that was written to the associated remote device.
     * @param status The result of the write operation {@link BluetoothGatt#GATT_SUCCESS} if the
     * operation succeeds.
     */

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        delegate.onDescriptorWrite(gatt, descriptor, status);
        if (canceled)
            return;
        if (descriptor.getCharacteristic() == readCharacteristic) {
            Log.d(TAG, "writing read characteristic descriptor finished, status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onSerialConnectError(new IOException("write descriptor failed"));
            } else {
                // onCharacteristicChanged with incoming data can happen after writeDescriptor(ENABLE_INDICATION/NOTIFICATION)
                // before confirmed by this method, so receive data can be shown before device is shown as 'Connected'.
                onSerialConnect();
                connected = true;
                Log.d(TAG, "connected");
            }
        }
    }

    /**
     * Callback triggered as a result of a remote characteristic notification.
     *
     * @param gatt GATT client the characteristic is associated with
     * @param characteristic Characteristic that has been updated as a result of a remote
     * notification event.
     */
    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (canceled)
            return;
        delegate.onCharacteristicChanged(gatt, characteristic);
        if (canceled)
            return;
        if (characteristic == readCharacteristic) { // NOPMD - test object identity
            byte[] data = readCharacteristic.getValue();
            onSerialRead(data);
            Log.d(TAG, "read, len=" + data.length);
        }
    }

    /**
     * write
     */
    void write(byte[] data) throws IOException {
        if (canceled || !connected || writeCharacteristic == null)
            throw new IOException("not connected");
        byte[] data0;
        synchronized (writeBuffer) {
            if (data.length <= payloadSize) {
                data0 = data;
            } else {
                data0 = Arrays.copyOfRange(data, 0, payloadSize);
            }
            if (!writePending && writeBuffer.isEmpty() && delegate.canWrite()) {
                writePending = true;
            } else {
                writeBuffer.add(data0);
                Log.d(TAG, "write queued, len=" + data0.length);
                data0 = null;
            }
            if (data.length > payloadSize) {
                for (int i = 1; i < (data.length + payloadSize - 1) / payloadSize; i++) {
                    int from = i * payloadSize;
                    int to = Math.min(from + payloadSize, data.length);
                    writeBuffer.add(Arrays.copyOfRange(data, from, to));
                    Log.d(TAG, "write queued, len=" + (to - from));
                }
            }
        }
        if (data0 != null) {
            writeCharacteristic.setValue(data0);
            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                onSerialIoError(new IOException("write failed"));
            } else {
                Log.d(TAG, "write started, len=" + data0.length);
            }
        }
        // continues asynchronously in onCharacteristicWrite()
    }

    /**
     * Callback indicating the result of a characteristic write operation.
     * @param gatt GATT client invoked {@link BluetoothGatt#writeCharacteristic}
     * @param characteristic Characteristic that was written to the associated remote device.
     * @param status The result of the write operation {@link BluetoothGatt#GATT_SUCCESS} if the
     * operation succeeds.
     */

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        if (canceled || !connected || writeCharacteristic == null)
            return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            onSerialIoError(new IOException("write failed"));
            return;
        }
        delegate.onCharacteristicWrite(gatt, characteristic, status);
        if (canceled)
            return;
        if (characteristic == writeCharacteristic) { // NOPMD - test object identity
            Log.d(TAG, "write finished, status=" + status);
            writeNext();
        }
    }

    /**
     * code is used to write data into the buffer.
     * If there is no data in the buffer, then it will be written asynchronously and if there is data in the buffer,
     * then it will be written synchronously.
     * If there are bytes in the buffer, then they are written into a byte array called data.
     */

    private void writeNext() {
        final byte[] data;
        synchronized (writeBuffer) {
            if (!writeBuffer.isEmpty() && delegate.canWrite()) {
                writePending = true;
                data = writeBuffer.remove(0);
            } else {
                writePending = false;
                data = null;
            }
        }
        if (data != null) {
            writeCharacteristic.setValue(data);
            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                onSerialIoError(new IOException("write failed"));
            } else {
                Log.d(TAG, "write started, len=" + data.length);
            }
        }
    }

    /**
     * SerialListener
     */
    private void onSerialConnect() {
        if (listener != null)
            listener.onSerialConnect();
    }

    private void onSerialConnectError(Exception e) {
        canceled = true;
        if (listener != null)
            listener.onSerialConnectError(e);
    }

    private void onSerialRead(byte[] data) {
        if (listener != null)
            listener.onSerialRead(data);
    }

    private void onSerialIoError(Exception e) {
        writePending = false;
        canceled = true;
        if (listener != null)
            listener.onSerialIoError(e);
    }


    /**
     * the NrfDelegate class that will be used to handle all of the BluetoothGattService methods.
     */
    private class NrfDelegate extends DeviceDelegate {
        @Override
        boolean connectCharacteristics(BluetoothGattService gattService) {
            Log.d(TAG, "service nrf uart");
            BluetoothGattCharacteristic rw2 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW2);
            BluetoothGattCharacteristic rw3 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW3);
            if (rw2 != null && rw3 != null) {
                int rw2prop = rw2.getProperties();
                int rw3prop = rw3.getProperties();
                boolean rw2write = (rw2prop & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                boolean rw3write = (rw3prop & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                Log.d(TAG, "characteristic properties " + rw2prop + "/" + rw3prop);
                if (rw2write && rw3write) {
                    onSerialConnectError(new IOException("multiple write characteristics (" + rw2prop + "/" + rw3prop + ")"));
                } else if (rw2write) {
                    writeCharacteristic = rw2;
                    readCharacteristic = rw3;
                } else if (rw3write) {
                    writeCharacteristic = rw3;
                    readCharacteristic = rw2;
                } else {
                    onSerialConnectError(new IOException("no write characteristic (" + rw2prop + "/" + rw3prop + ")"));
                }
            }
            return true;
        }
    }




}
