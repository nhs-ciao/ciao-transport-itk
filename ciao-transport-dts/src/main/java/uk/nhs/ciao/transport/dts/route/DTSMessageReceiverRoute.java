package uk.nhs.ciao.transport.dts.route;

import static uk.nhs.ciao.logging.CiaoCamelLogMessage.camelLogMsg;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.util.FileUtil;
import org.apache.camel.util.toolbox.AggregationStrategies;

import com.google.common.base.Strings;

import uk.nhs.ciao.camel.BaseRouteBuilder;
import uk.nhs.ciao.camel.URIBuilder;
import uk.nhs.ciao.dts.ControlFile;
import uk.nhs.ciao.logging.CiaoCamelLogger;
import uk.nhs.ciao.transport.dts.processor.DTSDataFilePoller;

public class DTSMessageReceiverRoute extends BaseRouteBuilder {
	private static final CiaoCamelLogger LOGGER = CiaoCamelLogger.getLogger(DTSMessageReceiverRoute.class);
	
	/**
	 * Maintains the control file as a header in the payload message
	 * <p>
	 * This is required to join up response messages with the correct worflowId
	 */
	public static final String HEADER_CONTROL_FILE = "dtsControlFile";
	
	private String dtsMessageReceiverUri;
	private String payloadDestinationUri;
	private String idempotentRepositoryId;
	private String inProgressRepositoryId;
	private String errorFolder;
	
	// optional properties
	private String dtsFilePrefix = ""; // TODO: Check if this is valid for the receiver / IN folder
	private long dataFilePollingInterval = 200;
	private int dataFileMaxAttempts = 100; // == 20 seconds
	
	/**
	 * URI where incoming DTS messages are received from
	 * <p>
	 * input only
	 */
	public void setDTSMessageReceiverUri(final String dtsMessageReceiverUri) {
		this.dtsMessageReceiverUri = dtsMessageReceiverUri;
	}
	
	/**
	 * URI where outgoing payload messages are sent to
	 * <p>
	 * output only
	 */
	public void setPayloadDestinationUri(final String payloadDestinationUri) {
		this.payloadDestinationUri = payloadDestinationUri;
	}
	
	/**
	 * Identifier of idempotent repository used to track incoming messages
	 */
	public void setIdempotentRepositoryId(final String idempotentRepositoryId) {
		this.idempotentRepositoryId = idempotentRepositoryId;
	}
	
	/**
	 * Identifier of idempotent repository to use to track in-progress incoming messages
	 */
	public void setInProgressRepositoryId(final String inProgressRepositoryId) {
		this.inProgressRepositoryId = inProgressRepositoryId;
	}
	
	/**
	 * Folder files should be moved to if an error occurs while processing
	 * <p>
	 * Only files intended for this application will be moved, other files will be left in the
	 * processing folder.
	 * 
	 * @see #setDTSFilePrefix(String)
	 */
	public void setErrorFolder(final String errorFolder) {
		this.errorFolder = errorFolder;
	}
	
	/**
	 * A prefix to be added to DTS file names before they are sent to the client.
	 * <p>
	 * The DTS documentation recommends <code>${siteid}${APP}</code>:
	 * <ul>
	 * <li>siteId: will uniquely identify the client site. The DTS Client userid should be used for this value.</li>
	 * <li>APP: an optional value used to allow different applications on the platform to use the same client to transfer files.</li>
	 * </ul>
	 */
	public void setDTSFilePrefix(final String dtsFilePrefix) {
		this.dtsFilePrefix = Strings.nullToEmpty(dtsFilePrefix);
	}
	
	/**
	 * Time to wait between poll attempts when waiting for a DTS data file
	 */
	public void setDataFilePollingInterval(final int dataFilePollingInterval) {
		this.dataFilePollingInterval = dataFilePollingInterval;
	}
	
	/**
	 * Maximum polling attempts to make while waiting for a DTS data file
	 */
	public void setDataFileMaxAttempts(final int dataFileMaxAttempts) {
		this.dataFileMaxAttempts = dataFileMaxAttempts;
	}
	
