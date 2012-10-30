package com.gnychis.awmon.NameResolution;

import java.util.ArrayList;
import java.util.Arrays;

import com.gnychis.awmon.Core.Device;

public class ResolverOUI extends NameResolver {

	public ResolverOUI(NameResolutionManager nrm) {
		super(nrm, Arrays.asList(Device.Type.Bluetooth, Device.Type.Wifi));
	}
	
	public ArrayList<Device> resolveSupportedDevices(ArrayList<Device> supportedDevices) {
		
		return supportedDevices;
	}
}
