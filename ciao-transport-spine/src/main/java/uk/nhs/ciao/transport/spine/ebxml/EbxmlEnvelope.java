package uk.nhs.ciao.transport.spine.ebxml;

import java.util.List;
import java.util.UUID;

import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;

/**
 * Simplified bean-like view of a SOAP+ebXml envelope 
 * <p>
 * This makes various assumptions about the incoming/outgoing messages to flatten the overall
 * structure (e.g. a single fromParty / toParty) etc..
 */
public class EbxmlEnvelope {
	public static final String ACTION_ACKNOWLEDGMENT = "Acknowledgment";	
	public static final String ACTION_MESSAGE_ERROR = "MessageError";
	
	public static final String SERVICE_EBXML_MSG = "urn:oasis:names:tc:ebxml-msg:service";
	
	public static final String ERROR_CODE_CLIENT = "Client";
	public static final String ERROR_CODE_DELIVERY_FAILURE = "DeliveryFailure";
	
	public static final String ERROR_SEVERITY_ERROR = "Error";
	public static final String ERROR_SEVERITY_WARNING = "Warning";
	
	/**
	 * Date format used in ebXml time-stamps
	 * <p>
	 * This is expressed in the UTC time-zone
	 */
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZoneUTC();
	
	private String fromParty;
	private String toParty;	
	private String cpaId;
	private String conversationId;
	private String service;
	private String action;
	private final MessageData messageData = new MessageData(); // required
	private boolean ackRequested = true;
	private boolean acknowledgment;
	private boolean duplicateElimination;
	private ErrorDetail error;
	private final List<ManifestReference> manifestReferences = Lists.newArrayList();
	
	/**
	 * Copies properties from the specified prototype envelope
	 * 
	 * @param prototype The prototype to copy from
	 * @param overwrite true if non-empty properties should be overwritten, or false if the existing values should be kept
	 */
	public void copyFrom(final EbxmlEnvelope prototype, final boolean overwrite) {
		if (prototype == null) {
			return;
		}
		
		fromParty = copyProperty(fromParty, prototype.fromParty, overwrite);
		toParty = copyProperty(toParty, prototype.toParty, overwrite);
		cpaId = copyProperty(cpaId, prototype.cpaId, overwrite);
		conversationId = copyProperty(conversationId, prototype.conversationId, overwrite);
		service = copyProperty(service, prototype.service, overwrite);
		action = copyProperty(action, prototype.action, overwrite);
		if (overwrite) {
			acknowledgment = prototype.acknowledgment;
			ackRequested = prototype.ackRequested;
			duplicateElimination = prototype.duplicateElimination;
		}
		
		messageData.copyFrom(prototype.messageData, overwrite);
		
		if (prototype.error != null) {
			if (error == null) {
				error = new ErrorDetail();
			}
			
			error.copyFrom(prototype.error, overwrite);
		}
		
		if ((manifestReferences.isEmpty() || overwrite) && !prototype.manifestReferences.isEmpty()) {
			manifestReferences.clear();
			
			for (final ManifestReference prototypeManifestReference: prototype.manifestReferences) {
				manifestReferences.add(new ManifestReference(prototypeManifestReference));
			}
		}
	}
	
	private String copyProperty(final String original, final String prototype, final boolean overwrite) {
		return (prototype != null && (original == null || overwrite)) ? prototype : original;
	}
	
	public String getFromParty() {
		return fromParty;
	}

	public void setFromParty(final String fromParty) {
		this.fromParty = fromParty;
	}

	public String getToParty() {
		return toParty;
	}

	public void setToParty(final String toParty) {
		this.toParty = toParty;
	}

	public String getCpaId() {
		return cpaId;
	}

	public void setCpaId(final String cpaId) {
		this.cpaId = cpaId;
	}

	public String getConversationId() {
		return conversationId;
	}

	public void setConversationId(final String conversationId) {
		this.conversationId = conversationId;
	}

	public String getService() {
		return service;
	}

	public void setService(final String service) {
		this.service = service;
	}

	public String getAction() {
		return action;
	}

	public void setAction(final String action) {
		this.action = action;
	}
	
	public boolean isAckRequested() {
		return ackRequested;
	}
	
	public void setAckRequested(final boolean ackRequested) {
		this.ackRequested = ackRequested;
	}

	public boolean isAcknowledgment() {
		return acknowledgment;
	}

	public void setAcknowledgment(final boolean acknowledgment) {
		this.acknowledgment = acknowledgment;
	}
	
	public boolean isDuplicateElimination() {
		return duplicateElimination;
	}
	
	public void setDuplicateElimination(final boolean duplicateElimination) {
		this.duplicateElimination = duplicateElimination;
	}

	public ErrorDetail getError() {
		return error;
	}

	public ErrorDetail addError() {
		this.error = new ErrorDetail();
		return error;
	}
	
	/**
	 * Tests if this envelope represents an 'Error Message'
	 * (i.e. contains an error element)
	 */
	public boolean isErrorMessage() {
		return error != null;
	}
	