	@Override
	public void configure() throws Exception {
		final ScheduledExecutorService executorService = getContext().getExecutorServiceManager()
				.newSingleThreadScheduledExecutor(this, "data-file-poller");
		
		// Additional configuration parameters are appended to the endpoint URI
		final URIBuilder uri = new URIBuilder(dtsMessageReceiverUri);
		
		// only process files intended for this application
		if (!Strings.isNullOrEmpty(dtsFilePrefix)) {
			uri.set("include", Pattern.quote(dtsFilePrefix) + ".+\\.ctl");
		}
		
		// only process each file once
		// Details of processed files should be kept in the repository (they can be expunged over time)
		// The backing repository will need additional configuration to expunge old entries
		// If using hazelcast the backing map can be configured with a max time to live for each key
		uri.set("idempotent", true)
			.set("idempotentRepository", "#" + idempotentRepositoryId)
			.set("inProgressRepository", "#" + inProgressRepositoryId)
			.set("readLock", "idempotent");
		
		// Unknown files will be left in the folder (other application may be scanning for them)
		// Error control and data files will be moved to the error folder
		// Successfully processed control and data files are deleted as part of the route
		uri.set("delete", false)
			.set("moveFailed", errorFolder);
		
		from(uri.toString())
			.onException(Exception.class)
				.process(new DataFileMover())
			.end()
		
			.process(LOGGER.info(camelLogMsg("Received incoming DTS control file")
					.fileName(header(Exchange.FILE_NAME))))
			.setHeader("controlFileName").header(Exchange.FILE_NAME)
			.convertBodyTo(ControlFile.class)
			
			// Only handle control files for Data messages
			.choice()
				.when(simple("${getMessageType} != Data"))
					.process(LOGGER.info(camelLogMsg("Received DTS Report control file - stopping processing of file")
							.fileName(header(Exchange.FILE_NAME))))
					.stop()
				.endChoice()
			.end()
			
			// TODO: Does the workflowId and/or address need checking???
			
			// Wait for the associated data file
			.setHeader(DTSDataFilePoller.HEADER_FOLDER_NAME).header(Exchange.FILE_PARENT)
			.setHeader(DTSDataFilePoller.HEADER_FILE_NAME, regexReplaceAll(
					simple("${header.CamelFileName}"), "(..*)\\.ctl", "$1.dat"))
			.process(new DTSDataFilePoller(executorService, dataFilePollingInterval, dataFileMaxAttempts))
			
			// Publish the payload (using multicast/pipeline to maintin original message)
			.multicast(AggregationStrategies.useOriginal())
				.pipeline()
					.setProperty(HEADER_CONTROL_FILE).body(String.class)
					.setBody().header(DTSDataFilePoller.HEADER_FILE)
					.convertBodyTo(byte[].class)
					
					// Store the control file - it may be required for future DTS exchanges (especially the workflowId)
					.removeHeaders("*")
					.setHeader(HEADER_CONTROL_FILE).property(HEADER_CONTROL_FILE)
					.removeProperty(HEADER_CONTROL_FILE)
					
					.to(payloadDestinationUri)
				.end()
			.end()
			
			// If successful delete the pair of files
			.process(new ControlFileAndDataFileDeleter())
		.end();
	}
	
	/**
	 * If successful, the control and data file are deleted
	 */
	private class ControlFileAndDataFileDeleter implements Processor {
		@Override
		public void process(final Exchange exchange) throws Exception {
			final File dataFile = exchange.getIn().getHeader(DTSDataFilePoller.HEADER_FILE, File.class);
			final File controlFile = new File(dataFile.getParentFile(),
					exchange.getIn().getHeader(Exchange.FILE_NAME, String.class));
			
			FileUtil.deleteFile(dataFile);
			FileUtil.deleteFile(controlFile);
		}
	}
	
	/**
	 * If successful, the data file is moved to the error folder
	 */
	private class DataFileMover implements Processor {
		@Override
		public void process(final Exchange exchange) throws Exception {
			final File dataFile = exchange.getIn().getHeader(DTSDataFilePoller.HEADER_FILE, File.class);
			if (dataFile != null && dataFile.isFile()) {
				final File folder = dataFile.getParentFile();
				FileUtil.renameFile(dataFile, new File(folder, errorFolder), true);
			}
		}
	}
}
