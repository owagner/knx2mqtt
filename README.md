knx2mqtt
========

  Written and (C) 2015 Oliver Wagner <owagner@tellerulam.com> 
  
  Provided under the terms of the MIT license.

Overview
--------
knx2mqtt is a gateway between a KNX bus interface and MQTT. It receives group telegrams and publishes them as MQTT topics, and similarily subscribes to MQTT topics and converts them into KNX group writes.

It's intended as a building block in heterogenous smart home environments where an MQTT message broker is used as the centralized message bus.
See https://github.com/mqtt-smarthome for a rationale and architectural overview.


Dependencies
------------
* Java 1.7 SE Runtime Environment: https://www.java.com/
* Calimero 2.2.0 or newer: https://github.com/calimero-project/calimero / https://www.auto.tuwien.ac.at/a-lab/calimero.html (used for KNX communication)
* Eclipse Paho: https://www.eclipse.org/paho/clients/java/ (used for MQTT communication)
* Minimal-JSON: https://github.com/ralfstx/minimal-json (used for JSON creation and parsing)

[![Build Status](https://travis-ci.org/mqtt-smarthome/knx2mqtt.svg)](https://travis-ci.org/mqtt-smarthome/knx2mqtt) Automatically built jars can be downloaded from the release page on GitHub at https://github.com/mqtt-smarthome/knx2mqtt/releases


EIBD
----
The Calimero library is able to directly talk to EIBnet/IP gateways or routers. However, knx2mqtt can be used in conjunction with 
eibd if eibd is run as an EIBnet/IP server with option "-S". Note that this is not the same as eibd's proprietary TCP protocol
which by default uses TCP port 6720.


Topics
------
A special topic is *prefix/connected*. It holds an enum value which denotes whether the adapter is
currently running (1) and connected to the KNX bus (2). It's set to 0 on disconnect using a MQTT will.


MQTT Message format
--------------------
The message format generated is a JSON encoded object with the following members:

* val - the actual value, in numeric format
* knx_src_addr - when sending message, knx2mqtt fills in the source EIB address of the group write which triggered the message.
  This field is ignored on incoming messages.
* ack - when sending messages, knx2mqtt sets this to _true_. If this is set to _true_ on incoming messages, they
  are ignored, to avoid loops.
 
 
DPT Guessing
------------
The interpretation of KNX values is not specified as part of the wire protocol, but done by the device configuration.
This is called a Datapoint Type or DPT. knx2mqtt guesses the DPT of outgoing messages by looking at the numeric value:

* if the value contains a decimal point and has a fractional part, a 2 byte float is assumed
* otherwise, if the value is 0, a boolean _false_ is assumed
* otherwise, if the value is 1, a boolean _true_ is assumed
* otherwise, a 8 bit scaled integer is assumed


Usage
-----
Configuration options can either be specified on the command line, or as system properties with the prefix "knx2mqtt".
Examples:

    java -jar knx2mqtt.jar knx.ip=127.0.0.1
    
    java -Dknxmqtt.knx.ip=127.0.0.1 -jar knx2mqtt.jar
    
### Available options:    

- knx.type

  Connection type. Can be either TUNNELING or ROUTING. Defaults to TUNNELING.

- knx.ip
  
  IP address of the EIBnet/IP server/gateway (no default, must be specified)
  
- knx.port

  Port of the EIBnet/IP server/gateway. Defaults to 3671.

- knx.localip
  
  IP address (interface) to use for originating EIBnet/IP messages. No default, mainly useful
  in ROUTING mode to specify the multicast interface.
  
- knx.groupaddresstable

  A ETS4 group address export file in XML format. No default. If specified, received group addresses
  are translated to their assigned names before they are published.

- mqtt.broker

  ServerURI of the MQTT broker to connect to. Defaults to "tcp://localhost:1883".
  
- mqtt.clientid

  ClientID to use in the MQTT connection. Defaults to "knx2mqtt".
  
- mqtt.topic

  The topic prefix used for publishing and subscribing. Defaults to "knx/".


See also
--------
- Project overview: https://github.com/mqtt-smarthome
  
  
Changelog
---------
* 0.2 - 2015/01/02 - owagner
  - converted to Gradle build
* 0.3 - 2015/01/05 - owagner
  - make sure numeric values are not sent as strings
  - change type guessing to send integer numbers not as floats if they have a decimal point, but no fractional part
* 0.4 - 2015/01/25 - owagner
  - adapted to new mqtt-smarthome topic hierarchies: /status/ for reports, /set/ for setting values
  - prefix/connected is now an enum as suggested by new mqtt-smarthome spec
  - use QoS 0 for published status reports
      

 
