package com.revolverobotics.kubiapi;

import android.bluetooth.BluetoothDevice;

/**
 * This class represents a connectable kubi that has been found. This class should not be used too long after it has been created since devices may become unavailable over time.
 * @author Oliver Rice
 * 
 */
public class KubiSearchResult {

	BluetoothDevice mDevice;
	int 			mRSSI;
	
	public KubiSearchResult(BluetoothDevice device, int RSSI)
	{
		mDevice = device;
		mRSSI = RSSI;
	}
	
	public BluetoothDevice 	getDevice() { return mDevice;}
	public int 				getRSSI() { return mRSSI; }
	public String			getName() { return mDevice.getName(); }
	public String			getMac() { return mDevice.getAddress(); }
}
