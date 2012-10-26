package com.gnychis.awmon.DeviceHandlers;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import org.jnetpcap.PcapHeader;
import org.jnetpcap.nio.JBuffer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Message;
import android.util.Log;

import com.gnychis.awmon.AWMon;
import com.gnychis.awmon.AWMon.ThreadMessages;
import com.gnychis.awmon.Core.Packet;
import com.gnychis.awmon.Core.USBMon;
import com.gnychis.awmon.Core.USBSerial;
import com.gnychis.awmon.DeviceHandlers.Wifi.USBWifiDev;
import com.stericson.RootTools.RootTools;

public class ZigBee {
	private static final String TAG = "ZigbeeDev";
	private static final boolean VERBOSE = true;
	
	// This defines the device USB ID we are looking for
	class USBZigBeeDev {
		public static final int vendorID=0x0403;
		public static final int productID=0x6010;
	}

	public static final int ZIGBEE_CONNECT = 200;
	public static final int ZIGBEE_DISCONNECT = 201;
	public static final String ZIGBEE_SCAN_RESULT = AWMon._app_name + ".ZIGBEE_SCAN_RESULT";
	public static final int MS_SLEEP_UNTIL_PCAPD = 5000;
	
	Context _parent;
	
	public boolean _device_connected;
	ZigBeeScan _monitor_thread;
	
	static int WTAP_ENCAP_802_15 = 127;
	
	ZigBeeState _state;
	private Semaphore _state_lock;
	public enum ZigBeeState {
		IDLE,
		SCANNING,
	}
	
	ArrayList<Packet> _scan_results;
	
	public ZigBee(Context c) {
		_state_lock = new Semaphore(1,true);
		_scan_results = new ArrayList<Packet>();
		_parent = c;
		_state = ZigBeeState.IDLE;
		Log.d(TAG, "Initializing ZigBee class...");
		_parent.registerReceiver(usbUpdate, new IntentFilter(USBMon.USBMON_DEVICELIST));
	}
	
	//////////////////////////////////////////////////////////////////////////////////////////////////
	// Related to the connection and disconnection of the USB device
	
    // Receives messages about USB devices
    private BroadcastReceiver usbUpdate = new BroadcastReceiver() {
    	@Override @SuppressWarnings("unchecked")
        public void onReceive(Context context, Intent intent) {
        	ArrayList<String> devices = (ArrayList<String>) intent.getExtras().get("devices");
        	if(USBMon.isDeviceInList(devices, USBZigBeeDev.vendorID, USBZigBeeDev.productID)) {
        		if(!_device_connected)
        			connected();
        	} else {
        		if(_device_connected)
        			disconnected();
        	}
        }
    };  
   
	public void connected() {
		_device_connected=true;
		ZigBeeInit zbi = new ZigBeeInit();
		zbi.execute(_parent);
	}
	
	public void disconnected() {
		_device_connected=false;
		AWMon.sendToastRequest(_parent, "ZigBee device disconnected");
	}
	
	public boolean isConnected() {
		return _device_connected;
	}
	
	///////////////////////////////////////////////////////////////////////////////////////////////////////////
	// Related to initializing the hardware
	protected class ZigBeeInit extends AsyncTask<Context, Integer, String>
	{
		Context parent;
		USBSerial _dev;
		
		// The initialized sequence (hardware sends it when it is initialized)
		byte initialized_sequence[] = {0x67, 0x65, 0x6f, 0x72, 0x67, 0x65, 0x6e, 0x79, 0x63, 0x68, 0x69, 0x73};

	    @Override
	    protected void onPreExecute() {
	        super.onPreExecute();
	        AWMon.sendProgressDialogRequest(_parent, "Initializing ZigBee device..");
	    }
		
		@Override
		protected String doInBackground( Context ... params )
		{
			parent = params[0];
			
			// Create a serial device
			_dev = new USBSerial();
			
			// Get the name of the USB device, which will be the last thing in dmesg
			String ttyUSB_name;
			try {
				List<String> res = RootTools.sendShell("dmesg | grep ttyUSB | tail -n 1 | awk '{print $NF}'",0);
				ttyUSB_name = res.get(0);
			} catch (Exception e) { return ""; }	
			
			// Attempt to open the COM port which calls the native libraries
			if(!_dev.openPort("/dev/" + ttyUSB_name))
				return "FAIL";
			
			debugOut("opened device, now waiting for sequence");
			
			// Wait for the initialized sequence...
			byte[] readSeq = new byte[initialized_sequence.length];
			AWMon.sendThreadMessage(_parent, AWMon.ThreadMessages.CANCEL_PROGRESS_DIALOG);
			AWMon.sendProgressDialogRequest(_parent, "Press the ZigBee reset button...");
			while(!checkInitSeq(readSeq)) {
				for(int i=0; i<initialized_sequence.length-1; i++)
					readSeq[i] = readSeq[i+1];
				readSeq[initialized_sequence.length-1] = _dev.getByte();
			}
			
			debugOut("received the initialization sequence!");
			
			// Close the port
			if(!_dev.closePort())
				AWMon.sendToastRequest(_parent, "Failed to initialize ZigBee device");

			return "OK";
		}
		
