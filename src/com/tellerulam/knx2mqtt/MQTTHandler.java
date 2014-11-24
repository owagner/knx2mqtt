package com.tellerulam.knx2mqtt;

import java.nio.charset.*;
import java.util.*;
import java.util.logging.*;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.*;

import com.eclipsesource.json.*;

public class MQTTHandler
{
	private final Logger L=Logger.getLogger(getClass().getName());

	public static void init() throws MqttException
	{
		instance=new MQTTHandler();
		instance.doInit();
	}

	private final String topicPrefix;
	private MQTTHandler()
	{
		String tp=System.getProperty("knx2mqtt.mqtt.topic","knx/unspecified");
		if(!tp.endsWith("/"))
			tp+="/";
		topicPrefix=tp;
	}

	private static MQTTHandler instance;

	private MqttAsyncClient mqttc;

	private void queueConnect()
	{
		shouldBeConnected=false;
		Main.t.schedule(new TimerTask(){
			@Override
			public void run()
			{
				doConnect();
			}
		},10*1000);
	}

	private class StateChecker extends TimerTask
	{
		@Override
		public void run()
		{
			if(!mqttc.isConnected() && shouldBeConnected)
			{
				L.warning("Should be connected but aren't, reconnecting");
				queueConnect();
			}
		}
	}

	private boolean shouldBeConnected;

	private void doConnect()
	{
		L.info("Connecting to MQTT broker "+mqttc.getServerURI()+" with CLIENTID="+mqttc.getClientId()+" and TOPIC PREFIX="+topicPrefix);

		MqttConnectOptions copts=new MqttConnectOptions();
		try
		{
			mqttc.connect(copts,null,new IMqttActionListener(){
				@Override
				public void onFailure(IMqttToken tok, Throwable t)
				{
					L.log(Level.WARNING,"Error while connecting to MQTT broker, will retry: "+t.getMessage(),t);
					queueConnect();
				}
				@Override
				public void onSuccess(IMqttToken tok)
				{
					L.info("Successfully connected to broker, subscribing to "+topicPrefix+"#");
					try
					{
						mqttc.subscribe(topicPrefix+"#",1);
						shouldBeConnected=true;
					}
					catch(MqttException mqe)
					{
						L.log(Level.WARNING,"Error subscribing to topic hierarchy, check your configuration",mqe);
					}

				}
			});
		}
		catch(MqttException mqe)
		{
			L.log(Level.WARNING,"Error while connecting to MQTT broker, will retry: "+mqe.getMessage(),mqe);
			queueConnect(); // Attempt reconnect
		}
	}

	private void doInit() throws MqttException
	{
		String server=System.getProperty("knx2mqtt.mqtt.server","tcp://localhost:1833");
		String clientID=System.getProperty("knx2mqtt.mqtt.clientid","knx2mqtt");
		mqttc=new MqttAsyncClient(server,clientID,new MemoryPersistence());
		doConnect();
		Main.t.schedule(new StateChecker(),30*1000,30*1000);
	}

	private void doPublish(String name, String val, String src)
	{
		String txtmsg=new JsonObject().add("val",val).add("src",src).toString();
		MqttMessage msg=new MqttMessage(txtmsg.getBytes(Charset.forName("UTF-8")));

		try
		{
			mqttc.publish(topicPrefix+name, msg);
			L.info("Published "+txtmsg+" to "+topicPrefix+name);
		}
		catch(MqttException e)
		{
			L.log(Level.WARNING,"Error when publishing message",e);
		}
	}

	public static void publish(String name, String val, String src)
	{
		instance.doPublish(name,val,src);
	}

}