	/**
	 * Tests if this envelope represents a SOAP fault
	 */
	public boolean isSOAPFault() {
		return isErrorMessage() && !isDeliveryFailure();
	}
	
	/**
	 * Tests if this envelope represents a message delivery failure
	 */
	public boolean isDeliveryFailure() {
		return isErrorMessage() && error.isDeliveryFailure();
	}
	
	public List<ManifestReference> getManifestReferences() {
		return manifestReferences;
	}
	
	public ManifestReference addManifestReference() {
		final ManifestReference reference = new ManifestReference();
		manifestReferences.add(reference);
		return reference;
	}
	
	/**
	 * Tests if this envelope represents a manifest (i.e. contains manifest references)
	 */
	public boolean isManifest() {
		return !manifestReferences.isEmpty();
	}

	public MessageData getMessageData() {
		return messageData;
	}
	
	/**
	 * Generates default values for required properties which
	 * have not been specified
	 */
	public void applyDefaults() {
		messageData.applyDefaults();
		if (error != null) {
			error.applyDefaults();
		}
		
		if (conversationId == null) {
			conversationId = messageData.messageId;
		}
	}
	
	/**
	 * Creates a new envelope representing an acknowledgment associated with this message.
	 * <p>
	 * Standard reply message fields are populated from this envelope and required fields (e.g. messageId)
	 * are generated.
	 *
	 * @return A new acknowledgment instance
	 */
	public EbxmlEnvelope generateAcknowledgment() {
		final EbxmlEnvelope ack = generateBaseReply();		
		ack.action = ACTION_ACKNOWLEDGMENT;
		ack.acknowledgment = true;
		
		ack.applyDefaults();
		
		return ack;
	}
	
	/**
	 * Creates a new envelope representing delivery failure associated with this message.
	 * <p>
	 * Standard reply message fields are populated from this envelope and required fields (e.g. messageId)
	 * are generated. Error properties with details of the delivery failure are included, however the failure
	 * is not marked as an acknowledgement.
	 *
	 * @return A new delivery failure notification instance
	 */
	public EbxmlEnvelope generateDeliveryFailureNotification(final String description) {
		final EbxmlEnvelope deliveryFailure = generateSOAPFault(ERROR_CODE_DELIVERY_FAILURE, description);
		deliveryFailure.error.setWarning();
		return deliveryFailure;
	}
	
	/**
	 * Creates a new envelope representing a SOAPFault associated with this message.
	 * <p>
	 * Standard reply message fields are populated from this envelope and required fields (e.g. messageId)
	 * are generated.
	 *
	 * @return A new SOAPFault instance
	 */
	public EbxmlEnvelope generateSOAPFault(final String code, final String description) {
		final EbxmlEnvelope fault = generateBaseReply();
		fault.action = ACTION_MESSAGE_ERROR;
		
		fault.addError();
		fault.error.code = code;
		fault.error.description = description;
		
		fault.applyDefaults();
		
		return fault;
	}
	
