package com.tellerulam.knx2mqtt;

import java.io.*;
import java.util.*;
import java.util.logging.*;
import java.util.zip.*;

import javax.xml.parsers.*;

import org.w3c.dom.*;
import org.xml.sax.*;

public class GroupAddressManager
{
	private static final Logger L=Logger.getLogger(GroupAddressManager.class.getName());

	public static GroupAddressInfo getGAInfoForAddress(String address)
	{
		return gaTable.get(address);
	}

	public static GroupAddressInfo getGAInfoForName(String name)
	{
		return gaByName.get(name);
	}

	public static class GroupAddressInfo
	{
		final String name;
		final String address;
		String dpt;
		private GroupAddressInfo(String name,String address)
		{
			this.name=name;
			this.address=address;
		}
	}
	static private final Map<String,GroupAddressInfo> gaTable=new HashMap<>();
	static private final Map<String,GroupAddressInfo> gaByName=new HashMap<>();

	/**
	 * Load an ETS4 Group Address Export
	 */
	static void loadGroupAddressTable()
	{
		String gaFile=System.getProperty("knx2mqtt.knx.groupaddresstable");
		if(gaFile==null)
		{
			L.config("No Group Address table specified");
			return;
		}

		try
		{
	        DocumentBuilderFactory docBuilderFactory=DocumentBuilderFactory.newInstance();
	        DocumentBuilder docBuilder=docBuilderFactory.newDocumentBuilder();
	        Document doc=docBuilder.parse(new File(gaFile));
	        NodeList root=doc.getElementsByTagName("GroupAddress-Export");
	        iterateGAElement(root.item(0),"");
	        L.info("Read "+gaTable.size()+" Group Address entries from "+gaFile);
		}
		catch(Exception e)
		{
			L.log(Level.SEVERE,"Unable to parse Group Address table file "+gaFile,e);
			System.exit(1);
		}
	}

	private static void iterateGAElement(Node n, String prefix)
	{
		NodeList nlist=n.getChildNodes();
        for(int ix=0;ix<nlist.getLength();ix++)
        {
        	Node sn=nlist.item(ix);
        	if("GroupRange".equals(sn.getNodeName()))
        	{
            	String name=((Element)sn).getAttribute("Name");
            	iterateGAElement(sn,prefix+name+"/");
        	}
        	else if("GroupAddress".equals(sn.getNodeName()))
        	{
            	String name=prefix+((Element)sn).getAttribute("Name");
            	String addr=((Element)sn).getAttribute("Address");
            	GroupAddressInfo gai=new GroupAddressInfo(name,addr);
            	gaTable.put(addr, gai);
            	gaByName.put(name, gai);
        	}
        }
	}

	/**
	 * Load an ETS4 project file
	 */
	static void loadETS4Project()
	{
		String gaFile=System.getProperty("knx2mqtt.knx.projectfile");
		if(gaFile==null)
		{
			L.config("No project file specified");
			return;
		}
		try(ZipFile zf=new ZipFile(gaFile))
		{
			// Find the project file
			Enumeration<? extends ZipEntry> entries=zf.entries();
			while(entries.hasMoreElements())
			{
				ZipEntry ze=entries.nextElement();
				if(ze.getName().endsWith("Project.xml"))
				{
					String projDir=ze.getName().substring(0, ze.getName().indexOf('/')+1);
					L.info("Found project directory "+projDir);
					// Now find the project data file
					ZipEntry zep=zf.getEntry(projDir+"0.xml");
					if(zep==null)
						throw new IllegalArgumentException("Unable to locate 0.xml in project");
					processETS4ProjectFile(zf,zep);
					break;
				}
			}
		}
		catch(Exception e)
		{
			L.log(Level.SEVERE, "Error reading project file "+gaFile,e);
			System.exit(1);
		}
	}

	/*
	 * First step in parsing: find the GroupAddresses and their IDs
	 */
	private static void processETS4ProjectFile(ZipFile zf, ZipEntry zep) throws ParserConfigurationException, SAXException, IOException
	{
        DocumentBuilderFactory docBuilderFactory=DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder=docBuilderFactory.newDocumentBuilder();
        Document doc=docBuilder.parse(zf.getInputStream(zep));
        NodeList gas=doc.getElementsByTagName("GroupAddress");
        for(int ix=0;ix<gas.getLength();ix++)
        {
        	Element e=(Element)gas.item(ix);
        	processETS4GroupAddress(zf,doc,docBuilder,
        		e.getAttribute("Id"),
        		e.getAttribute("Address"),
        		e.getAttribute("Name")
        	);
        }
	}

	/*
	 * ...then find out what is connected to this group address
	 */
	private static void processETS4GroupAddress(ZipFile zf, Document doc,DocumentBuilder docBuilder, String id, String address, String name) throws SAXException, IOException
	{
		final String connectTypes[]=new String[]{"Send","Receive"};
		for(String connectType:connectTypes)
		{
			NodeList connectors=doc.getElementsByTagName(connectType);
	        for(int ix=0;ix<connectors.getLength();ix++)
	        {
	        	Element e=(Element)connectors.item(ix);
	        	if(id.equals(e.getAttribute("GroupAddressRefId")))
	        	{
	        		Element pe=(Element)e.getParentNode().getParentNode();
	        		if(!"ComObjectInstanceRef".equals(pe.getNodeName()))
        			{
	        			L.warning("Weird project structure -- connection not owned by a ComObjectInstanceRef, but "+pe.getNodeName());
	        			continue;
        			}
	        		processETS4GroupConnection(zf, doc, docBuilder, pe.getAttribute("RefId"),id, address, name);
	        		return;
	        	}
	        }
		}
		L.info("Group "+id+"/"+address+"/"+name+" does not seem to be connected to anywhere");
	}

	private static void processETS4GroupConnection(ZipFile zf, Document doc, DocumentBuilder docBuilder, String refId, String id, String address, String name) throws SAXException, IOException
	{
		// Right, we need to look into the device description. Determine it's filename
		String refIdParts[]=refId.split("_");
		String pathName=refIdParts[0]+"/"+refIdParts[0]+"_"+refIdParts[1]+".xml";
		ZipEntry ze=zf.getEntry(pathName);
		if(ze==null)
			throw new IllegalArgumentException("Unable to find device description "+pathName);
        Document mdoc=docBuilder.parse(zf.getInputStream(ze));
        NodeList cobjs=mdoc.getElementsByTagName("ComObjectRef");
        for(int ix=0;ix<cobjs.getLength();ix++)
        {
        	Element e=(Element)cobjs.item(ix);
        	if(refId.equals(e.getAttribute("Id")))
        	{

        	}
        }
       	throw new IllegalArgumentException("Unable to find ComObjectRef with Id="+refId+" in "+pathName);
	}
}
