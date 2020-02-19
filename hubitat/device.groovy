/**
 *  Wink Relay Device Handler
 *
 *  Copyright 2017 Joshua Lyon
 *
 *  Child Device support by @AdamKempenich (Feb 17, 2020)
 *      - Added child devices for relays and backlight
 *      - Added logDescriptionText and logDebugText options
 *      - Added importURL
 *      - Added option to turn screen on when proximity falls below a value
 *	- Removed extraneous values
 *      
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
    definition (name: "Wink Relay", namespace: "joshualyon", author: "Josh Lyon", importURL: "https://raw.githubusercontent.com/joshualyon/STWinkRelay/master/hubitat/device.groovy") {
        capability "Switch"

        capability "Polling"
        capability "Refresh"
        capability "Temperature Measurement"
        capability "Motion Sensor"
        capability "Relative Humidity Measurement"
        capability "Pushable Button"

        attribute "proximityRaw", "string"
        attribute "proximity", "number"
        
        attribute "relay1", "enum", ["on", "off"]
        command "relay1On"
        command "relay1Off"

        attribute "relay2", "enum", ["on", "off"]
        command "relay2On"
        command "relay2Off"

        attribute "screenBacklight", "enum", ["on", "off"]
        command "screenBacklightOn"
        command "screenBacklightOff"

        attribute "topButton", "enum", ["on", "off"]
        attribute "bottomButton", "enum", ["on", "off"]
    } 
    preferences {
        
        input(name:"screenOnProximity", type:"number", title: "Turn on screen when proximity is above...",
            description: "Lower is more sensitive. To disable, set it to something high, like 999,999", defaultValue: 3900,
            required: true, displayDuringSetup: true)
        
        input(name:"logDebug", type:"bool", title: "Log debug information?",
                  description: "Logs raw data for debugging. (Default: Off)", defaultValue: false,
                  required: true, displayDuringSetup: true)

        input(name:"logDescriptionText", type:"bool", title: "Log descriptionText?",
                  description: "Logs when things happen. (Default: On)", defaultValue: true,
                  required: true, displayDuringSetup: true)
    }
}

def installed(){
    def networkID = device.deviceNetworkId

    addChildDevice("joshualyon", "Wink Relay Child", "${networkID}-Relay-1", [label: "Relay 1", isComponent: true])
    addChildDevice("joshualyon", "Wink Relay Child", "${networkID}-Relay-2", [label: "Relay 2", isComponent: true])
    addChildDevice("joshualyon", "Wink Relay Child", "${networkID}-Backlight", [label: "Backlight", isComponent: true])
    refresh()
    //setupEventSubscription() - refresh includes this now
}
def updated(){
    sendEvent(name:"numberOfButtons", value:2)
    refresh()
    //setupEventSubscription() - refresh includes this now
}

// parse events into attributes
def parse(String description) {
    //logDebug "Parsing '${description}'"
    def msg = parseLanMessage(description)
    //logDebug "JSON: ${msg.json}"
    def networkID = device.deviceNetworkId

    childRelay1 = getChildDevice("${networkID}-Relay-1")
    childRelay2 = getChildDevice("${networkID}-Relay-2")
    childBacklight = getChildDevice("${networkID}-Backlight")

    if(msg?.json?.Relay1){
        logDescriptionText "Relay 1: ${msg.json.Relay1}"
        childRelay1.sendEvent(name: "switch", value: msg.json.Relay1)        
    }
    if(msg?.json?.Relay2){
        logDescriptionText "Relay 2: ${msg.json.Relay2}"
        childRelay2.sendEvent(name: "switch", value: msg.json.Relay2)      
    }
    if(msg?.json?.Temperature){
        if(msg?.json?.isRaw){
            logDescriptionText "Temperature (Raw): ${msg.json.Temperature}"
            def temperature = roundValue( (msg.json.Temperature.toInteger() / 1000) * 1.8 + 32 )
            logDescriptionText "Temperature: ${temperature}"
            sendEvent(name: "temperature", value: temperature)
        }
        else{
            logDescriptionText "Temperature: ${msg.json.Temperature}"
            sendEvent(name: "temperature", value: roundValue(msg.json.Temperature))
        }
    }
    if(msg?.json?.Humidity){
        if(msg?.json?.isRaw){
            logDescriptionText "Humidity (Raw): ${msg.json.Humidity}"
            def humidity = roundValue(msg.json.Humidity.toInteger() / 1000)
            logDescriptionText "Humidity: ${humidity}"
            sendEvent(name: "humidity", value: humidity)
        }
        else{
            logDescriptionText "Humidity: ${msg.json.Humidity}"
            sendEvent(name: "Humidity", value: roundValue(msg.json.Humidity))
        }
    }
  
    if(msg?.json?.Proximity){
        runInMillis(300, checkProximity)
        if(msg?.json?.isRaw){
            logDescriptionText "Proximity (RAW): ${msg.json.Proximity}"
            def prox = parseProximity(msg.json.Proximity)
            proximity = prox
            logDescriptionText "Proximity: ${prox}"
            sendEvent(name: "proximityRaw", value: msg.json.Proximity)
            sendEvent(name: "proximity", value: prox)
        }
        else{
            logDescriptionText "Proximity: ${msg.json.Proximity}"
            proximity = msg.json.Proximity
            sendEvent(name: "proximity", value: msg.json.Proximity)
        }
        
    }
    if(msg?.json?.LCDBacklight){
        logDescriptionText "LCD Backlight: ${msg.json.LCDBacklight}"
        childBacklight.sendEvent(name: "switch", value: msg.json.LCDBacklight)      
    }
    if(msg?.json?.BottomButton){
        logDescriptionText "Bottom Button: ${msg.json.BottomButton}"
        sendEvent(name: "bottomButton", value: msg.json.BottomButton)
        if(msg.json.BottomButton == "on"){
            sendEvent(name: "pushed", value: 2, isStateChange: true);
        }
    }
    if(msg?.json?.TopButton){
        logDescriptionText "Top Button: ${msg.json.TopButton}"
        sendEvent(name: "topButton", value: msg.json.TopButton)
        if(msg.json.TopButton == "on"){
            sendEvent(name: "pushed", value: 1, isStateChange: true);
        }
    }

    //if both relays are on and the switch isn't currently on, let's raise that value
    if((childRelay1.currentValue("switch") == "on" || childRelay2.currentValue("switch") == "on") && device.currentValue("switch") != "on"){
        sendEvent(name: "switch", value: "on")
    }
    //and same in reverse
    if(childRelay1.currentValue("switch") == "off" && childRelay2.currentValue("switch") == "off" && device.currentValue("switch") != "off"){
        sendEvent(name: "switch", value: "off")
    }
    
    
}

def checkProximity(){
   if(device.currentValue('proximity') >= settings.screenOnProximity){
      screenBacklightOn()
   } else{ screenBacklightOff() }
}
def roundValue(x){
	Math.round(x * 10) / 10
}

def parseProximity(proxRaw){
	//split on spaces and grab the first value
    proxRaw.split(" ")[0] as Integer
}

//for now, we'll just have these turn on both relays
//in the future, we plan on providing the ability to disable either relay via the Android app
def on(){
    def action = []
    action << relay1On()
    action << relay2On()
    return action
}
def off(){
    def action = []
    action << relay1Off()
    action << relay2Off()
    return action
}

//TODO: change actions to POST commands on the server and here
def relay1On(){
    def networkID = device.deviceNetworkId
    childRelay1 = getChildDevice("${networkID}-Relay-1")
    childRelay1.sendEvent(name: "switch", value: "on")
    httpGET("/relay/top/on")
}
def relay1Off(){
    def networkID = device.deviceNetworkId
    childRelay1 = getChildDevice("${networkID}-Relay-1")
    childRelay1.sendEvent(name: "switch", value: "off")
    httpGET("/relay/top/off")
}
def relay1Toggle(){
    def networkID = device.deviceNetworkId
    childRelay1 = getChildDevice("${networkID}-Relay-1")
    childRelay1.currentValue("switch") == "on" ? relay1Off() : relay1On()
}

def relay2On(){   
    def networkID = device.deviceNetworkId
    childRelay2 = getChildDevice("${networkID}-Relay-2")
    childRelay2.sendEvent(name: "switch", value: "on")
    httpGET("/relay/bottom/on")
}
def relay2Off(){
    def networkID = device.deviceNetworkId
    childRelay2 = getChildDevice("${networkID}-Relay-2")
    childRelay2.sendEvent(name: "switch", value: "on")    
    httpGET("/relay/bottom/off")
}
def relay2Toggle(){
    def networkID = device.deviceNetworkId
    childRelay2 = getChildDevice("${networkID}-Relay-2")
    childRelay1.currentValue("switch") == "on" ? relay2Off() : relay2On()
}

def screenBacklightOn(){ 
    def networkID = device.deviceNetworkId
    childBacklight = getChildDevice("${networkID}-Backlight")
    childBacklight.sendEvent(name: "switch", value: "on")
    httpGET("/lcd/backlight/on") 
}
def screenBacklightOff(){ 
    def networkID = device.deviceNetworkId
    childBacklight = getChildDevice("${networkID}-Backlight")
    childBacklight.sendEvent(name: "switch", value: "off")
    httpGET("/lcd/backlight/off") 
}
def screenBacklightToggle(){
    def networkID = device.deviceNetworkId
    childBacklight = getChildDevice("${networkID}-Backlight")

    childBacklight.currentValue("switch") == "on" ? screenBacklightOff() : screenBacklightOn()
}

//Individual commands for retrieving the status of the Wink Relay over HTTP
def retrieveRelay1(){ httpGET("/relay/top") }
def retrieveRelay2(){ httpGET("/relay/bottom")}
def retrieveTemperature(){ httpGET("/sensor/temperature/raw") }
def retrieveHumidity(){ httpGET("/sensor/humidity/raw") }
def retrieveProximity(){ httpGET("/sensor/proximity/raw") }
def retrieveScreenBacklight(){ httpGET("/lcd/backlight") }

def setupEventSubscription(){
    logDebug "Subscribing to events from Wink Relay"
    def result = new hubitat.device.HubAction(
            method: "POST",
            path: "/subscribe",
            headers: [
                    HOST: getHostAddress(),
                    CALLBACK: getCallBackAddress()
            ]
    )
    //logDebug "Request: ${result.requestId}"
    return result
}



def poll(){
    refresh()
}
def refresh(){
    //manually get the state of sensors and relays via HTTP calls
    def httpCalls = []
    httpCalls << retrieveRelay1()
    httpCalls << retrieveRelay2()
    httpCalls << retrieveTemperature()
    httpCalls << retrieveHumidity()
    httpCalls << retrieveProximity()
    httpCalls << retrieveScreenBacklight()
    httpCalls << setupEventSubscription()
    return httpCalls
}


def sync(ip, port) {
    def existingIp = getDataValue("ip")
    def existingPort = getDataValue("port")
    if (ip && ip != existingIp) {
        updateDataValue("ip", ip)
    }
    if (port && port != existingPort) {
        updateDataValue("port", port)
    }
    refresh()
}

def httpGET(path) {
	def hostUri = hostAddress
    logDebug "Sending command ${path} to ${hostUri}"
    def result = new hubitat.device.HubAction(
            method: "GET",
            path: path,
            headers: [
                    HOST: hostUri
            ]
    )
    //logDebug "Request: ${result.requestId}"
    return result
}


// gets the address of the Hub
private getCallBackAddress() {
    return "http://" + device.hub.getDataValue("localIP") + ":" + device.hub.getDataValue("localSrvPortTCP")
}

// gets the address of the device
private getHostAddress() {
    def ip = getDataValue("ip")
    def port = getDataValue("port")

    if (!ip || !port) {
        def parts = device.deviceNetworkId.split(":")
        if (parts.length == 2) {    
            ip = parts[0]
            port = parts[1]
        } else {
            log.warn "Can't figure out ip and port for device: ${device.id}"
        }
    }

    logDebug "Using IP: $ip and port: $port for device: ${device.id}"
    return convertHexToIP(ip) + ":" + convertHexToInt(port)
}

private Integer convertHexToInt(hex) {
    return Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
    return [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private logDebug( text ){
    // If debugging is enabled in settings, pass text to the logs
    
    if( settings.logDebug ) { 
        log.debug "${text}"
    }
}

private logDescriptionText( text ){
    // If description text is enabled, pass it as info to logs

    if( settings.logDescriptionText ) { 
        log.info "${text}"
    }
}
