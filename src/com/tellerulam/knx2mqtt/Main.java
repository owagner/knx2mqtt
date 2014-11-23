package com.tellerulam.knx2mqtt;

public class Main
{
	public static void main(String[] args)
	{
		/*
		 * Interpret all command line arguments as property definitions (without the knx2mqtt prefix)
		 */
		for(String s:args)
		{
			String sp[]=s.split("=",2);
			if(sp.length!=2)
			{
				System.out.println("Invalid argument (no =): "+s);
				System.exit(1);
			}
			System.setProperty("knx2mqtt."+sp[0],sp[1]);
		}
		KNXConnector.launch();
	}
}
