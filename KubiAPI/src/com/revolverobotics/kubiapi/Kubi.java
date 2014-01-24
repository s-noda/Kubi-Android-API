package com.revolverobotics.kubiapi;
import java.util.UUID;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Handler;
import android.util.Log;

/**
 * This class provides functions for controlling a kubi directly. It provides an interface for sending move commands, changing the color of the activity indicator and performing gestures.  <br>
 * NB Using this class directly to connect/disconnect from Kubis should be avoided, instead use the KubiManager class.
 * @author Oliver Rice
 * 
 */
public class Kubi extends GattInterface {
	
	// Gestures
	/**
	 * Bow gesture
	 */
	public static final int GESTURE_BOW = 	0;
	/**
	 * Head nod gesture
	 */
	public static final int GESTURE_NOD = 	1;
	/**
	 * Head shake gesture
	 */
	public static final int GESTURE_SHAKE = 2;
	/**
	 * Scan the room gesture
	 */
	public static final int GESTURE_SCAN = 	3;
	
	// CONSTANTS
	private static final byte SERVO_SPEED = 		0x20;
	
	private static final int RSSI_REQUEST_INTERVAL = 3000;
	
	// Service UUIDs
	private final UUID SERVO_SERVICE_UUID = 	UUID.fromString("2A001800-2803-2801-2800-1D9FF2D5C442");
	private final UUID KUBI_SERVICE_UUID = 		UUID.fromString("0000E001-0000-1000-8000-00805F9B34FB");
	
	// Servo Characteristic UUIDs
	private final UUID REGISTER_WRITE1P_UUID = 	UUID.fromString("00009141-0000-1000-8000-00805F9B34FB");
	private final UUID REGISTER_WRITE2P_UUID = 	UUID.fromString("00009142-0000-1000-8000-00805F9B34FB");
	private final UUID SERVO_HORIZONTAL_UUID = 	UUID.fromString("00009145-0000-1000-8000-00805F9B34FB");
	private final UUID SERVO_VERTICAL_UUID = 	UUID.fromString("00009146-0000-1000-8000-00805F9B34FB");
	
	// Kubi Characteristic UUIDs
	private final UUID BATTERY_UUID = 			UUID.fromString("0000E101-0000-1000-8000-00805F9B34FB");
	private final UUID SERVO_ERROR_UUID = 		UUID.fromString("0000E102-0000-1000-8000-00805F9B34FB");
	private final UUID SERVO_ERROR_ID_UUID = 	UUID.fromString("0000E103-0000-1000-8000-00805F9B34FB");
	private final UUID LED_COLOR_UUID = 		UUID.fromString("0000E104-0000-1000-8000-00805F9B34FB");
	private final UUID BATTERY_STATUS_UUID = 	UUID.fromString("0000E105-0000-1000-8000-00805F9B34FB");
	private final UUID BUTTON_UUID = 			UUID.fromString("0000E10A-0000-1000-8000-00805F9B34FB");
	
	// Constants
	private final float DEFAULT_SPEED =				0.89f;
	private static final float MAX_SPEED =			1.0f;
	private static final int MIN_SPEED_VAL = 		15;
	
	BluetoothDevice mDevice;
	KubiManager		mKubiManager;
	int				mRSSI;
	
	// Services
	private BluetoothGattService servoService;
	private BluetoothGattService kubiService;
	
	private Handler mHandler;
	
	// Servo characteristics
	private BluetoothGattCharacteristic registerWrite1p;
	private BluetoothGattCharacteristic registerWrite2p;
	private BluetoothGattCharacteristic servoHorizontal;
	private BluetoothGattCharacteristic servoVertical;
	
	// Kubi Characteristic
	private BluetoothGattCharacteristic battery;
	private BluetoothGattCharacteristic servoError;
	private BluetoothGattCharacteristic servoErrorID;
	private BluetoothGattCharacteristic ledColor;
	private BluetoothGattCharacteristic batteryStatus;
	private BluetoothGattCharacteristic button;
	
