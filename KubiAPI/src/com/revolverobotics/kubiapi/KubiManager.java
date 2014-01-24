package com.revolverobotics.kubiapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.util.Log;

/**
 * This class manages the connections to kubi devices. It provides methods to list available kubis, find the nearest kubi and connect/disconnect from a kubi. <br>  <br>
 * 
 * To use, create and instance of the manager and pass it a class that implements the IKubiManagerDelegate interface. If the autoFind flag is set, then the manager will automatically start finding kubis and will keep searching until one is found.
 * If it is not set then use findKubi() or findAllKubis() to find a kubi to connect to. <br>
 * 
 * Use findKubi() to find the closest kubi to the device and notify the delegate if that kubi is close enough to connect to. <br>
 * Use findAllKubis() to update the list of all nearby kubis. <br>
 * Use stopFinding() to cancel either of the previous actions <br>
 * Use connectToKubi() to connect to specific kubi. <br>
 * Use disconnect() to cancel the current connection. <br>  <br>
 * 
 * Note that this manager will only connect to a single kubi at a time. If a second connection is requested while one is already active, the active one will be disconnected before starting the second.
 * @author Oliver Rice
 */
public class KubiManager implements BluetoothAdapter.LeScanCallback {
		
	// CONSTANTS
	private final int RSSI_CONNECT = -52;
	private final int RSSI_DISCONNECT = -80;
	private final int AUTO_SCAN_INTERVAL = 0;
	
	// ENUMERATION VALUES
	/**
	 * There is no current connection
	 */
	public static final int STATUS_DISCONNECTED =	0;
	
	/**
	 * The manager is intentionally disconnecting from a kubi
	 */
	public static final int STATUS_DISCONNECTING =	1;
	
	/**
	 * The manager is actively searching for a kubi to connect to. This will only be set for the findKubi() command, NOT findAllKubis().
	 */
	public static final int STATUS_FINDING =		2;
	
	/**
	 * The manager is attempting to connect to a kubi
	 */
	public static final int STATUS_CONNECTING =		3;
	
	/**
	 * There is an active kubi connection.
	 */
	public static final int STATUS_CONNECTED =		4;
	
	/**
	 * No failure
	 */
	public static final int FAIL_NONE =				0;
	
	/**
	 * The connection to the kubi was unintentionally lost.
	 */
	public static final int FAIL_CONNECTION_LOST =	1;
	
	/**
	 * The connection to the kubi was dropped due to the device moving to far away from the kubi.
	 */
	public static final int FAIL_DISTANCE =			2;
	
	/**
	 * A kubi search was cancelled since the bluetooth adapter is either disabled or not available.
	 */
	public static final int FAIL_NO_BLUETOOTH =		3;
	
	/**
	 * A kubi search was cancelled since the current device is not compatible with bluetooth 4.0
	 */
	public static final int FAIL_NO_BLE =			4;
	
	IKubiManagerDelegate mDelegate;
	boolean mAutoFind = false;
	boolean mAutoDisconnect = false;
	Handler mHandler;
	int mFailure = FAIL_NONE;
	int mStatus = STATUS_DISCONNECTED;
	boolean cancelScan = false;
	
	ArrayList<KubiSearchResult> 	mKubiList = new ArrayList<KubiSearchResult>();	// Current list of available kubis
	
	private ArrayList<KubiSearchResult> nearKubis = new ArrayList<KubiSearchResult> ();
	private ArrayList<String> foundMACs = new ArrayList<String> ();
	private BluetoothAdapter	adapter;
	
	private Kubi connectedKubi;				// The currently connected kubi, if null then no Kubi is connected
	private BluetoothDevice connectDevice;
	private Runnable fullScanFinish = new Runnable() {
        @Override
        public void run() {
        	finishScan(true);
        }
    };
    
	private Runnable findFinish = new Runnable() {
        @Override
        public void run() {
        	finishScan(false);
        }
    };
	
	public IKubiManagerDelegate getDelegate() {
		return mDelegate;
	}
	public void setDelegate(IKubiManagerDelegate mDelegate) {
		this.mDelegate = mDelegate;
	}
	public Boolean getAutoFind() {
		return mAutoFind;
	}
	public void setAutoFind(Boolean mAutoFind) {
		this.mAutoFind = mAutoFind;
	}
	public ArrayList<KubiSearchResult> getKubiList() {
		return mKubiList;
	}
	
	/**
	 * Get the last failure reason
	 * @return The previous failure
	 */
	public int getFailure() { return mFailure;}
	
	public int getStatus() { return mStatus;}
	
	/**
	 * Get the currently connected kubi
	 * @return The currently connected kubi
	 */
	public Kubi getKubi() { return connectedKubi;}
	
	// Constructors
	
