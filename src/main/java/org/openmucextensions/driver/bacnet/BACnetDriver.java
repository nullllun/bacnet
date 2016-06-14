/*  OpenMUC BACnet driver service
 *  Copyright (C) 2015 Mike Pichler
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.openmucextensions.driver.bacnet;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.openmuc.framework.config.ArgumentSyntaxException;
import org.openmuc.framework.config.ConfigService;
import org.openmuc.framework.config.DeviceConfig;
import org.openmuc.framework.config.DeviceScanInfo;
import org.openmuc.framework.config.DriverConfig;
import org.openmuc.framework.config.DriverInfo;
import org.openmuc.framework.config.ScanException;
import org.openmuc.framework.config.ScanInterruptedException;
import org.openmuc.framework.driver.spi.Connection;
import org.openmuc.framework.driver.spi.ConnectionException;
import org.openmuc.framework.driver.spi.DriverDeviceScanListener;
import org.openmuc.framework.driver.spi.DriverService;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.util.DiscoveryUtils;

/**
 * BACnet/IP communication driver for OpenMUC based on bacnet4J.
 * 
 * @author Mike Pichler
 */
public class BACnetDriver implements DriverService {

	private final static Logger logger = LoggerFactory.getLogger(BACnetDriver.class);
	
	/** Setting-name for the sleep time of the discovery process (in ms) */
	private final static String SETTING_SCAN_DISCOVERYSLEEPTIME = "discoverySleepTime";
	/** Setting-name for the single port used for scanning */
	private final static String SETTING_SCAN_PORT = "scanPort";
	/** Setting-name for the broadcast ip address */
	private final static String SETTING_BROADCAST_IP = "broadcastIP";
	/** Setting-name for the local ip address used for binding of the driver */
	private final static String SETTING_LOCALBIND_ADDRESS = "localBindAddress";
	/** Setting-name for the local UDP port which has to be used (for local BACnet server) */
	private final static String SETTING_LOCAL_PORT = "localDevicePort";
	/** Setting-name for the instance number of the local device (for local BACnet server) */
	private final static String SETTING_LOCAL_DVC_INSTANCENUMBER = "localInstanceNumber";
	/** Setting-name for the UDP port of the remote device */
	private final static String SETTING_REMOTE_PORT = "remoteDevicePort";
	/** Setting-name for the flag whether this device is a BACnet server */
	private final static String SETTING_ISSERVER = "isServer";

	private final static long defaultDiscoverySleepTime = 2*1000;
    private ConfigService configService;
	
	private final LocalDeviceCache localDevicesCache = new LocalDeviceCache();
	
	// key is the remote device instance number
	private final Map<Integer, RemoteDevice> remoteDevices = new ConcurrentHashMap<Integer, RemoteDevice>();
	
	private volatile boolean deviceScanInterrupted = false;
	private final Object lock = new Object();
	
	private final static DriverInfo driverInfo = new DriverInfo("bacnet", // id
			// description
			"BACnet/IP communication protocol driver",
			// device address
			"BACnet device instance number is used as device address",
			// settings
			"No settings possible",
			// channel address
			"The technical designation is used as channel address",
			// device scan parameters
			"No settings possible");
	
	/**
	 * The activate method will be called from the OSGi framework on startup of the bundle.
	 * 
	 * @param context OSGi component context
	 * @param properties OSGi config admin properties
	 */
	protected void activate(ComponentContext context, Map<String, Object> properties) {			
		logger.info("BACnet communcation driver activated");
	}
	
	/**
	 * The deactivate method will be called from the OSGi framework on shutdown of the bundle.
	 * 
	 * @param context OSGi component context
	 */
	protected void deactivate(ComponentContext context) {
		localDevicesCache.dismissAll();
		logger.info("BACnet communication driver deactivated, all local devices terminated");
	}
	
	protected void setConfigService(ConfigService cs) {
	    this.configService = cs;
	}
	
	protected void unsetConfigService(ConfigService cs) {
	    if (configService == cs)
	       configService = null;
	}
		
	@Override
	public DriverInfo getInfo() {
		return driverInfo;
	}