	private float lastPan = 0;
	private float lastTilt = 0;
	private float nodTemp = 0;
	private float shakeTemp = 0;
	
	
	/**
	 * Initialise the kubi. Do not use this constructor, use KubiManager.connectToKubi() instead.
	 * @param manager
	 * @param device
	 */
	public Kubi(KubiManager manager, BluetoothDevice device)
	{	
		mDevice = device;
		mKubiManager = manager;
		mHandler = new Handler();
		
		super.mGatt = device.connectGatt(null, false, this);
	}
	
	
	/**
	 * Converts a given angle to the servo value
	 * @param angle Angle in degrees
	 * @return The value for the servo's goal position register.
	 */
	public static int servoAngle(float angle) { return ((int)((((angle+150) * 0x3FF) / 300))); }	
	

	/**
	 * Converts a given speed to the servo value. The actual speed may vary due to load on the the servo.
	 * @param speed Rotation speed in degrees/second
	 * @return The value for the servo speed register
	 */
	public static int servoSpeed(float speed) { return (int)Math.max(((speed * 0x3FF) / 11.4f), MAX_SPEED); }
	
	public float getPan() { return lastPan; }
	public float getTilt() { return lastTilt; }
	public int getRSSI() { return mRSSI;}
	public String getName() { return mDevice.getName(); }
	
	/**
	 * Move to a given pan and tilt. This will move at default speed with smoothing enabled.
	 * @param pan Pan angle in degrees
	 * @param tilt Tilt angle in degrees.
	 */
	public void moveTo(float pan, float tilt) 				{ moveTo(pan, tilt, DEFAULT_SPEED, true); }
	
	
	/**
	 * Move to a given pan and tilt at a given speed.
	 * @param pan Pan angle in degrees
	 * @param tilt Tilt angle in degrees
	 * @param speed Turn rate in degrees/second
	 */
	public void moveTo(float pan, float tilt, float speed) 	{ moveTo(pan, tilt, speed, true); }
	
