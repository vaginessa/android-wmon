package com.gnychis.awmon.BackgroundService;

import java.util.ArrayList;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.gnychis.awmon.Core.ScanRequest;
import com.gnychis.awmon.DeviceAbstraction.Interface;
import com.gnychis.awmon.HardwareHandlers.HardwareHandler;
import com.gnychis.awmon.InterfaceScanners.InterfaceScanManager;
import com.gnychis.awmon.NameResolution.NameResolutionManager;

@SuppressWarnings("unchecked")
public class ScanManager {
	
	Context _parent;					// Access to the parent class for broadcasts
	HardwareHandler _hardwareHandler;	// To have access to the internal radios
	ScanRequest _workingRequest;		// The most recent scan request we are working on
	
	public static final String SCAN_REQUEST = "awmon.scanmanager.scan_request";
	public static final String SCAN_RESPONSE = "awmon.scanmanager.scan_response";

	State _state;
	public enum State {
		IDLE,
		SCANNING,
		RESOLVING,
		MERGING,
	}
	
	public enum ResultType {
		INTERFACES,
		DEVICES,
	}
	
	/** Parent is anything we can send a broadcast from.  HardwareHandler is needed to access the
	 * internal radios.  This allows us to see if the radio is connected and request a scan from them.
	  * 
	  * @param p  Any parent context.
	  * @param dh A device handler
	  *
	*/
	public ScanManager(Context p, HardwareHandler dh) {
		_parent=p;
		_hardwareHandler=dh;
		
		_parent.registerReceiver(incomingEvent, new IntentFilter(ScanManager.SCAN_REQUEST));
		_parent.registerReceiver(incomingEvent, new IntentFilter(InterfaceScanManager.INTERFACE_SCAN_RESULT));
		_parent.registerReceiver(incomingEvent, new IntentFilter(NameResolutionManager.NAME_RESOLUTION_RESPONSE));

	}
	
	private void broadcastResults(ScanManager.ResultType type, ArrayList<?> results) {
		Intent i = new Intent();
		i.setAction(InterfaceScanManager.INTERFACE_SCAN_REQUEST);
		i.putExtra("type", type);
		i.putExtra("result", results);
		_parent.sendBroadcast(i);
	}
	
	/** This is an incoming scan request.  A ScanRequest must be passed with it so we know what kind of
	 * scan is being requested.*/
    private BroadcastReceiver incomingEvent = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
        	
        	switch(_state) {	// Based on the current state, decide what next to do
        	
        		/***************************** IDLE **********************************/
        		case IDLE:
        			
        			if(intent.getAction().equals(ScanManager.SCAN_REQUEST)) {
        				
        				// Get the type of scan request, then cache it as our active request
        				ScanRequest request = null;
        				if((request = (ScanRequest) intent.getExtras().get("request"))==null)
        					return;
        				_workingRequest = request;
        				
        				// Go ahead and switch out state to scanning, then send out the request
        				// for an interface scan.
        				Intent i = new Intent();
        				i.setAction(InterfaceScanManager.INTERFACE_SCAN_REQUEST);
        				_parent.sendBroadcast(i);
        				
        				_state=State.SCANNING;       // We are scanning now!				
        			}
        
    			break;
    			
    			/*************************** SCANNING ********************************/
        		case SCANNING:
        			
        			if(intent.getAction().equals(InterfaceScanManager.INTERFACE_SCAN_RESULT)) {
        				ArrayList<Interface> interfaces = (ArrayList<Interface>) intent.getExtras().get("result");
        				
        				// Now, we decide to do name resolution and merging.  If we do not do name resolution,
        				// then we do NOT merge.  This is because a significant portion of merging uses names.
        				if(!_workingRequest.doNameResolution()) {
        					broadcastResults(ScanManager.ResultType.INTERFACES, interfaces);
        					_state=State.IDLE;
        					return;
        				}
        				
        				// Send the request to do name resolution on the interfaces, passing them along
        				Intent i = new Intent();
        				i.setAction(NameResolutionManager.NAME_RESOLUTION_REQUEST);
        				i.putExtra("interfaces", interfaces);
        				_parent.sendBroadcast(i);
        				
        				_state=ScanManager.State.RESOLVING;
        			}
        			
    			break;
    			
    			/*************************** RESOLVING ********************************/
        		case RESOLVING:
        			
        			if(intent.getAction().equals(NameResolutionManager.NAME_RESOLUTION_RESPONSE)) {
        				ArrayList<Interface> interfaces = (ArrayList<Interface>) intent.getExtras().get("result");
        				
        				// If we are not doing merging (OK), then we just return the interfaces with names.
        				if(!_workingRequest.doMerging()) {
        					broadcastResults(ScanManager.ResultType.INTERFACES, interfaces);
        					_state=State.IDLE;
        					return;
        				}
        				
        				// FIXME: Fill in the code here for merging
        			}
        			
        		break;
        		
        		/**************************** MERGING *********************************/
        		case MERGING:
        			
        		break;
        		
        	}
        	
    	}
    };
}