	@Override
	public void scanForDevices(String settingsString, DriverDeviceScanListener listener)
			throws UnsupportedOperationException, ArgumentSyntaxException, ScanException, ScanInterruptedException {
		
		deviceScanInterrupted = false;
		
		Settings settings = new Settings(settingsString);

		long discoverySleepTime;
		if (settings.containsKey(SETTING_SCAN_DISCOVERYSLEEPTIME)) {
			final String discoverySleepTimeSetting = settings.get(SETTING_SCAN_DISCOVERYSLEEPTIME);
			try {
				discoverySleepTime = Long.parseLong(discoverySleepTimeSetting);
			}
			catch (NumberFormatException nfe) {
				logger.info("invalid parameter discoverySleepTime " + SETTING_SCAN_DISCOVERYSLEEPTIME + "; using default value");
				discoverySleepTime = defaultDiscoverySleepTime;
			}
		}
		else
			discoverySleepTime = defaultDiscoverySleepTime;
		
		if(!settings.containsKey(SETTING_SCAN_PORT)) {
			int progress = 0;
			for(int scanPort=0xBAC0; scanPort<=0xBACF; scanPort++) {
				scanAtPort(settings.get(SETTING_BROADCAST_IP), scanPort, listener, discoverySleepTime);
				progress += 6; // add 6% progress per port
				if(listener!=null) listener.scanProgressUpdate(progress);
				if(deviceScanInterrupted) return;
			}
		} else {
			Integer scanPort = (settings.containsKey(SETTING_SCAN_PORT)) ? parsePort(settings.get(SETTING_SCAN_PORT)) : IpNetwork.DEFAULT_PORT;				
			scanAtPort(settings.get(SETTING_BROADCAST_IP), scanPort, listener, discoverySleepTime);
		}
	}

	@Override
	public void interruptDeviceScan() throws UnsupportedOperationException {
		deviceScanInterrupted = true;
	}

	@Override
	public Connection connect(final String deviceAddress, final String settingsString)
			throws ArgumentSyntaxException, ConnectionException {
		
		Integer remoteInstance = parseDeviceAddress(deviceAddress);
		
		Settings settings = new Settings(settingsString);
		
		LocalDevice localDevice = null;
		boolean isServer;
		try {
			String broadcastIP = settings.get(SETTING_BROADCAST_IP);
			String localBindAddress = settings.get(SETTING_LOCALBIND_ADDRESS);
			final Integer localDevicePort;
			if (settings.containsKey(SETTING_LOCAL_PORT))
			    localDevicePort = parsePort(settings.get(SETTING_LOCAL_PORT));
			else {
	            if (settings.containsKey(SETTING_REMOTE_PORT))
	                localDevicePort = parsePort(settings.get(SETTING_REMOTE_PORT));
	            else
	                localDevicePort = null;
			}
			Integer localDeviceInstanceNumber = (settings.containsKey(SETTING_LOCAL_DVC_INSTANCENUMBER)) ? parseDeviceAddress(settings.get(SETTING_LOCAL_DVC_INSTANCENUMBER)) : null;
			isServer = (settings.containsKey(SETTING_ISSERVER)) ? Boolean.parseBoolean(settings.get(SETTING_ISSERVER)) : Boolean.FALSE;
			
			localDevice = localDevicesCache.obtainLocalDevice(broadcastIP, localBindAddress, localDevicePort, localDeviceInstanceNumber);
		} catch (Exception e) {
			throw new ConnectionException("error while getting/creating local device: " + e.getMessage());
		}
		
		if (isServer) {
		    final DriverConfig driverConfig = configService.getConfig().getDriver(driverInfo.getId());
		    
		    // try to find the device-configuration by address and settings-string
		    final Optional<DeviceConfig> deviceConfig = driverConfig.getDevices().stream()
		        .filter(dc -> deviceAddress.equals(dc.getDeviceAddress()) && settingsString.equals(dc.getSettings()))
		        .findAny();
            if (!deviceConfig.isPresent()) {
                throw new InternalError(String.format("cannot find deviceConfig for address {}", deviceAddress));
            }
		    
		    final BACnetServerConnection connection = new BACnetServerConnection(localDevice, deviceConfig.get());
		    return connection;
		}
		else {
    		if(!remoteDevices.containsKey(remoteInstance)) {
    			try {
    			    // remote device not found in cached ones --> re-scan for devices
    				final Settings scanSettings = new Settings();
    				scanSettings.put(SETTING_BROADCAST_IP, settings.get(SETTING_BROADCAST_IP));
    				scanSettings.put(SETTING_LOCALBIND_ADDRESS, settings.get(SETTING_LOCALBIND_ADDRESS));
    				scanSettings.put(SETTING_SCAN_PORT, settings.get(SETTING_REMOTE_PORT));
    				scanForDevices(scanSettings.toSettingsString(), null);
    			} catch (UnsupportedOperationException ignore) {
    				throw new AssertionError();
    			} catch (ScanException e) {
    				throw new ConnectionException(e.getMessage());
    			} catch (ScanInterruptedException ignore) {
    				// scan not started by framework, so don't propagate exception
    			}
    		}
    		
    		RemoteDevice remoteDevice = remoteDevices.get(remoteInstance);
    		if(remoteDevice == null) throw new ConnectionException("could not find device " + deviceAddress);
    		
    		// test if device is reachable (according to OpenMUC method documentation)
    		try {
    			DiscoveryUtils.getExtendedDeviceInformation(localDevice, remoteDevice);
    		} catch (BACnetException e) {
    			throw new ConnectionException("Couldn't reach device " + deviceAddress, e);
    		}
		
			BACnetConnection connection = new BACnetConnection(localDevice, remoteDevice);
			
			Integer writePriority = (settings.containsKey("writePriority")) ? parseWritePriority(settings.get("writePriority")) : null;
			connection.setWritePriority(writePriority);
			
			return connection;
		}
	}
	