	/**
	 * Move to a given pan and tilt at a given speed.
	 * @param pan Pan angle in degrees
	 * @param tilt Tilt angle in degrees
	 * @param speed Turn rate in degrees/second
	 * @param smooth If enabled, the pan or tilt speed will be changed so that the move will be done in a single smooth motion.
	 */
	public void moveTo(float pan, float tilt, float speed, boolean smooth)
	{
		// Either the characteristics haven't been discovered yet or there has been an error during discovery
		if (servoHorizontal != null && servoVertical != null)
		{
			int panSpeed, tiltSpeed, panVal, tiltVal;
			
			// Calculate pan and tilt values
			panVal = servoAngle(pan);
			tiltVal = servoAngle(tilt);
			
			// If the smooth flag is not checked, move at the same speed on both axes
			if (!smooth)
			{
				panSpeed = tiltSpeed = servoSpeed(speed);
			}
			
			// For smooth moving, the ratio of the pan speed to the tilt speed is the same as the ratio of the pan distance to the tilt distance.
			// This ensures that the it takes the same amount of time to pan to the given position as it does to tilt.
			else
			{
				// First calculate how far the kubi will pan and tilt
				int panArc = (int)Math.abs(pan - lastPan); int tiltArc = (int)Math.abs(tilt - lastTilt);
				
				// Find the axis along which the kubi will move the most, and assign the given speed to that axis.
				// Then calculate the speed of the other axes as equal to the max speed times the ratio of the movements.
				if (panArc > tiltArc)
				{
					panSpeed = servoSpeed(speed);
					tiltSpeed = (int)(((float)tiltArc)/panArc * panSpeed);
				}
				else if (tiltArc > panArc)
				{
					tiltSpeed = servoSpeed(speed);
					panSpeed = (int)(((float)panArc)/tiltArc * tiltSpeed);
				}
				
				// If the distances are equal, move at the same speed
				else
				{
					panSpeed = tiltSpeed = servoSpeed(speed);
				}
			}
			
			// Ensure that the speed does not fall below the minimum value to prevent jerky movement
			if (tiltSpeed < Kubi.MIN_SPEED_VAL) tiltSpeed = Kubi.MIN_SPEED_VAL;
			if (panSpeed < Kubi.MIN_SPEED_VAL) panSpeed = Kubi.MIN_SPEED_VAL;
			
			// Pan Speed
			byte[] buff = new byte[4];
			buff[0] = 1;
			buff[1] = SERVO_SPEED;
			buff[2] = (byte)panSpeed;
			buff[3] = (byte)(panSpeed >> 8);
			super.enqueueWrite(registerWrite2p, buff);
			
			// Tilt speed
			buff = new byte[4];
			buff[0] = 2;
			buff[1] = SERVO_SPEED;
			buff[2] = (byte)tiltSpeed;
			buff[3] = (byte)(tiltSpeed >> 8);
			super.enqueueWrite(registerWrite2p, buff);
			
			// Pan value
			buff = new byte[2];
			buff[0] = (byte)(panVal >> 8);
			buff[1] = (byte)panVal;
			super.enqueueWrite(servoHorizontal, buff);
			
			// Tilt value
			buff = new byte[2];
			buff[0] = (byte)(tiltVal >> 8);
			buff[1] = (byte)tiltVal;
			super.enqueueWrite(servoVertical, buff);
			
			lastPan = pan;
			lastTilt = tilt;
			
		}
	}
	
	
	/**
	 * Disconnect this kubi. Avoid using this, instead use KubiManager.disconnect()
	 */
	public void disconnect()
	{
		// Disconnect GATT and set the value to null so that no queued commands are executed
		super.mGatt.disconnect();
	}
	
	
	/**
	 * Set the kubi's status indicator to a certain color
	 * @param red Red component (0-255)
	 * @param green Green component (0-255)
	 * @param blue Blue component (0-255)
	 */
	public void setIndicatorColor(byte red, byte green, byte blue)
	{
		// Send the values to the led colour characteristic
		byte[] buff = new byte[3];
		buff[0] = red;
		buff[1] = green;
		buff[2] = blue;
		super.enqueueWrite(ledColor,buff);
	}
	
	
	/**
	 * Get's the kubi ID for this kubi
	 * @return 6 character kubi ID string
	 */
	public String getKubiID()
	{
		String name = mDevice.getName();
		return name.substring(name.length()-6);
	}
	
	/**
	 * Perform a given gesture.
	 * @param gesture The gesture code, e.g Kubi.GESUTRE_NOD
	 */
	public void performGesture(int gesture)
	{
		// Choose the gesture to run
		switch(gesture)
		{
			case GESTURE_BOW:
				this.bow();
				break;
			case GESTURE_NOD:
				this.nod();
				break;
			case GESTURE_SHAKE:
				this.shake();
				break;
			case GESTURE_SCAN:
				this.scan();
				break;
		}
	}

