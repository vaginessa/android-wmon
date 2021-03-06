package com.gnychis.awmon.InterfaceMerging;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Stack;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.gnychis.awmon.DeviceAbstraction.Device;
import com.gnychis.awmon.DeviceAbstraction.Interface;

@SuppressWarnings("unchecked")
public class InterfaceMergingManager {
	
	public static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS"); 

	private static final String TAG = "InterfaceMergingManager";
	private static final boolean VERBOSE = true;
	
	public static final String INTERFACE_MERGING_REQUEST = "awmon.interfacemerging.interface_merging_request";
	public static final String INTERFACE_MERGING_RESPONSE = "awmon.interfacemerging.interface_merging_response";
	
	Context _parent;
	Queue<Class<?>> _pendingResults;
	Stack<Class<?>> _heuristicQueue;	// These should be kept in a hierarchy such that
										// it would be OK if the next resolver overwrote previous.
	
	FileOutputStream _data_ostream;
	JSONArray _mergeStats;
	JSONObject _overallStats;

	State _state;
	public enum State {
		IDLE,
		MERGING,
	}
	
	public InterfaceMergingManager(Context parent) {
		_parent = parent;
		_state = State.IDLE;
        
        _parent.registerReceiver(incomingEvent, new IntentFilter(MergeHeuristic.MERGE_HEURISTIC_RESPONSE));
        _parent.registerReceiver(incomingEvent, new IntentFilter(INTERFACE_MERGING_REQUEST));
	}
	
	public void requestMerge(ArrayList<Interface> interfaces) {
		Intent i = new Intent();
		i.setAction(InterfaceMergingManager.INTERFACE_MERGING_REQUEST);
		i.putExtra("interfaces", interfaces);
		_parent.sendBroadcast(i);
	}
	
	private void registerHeuristic(List<? extends Class<? extends MergeHeuristic>> heuristics) {
		_heuristicQueue = new Stack<Class<?>>();
		_pendingResults = new LinkedList < Class<?> >();
		for(Class<?> heuristic : heuristics) {
			debugOut("Registering the following interface merging heuristic: " + heuristic.getName());
			_heuristicQueue.push(heuristic);
			_pendingResults.add(heuristic);
		}
	}
	
    private BroadcastReceiver incomingEvent = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
        	
        	switch(_state) {	// Based on the current state, decide what next to do
        	
    		/***************************** IDLE **********************************/
    		case IDLE:
    			if(intent.getAction().equals(INTERFACE_MERGING_REQUEST)) {
    				debugOut("Receiving an incoming interface merging request, triggering");
    				ArrayList<Interface> interfaces = (ArrayList<Interface>) intent.getExtras().get("interfaces");
    				
    				// From the incoming interfaces, create a new InterfaceConnectivityGraph
    				InterfaceConnectivityGraph graph = new InterfaceConnectivityGraph(interfaces);
    				
    				// Put all of the name resolves on to a stack.  Push last the one that you want to go first.
    				registerHeuristic(Arrays.asList(SimilarInterfaceNames.class,
    													AdjacentMACs.class,
    													SameMAC.class,
    													ThisPhone.class,
    													APGatewayInterface.class));
    				
    				// Set the state to scanning, then clear the scan results.
    				_state = State.MERGING;
    				
    				try {
	    				_mergeStats = new JSONArray(); 
	    				_overallStats = new JSONObject();
	    				_overallStats.put("interfaces", interfaces.size());
    				} catch(Exception e) {}
    				
    				triggerNextHeuristic(graph);
    			}
    		break;
    		
			/**************************** MERGING ********************************/
    		case MERGING:
    			if(intent.getAction().equals(MergeHeuristic.MERGE_HEURISTIC_RESPONSE)) {
    	        	InterfaceConnectivityGraph graph = (InterfaceConnectivityGraph) intent.getExtras().get("result");
    	        	Class<?> heuristicType = (Class<?>) intent.getExtras().get("heuristic");
    	        	int connected = (Integer) intent.getExtras().get("connected");
    	        	
    	        	// Remove this result as pending, then check if there are any more heuristics we need.
    	        	debugOut("Received heuristic response from: " + heuristicType.getName());
    	        	_pendingResults.remove(heuristicType);
    	        	triggerNextHeuristic(graph);
    	        	
    	        	// Add the stats for this merge
    	        	try {
	    	        	JSONObject mergeStat = new JSONObject();
	    	        	mergeStat.put("name", heuristicType.toString());
	    	        	mergeStat.put("connected", connected);
	    	        	_mergeStats.put(mergeStat);
    	        	} catch(Exception e) { }
    	        	
    	        	if(_pendingResults.size()==0) {
    	        		
    	        		// Since there are no more heuristics to run, we can now use the graph to get the current
    	        		// groups of interfaces and create Devices from them.
    	        		ArrayList<Device> devices = graph.devicesFromConnectivityGraph();
    	        		
        				try {
        					_data_ostream = _parent.openFileOutput("merge_activity.json", Context.MODE_WORLD_READABLE | Context.MODE_APPEND);
        					_overallStats.put("date", dateFormat.format(new Date()));
        					_overallStats.put("heuristics", _mergeStats);
        					_data_ostream.write(_overallStats.toString().getBytes());
        					_data_ostream.write("\n".getBytes()); 
        					_data_ostream.close();
        				} catch(Exception e) {  }	
    	        		
    	        		// Broadcast out the list of devices that came from our interface scan, naming, and merging.
    	        		Intent i = new Intent();
    	        		i.setAction(INTERFACE_MERGING_RESPONSE);
    	        		i.putExtra("result", devices);
    	        		_parent.sendBroadcast(i);
    	        		_state=State.IDLE;
    	        		
    	        		debugOut("Received responses from all of the heuristics, going back to idle.");
    	        		return;
    	        	}
    			}
    		break;
    	}
        }
    };
    
	public boolean triggerNextHeuristic(InterfaceConnectivityGraph graph) {
		
		if(_heuristicQueue.size()==0)
			return false;
		
		Class<?> heuristicRequest = _heuristicQueue.pop();
		MergeHeuristic heuristic = null;	
				
		if(heuristicRequest == AdjacentMACs.class)
			heuristic = new AdjacentMACs(_parent);
		if(heuristicRequest == SimilarInterfaceNames.class)
			heuristic = new SimilarInterfaceNames(_parent);
		if(heuristicRequest == SameMAC.class)
			heuristic = new SameMAC(_parent);
		if(heuristicRequest == APGatewayInterface.class)
			heuristic = new APGatewayInterface(_parent);
		if(heuristicRequest == ThisPhone.class)
			heuristic = new ThisPhone(_parent);
		
		if(heuristic == null)
			return false;
		
		heuristic.execute(graph);
		
		debugOut("Executing the heuristic: " + heuristic.getClass().getName());
		return true;		
	}
	
	private void debugOut(String msg) {
		if(VERBOSE)
			Log.d(TAG, msg);
	}
}
