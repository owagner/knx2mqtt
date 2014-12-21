knx2mqtt
========

  Written and (C) 2014 Oliver Wagner <owagner@tellerulam.com> 
  
  Provided under the terms of the MIT license.

Overview
--------

knx2mqtt is a gateway between a KNX bus interface and MQTT. It receives group telegrams and publishes them as MQTT topics, and similarily subscribes to MQTT topics and converts them into KNX group writes.

It's intended as a building block in heterogenous smart home environments where an MQTT message broker is used as the centralized message bus.

If you don't understand any of the above, knx2mqtt is most likely not useful to you.


Prerequisites
-------------

* Java 1.7 SE Runtime Environment: https://www.java.com/
* Calimero 2.2.0 or newer: https://github.com/calimero-project/calimero / https://www.auto.tuwien.ac.at/a-lab/calimero.html (used for KNX communication)
* Eclipse Paho: https://www.eclipse.org/paho/clients/java/ (used for MQTT communication)
* Minimal-JSON: https://github.com/ralfstx/minimal-json (used for JSON creation and parsing)


EIBD
----

The Calimero library is able to directly talk to EIBnet/IP gateways or routers. However, knx2mqtt can be used in conjunction with 
eibd if eibd is run as an EIBnet/IP server with option "-S". Note that this is not the same as eibd's proprietary TCP protocol
which by default uses TCP port 6720.


Topics
------

A special topic is *prefix/connected*. It holds a boolean value which denotes whether the adapter is
currently running. It's set to false on disconnect using a MQTT will.



MQTT Message format
--------------------

The message format accepted and generated is a JSON encoded object with the following members:

* val - the actual value, in numeric format
* knx_src_addr - when sending message, knx2mqtt fills in the source EIB address of the group write which triggered the message.
  This field is ignored on incoming messages.
* ack - when sending messages, knx2mqtt sets this to _true_. If this is set to _true_ on incoming messages, they
  are ignored, to avoid loops.
 
 
DPT Guessing
------------
The interpretation of KNX values is not specified as part of the wire protocol, but done by the device configuration.
This is called a Datapoint Type or DPT. knx2mqtt guesses the DPT of outgoing messages by looking at the numeric value:

* if the value contains a dot, a 2 byte float is assumed
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
  
  IP address of the EIBnet/IP server/gateway (no default)
  
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
- hm2mqtt - similiar tool for Homematic integration 
  
  
Changelog
---------
(work in progress)
 