	/**
	 * Create an instance of the kubi connection manager.
	 * @param delegate The manager's delegate which receives notifications of events related to scanning for kubis and kubi connections.
	 */
	public KubiManager(IKubiManagerDelegate delegate) 
	{
		this.mDelegate = delegate;
		this.mHandler = new Handler(); // Create handler for posting tasks to this thread
		startScanning();
	}
	
	
	/**
	 * Create an instance of the kubi connection manager.
	 * @param delegate The manager's delegate which receives notifications of events related to scanning for kubis and kubi connections.
	 * @param autoFind If this is set to true, the manager will automatically search for kubis in the background and notify the delegate when one comes in range.
	 */
	public KubiManager(IKubiManagerDelegate delegate, boolean autoFind) 
	{
		this.mDelegate = delegate;
		this.mAutoFind = autoFind;
		this.mHandler = new Handler(); // Create handler for posting tasks to this thread
		startScanning();
	}
	
	// Public Methods
	
	/**
	 * Disconnect from the kubi
	 */
	public void disconnect()
	{
		if (connectedKubi != null)
		{
			setStatus(STATUS_DISCONNECTING);
			connectedKubi.disconnect();
		}
	}
	
	
	/**
	 * Connect to a given kubi.
	 * @param device The kubi to connect to.
	 */
	public void connectToKubi(KubiSearchResult device)
	{
		// Disconnect current kubi
		if (mStatus == STATUS_CONNECTED)
		{
			connectedKubi.disconnect();
			connectedKubi = null;
		}
		
		Log.i("Kubi Manager","Connecting to kubi with ID "+device.getName());
		
		// Ensure that the kubi device is created on the same thread as the manager
		connectDevice = device.getDevice();
		setStatus(STATUS_CONNECTING);
        this.mHandler.post(new Runnable() {
            @Override
            public void run() {
            	connectKubi();
            }
        });
		
	}
	
