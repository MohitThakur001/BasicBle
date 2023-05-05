package com.apogee.bluetoothmodule.Fragments;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;

import com.apogee.bluetoothmodule.R;
import com.apogee.bluetoothmodule.Utils.BluetoothUtil;

import java.util.ArrayList;

/**
 * This Fragment is basically showing the list of available BLE devices according to given particular keyword
 */
public class DevicesFragment extends ListFragment {

    private enum ScanState {NONE, BLE_SCAN, DISCOVERY, DISCOVERY_FINISHED} // Group of constants for the scanState status
    private ScanState scanState = ScanState.NONE; // By default the status of scanState is none
    private static final long BLE_SCAN_PERIOD = 10000; // Discovery time for BLE device
    private final Handler bleScanStopHandler = new Handler();  // Used to update the main thread from background thread
    private final BluetoothAdapter.LeScanCallback bleScanCallback;  // Callback indication that an BLE device found during a device scan
    private final Runnable bleScanStopCallback; // Used to execute code on a concurrent thread
    private final BroadcastReceiver discoveryBroadcastReceiver;  // It occur when the device starts or when message received
    private final IntentFilter discoveryIntentFilter; // Declares the capability of its parent component
    private Menu menu; // Defined because of the navigation Menu
    private BluetoothAdapter bluetoothAdapter; // Represents the local device bluetooth , which allows us to perform fundamental task like discovery
    private final ArrayList<BluetoothUtil.Device> listItems = new ArrayList<>(); // Used to save the segregated devices , which were found during the scanning
    private ArrayAdapter<BluetoothUtil.Device> listAdapter;  // Used for connecting the data source with the UI component
    ActivityResultLauncher<String[]> requestBluetoothPermissionLauncherForStartScan; // To provide an onActivityResult method that is run when the activity ends
    ActivityResultLauncher<String> requestLocationPermissionLauncherForStartScan; // To provide an onActivityResult method that is run when the activity ends


    /** Used for the required permission , deviceDiscovery , Updation of the device
     */

