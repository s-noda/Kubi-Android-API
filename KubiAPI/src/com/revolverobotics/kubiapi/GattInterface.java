package com.revolverobotics.kubiapi;

import java.util.LinkedList;
import java.util.Queue;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.util.Log;

/**
 * This class provides a simple way to perform GATT reads and writes.
 * @author Oliver Rice
 * 
 */
public abstract class GattInterface extends BluetoothGattCallback{
	// Write queue
	private Queue<BluetoothGattCharacteristic> writeQueue = new LinkedList<BluetoothGattCharacteristic>();
	private Queue<BluetoothGattCharacteristic> readQueue = new LinkedList<BluetoothGattCharacteristic>();
	private Queue<byte[]> dataQueue = new LinkedList<byte[]>();
	private boolean idle = true;
	
	// Gatt must be set before queueing writes
	protected BluetoothGatt mGatt = null;
	protected Handler		mHandler;
	
	public GattInterface() { mHandler = new Handler(); }
	
	protected void enqueueWrite(BluetoothGattCharacteristic c, byte[] data)
	{
		if (mGatt != null)
		{
			// Add the values to the queue
			writeQueue.add(c);
			dataQueue.add(data);
			
			// If the gatt isn't busy, perform the write operation
			if (idle)
			{
				idle = false;
				executeNextWrite();
			}
		}
	}
	
	protected void enqueueRead(BluetoothGattCharacteristic c)
	{
		if (mGatt != null)
		{
			// Add the characteristic to the queue
			readQueue.add(c);
			
			// If the gatt isn't busy, perform the read operation
			if (idle)
			{
				idle = false;
				executeNextRead();
			}
		}
	}
	
	protected abstract void characteristicValueRead(BluetoothGattCharacteristic c);
	
	private void executeNextWrite()
	{
		if (mGatt != null)
		{
			// Get the next values from the queue
			BluetoothGattCharacteristic c = writeQueue.peek();
			if (c != null)
			{
				byte[] data = dataQueue.peek();
				c.setValue(data);
				
				if (!mGatt.writeCharacteristic(c))
					Log.e("GattWriter","Unable to write to characteristic "+c.getUuid().toString());
					
			}
		}
	}
	
	private void executeNextRead()
	{
		if (mGatt != null)
		{
			// Get the next values from the queue
			BluetoothGattCharacteristic c = readQueue.peek();
			if (c != null)
			{	
				if (!mGatt.readCharacteristic(c))
					Log.e("GattWriter","Unable to write to characteristic "+c.getUuid().toString());
			}
					
		}
	}
	
	private void performNextAction()
	{
		// Execute the next write if there is a write available
		if (writeQueue.size() > 0)
		{
        	executeNextWrite();
		} 
		else if (readQueue.size() > 0)
		{
			executeNextRead();
		} 
		else idle = true;
	}
	
	@Override
	public void onCharacteristicWrite (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
	{
		// If the write was a success, pop the current item off the queue
		BluetoothGattCharacteristic c = writeQueue.peek();
		if (characteristic == c && status == BluetoothGatt.GATT_SUCCESS)
		{
			writeQueue.poll();
			dataQueue.poll();
		}
		
        this.mHandler.post(new Runnable() {
            @Override
            public void run() {
            	performNextAction();
            }
        });
		
	}
	
	@Override
	public void onCharacteristicRead (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
	{
		BluetoothGattCharacteristic c = readQueue.peek();
		if (characteristic == c && status == BluetoothGatt.GATT_SUCCESS)
		{
			
			Runnable r = new Runnable() {
	            @Override
	            public void run() {
	            	characteristicValueRead(readQueue.poll());
	            }
			};
	        this.mHandler.post(r);
			
		}
        this.mHandler.post(new Runnable() {
            @Override
            public void run() {
            	performNextAction();
            }
        });
	}
}
