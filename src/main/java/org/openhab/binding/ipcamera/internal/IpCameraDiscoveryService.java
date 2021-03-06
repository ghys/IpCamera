/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.binding.ipcamera.internal;

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.*;

import java.net.UnknownHostException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;
import org.openhab.binding.ipcamera.onvif.OnvifDiscovery;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link IpCameraDiscoveryService} is responsible for auto finding cameras that have Onvif
 *
 * @author Matthew Skinner - Initial contribution
 */

@NonNullByDefault
@Component(service = DiscoveryService.class, immediate = true, configurationPid = "binding.ipcamera")
public class IpCameraDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(IpCameraDiscoveryService.class);

    public IpCameraDiscoveryService() {
        super(IpCameraHandler.SUPPORTED_THING_TYPES, 30, false);
    }

    @Override
    protected void startBackgroundDiscovery() {

    }

    @Override
    protected void deactivate() {
        super.deactivate();
    }

    public void newCameraFound(String brand, String hostname, int onvifPort) {
        ThingTypeUID thingtypeuid = new ThingTypeUID("ipcamera", brand);
        ThingUID thingUID = new ThingUID(thingtypeuid, hostname.replace(".", ""));
        DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                .withProperty(CONFIG_IPADDRESS, hostname).withProperty(CONFIG_ONVIF_PORT, onvifPort)
                .withLabel(brand + " Camera @" + hostname).build();
        thingDiscovered(discoveryResult);
    }

    @Override
    protected void startScan() {
        removeOlderResults(getTimestampOfLastScan());
        OnvifDiscovery onvifDiscovery = new OnvifDiscovery(this);
        try {
            onvifDiscovery.discoverCameras(3702);
            onvifDiscovery.discoverCameras(1900);
        } catch (UnknownHostException | InterruptedException e) {
            logger.error(
                    "IpCamera Discovery has an issue discovering the network setting to find cameras with. Setup camera manually.");
        }
    }
}
