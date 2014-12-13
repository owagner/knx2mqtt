package com.tellerulam.knx2mqtt;

import java.net.*;
import java.util.logging.*;

import com.tellerulam.knx2mqtt.GroupAddressManager.GroupAddressInfo;

import tuwien.auto.calimero.*;
import tuwien.auto.calimero.exception.*;
import tuwien.auto.calimero.knxnetip.*;
import tuwien.auto.calimero.link.*;
import tuwien.auto.calimero.link.medium.*;
import tuwien.auto.calimero.process.*;

public class KNXConnector extends Thread implements NetworkLinkListener
{
	private final Logger L=Logger.getLogger(getClass().getName());

	private KNXNetworkLink link;
	private ProcessCommunicator pc;

	public void connect() throws KNXException, InterruptedException
	{
		int knxConnectionType=KNXNetworkLinkIP.TUNNELING;
		String connType=System.getProperty("knx2mqtt.knx.type");
		if(connType!=null)
		{
			if("TUNNELING".equals(connType))
				knxConnectionType=KNXNetworkLinkIP.TUNNELING;
			else if("ROUTING".equals(connType))
				knxConnectionType=KNXNetworkLinkIP.ROUTING;
			else if("SERIAL".equals(connType))
			{
				connectSerial();
				knxConnectionType=-1;
			}
			else
				throw new IllegalArgumentException("knx2mqtt.knx.type must bei either TUNNELING, ROUTING or SERIAL");
		}
		if(knxConnectionType!=-1)
			connectIP(knxConnectionType);

		link.addLinkListener(this);
		pc=new ProcessCommunicatorImpl(link);
		pc.addProcessListener(processListener);
	}

	private void connectIP(int knxConnectionType) throws KNXException, InterruptedException
	{
		String hostIP=System.getProperty("knx2mqtt.knx.ip","setme");
		int port=Integer.getInteger("knx2mqtt.knx.port", KNXnetIPConnection.DEFAULT_PORT).intValue();
		String localIP=System.getProperty("knx2mqtt.knx.localip");
		InetSocketAddress local;
		if(localIP!=null)
		{
			local=new InetSocketAddress(localIP,0);
		}
		else
		{
			InetAddress localhost;
			try
			{
				localhost = InetAddress.getLocalHost();
			}
			catch(UnknownHostException e)
			{
				L.log(Level.SEVERE,"Unable to lookup local host",e);
				throw new IllegalArgumentException("Unable to determine local host address");
			}
			local=new InetSocketAddress(localhost,0);
		}
		L.log(Level.INFO,"Establishing KNX IP connection to "+hostIP+":"+port+" ("+(knxConnectionType==KNXNetworkLinkIP.TUNNELING?"TUNNEL":"ROUTER")+") from "+local);
		link=new KNXNetworkLinkIP(knxConnectionType, local, new InetSocketAddress(hostIP, port), false, TPSettings.TP1);
		L.info("Connection established");
	}

	private void connectSerial()
	{
		throw new IllegalArgumentException("Serial connection not yet implemented");
	}

	@Override
	public void indication(FrameEvent fe)
	{
		/* Ignore */
	}

	@Override
	public void linkClosed(CloseEvent ce)
	{
		L.info("Link closed: "+ce.getReason());
	}

	@Override
	public void confirmation(FrameEvent fe)
	{
		/* Ignore */
	}

	private class MyProcessListener extends ProcessListenerEx
	{
		@Override
		public void groupWrite(ProcessEvent pe)
		{
			GroupAddress dest=pe.getDestination();
			IndividualAddress src=pe.getSourceAddr();
			byte[] asdu=pe.getASDU();
			if(asdu.length==0)
			{
				L.info("Zero-length write to "+dest+" from "+src);
				return;
			}

			GroupAddressInfo gaInfo=GroupAddressManager.getGAInfoForAddress(dest.toString());

			String val;
			try
			{
				if(asdu.length==1)
					val=String.valueOf(asUnsigned(pe, ProcessCommunicationBase.UNSCALED));
				else if(asdu.length==2||asdu.length==4)
					val=String.valueOf(asFloat(pe));
				else
					val="Unknown";

				L.info("Got "+val+" to "+dest+" from "+src+" (ASDU length "+asdu.length+")");

				if(gaInfo!=null)
					MQTTHandler.publish(gaInfo.name,val,src.toString());
				else
					MQTTHandler.publish(dest.toString(),val,src.toString());

			}
			catch(KNXException e)
			{
				L.log(Level.WARNING,"Error converting ASDU to "+dest+" from "+src);
			}

		}

		@Override
		public void detached(DetachEvent arg0)
		{
			/* Ignoer */
		}

		@Override
		public void groupReadRequest(ProcessEvent arg0)
		{
			/* Ignore */
		}

		@Override
		public void groupReadResponse(ProcessEvent arg0)
		{
			/* Ignore */
		}

	}
	ProcessListener processListener=new MyProcessListener();

	@Override
	public void run()
	{
		for(;;)
		{
			try
			{
				connect();
				while(link.isOpen())
					Thread.sleep(1000);
			}
			catch(Exception e)
			{
				L.log(Level.WARNING,"Error in KNX connection, will retry in 10s",e);
				try
				{
					Thread.sleep(10*1000);
				}
				catch(InterruptedException e1)
				{
					/* Ignore */
				}
			}
		}
	}

	private KNXConnector()
	{
		super("KNX Connection Thread");
	}

	private static KNXConnector conn;
	public static void launch()
	{
		conn=new KNXConnector();
		conn.start();
	}

	public static void doGroupWrite(String gaspec,String val,String dp)
	{
		try
		{
			GroupAddress ga=new GroupAddress(gaspec);
			if(dp==null)
			{
				// Guessing the datapoint type
				if(val.indexOf('.')>=0)
					conn.pc.write(ga, Float.parseFloat(val));
				else
				{
					int v=Integer.parseInt(val);
					if(v==0)
						conn.pc.write(ga, false);
					else if(v==1)
						conn.pc.write(ga, true);
					else
						conn.pc.write(ga, v, ProcessCommunicationBase.SCALING );
				}
			}
		}
		catch(Exception e)
		{
			conn.L.log(Level.WARNING,"Error when writing "+val+" to "+gaspec,e);
		}
	}
}