	/**
	 * Creates a new envelope instance and copies standard 'reply' fields into it
	 * <p>
	 * Note to and from are inverted (since it is a reply)
	 * 
	 * @return The reply instance
	 */
	private EbxmlEnvelope generateBaseReply() {
		final EbxmlEnvelope reply = new EbxmlEnvelope();
		
		reply.fromParty = toParty;
		reply.toParty = fromParty;
		reply.cpaId = cpaId;
		reply.conversationId = conversationId;
		reply.service = SERVICE_EBXML_MSG;
		reply.messageData.refToMessageId = messageData.messageId;
		reply.duplicateElimination = duplicateElimination;
		reply.ackRequested = false;
		
		return reply;
	}
	
	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
			.add("fromParty", fromParty)
			.add("toParty", toParty)
			.add("cpaId", cpaId)
			.add("conversationId", conversationId)
			.add("service", service)
			.add("action", action)
			.add("messageData", messageData)
			.add("ackRequested", ackRequested)
			.add("acknowledgment", acknowledgment)
			.add("duplicateElimination", duplicateElimination)
			.add("error", error)
			.add("manifestReferences", manifestReferences)
			.toString();
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public int hashCode() {
		return messageData.messageId == null ? 0 : messageData.messageId.hashCode();
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Envelopes are considered equal if they have the same messageId
	 */
	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		} else if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		
		final EbxmlEnvelope other = (EbxmlEnvelope) obj;
		return Objects.equal(messageData.messageId, other.messageData.messageId);
	}

	protected String generateId() {
		return UUID.randomUUID().toString();
	}
	
	protected String generateTimestamp() {
		return TIMESTAMP_FORMAT.print(System.currentTimeMillis());
	}

	public class MessageData {
		private String messageId; // required
		private String timestamp; // required
		private String refToMessageId; // required if ERROR message
		
		public String getMessageId() {
			return messageId;
		}
		
		
		public void copyFrom(final MessageData prototype, boolean overwrite) {
			if (prototype == null) {
				return;
			}
			
			messageId = copyProperty(messageId, prototype.messageId, overwrite);
			timestamp = copyProperty(timestamp, prototype.timestamp, overwrite);
			refToMessageId = copyProperty(refToMessageId, prototype.refToMessageId, overwrite);
		}

		public void setMessageId(final String messageId) {
			this.messageId = messageId;
		}
		
		public String getTimestamp() {
			return timestamp;
		}
		
		public void setTimestamp(final String timestamp) {
			this.timestamp = timestamp;
		}
		
		public String getRefToMessageId() {
			return refToMessageId;
		}
		
		public void setRefToMessageId(final String refToMessageId) {
			this.refToMessageId = refToMessageId;
		}
		
		/**
		 * Generates default values for required properties which
		 * have not been specified
		 */
		public void applyDefaults() {
			if (Strings.isNullOrEmpty(messageId)) {
				messageId = generateId();
			}
			
			if (Strings.isNullOrEmpty(timestamp)) {
				timestamp = generateTimestamp();
			}
		}
		
		@Override
		public String toString() {
			return MoreObjects.toStringHelper(this)
				.add("messageId", messageId)
				.add("timestamp", timestamp)
				.add("refToMessageId", refToMessageId)
				.toString();
		}
	}
	
	public class ErrorDetail {
		private String listId;
		private String id;
		private String code;
		private String severity;
		private String codeContext;
		private String description;
		
		/**
		 * Generates default values for required properties which
		 * have not been specified
		 */
		public void applyDefaults() {
			if (Strings.isNullOrEmpty(listId)) {
				listId = generateId();
			}
			
			if (Strings.isNullOrEmpty(id)) {
				id = generateId();
			}
			
			if (Strings.isNullOrEmpty(codeContext)) {
				codeContext = "";
			}
			
			if (Strings.isNullOrEmpty(severity)) {
				setError();
			}
		}
		
		public void copyFrom(final ErrorDetail prototype, boolean overwrite) {
			if (prototype == null) {
				return;
			}
			
			listId = copyProperty(listId, prototype.listId, overwrite);
			id = copyProperty(id, prototype.id, overwrite);
			code = copyProperty(code, prototype.code, overwrite);
			severity = copyProperty(severity, prototype.severity, overwrite);
			codeContext = copyProperty(codeContext, prototype.codeContext, overwrite);
			description = copyProperty(description, prototype.description, overwrite);
		}

		public String getListId() {
			return listId;
		}
		
		public void setListId(final String listId) {
			this.listId = listId;
		}
		
		public String getId() {
			return id;
		}
		
		public void setId(final String id) {
			this.id = id;
		}
		
		public String getCode() {
			return code;
		}
		
		public void setCode(final String code) {
			this.code = code;
		}
		
		public String getSeverity() {
			return severity;
		}
		
		public void setSeverity(final String severity) {
			this.severity = severity;
		}
		
		public boolean isDeliveryFailure() {
			return ERROR_CODE_DELIVERY_FAILURE.equalsIgnoreCase(code);
		}
		
		public void setDeliveryFailure() {
			setCode(ERROR_CODE_DELIVERY_FAILURE);
		}
		
		public boolean isClientError() {
			return ERROR_CODE_CLIENT.equalsIgnoreCase(code);
		}
		
		public void setClientError() {
			setCode(ERROR_CODE_CLIENT);
		}
		
		public boolean isError() {
			return ERROR_SEVERITY_ERROR.equalsIgnoreCase(severity);
		}
		
		public void setError() {
			setSeverity(ERROR_SEVERITY_ERROR);
		}
		
		public boolean isWarning() {
			return ERROR_SEVERITY_WARNING.equalsIgnoreCase(severity);
		}
		
		public void setWarning() {
			setSeverity(ERROR_SEVERITY_WARNING);
		}
		
		public String getCodeContext() {
			return codeContext;
		}
		
		public void setCodeContext(final String codeContext) {
			this.codeContext = codeContext;
		}
		
		public String getDescription() {
			return description;
		}
		
		public void setDescription(final String description) {
			this.description = description;
		}
		
		@Override
		public String toString() {
			return MoreObjects.toStringHelper(this)
				.add("listId", listId)
				.add("id", id)
				.add("code", code)
				.add("severity", severity)
				.add("codeContext", codeContext)
				.add("description", description)
				.toString();
		}
	}
	
	public class ManifestReference {
		private String href;
		private boolean hl7;
		private String description;
		
		public ManifestReference() {
			// NOOP
		}
		
		/**
		 * Copy constructor
		 */
		public ManifestReference(final ManifestReference copy) {
			href = copy.href;
			hl7 = copy.hl7;
			description = copy.description;
		}

		public String getHref() {
			return href;
		}
		
		public void setHref(String href) {
			this.href = href;
		}
		
		public boolean isHl7() {
			return hl7;
		}
		
		public void setHl7(final boolean hl7) {
			this.hl7 = hl7;
		}
		
		public String getDescription() {
			return description;
		}
		
		public void setDescription(String description) {
			this.description = description;
		}
		
		@Override
		public String toString() {
			return MoreObjects.toStringHelper(this)
				.add("href", href)
				.add("hl7", hl7)
				.add("description", description)
				.toString();
		}
	}
}
