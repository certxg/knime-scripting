package de.mpicbg.knime.scripting.matlab.srv;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.ExecutionContext;

import de.mpicbg.knime.scripting.matlab.srv.utils.MatlabCode;
import de.mpicbg.knime.scripting.matlab.srv.utils.MatlabTable;

import matlabcontrol.MatlabConnectionException;
import matlabcontrol.MatlabInvocationException;
import matlabcontrol.MatlabProxy;


/**
 * This MATLAB client uses the {@link Matlab} interface to expose 
 * the needed functions to the KNIME nodes. The node does not have to care
 * if the MATLAB code is executed locally or remotely. In the first case 
 * it uses the {@link MatlabController} to start and process the MATLAB
 * commands, in the second case it uses the cajo library to communicate
 * with the JVM on a remote machine that works like a MATLAB controller.
 * 
 * @author Felix Meyenhofer
 */
public class MatlabClient {
	
	/** MATLAB client object (can either use a remote or a local MATLAB session) */
	public final Matlab client;
	
	/** Store the local/remote flag for information purposes */
	public final boolean local;
	
	
	/**
	 * Constructor of the MATLAB client.
	 * It uses the local flag to determine weather to use a local
	 * MATLAB session or to communicate with a remote session on another
	 * machine.
	 * 
	 * @param local
	 * @throws MatlabConnectionException
	 */
	public MatlabClient(boolean local) throws MatlabConnectionException {
		this.local = local;
		
		if (local) {
			client = new Local();
		} else {
			client = new Remote();
		}
	} 
	
	/**
	 * Wrapper of the corresponding method in {@link Matlab} for easier access
	 * 
	 * @throws InterruptedException
	 */
	public void rollback() throws InterruptedException {
		this.client.rollback();
	}
	
	/**
	 * Wrapper of the corresponding method in {@link Matlab} for easier access
	 */
	public void cleanup() {
		this.client.cleanup();
	}
	
	
	
	/**
	 * Implementation of a local MATLAB client
	 */
	private class Local implements Matlab{
		
		/** MATLAB controller object */
		private MatlabController matlabController;
		
		private ArrayList<MatlabProxy> matlabProxyHolder = new ArrayList<MatlabProxy>(1);
		
		/** Object to generate the MATLAB code needed for a particular task */ 
		private MatlabCode code;
		
