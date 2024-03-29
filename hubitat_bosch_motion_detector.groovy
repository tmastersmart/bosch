/*Bosch Motion Detector  driver for hubitat
ISW-ZPR1-WP13 and ISW-ZDL1-WP11G
With battery support. 


This project is closed I no longer have a working device. 
So you may use this code but no more updates.

this is a work in process trying to get the battery reporting to work. 

=============================================================
v1.2  5/3/21  converted to hubitat..

=============================================================
forked from namespace: "tomasaxerot" 
               author: "Tomas Axerot"

 *  Copyright 2017 Tomas Axerot
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy
 *  of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
import  hubitat.zigbee.clusters.iaszone.ZoneStatus


metadata {
	definition(name: "Bosch Motion Detector (ISW-ZPR1-WP13)", namespace: "tmastersmart", author: "Tmaster",importUrl: "https://github.com/tmastersmart/bosch/raw/main/hubitat_bosch_motion_detector.groovy" ) {
		capability "Motion Sensor"
		capability "Configuration"
		capability "Battery"
		capability "Temperature Measurement"
		capability "Refresh"
		capability "Health Check"
		capability "Sensor"

//		command "enrollResponse"

		fingerprint inClusters: "0000,0001,0003,0402,0500,0020,0B05", outClusters: "0019", manufacturer: "Bosch", model: "ISW-ZDL1-WP11G", deviceJoinName: "Bosch TriTech Motion Detector"
        fingerprint inClusters: "0000,0001,0003,0402,0500,0020,0B05", outClusters: "0019", manufacturer: "Bosch", model: "ISW-ZPR1-WP13", deviceJoinName: "Bosch PIR Motion Detector"
	}



	preferences {
		input "debugOutput", "bool", 
			title: "Enable debug logging?", 
			defaultValue: true, 
			displayDuringSetup: false, 
			required: false
		
//			input title: "Temperature Offset", description: "Temp offset", displayDuringSetup: false, type: "paragraph", element: "paragraph"
			input "tempOffset", "number", title: "Degrees", description: "Adjust temperature by this many degrees", range: "*..*", displayDuringSetup: false
		
	}

	
}

def parse(String description) {
	log.debug "description: $description"
	Map map = zigbee.getEvent(description)
	if (!map) {
		if (description?.startsWith('zone status')) {
			map = parseIasMessage(description)
		} else {
			Map descMap = zigbee.parseDescriptionAsMap(description)
			if (descMap?.clusterInt == 0x0001 && descMap.attrInt == 0x0020 && descMap.commandInt != 0x07 && descMap?.value) {
                
                def volts = descMap.value
                
                def maxVolts = 6.0
                def minVolts = 5.0
            if (model == "ISW-ZDL1-WP11G") {   
	           minVolts = 30        
               maxVolts = 60
           }else if(model == "ISW-ZPR1-WP13") {
                		minVolts = 15
			            maxVolts = 30  
            }
                
	        
	        def batteryPercentages = (volts - minVolts ) / (maxVolts - minVolts)	
            def batteryLevel = (int) batteryPercentages * 100
            if (batteryLevel > 100) {batteryLevel​ = 100}
            if (batteryLevel < 1) {batteryLevel​ = 0}  
            logDebug "Set Battery to ${batteryLevel}% {battest}v​ line94"
            result << createEvent(name: "battery", value: batteryLevel, unit:"%")       
                
                
				map = getBatteryResult(Integer.parseInt(descMap.value, 16))
                
                logDebug "Old bat routine says {map} "

                
                
                
			} else if (descMap?.clusterInt == zigbee.TEMPERATURE_MEASUREMENT_CLUSTER && descMap.commandInt == 0x07) {
				if (descMap.data[0] == "00") {
					log.debug "TEMP REPORTING CONFIG RESPONSE: $descMap"
					sendEvent(name: "checkInterval", value: 60 * 12, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
				} else {
					log.warn "TEMP REPORTING CONFIG FAILED- error code: ${descMap.data[0]}"
				}
			} else if (descMap.clusterInt == 0x0406 && descMap.attrInt == 0x0000) {
				def value = descMap.value.endsWith("01") ? "active" : "inactive"
				log.debug "Doing a read attr motion event"
				map = getMotionResult(value)
			}
		}
	} else if (map.name == "temperature") {
		if (tempOffset) {
			map.value = (int) map.value + (int) tempOffset
		}
		map.descriptionText = temperatureScale == 'C' ? 'map.value ' : ' map.value °F'
		map.translatable = true
	}
    
// alt backup routine. Others not working......
    else  if (map.name == "batteryVoltage") {
            def battest = map.value 
            def maxVolts = 6.0
	        def minVolts = 5.0
	        def volts = (battest)
	        def batteryPercentages = (volts - minVolts ) / (maxVolts - minVolts)	
            def batteryLevel = (int) batteryPercentages * 100
            if (batteryLevel > 100) {batteryLevel​ = 100}
            if (batteryLevel < 1) {batteryLevel​ = 0}            
        logDebug "Set Battery to ${batteryLevel}% {battest}v  line136"
            result << createEvent(name: "battery", value: batteryLevel, unit:"%")
        }
    
    

	log.debug "Parse returned $map"
	def result = map ? createEvent(map) : [:]

	if (description?.startsWith('enroll request')) {
		List cmds = zigbee.enrollResponse()
		log.debug "enroll response: ${cmds}"
		result = cmds?.collect { new  hubitat.device.HubAction(it) }
	}
	return result
}

private Map parseIasMessage(String description) {
	ZoneStatus zs = zigbee.parseZoneStatus(description)

	// Some sensor models that use this DTH use alarm1 and some use alarm2 to signify motion
	return (zs.isAlarm1Set() || zs.isAlarm2Set()) ? getMotionResult('active') : getMotionResult('inactive')
}

private Map getBatteryResult(rawValue) {
	log.debug "Battery rawValue = ${rawValue}"
	def linkText = getLinkText(device)

	def result = [:]

	//ISW-ZPR1-WP13 uses 4 batteries, 2 are used in measurement
    //ISW-ZDL1-WP11G uses 6 batteries, 4 are used in measurement 
    
	if (!(rawValue == 0 || rawValue == 255)) {
		result.name = 'battery'
		result.translatable = true
		result.descriptionText = " battery was rawValue%"		
        
		def model = device.getDataValue("model")
        def volts = rawValue // For the batteryMap to work the key needs to be an int
		def batteryMap = []
        def minVolts = 0
		def maxVolts = 0
                          
        if (model == "ISW-ZDL1-WP11G") {	
        	batteryMap = [60: 100, 59: 100, 58: 100, 57: 100, 56: 100, 55: 100,            
        				  54: 100, 53: 100, 52: 100, 51: 100, 50: 90, 49: 90,
                          48: 90, 47: 90, 46: 70, 45: 70, 44: 70, 43: 70, 42: 50, 
                          41: 50, 40: 50, 39: 50, 38: 30, 37: 30, 36: 30, 35: 30,
                          34: 15, 33: 15, 32: 1, 31: 1, 30: 0]                          
        	minVolts = 30        
            maxVolts = 60			           
        } else if(model == "ISW-ZPR1-WP13") {
        	batteryMap = [30: 100, 29: 100, 28: 100, 27: 100, 26: 100, 25: 90, 24: 90, 23: 70,
							  22: 70, 21: 50, 20: 50, 19: 30, 18: 30, 17: 15, 16: 1, 15: 0]
			minVolts = 15
			maxVolts = 30         
        } else {
        	result.value = 0
            return result
        }

		if (volts < minVolts)
			volts = minVolts
		else if (volts > maxVolts)
			volts = maxVolts
		

        
	}

	return result
}

private Map getMotionResult(value) {
	log.debug 'motion'
	String descriptionText = value == 'active' ? " detected motion" : " motion has stopped"
	return [
			name           : 'motion',
			value          : value,
			descriptionText: descriptionText,
			translatable   : true
	]
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) // Read the Battery Level
}

def refresh() {
	log.debug "refresh called"

	def refreshCmds = zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) +
			zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000)

	return refreshCmds + zigbee.enrollResponse()
}

def configure() {
	// Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
	// enrolls with default periodic reporting until newer 5 min interval is confirmed
	sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 1 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])

	// temperature minReportTime 30 seconds, maxReportTime 5 min. Reporting interval if no activity
	// battery minReport 30 seconds, maxReportTime 6 hrs by default
	return refresh() + zigbee.batteryConfig() + zigbee.temperatureConfig(30, 300) // send refresh cmds as part of config
}
