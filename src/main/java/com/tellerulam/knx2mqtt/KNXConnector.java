package com.tellerulam.knx2mqtt;

import java.net.*;
import java.util.logging.*;

import com.tellerulam.knx2mqtt.GroupAddressManager.GroupAddressInfo;

import tuwien.auto.calimero.*;
import tuwien.auto.calimero.dptxlator.*;
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

		MQTTHandler.setKNXConnectionState(true);

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
		L.info("KNX IP Connection established");
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
		MQTTHandler.setKNXConnectionState(false);
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

			try
			{
				Object val;
				if(gaInfo==null)
				{
					String dpt;
					if(asdu.length==1)
					{
						val=Integer.valueOf(asUnsigned(pe, ProcessCommunicationBase.UNSCALED));
						dpt="5.004";
					}
					else if(asdu.length==2)
					{
						val=Float.valueOf(asFloat(pe));
						dpt="9.001";
					}
					else
					{
						val="Unknown";
						dpt="0.000";
					}
					L.info("Got "+val+" to unknown "+dest+" from "+src+" (ASDU length "+asdu.length+")");
					MQTTHandler.publish(dest.toString(),val,src.toString(),dpt,null);
				}
				else
				{
					MQTTHandler.publish(gaInfo.name,gaInfo.translate(asdu),src.toString(),gaInfo.dpt,gaInfo.getTextutal());
				}
			}
			catch(KNXException e)
			{
				L.log(Level.WARNING,"Error converting ASDU to "+dest+" from "+src);
			}

		}

		@Override
		public void detached(DetachEvent arg0)
		{
			/* Ignore */
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

	/* This is straight from Calimero / ProcessCommunicatorImpl */
	private static final int GROUP_WRITE = 0x80;
	private static byte[] createGroupAPDU(final int service, final DPTXlator t)
	{
		// check for group read
		if (service == 0x00)
			return new byte[2];
		// only group response and group write are allowed
		if (service != 0x40 && service != 0x80)
			throw new KNXIllegalArgumentException("not an APDU group service");
		// determine if data starts at byte offset 1 (optimized) or 2 (default)
		final int offset = t.getItems() == 1 && t.getTypeSize() == 0 ? 1 : 2;
		final byte[] buf = new byte[t.getItems() * Math.max(1, t.getTypeSize()) + offset];
		buf[0] = (byte) (service >> 8);
		buf[1] = (byte) service;
		return t.getData(buf, offset);
	}

	public static void doGroupWrite(String gaspec,String val,GroupAddressInfo gai)
	{
		try
		{
			GroupAddress ga=new GroupAddress(gaspec);

			// We do special handling for booleans
			if(gai.xlator instanceof DPTXlatorBoolean)
			{
				if("0".equals(val))
					((DPTXlatorBoolean)gai.xlator).setValue(false);
				else if("1".equals(val))
					((DPTXlatorBoolean)gai.xlator).setValue(true);
				else
					gai.xlator.setValue(val);
			}
			else
				gai.xlator.setValue(val);
			conn.link.sendRequestWait(ga, Priority.LOW, createGroupAPDU(GROUP_WRITE, gai.xlator));
		}
		catch(Exception e)
		{
			conn.L.log(Level.WARNING,"Error when writing "+val+" to "+gaspec,e);
		}
	}
}