	private void scanAtPort(String broadcastIP, Integer scanPort, DriverDeviceScanListener listener, long discoverySleepTime) throws ScanException, ScanInterruptedException, ArgumentSyntaxException {
		if (scanPort == null)
			throw new IllegalArgumentException("scanPort must not be null");
		final LocalDevice localDevice;
		// check if createLocalDevice will create a new one
		try {
		    localDevice = localDevicesCache.obtainLocalDevice(broadcastIP, null, scanPort, null);
		} catch (Exception e) {
			throw new ScanException("error while getting/creating local device for scan: " + e.getMessage());
		}
		
		try {
			localDevice.sendGlobalBroadcast(new WhoIsRequest());
			
			// Thread.sleep(discoverySleepTime);
			synchronized (lock) {
				lock.wait(discoverySleepTime);
			}
			
		} catch (InterruptedException ignore) {
			logger.warn("device scan has been interrupted while waiting for responses");
		}
        
		logger.debug("found {} remote device(s) from scan at port 0x{}", localDevice.getRemoteDevices().size(), Integer.toHexString(scanPort.intValue())); 
		 
        for (RemoteDevice device : localDevice.getRemoteDevices()) {
        	
        	if(deviceScanInterrupted) throw new ScanInterruptedException();
        	
        	try {
				DiscoveryUtils.getExtendedDeviceInformation(localDevice, device);
			} catch (BACnetException e) {
				logger.warn("error while reading extended device information from device " + device.getInstanceNumber());
			}
			remoteDevices.put(device.getInstanceNumber(), device);
			
			if(listener != null) {
				final Settings scanSettings = new Settings();
				if (broadcastIP != null)
					scanSettings.put(SETTING_BROADCAST_IP, broadcastIP);
				scanSettings.put(SETTING_REMOTE_PORT, scanPort.toString());
				listener.deviceFound(new DeviceScanInfo(Integer.toString(device.getInstanceNumber()), scanSettings.toSettingsString(), device.getName()));
			}
		}
        
        if((localDevice.getRemoteDevices().size()==0)) {
            logger.debug("dismiss local device {} because no remote devices have been found", localDevice.getConfiguration().getInstanceId());
            localDevicesCache.dismissLocalDevice(scanPort);
        }
	}
	
	private int parsePort(String port) throws ArgumentSyntaxException {
		try {
			return Integer.decode(port).intValue();
		} catch (NumberFormatException e) {
			throw new ArgumentSyntaxException("port value is not a number");
		}
	}
	
	private Integer parseWritePriority(String priority) throws ArgumentSyntaxException {
		try {
			Integer writePriority = Integer.decode(priority);
			if(writePriority.intValue()<1 || writePriority.intValue()>16) {
				throw new ArgumentSyntaxException("writePriority value must be between 1 and 16");
			}
			return writePriority;
		} catch (NumberFormatException e) {
			throw new ArgumentSyntaxException("writePriority value is not a number");
		}
	}
	
	private Integer parseDeviceAddress(String deviceAddress) throws ArgumentSyntaxException {
		
		int result;
		
		try {
			result = Integer.parseInt(deviceAddress);
		} catch (NumberFormatException e) {
			throw new ArgumentSyntaxException("Argument deviceAddress is not a number");
		}
	
		// accoring to the BTL device implementation guidelines, the device instance number
		// shall be configurable in the range of 0 to 4194302
		if(result < 0 || result > 4194302)
			throw new ArgumentSyntaxException("Argument deviceAddress must be in the range of 0 to 4194302");
		
		return new Integer(result);
	}
}
