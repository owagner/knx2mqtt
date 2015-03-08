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

[![Build Status](https://travis-ci.org/owagner/knx2mqtt.svg)](https://travis-ci.org/owagner/knx2mqtt) Automatically built jars can be downloaded from the release page on GitHub at https://github.com/owagner/knx2mqtt/releases


EIBD
----
The Calimero library is able to directly talk to EIBnet/IP gateways or routers. However, knx2mqtt can be used in conjunction with 
eibd if eibd is run as an EIBnet/IP server with option "-S". Note that this is not the same as eibd's proprietary TCP protocol
which by default uses TCP port 6720.


Topics
------
knx2mqtt uses the group address hierarchy as defined in ETS4 for topics. The group addresses are translated to
hierarchical group names if a ETS4 project file is specifed. Example

	knx/set/Keller/Beleuchtung/Kellerflur Schalten

A special topic is *prefix/connected*. It holds an enum value which denotes whether the adapter is
currently running (1) and connected to the KNX bus (2). It's set to 0 on disconnect using a MQTT will.


MQTT Message format
--------------------
The message format generated is a JSON encoded object with the following members:

* val - the actual value, in numeric format
* knx_src_addr - when sending message, knx2mqtt fills in the source EIB address of the group write which 
  triggered the message. This field is ignored on incoming messages.
* knx_textual - a textual representation of the value, or the numeric value with a unit specififer (e.g. "100%")


DPT Definitions
--------------- 
The interpretation of KNX values is not specified as part of the wire protocol, but done by the device configuration.
It is therefore important for knx2mqtt to know about the datapoint definition of a group address.

The easiest way to archieve this is to specify a ETS4 exported project file (.knxproj). knx2mqtt will read and parse this
both for the group address names and data point types. A multi-level approach to determining the data point type is
used, with the ultimate fallback being the data size in bits. It is recommended to always define DPTs in your ETS4
projects, notably when converting ETS3 projects, which did not have those definitions at all.

Special treatment is given to boolean DPTs: Instead of translating them into their textual representations, they
are transfered (and accepted) as numeric "0" and "1" values. 


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
  
- knx.ets4projectfile

  A ETS4 exported projectfile. No default. Will be used to determine group address names
  and DPTs. 
  
- mqtt.server

  ServerURI of the MQTT broker to connect to. Defaults to "tcp://localhost:1883".
  
- mqtt.clientid

  ClientID to use in the MQTT connection. Defaults to "knx2mqtt".
  
- mqtt.topic

  The topic prefix used for publishing and subscribing. Defaults to "knx/".

When running knx2mqtt on a server class machine, it makes sense to limit the memory usage
to 64MB using the java options

    -Xmx64M
    

See also
--------
- Project overview: https://github.com/mqtt-smarthome
  
  
Changelog
---------
* 0.8 - 2015/03/08 - owagner
  - support /get/ function, which issues a KNX Group Read
    
* 0.7 - 2015/03/02 - owagner
  - added a syslog log handler which is active by default and can be overriden using
    JUL properties
    
* 0.6 - 2015/01/29 - owagner
  - use a SoftReference to cache XML documents during project file loading, to reduce initial
    memory impact

* 0.5 - 2015/01/28 - owagner
  - now capable of reading ETS4 project files to determine both group address names and
    their DPT values. Obsoletes "knx.groupaddressfile"
  - now properly converts incoming and outgoing messages according to the specified
    data point values.
  - includes JSON attribute "knx_textual" with a textual representation of the DPT value

* 0.4 - 2015/01/25 - owagner
  - adapted to new mqtt-smarthome topic hierarchy scheme: /status/ for reports, /set/ for setting values
  - prefix/connected is now an enum (0 - disconnected, 1 - connected to broker, 2 - connected to KNX bus)
  - use QoS 0 for published status reports

* 0.3 - 2015/01/05 - owagner
  - make sure numeric values are not sent as strings
  - change type guessing to send integer numbers not as floats if they have a decimal point, but no fractional part
  
* 0.2 - 2015/01/02 - owagner
  - converted to Gradle build
      

 