	/**
	 * Find the nearest kubi after a delay. Only devices that are close to the device will be found.
	 * @param delayMs The delay in milliseconds.
	 */
	public void findKubi(int delayMs)
	{
        this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	findKubi();
            }
        }, delayMs);
	}
	
	/**
	 * Find the nearest kubi, only devices that are close to the device will be found.
	 */
	public void findKubi()
	{
		if (this.mStatus == STATUS_DISCONNECTED || this.mStatus == STATUS_FINDING)
		{
			mFailure = FAIL_NONE;
			
			// Re-initialise adapter if necessary
			if (this.adapter == null)
				this.adapter = BluetoothAdapter.getDefaultAdapter();	
			
			startScan(false);
			
			setStatus(STATUS_FINDING);
		}
	}
	
	
	/**
	 * Stop searching for kubis
	 */
	public void stopFinding()
	{
			cancelScan = true;
			mHandler.removeCallbacks(fullScanFinish);
			mHandler.removeCallbacks(findFinish);
			this.adapter.stopLeScan(this);
			setStatus(STATUS_DISCONNECTED);
	}
	
	/**
	 * Update the list of available. This will find all kubis no matter how far away they are from the device.
	 */
	public void findAllKubis()
	{
		mFailure = FAIL_NONE;
		
		// Re-initialise adapter if necessary
		if (this.adapter == null)
			this.adapter = BluetoothAdapter.getDefaultAdapter();	
		
		startScan(true);
	}
	
	// Private Methods
	private void setStatus(final int status)
	{
		if (status != mStatus)
		{
			final int old = mStatus;
			
			// Notify delegate of status change
	        this.mHandler.post(new Runnable() {
	            @Override
	            public void run() {
	            	notifyChangeStatus(old,status);
	            }
	        });
	        
	        mStatus = status;
		}
	}
	
	private void sendFail(final int fail)
	{
        if (mFailure == FAIL_NONE)
        {
        	mFailure = fail;
		
			// Notify the delegate of the failure
			this.mHandler.post(new Runnable() {
		        @Override
		        public void run() {
		        	notifyFailure(fail);
		        }
		    });
        }
	}
	
	// =======================================
	// ==== Kubi Events
	/**
	 * Kubi event DO NOT USE
	 */
	public void onKubiReady(Kubi kubi)
	{
		if (kubi == connectedKubi){
			setStatus(STATUS_CONNECTED);
		} else
			kubi.disconnect();
	}
	
	/**
	 * Kubi event DO NOT USE
	 */
	public void onKubiDisconnect(Kubi kubi)
	{
		if (kubi == connectedKubi)
		{
			if (mStatus != STATUS_DISCONNECTING)
				sendFail(FAIL_CONNECTION_LOST);
				
			connectedKubi = null;
			setStatus(STATUS_DISCONNECTED);
		}
	}
	
	/**
	 * Kubi event DO NOT USE
	 */
	public void onKubiUpdateRSSI(Kubi kubi, int rssi)
	{
		if (kubi == connectedKubi && rssi < RSSI_DISCONNECT && mAutoDisconnect)
		{
			// Notify delegate of connection failure due to distance then disconnect the kubi
			sendFail(FAIL_DISTANCE);
			kubi.disconnect();
		}
	}
	// ==== End Kubi Events
	// =======================================
	
	// Start scanning for BLE devices
	private void startScanning()
	{
		// Initialise bluetooth adapter
		adapter = BluetoothAdapter.getDefaultAdapter();
		
		// Check that bluetooth is available
		if (adapter != null && adapter.isEnabled() )
		{
			if (this.mAutoFind)
				findKubi(0);
		}
		else if (this.mDelegate != null)
    	{
    		sendFail(FAIL_NO_BLUETOOTH);
    		setStatus(STATUS_DISCONNECTED);
    	}
	}
	
	
	
	private void startScan(final boolean fullScan)
	{

		// Check that the bluetooth adapter is available
		if (this.adapter == null || !this.adapter.isEnabled())
		{
			sendFail(FAIL_NO_BLUETOOTH);
			return;
		}
		
		cancelScan = false;
		
		// Automatically stop scanning after 2 secs to reduce battery consumption
		if (fullScan)
		{
	        this.mHandler.postDelayed(fullScanFinish, 2000);
		} 
		else
		{
			this.mHandler.postDelayed(findFinish, 2000);
		}
        
        // The call to BluetoothAdapter.startLeScan can block for a long time if BLE is not available.
        // To avoid this it is called on a background thread.
        new Thread(new Runnable() 
        {
            @Override
			public void run() 
            {
            	doScan();
            }
        }).start();
        
        // Clear lists
        this.nearKubis.clear();
        this.foundMACs.clear();
	}
	
	// Finish current scan
	private void finishScan(final boolean fullScan)
	{
		// Stop scan
		adapter.stopLeScan(this);
		
		if (!cancelScan)
		{
			// Sort the kubis by RSSI
			Collections.sort(nearKubis, new Comparator<KubiSearchResult>()
			{
				@Override
				public int compare(KubiSearchResult a, KubiSearchResult b)
				{
					return b.getRSSI() - a.getRSSI();
				}
			});
			
			// Publish the list of kubis
			this.mKubiList = new ArrayList<KubiSearchResult>(nearKubis);
			
			// If autofind is enabled, find the closest kubi then check whether its RSSI falls within the connect threshold
			if (!fullScan)
			{	
					if (nearKubis.size() > 0)
					{
					final KubiSearchResult device = this.mKubiList.get(0);
					
					// If the closest (largest RSSI) is within the threshold, notify that a Kubi has been found
					if (device.getRSSI() > RSSI_CONNECT)
					{
				        // Notify the delegate of the new device
						this.mHandler.post(new Runnable() {
				            @Override
				            public void run() {
				            	notifyKubiDeviceFound(device);
				            }
				        });
					}
					
					// Rescan after a delay if no devices are found
					else if (this.mAutoFind)
					{
						findKubi(AUTO_SCAN_INTERVAL);
					}
					else
						setStatus(STATUS_DISCONNECTED);
					
					}
				
				}
				else
				{
				
			        // Notify the delegate that the scan has completed
					this.mHandler.post(new Runnable() {
			            @Override
			            public void run() {
			            	notifyScanComplete(nearKubis);
			            }
			        });
					
				}
			}
		}
		
	
	// Runnable Methods
	private void doScan()
	{
        if (!cancelScan)
        {
			boolean result = adapter.startLeScan(this);
	        if (!result)
	        {
	        	sendFail(FAIL_NO_BLE);
	        	return;
	        }
        }
	}
	
	private void connectKubi()
	{
		if (connectDevice != null)
			connectedKubi = new Kubi(this, connectDevice);
	}
	
	private void notifyChangeStatus(final int oldStatus, final int newStatus)
	{
		if (mDelegate != null)
			mDelegate.kubiManagerStatusChanged(this, oldStatus, newStatus);
	}
	private void notifyFailure(int reason)
	{
		if (mDelegate != null)
			mDelegate.kubiManagerFailed(this, reason);
	}
	private void notifyKubiDeviceFound(KubiSearchResult device)
	{
		if (mDelegate != null)
			mDelegate.kubiDeviceFound(this, device);
	}
	private void notifyScanComplete(ArrayList<KubiSearchResult> list)
	{
		if (mDelegate != null)
			mDelegate.kubiScanComplete(this, list);
	}
	
	/* (non-Javadoc)
	 * @see android.bluetooth.BluetoothAdapter.LeScanCallback#onLeScan(android.bluetooth.BluetoothDevice, int, byte[])
	 */
	@Override
	public void onLeScan(BluetoothDevice device, int rssi, byte[] arg2) 
	{
		if (!this.foundMACs.contains(device.getAddress()))
		{
			this.foundMACs.add(device.getAddress());
			
			// Only add devices that have the correct prefixes to the list
			String prefix = device.getName().substring(0,4);
			if (prefix.equals("kubi") || prefix.equals("Rev-"))
				nearKubis.add(new KubiSearchResult(device,rssi));
		}
			
		
	}
	
}