	private void bow()
	{
        this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(lastPan, 10, DEFAULT_SPEED, false);
            }
        }, 200);
        
        this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(lastPan, -27, DEFAULT_SPEED, false);
            }
        }, 700);
        
        this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(lastPan, 0, DEFAULT_SPEED, false);
            }
        }, 1650);
	}
	
	private void shake()
	{
		shakeTemp = lastPan;
		this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(shakeTemp-15, lastTilt, DEFAULT_SPEED, false);
            }
        }, 200);
        this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(shakeTemp+15, lastTilt, DEFAULT_SPEED, false);
            }
        }, 500);
        this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(shakeTemp, lastTilt, DEFAULT_SPEED, false);
            }
        }, 1250);
	}
	
	private void nod()
	{
        nodTemp = lastTilt;
		this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(lastPan, -15, DEFAULT_SPEED, false);
            }
        }, 200);
        this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(lastPan, 0, DEFAULT_SPEED, false);
            }
        }, 500);
        this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(lastPan, -15, DEFAULT_SPEED, false);
            }
        }, 800);
        this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(lastPan, nodTemp);
            }
        }, 1100);
	}
	
	private void scan()
	{
		this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(-120, 0, DEFAULT_SPEED, false);
            }
        }, 200);
		this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(-60, 0, DEFAULT_SPEED, false);
            }
        }, 3000);
		this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(0, 0, DEFAULT_SPEED, false);
            }
        }, 5000);
		this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(60, 0, DEFAULT_SPEED, false);
            }
        }, 7000);
		this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(120, 0, DEFAULT_SPEED, false);
            }
        }, 9000);
		this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	moveTo(0, 0, DEFAULT_SPEED, false);
            }
        }, 11000);
	}
	
	private void requestRSSI()
	{
		super.mGatt.readRemoteRssi();
	}
	
	private void sendOnReady()
	{
		if (mKubiManager != null)
			mKubiManager.onKubiReady(this);
	}

	// ===============================================================================
	// == GATT Callbacks
	@Override
	public void onConnectionStateChange (BluetoothGatt gatt, int status, int newState)
	{
		// On connect, discover service
		if (newState == BluetoothProfile.STATE_CONNECTED)
		{
			Log.i("Kubi","Kubi connected.");
			super.mGatt = gatt;
			gatt.discoverServices();
		} else if (newState == BluetoothProfile.STATE_DISCONNECTED)
		{
			this.mKubiManager.onKubiDisconnect(this);
			Log.i("Kubi","Kubi disconnected.");
		}
	}
	
	@Override
	public void onServicesDiscovered (BluetoothGatt gatt, int status)
	{
		// Enumerate services and characteristics
		if (status == BluetoothGatt.GATT_SUCCESS)
		{
			// Services
			servoService = 		gatt.getService(SERVO_SERVICE_UUID);
			kubiService = 		gatt.getService(KUBI_SERVICE_UUID);
			
			if (servoService != null && kubiService != null)
			{
				
				// Servo Characteristics
				registerWrite1p = 	servoService.getCharacteristic(REGISTER_WRITE1P_UUID);
				registerWrite2p = 	servoService.getCharacteristic(REGISTER_WRITE2P_UUID);
				servoHorizontal = 	servoService.getCharacteristic(SERVO_HORIZONTAL_UUID);
				servoVertical = 	servoService.getCharacteristic(SERVO_VERTICAL_UUID);
				
				// Kubi Characteristics
				battery = 			kubiService.getCharacteristic(BATTERY_UUID);
				servoError = 		kubiService.getCharacteristic(SERVO_ERROR_UUID);
				servoErrorID = 		kubiService.getCharacteristic(SERVO_ERROR_ID_UUID);
				ledColor = 			kubiService.getCharacteristic(LED_COLOR_UUID);
				batteryStatus = 	kubiService.getCharacteristic(BATTERY_STATUS_UUID);
				button = 			kubiService.getCharacteristic(BUTTON_UUID);
				
				// Notify manager that the kubi is ready for commands
				if (mKubiManager != null)
				{
			        this.mHandler.post(new Runnable() {
			            @Override
			            public void run() {
			            	sendOnReady();
			            }
			        });
			        this.requestRSSI();
				}
			}
			else
				this.mKubiManager.disconnect();
		}
		else
			Log.e("Kubi", "Unable to discover services.");
	}
	
	@Override
	public void onReadRemoteRssi (BluetoothGatt gatt, int rssi, int status) {
		if (status == BluetoothGatt.GATT_SUCCESS)
		{
			mRSSI = rssi;
			Log.i("Kubi", String.format("Kubi RSSI (%d) successfully updated.",rssi));
			// Notify manager of the updated rssi
			this.mKubiManager.onKubiUpdateRSSI(this, rssi);
		}
		
		// Queue a new rssi read
        this.mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
            	requestRSSI();
            }
        },RSSI_REQUEST_INTERVAL);
	}
	
	// == End GATT Callbacks
	// ===============================================================================

	@Override
	protected void characteristicValueRead(BluetoothGattCharacteristic c) {
		
		
	}
}