		/** Object to hold the KNIME table and allowing MATLAB compatible transformations */
		private MatlabTable table;
	
		
		/**
		 * Constructor of the local MATLAB client.
		 * This initializes the MatlabController.
		 * 
		 * @throws MatlabConnectionException
		 */
		public Local() throws MatlabConnectionException {
			matlabController = new MatlabController();
		}

		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void openTask(BufferedDataTable inputTable, String matlabType) 
				throws Exception {
	       
			// Transfer the KNIME table as hash map object dump to the JVM temp-folder	        
	        table = new MatlabTable(inputTable);
	        table.writeHashMapToTempFolder();
	        
	        // Copy the MATLAB script to the temp-directory and get the file name with the random string in it
	        code = new MatlabCode(table.getTempFile(), matlabType);
	        String cmd = code.prepareOpenCode();
	        
			// Execute 
	        MatlabProxy proxy = acquireMatlabProxy();
	        proxy.eval(cmd);
	        returnMatlabProxy(proxy);
	        
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public BufferedDataTable snippetTask(BufferedDataTable inputTable, ExecutionContext exec, String snippet, String matlabType)
				throws Exception {
			
			// Convert the KNIME table and write it to the temp-directory
			table = new MatlabTable(inputTable);
			table.writeHashMapToTempFolder();
			
			// Add the MATLAB code to the snippet and transfer the scripts to the temp-directory
			code = new MatlabCode(snippet, table.getTempFile(), matlabType);
			String cmd = code.prepareSnippetCode();			
	        
	        // Run it in MATLAB
	        MatlabProxy proxy = acquireMatlabProxy();
	        proxy.eval(cmd);
	        proxy.eval("disp('exectuted snippet and updated " + Matlab.OUTPUT_VARIABLE_NAME + "')");
	        returnMatlabProxy(proxy);
	        	        
	        // Get the data back
	        table.readHashMapFromTempFolder(exec);
	        return table.getBufferedDataTable();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public File plotTask(BufferedDataTable inputTable, String snippet, Integer plotWidth, Integer plotHeight, String matlabType) 
				throws Exception {

			// Transfer the KNIME table as hash map object dump to the JVM temp-folder
			table = new MatlabTable(inputTable);
			table.writeHashMapToTempFolder();
			
			// Copy the MATLAB script to the temp-directory and get the file name with the random string in it
			code = new MatlabCode(snippet, table.getTempFile(), matlabType);
			String cmd = code.preparePlotCode(plotWidth, plotHeight);
			
			// Execute 
			MatlabProxy proxy = acquireMatlabProxy();
			proxy.eval(cmd);
			proxy.eval("disp('created plot.')");
		    returnMatlabProxy(proxy);
			
		    // Return the png-image
			return code.getPlotFile();
		}

		/**
		 * {@inheritDoc} 
		 */
		@Override
		public void rollback() throws InterruptedException {
			if (this.matlabProxyHolder.size() > 0) {
				MatlabProxy proxy = this.matlabProxyHolder.remove(0);
				if (proxy != null) {
					this.matlabController.returnProxyToQueue(proxy);
					System.out.println("Emergency proxy return");
				} else {
					System.out.println("No proxy to return");
				}
			} else {
				System.out.println("Proxy already returned.");
			}
		}

		/**
		 * {@inheritDoc} 
		 */
		@Override
		public void cleanup() {
			if (this.code != null)
				this.code.cleanup();
			if (this.table != null)
				this.table.cleanup();
		}
		
		/**
		 * This method get's the controller for the MATLAB application
		 * and keeps it accessible in a field in case of an interruption
		 * 
		 * @return {@link MatlabProxy}
		 * @throws MatlabInvocationException
		 * @throws MatlabConnectionException 
		 */
		private MatlabProxy acquireMatlabProxy() throws MatlabInvocationException, MatlabConnectionException {
			MatlabProxy proxy = matlabController.acquireProxyFromQueue();
			matlabProxyHolder.add(proxy);
			proxy.eval("disp(' ');disp('Thread "+ matlabController.getThreadNumber() +":');");
	        return proxy;
		}
		
		/**
		 * This method returns the MATLAB proxy and removes it from the field.
		 *  
		 * @param proxy
		 */
		private void returnMatlabProxy(MatlabProxy proxy) {
			matlabController.returnProxyToQueue(proxy);
			matlabProxyHolder.remove(proxy);
		}
		
	}
	
	
	
	/**
	 * Implementation of the client talking to a remote MATLAB session.
	 */
	private class Remote implements Matlab, MatlabRemote {
		
		/** Object to generate the MATLAB code needed for a particular task */ 
		private MatlabCode code;
		
		/** Object to hold the KNIME table and allowing MATLAB compatible transformations */
		private MatlabTable table;
		
		
		

		/**
		 * Constructor
		 */
		public Remote(String serverName, int serverPort) {
	        try {
	            String url = "//" + serverName + ":" + serverPort + "/" + MatlabWeb.REGISTRY_NAME;
	            matlab = (MatlabWeb) TransparentItemProxy.getItem(url, new Class[]{MatlabWeb.class});
	        } catch (Throwable e) {
	            throw new RuntimeException(e);
	        }
			
		}
		
		
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void openTask(BufferedDataTable inputTable, String matlabType)
				throws IOException, MatlabInvocationException {
			// Not used. The OpenInMatlab node always instantiates the parent class in local-mode.
		}
	
		/**
		 * {@inheritDoc}
		 */
		@Override
		public BufferedDataTable snippetTask(BufferedDataTable inputTable, ExecutionContext exec, String snippet, String matlabType)
				throws IOException {
			// TODO Auto-generated method stub
			return null;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public File plotTask(BufferedDataTable inputTable, String snippet, Integer plotWidth, Integer plotHeight, String matlabType) 
				throws IOException {
			// TODO Auto-generated method stub
			return null;
		}

		/**
		 * {@inheritDoc} 
		 */
		@Override
		public void rollback() {
			// TODO Auto-generated method stub
		}

		@Override
		public void cleanup() {
			// TODO Auto-generated method stub
			
		}
		
	}

	
}
