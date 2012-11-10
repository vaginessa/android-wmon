package com.gnychis.awmon.DeviceAbstraction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;

import android.os.Parcel;
import android.os.Parcelable;

// A radio is a physical radio that exists in a device.  
public class WirelessInterface extends Interface implements Parcelable {
	
	public ArrayList<Integer> _RSSI;			// The RSSI of the device at the phone
	public int _frequency;						// The frequency it operates on
	public String _SSID;						// If the device belongs to a SSID (e.g., "The Smith's Wifi")
	public String _BSSID;						// The BSSID (MAC) of the coordinator

	public WirelessInterface(Class<?> ifaceType) { super(ifaceType); initVars(); }
	public WirelessInterface(Interface i) { super(i); initVars(); }
	
	public void initVars() {
		_RSSI = new ArrayList<Integer>();
		_frequency = -1;
		_SSID = null;
		_BSSID = null;
	}

	// Report the average RSSI
	public int averageRSSI() {
		
		if(_RSSI.size()==0)
			return -500;

		Iterator<Integer> rssis = _RSSI.iterator();
		int sum=0;
		while(rssis.hasNext()) {
			int i = rssis.next().intValue();
			sum += i;
		}

		return sum / _RSSI.size();
	}

	static public Comparator<Object> compareRSSI = new Comparator<Object>() {
		public int compare(Object arg0, Object arg1) {
			if(((WirelessInterface)arg0).averageRSSI() < ((WirelessInterface)arg1).averageRSSI())
				return 1;
			else if( ((WirelessInterface)arg0).averageRSSI() > ((WirelessInterface)arg1).averageRSSI())
				return -1;
			else
				return 0;
		}
	};

	// ********************************************************************* //
	// This code is to make this class parcelable and needs to be updated if
	// any new members are added to the Device class
	// ********************************************************************* //
	public int describeContents() {
		return this.hashCode();
	}

	public void writeToParcel(Parcel dest, int parcelableFlags) {
		dest.writeSerializable(_RSSI);
		dest.writeInt(_frequency);
		dest.writeString(_SSID);
		dest.writeString(_BSSID);
		writeInterfaceToParcel(dest, parcelableFlags);
	}

	public static final Parcelable.Creator<WirelessInterface> CREATOR = new Parcelable.Creator<WirelessInterface>() {
		public WirelessInterface createFromParcel(Parcel in) {
			return new WirelessInterface(in);
		}

		public WirelessInterface[] newArray(int size) {
			return new WirelessInterface[size];
		}
	};

	@SuppressWarnings("unchecked")
	private WirelessInterface(Parcel source) {
		_RSSI = new ArrayList<Integer>();
		_RSSI = (ArrayList<Integer>) source.readSerializable();
		_frequency = source.readInt();
		_SSID = source.readString();
		_BSSID = source.readString();
		readInterfaceParcel(source);
	}
}