	    @Override
	    protected void onPostExecute(String result) {
	    	AWMon.sendThreadMessage(_parent, AWMon.ThreadMessages.CANCEL_PROGRESS_DIALOG);
	    	AWMon.sendToastRequest(_parent, "ZigBee device initialized");
	    }
		
		private void debugOut(String msg) {
			if(VERBOSE)
				Log.d("ZigBeeInit", msg);
		}
		
		public boolean checkInitSeq(byte buf[]) {
			
			for(int i=0; i<initialized_sequence.length; i++)
				if(initialized_sequence[i]!=buf[i])
					return false;
						
			return true;
		}
		
		
	}
	
	//////////////////////////////////////////////////////////////////////////////////////////////////
	// Functions for helping convert channels to frequencies
	public static int[] channels = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
	public static int[] frequencies = {2405, 2410, 2415, 2420, 2425, 2430, 2435, 
			2440, 2445, 2450, 2455, 2460, 2465, 2470, 2475, 2480};
	
	static public int freqToChan(int freq) {
		int i=0;
		for(i=0; i<frequencies.length; i++)
			if(frequencies[i]==freq)
				break;
		if(!(i<frequencies.length))
			return -1;
		
		return channels[i];
	}
	
	static public int chanToFreq(int chan) {
		if(chan<0 || chan>channels.length-1)
			return -1;
		return frequencies[chan];
	}	

	
	// Set the state to scan and start to switch channels
	public boolean scanStart() {
		
		// Only allow to enter scanning state IF idle
		if(!ZigBeeStateChange(ZigBeeState.SCANNING))
			return false;
		
		_scan_results.clear();
		
		_monitor_thread = new ZigBeeScan();
		_monitor_thread.execute(_parent);
		
		return true;  // in scanning state, and channel hopping
	}
	
	// Attempts to change the current state, will return
	// the state after the change if successful/failure
	public boolean ZigBeeStateChange(ZigBeeState s) {
		boolean res = false;
		if(_state_lock.tryAcquire()) {
			try {
				
				// Can add logic here to only allow certain state changes
				// Given a _state... then...
				switch(_state) {
				
				// From the IDLE state, we can go anywhere...
				case IDLE:
					Log.d(TAG, "Switching state from " + _state.toString() + " to " + s.toString());
					_state = s;
					res = true;
				break;
				
				// We can go to idle, or ignore if we are in a
				// scan already.
				case SCANNING:
					if(s==ZigBeeState.IDLE) {  // cannot go directly to IDLE from SCANNING
						Log.d(TAG, "Switching state from " + _state.toString() + " to " + s.toString());
						_state = s;
						res = true;
					} else if(s==ZigBeeState.SCANNING) {  // ignore an attempt to switch in to same state
						res = false;
					} 
				break;
				
				default:
					res = false;
				}
				
			} finally {
				_state_lock.release();
			}
		} 		
		
		return res;
	}
	
	public static byte[] parseMacAddress(String macAddress)
    {
        String[] bytes = macAddress.split(":");
        byte[] parsed = new byte[bytes.length];

        for (int x = 0; x < bytes.length; x++)
        {
            BigInteger temp = new BigInteger(bytes[x], 16);
            byte[] raw = temp.toByteArray();
            parsed[x] = raw[raw.length - 1];
        }
        return parsed;
    }
	
