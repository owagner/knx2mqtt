package com.tellerulam.knx2mqtt;

import java.io.*;
import java.util.*;
import java.util.logging.*;
import java.util.regex.*;
import java.util.zip.*;

import javax.xml.parsers.*;

import org.w3c.dom.*;
import org.xml.sax.*;

import tuwien.auto.calimero.*;
import tuwien.auto.calimero.dptxlator.*;
import tuwien.auto.calimero.exception.*;

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
		DPTXlator xlator;
		private GroupAddressInfo(String name,String address)
		{
			this.name=name;
			this.address=address;
		}
		@Override
		public String toString()
		{
			return "{"+name+"|"+dpt+"}";
		}
		void createTranslator() throws KNXException
		{
			xlator=TranslatorTypes.createTranslator(0, dpt);
			xlator.setAppendUnit(false);
		}
		public Object translate(byte[] asdu)
		{
			xlator.setData(asdu);
			if(xlator instanceof DPTXlatorBoolean)
			{
				if(((DPTXlatorBoolean)xlator).getValueBoolean())
					return Integer.valueOf(1);
				else
					return Integer.valueOf(0);
			}
			// TODO there must be a less lame method to do this
			String strVal=xlator.getValue();
			try
			{
				return Integer.valueOf(strVal);
			}
			catch(NumberFormatException nfe)
			{
				try
				{
					return Double.valueOf(strVal);
				}
				catch(NumberFormatException nfe2)
				{
					return strVal;
				}
			}
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
			for(GroupAddressInfo gai:gaTable.values())
				gai.createTranslator();
			L.info("Group address table: "+gaTable);
		}
		catch(Exception e)
		{
			L.log(Level.SEVERE, "Error reading project file "+gaFile,e);
			System.exit(1);
		}
		docCache=null;
	}

	private static void storeGAInfo(String address,String name,String datapointType)
	{
		String ga=new GroupAddress(Integer.parseInt(address)).toString();

		GroupAddressInfo gai=gaTable.get(ga);
		if(gai==null)
		{
			gai=new GroupAddressInfo(name, ga);
			gaTable.put(ga,gai);
			gaByName.put(name,gai);
		}
		Pattern p=Pattern.compile("DPST-([0-9]+)(-([0-9]+))?");
		Matcher m=p.matcher(datapointType);
		if(!m.find())
			throw new IllegalArgumentException("Unparsable DPST '"+datapointType+"'");
		StringBuilder dptBuilder=new StringBuilder();
		dptBuilder.append(m.group(1));
		dptBuilder.append('.');
		String suffix=m.group(3);
		if(suffix==null)
		{
			dptBuilder.append("001");
		}
		else
		{
			int suffixLength=suffix.length();
			while(suffixLength++<3)
				dptBuilder.append('0');
			dptBuilder.append(suffix);
		}
		gai.dpt=dptBuilder.toString();
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

        	// Resolve the full "path" name of the group by going upwards in the GroupRanges
        	String name=null;
        	for(Element pe=e;;)
        	{
        		if(name==null)
        			name=pe.getAttribute("Name");
        		else
        			name=pe.getAttribute("Name")+"/"+name;

        		pe=(Element)pe.getParentNode();
        		if(!"GroupRange".equals(pe.getNodeName()))
        			break;
        	}

        	String address=e.getAttribute("Address");

        	// If we're lucky, the DPT is already specified here
        	String dpt=e.getAttribute("DatapointType");
        	if(dpt.length()!=0)
        	{
        		storeGAInfo(address, name, dpt);
        		continue;
        	}

        	// We're not lucky. Look into the connections
        	processETS4GroupAddressConnections(zf,doc,docBuilder,
        		e.getAttribute("Id"),
        		address,
        		name
        	);
        }
	}

	/*
	 * Find out what is connected to this group address
	 */
	private static void processETS4GroupAddressConnections(ZipFile zf, Document doc,DocumentBuilder docBuilder, String id, String address, String name) throws SAXException, IOException
	{
		final String connectTypes[]=new String[]{"Send","Receive"};
		boolean foundConnection=false,foundDPT=false;
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
	        		foundConnection=true;

	        		/* Perhaps we're lucky and someone specified it in the CombObjectInstanceRef? */
	        		String dpt=e.getAttribute("DatapointType");
	        		if(dpt.length()!=0)
	        		{
	        			storeGAInfo(address, name, dpt);
	        			return;
	        		}
	        		/* No luck, no luck. Dig deeper */
	        		if(processETS4GroupConnection(zf, doc, docBuilder, pe.getAttribute("RefId"),id, address, name))
	        			foundDPT=true;
	        		return;
	        	}
	        }
		}
		if(!foundConnection)
			L.info("Group "+id+"/"+address+"/"+name+" does not seem to be connected to anywhere, ignoring it");
		else if(!foundDPT)
			throw new IllegalArgumentException("Unable to determine datapoint type for "+id);
	}

	/*
	 * We manage a cache of preparsed device descriptions
	 */
	private static Map<String,Document> docCache;
	private static Document getDocument(ZipFile zf, DocumentBuilder docBuilder, String filename) throws SAXException, IOException
	{
		if(docCache==null)
			docCache=new HashMap<>();
		Document doc=docCache.get(filename);
		if(doc==null)
		{
			ZipEntry ze=zf.getEntry(filename);
			if(ze==null)
				throw new IllegalArgumentException("Unable to find device description "+filename);
	        doc=docBuilder.parse(zf.getInputStream(ze));
	        // Make sure we can use getElementById()
	        NodeList nlist=doc.getElementsByTagName("ComObjectRef");
	        for(int ix=0;ix<nlist.getLength();ix++)
	        	((Element)nlist.item(ix)).setIdAttribute("Id", true);
	        nlist=doc.getElementsByTagName("ComObject");
	        for(int ix=0;ix<nlist.getLength();ix++)
	        	((Element)nlist.item(ix)).setIdAttribute("Id", true);
	        docCache.put(filename,doc);
		}
		return doc;
	}

	private static boolean processETS4GroupConnection(ZipFile zf, Document doc, DocumentBuilder docBuilder, String refId, String id, String address, String name) throws SAXException, IOException
	{
		// Right, we need to look into the device description. Determine it's filename
		String refIdParts[]=refId.split("_");
		String pathName=refIdParts[0]+"/"+refIdParts[0]+"_"+refIdParts[1]+".xml";
		Document mdoc=getDocument(zf, docBuilder, pathName);
		Element cobjref=mdoc.getElementById(refId);
		if(cobjref==null)
			throw new IllegalArgumentException("Unable to find ComObjectRef with Id "+refId+" in "+pathName);
		// Perhaps the ComObjectRef
		if(processETS4ComObj(cobjref,zf,doc,docBuilder,address,name))
			return true;

		String refco=cobjref.getAttribute("RefId");
		Element cobj=mdoc.getElementById(refco);
		if(cobj==null)
			throw new IllegalArgumentException("Unable to find ComObject with Id "+refco+" in "+pathName);

		if(processETS4ComObj(cobj,zf,doc,docBuilder,address,name))
			return true;

		return false;
	}

	private static boolean processETS4ComObj(Element cobj, ZipFile zf, Document doc, DocumentBuilder docBuilder, String address, String name) throws SAXException, IOException
	{
		String dpt=cobj.getAttribute("DatapointType");
		if(dpt.length()!=0)
		{
			storeGAInfo(address, name, dpt);
			return true;
		}
		String objSize=cobj.getAttribute("ObjectSize");
		if(objSize.length()!=0)
		{
			// "1 Bit" is pretty unambigious -- no warning for that
			if(!"1 Bit".equals(objSize))
				L.warning("Warning: Infering DPT for "+new GroupAddress(Integer.parseInt(address))+" ("+name+") by objSize "+objSize+" - this is not good, please update your ETS4 project with proper DPT specifications!");
			storeGAInfo(address, name, inferDPTFromObjectSize(zf, docBuilder, objSize));
			return true;
		}
		return false;
	}

	private static String inferDPTFromObjectSize(ZipFile zf, DocumentBuilder docBuilder, String objSize) throws SAXException, IOException
	{
		// Take a guess based on size
		String dpitid=null;
		// Some standard things
		if("1 Bit".equals(objSize))
			dpitid="1-1";
		else if("1 Byte".equals(objSize))
			dpitid="5-1";
		else if("2 Bytes".equals(objSize))
			dpitid="9-1";
		else
		{
			// Look up in knx_master table
			Document master=getDocument(zf, docBuilder, "knx_master.xml");
			NodeList allDPs=master.getElementsByTagName("DatapointType");
			String sizeSpec[]=objSize.split(" ");
			String bits=sizeSpec[0];
			if(sizeSpec[1].startsWith("Byte"))
				bits=String.valueOf(Integer.parseInt(bits)*8);
			for(int ix=0;ix<allDPs.getLength();ix++)
			{
				Element e=(Element)allDPs.item(ix);
				String dpSize=e.getAttribute("SizeInBit");
				if(bits.equals(dpSize))
				{
					// Find the first subtype
					Element subType=(Element)e.getElementsByTagName("DatapointSubtype").item(0);
					return subType.getAttribute("Id");
				}
			}
		}
		return "DPST-"+dpitid;
	}
}