    public DevicesFragment() {
        bleScanCallback = (device, rssi, scanRecord) -> {
            if (device != null && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    updateScan(device);
                });
            }
        };
        discoveryBroadcastReceiver = new BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC && getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateScan(device));
                    }
                }
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                    scanState = ScanState.DISCOVERY_FINISHED; // don't cancel again
                    stopScan();
                }
            }
        };
        discoveryIntentFilter = new IntentFilter();
        discoveryIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        discoveryIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        bleScanStopCallback = this::stopScan; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
        requestBluetoothPermissionLauncherForStartScan = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                granted -> BluetoothUtil.onPermissionsResult(this, granted, this::startScan));
        requestLocationPermissionLauncherForStartScan = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        new Handler(Looper.getMainLooper()).postDelayed(this::startScan, 1); // run after onResume to avoid wrong empty-text
                    } else {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                        builder.setTitle(getText(R.string.location_permission_title));
                        builder.setMessage(getText(R.string.location_permission_denied));
                        builder.setPositiveButton(android.R.string.ok, null);
                        builder.show();
                    }
                });
    }

    /** Initial Creation of the fragment
     *  We are setting up the device name and device mac-address of available devices
     *  according list's item position
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        if (getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        listAdapter = new ArrayAdapter<BluetoothUtil.Device>(getActivity(), 0, listItems) {
            @NonNull
            @Override
            public View getView(int position, View view, @NonNull ViewGroup parent) {
                BluetoothUtil.Device device = listItems.get(position);
                if (view == null)
                    view = getActivity().getLayoutInflater().inflate(R.layout.device_list_item, parent, false);
                TextView text1 = view.findViewById(R.id.text1);
                TextView text2 = view.findViewById(R.id.text2);
                String deviceName = device.getName();
                if (deviceName == null || deviceName.isEmpty())
                    deviceName = "<unnamed>";
                text1.setText(deviceName);
                text2.setText(device.getDevice().getAddress());
                return view;
            }
        };
    }

    /** Used for the final initialization
     *  ( for modifying the element )
     *  in this we are adding the header whenever we will get and display the expected list
     */

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setListAdapter(null);
        View header = getActivity().getLayoutInflater().inflate(R.layout.device_list_header, null, false);
        getListView().addHeaderView(header, null, false);
        setEmptyText("initializing...");
        ((TextView) getListView().getEmptyView()).setTextSize(18);
        setListAdapter(listAdapter);
    }

    /** Initial state of the menu
     * In this we are checking whether the bluetooth adapter is null or not enabled
     * accordingly we are disabling the menuItems
     */

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_devices, menu);
        this.menu = menu;
        if (bluetoothAdapter == null) {
            menu.findItem(R.id.bt_settings).setEnabled(false);
            menu.findItem(R.id.ble_scan).setEnabled(false);
        } else if (!bluetoothAdapter.isEnabled()) {
            menu.findItem(R.id.ble_scan).setEnabled(false);
        }
    }

    /** Called when the fragment comes from or goes to foreground state
     * We are registering a broadcast receiver for the discovery of device
     * In this we are enabling the menuItems according to checking the bluetooth Adapter's state
     */

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(discoveryBroadcastReceiver, discoveryIntentFilter);
        if (bluetoothAdapter == null) {
            setEmptyText("<bluetooth LE not supported>");
        } else if (!bluetoothAdapter.isEnabled()) {
            setEmptyText("<bluetooth is disabled>");
            if (menu != null) {
                listItems.clear();
                listAdapter.notifyDataSetChanged();
                menu.findItem(R.id.ble_scan).setEnabled(false);
            }
        } else {
            setEmptyText("<use SCAN to refresh devices>");
            if (menu != null)
                menu.findItem(R.id.ble_scan).setEnabled(true);
        }
    }

    /** Called when the fragment navigates away from the current state
     * In this we are un-registering the broadcast receiver of device discovery
     */

    @Override
    public void onPause() {
        super.onPause();
        stopScan();
        getActivity().unregisterReceiver(discoveryBroadcastReceiver);
    }

    /** used to cleanup the resources associated with its view
     * here we are initializing the value of menu null ( or resetting the state )
     */

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        menu = null;
    }


    /** Used to perform the action after clicking on the menu items
     */

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.ble_scan) {
            startScan();
            return true;
        } else if (id == R.id.ble_scan_stop) {
            stopScan();
            return true;
        } else if (id == R.id.bt_settings) {
            Intent intent = new Intent();
            intent.setAction(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            startActivity(intent);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Called to scan for the available BLE devices
     */

    private void startScan() {
        if (scanState != ScanState.NONE)
            return;
        ScanState nextScanState = ScanState.BLE_SCAN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!BluetoothUtil.hasPermissions(this, requestBluetoothPermissionLauncherForStartScan))
                return;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (getActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                scanState = ScanState.NONE;
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle(R.string.location_permission_title);
                builder.setMessage(R.string.location_permission_grant);
                builder.setPositiveButton(android.R.string.ok,
                        (dialog, which) -> requestLocationPermissionLauncherForStartScan.launch(Manifest.permission.ACCESS_FINE_LOCATION));
                builder.show();
                return;
            }
            LocationManager locationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);
            boolean locationEnabled = false;
            try {
                locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch (Exception ignored) {
            }
            try {
                locationEnabled |= locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            } catch (Exception ignored) {
            }
            if (!locationEnabled)
                scanState = ScanState.DISCOVERY;
            // Starting with Android 6.0 a bluetooth scan requires ACCESS_COARSE_LOCATION permission, but that's not all!
            // LESCAN also needs enabled 'location services', whereas DISCOVERY works without.
            // Most users think of GPS as 'location service', but it includes more, as we see here.
            // Instead of asking the user to enable something they consider unrelated,
            // we fall back to the older API that scans for bluetooth classic _and_ LE
            // sometimes the older API returns less results or slower
        }
        scanState = nextScanState;
        listItems.clear();
        listAdapter.notifyDataSetChanged();
        setEmptyText("<scanning...>");
        menu.findItem(R.id.ble_scan).setVisible(false);
        menu.findItem(R.id.ble_scan_stop).setVisible(true);
        if (scanState == ScanState.BLE_SCAN) {
            bleScanStopHandler.postDelayed(bleScanStopCallback, BLE_SCAN_PERIOD);
            new Thread(() -> bluetoothAdapter.startLeScan(null, bleScanCallback), "startLeScan")
                    .start(); // start async to prevent blocking UI, because startLeScan sometimes take some seconds
        } else {
            bluetoothAdapter.startDiscovery();
        }
    }

    /** After scanning for the devices we are segregating the devices according to -
     *  particular keywords ...
     *  After getting the expected Devices we are adding them in the list
     */

    @SuppressLint("MissingPermission")
    private void updateScan(BluetoothDevice device) {
        if (scanState == ScanState.NONE)
            return;
        BluetoothUtil.Device device2 = new BluetoothUtil.Device(device); // slow getName() only once

        if(device2.getName()!=null) {

            if (!listItems.contains(device2)) {

                if (device2.getName().contains("BLE_Test") || device2.getName().contains("NAVIK")) {

                    listItems.add(device2);

                }
                listAdapter.notifyDataSetChanged();

            }

        }

//        int pos = Collections.binarySearch(listItems, device2);
//
//
//        if (pos < 0) {
//            listItems.add(-pos - 1, device2);
//            listAdapter.notifyDataSetChanged();
//        }
    }

    /** Called to stop the scanning process and removing all the runnables which are in queue
     */


    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanState == ScanState.NONE)
            return;
        setEmptyText("<no bluetooth devices found>");
        if (menu != null) {
            menu.findItem(R.id.ble_scan).setVisible(true);
            menu.findItem(R.id.ble_scan_stop).setVisible(false);
        }
        switch (scanState) {
            case BLE_SCAN:
                bleScanStopHandler.removeCallbacks(bleScanStopCallback);
                bluetoothAdapter.stopLeScan(bleScanCallback);
                break;
            case DISCOVERY:
                bluetoothAdapter.cancelDiscovery();
                break;
            default:
                // already canceled
        }
        scanState = ScanState.NONE;

    }


    /** Navigating to terminal fragment after clicking on list items .. also passing the mac-address along with it
     * for the communication..
     */

    @Override
    public void onListItemClick(@NonNull ListView l, @NonNull View v, int position, long id) {
        stopScan();
        BluetoothUtil.Device device = listItems.get(position - 1);
        Bundle args = new Bundle();
        args.putString("device", device.getDevice().getAddress());
        Fragment fragment = new TerminalFragment();
        fragment.setArguments(args);
        getFragmentManager().beginTransaction().replace(R.id.fragment, fragment, "terminal").addToBackStack(null).commit();
    }
}