	public static BigInteger parseMacStringToBigInteger(String macAddress)
	{
		String newMac = macAddress.replaceAll(":", "");
		BigInteger ret = new BigInteger(newMac, 16);  // 16 specifies hex
		return ret;
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////
	// This is a class which spawns a background thread and executes the scanning for ZigBee
	// networks and devices in the area.  It
	protected class ZigBeeScan extends AsyncTask<Context, Integer, String>
	{
		Context parent;
		private int PCAP_HDR_SIZE = 16;
		int _channel;
		private Semaphore _comm_lock;
		USBSerial _dev;
		
		// Incoming commands
		byte CHANGE_CHAN=0x0000;
		byte TRANSMIT_PACKET=0x0001;
		byte RECEIVED_PACKET=0x0002;
		byte INITIALIZED=0x0003;
		byte TRANSMIT_BEACON=0x0004;
		byte START_SCAN=0x0005;
		byte SCAN_DONE=0x0006;
		byte CHAN_IS=0x0007;
		
		@Override
		protected void onCancelled()
		{
			Log.d(TAG, "ZigBee monitor thread is canceled...");
		}
		
		// Transmit a command to start a scan on the hardware (channel hop)
		public boolean initializeScan() {					
			try {
				// First, we need to get the name of the USB device from dmesg
				List<String> res = RootTools.sendShell("dmesg | grep ttyUSB | tail -n 1 | awk '{print $NF}'",0);
				String ttyUSB_name = res.get(0);	
				
				// We also setup the serial port and acquire a communication lock on it
				_dev = new USBSerial();
				_comm_lock.acquire();	
				if(!_dev.openPort("/dev/" + ttyUSB_name))
					return false;
				
				// Finally we trigger the scan
				_dev.writeByte(START_SCAN);
			} catch (Exception e) {  
				_comm_lock.release();
				return false;
			}	
			
			_comm_lock.release();	// Release the lock.
			return true;
		}
		
		// The entire meat of the thread, pulls packets off the interface and dissects them
		@Override
		protected String doInBackground( Context ... params )
		{
			parent = params[0];
			_comm_lock = new Semaphore(1,true);
			
			initializeScan(); 	// Initialize the scan
						
			// Loop and read headers and packets
			while(true) {
				byte cmd = getSocketData(1)[0];
				
				if(cmd==CHAN_IS) {
					_channel = (int)_dev.getByte() & 0xff;
					
					// Our way of tracking progress with the main UI
					AWMon.sendThreadMessage(parent, AWMon.ThreadMessages.INCREMENT_SCAN_PROGRESS);
				}
				
				if(cmd==SCAN_DONE)
					break;
				
				// Wait for a byte which signals a command
				if(cmd==RECEIVED_PACKET) {
				
					Packet rpkt = new Packet(WTAP_ENCAP_802_15);
					
					// The channel is read from the hardware
					rpkt._band = frequencies[(int)getSocketData(1)[0]];
					
					// Get the LQI
					rpkt._lqi = (int)getSocketData(1)[0] & 0xff;

					// Get the rx time
					getSocketData(4);
					
					// Get the data length
					rpkt._dataLen = (int)_dev.getByte();
					
					// Create a raw header (the serial device does not send one)
					rpkt._rawHeader = new byte[PCAP_HDR_SIZE];
					rpkt._headerLen = PCAP_HDR_SIZE;
					for(int k=0; k<8; k++)
						rpkt._rawHeader[k]=0;
					rpkt._rawHeader[8]=Integer.valueOf(rpkt._dataLen).byteValue(); rpkt._rawHeader[9]=0; rpkt._rawHeader[10]=0; rpkt._rawHeader[11]=0;
					rpkt._rawHeader[12]=Integer.valueOf(rpkt._dataLen).byteValue(); rpkt._rawHeader[13]=0; rpkt._rawHeader[14]=0; rpkt._rawHeader[15]=0;
									
					// Get the raw data now from the wirelen in the pcap header
					if((rpkt._rawData = getPcapPacket(rpkt._rawHeader))==null) {
						return "FAIL";
					}
					rpkt._dataLen = rpkt._rawData.length;

					// To identify a beacon from ZigBee, check for the field zbee.beacon.protocol.
					// If it exists, save the packet as part of our scan.
					if(rpkt.getField("zbee.beacon.protocol")!=null)
						_scan_results.add(rpkt);
				}
			}
			
			if(!_dev.closePort())
				AWMon.sendToastRequest(_parent, "ZigBee device failed while scanning");
			
			return "PASS";
		}
		
	    @Override
	    protected void onPostExecute(String result) {
	    	
    		// Need to return the state back to IDLE from scanning
    		if(!ZigBeeStateChange(ZigBeeState.IDLE))
    			Log.d(TAG, "Failed to change from scanning to IDLE");
	    		
    		// Now, send out a broadcast with the results
    		Intent i = new Intent();
    		i.setAction(ZIGBEE_SCAN_RESULT);
    		i.putExtra("packets", _scan_results);
    		_parent.sendBroadcast(i);
	    }
		
		// First, acquire the lock to communicate with the ZigBee device,
		// then send the command to change the channel and the channel number.
		public boolean setChannel(int channel) {
			try {
				_comm_lock.acquire();
				_dev.writeByte(CHANGE_CHAN);		// first send the command
				_dev.writeByte((byte)channel);	// then send the channel
			} catch(Exception e) { 
				_comm_lock.release();
				return false;
			}
			_channel = channel;
			_comm_lock.release();
			return true;
		}
		
		// Acquire the lock to communicate with the ZigBee device, then write
		// the command to transmit a beacon on the current channel.
		public boolean transmitBeacon() {
			try {
				_comm_lock.acquire();
				_dev.writeByte(TRANSMIT_BEACON);
			} catch(Exception e) {
				_comm_lock.release();
				return false;
			}
			_comm_lock.release();
			return true;
		}

		// Read the pcap packet from the socket, based on the number of bytes
		// specified in the header (needed).
		public byte[] getPcapPacket(byte[] rawHeader) {
			byte[] rawdata;
			PcapHeader header = null;

			try {
				header = new PcapHeader();
				JBuffer headerBuffer = new JBuffer(rawHeader);  
				header.peer(headerBuffer, 0);				
			} catch(Exception e) {
				Log.e("WifiMon", "exception trying to read pcap header",e);
			}
						
			rawdata = getSocketData(header.wirelen());
			return rawdata;
		}
		
		// Returns any length of socket data specified, blocking until complete.
		public byte[] getSocketData(int length) {
			return _dev.getBytes(length);
		}
	}
}
