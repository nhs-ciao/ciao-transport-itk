package uk.nhs.ciao.transport.spine.route;

import java.util.Collections;
import java.util.Set;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.component.http.HttpOperationFailedException;
import org.apache.camel.component.http4.HttpMethods;
import org.apache.camel.processor.idempotent.MemoryIdempotentRepository;
import org.apache.camel.spring.spi.TransactionErrorHandlerBuilder;
import org.apache.camel.util.toolbox.AggregationStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.SettableFuture;

import uk.nhs.ciao.transport.spine.ebxml.EbxmlEnvelope;
import uk.nhs.ciao.transport.spine.forwardexpress.ForwardExpressMessageExchange;
import uk.nhs.ciao.transport.spine.multipart.MultipartBody;

/**
 * Route to send outgoing multipart messages over spine and wait for async acknowledgement
 * <ul>
 * <li>Sends a multi-part trunk request message over the spine
 * <li>Blocks until an async ebXml ack is received off a configured JMS topic or a timeout occurs
 * <li>Marks message as success, retry or failure based on the ACK content
 */
public class MultipartMessageSenderRoute extends BaseRouteBuilder {
	private static final Logger LOGGER = LoggerFactory.getLogger(MultipartMessageSenderRoute.class);
	
	private final Set<String> inprogressIds = Collections.newSetFromMap(Maps.<String, Boolean>newConcurrentMap());
	private final int aggregatorTimeout = 30000;
	private String multipartMessageSenderUri;
	private String multipartMessageDestinationUri;
	private String ebxmlAckReceiverUri;
	private String ebxmlAckDestinationUri;
	private int maximumRedeliveries = 2;
	private int redeliveryDelay = 2000;
	
	public void setMultipartMessageSenderUri(final String multipartMessageSenderUri) {
		this.multipartMessageSenderUri = multipartMessageSenderUri;
	}
	
	public void setMultipartMessageDestinationUri(final String multipartMessageDestinationUri) {
		this.multipartMessageDestinationUri = multipartMessageDestinationUri;
	}
	
	public void setEbxmlAckReceiverUri(final String ebxmlAckReceiverUri) {
		this.ebxmlAckReceiverUri = ebxmlAckReceiverUri;
	}
	
	public void setEbxmlAckDestinationUri(final String ebxmlAckDestinationUri) {
		this.ebxmlAckDestinationUri = ebxmlAckDestinationUri;
	}
	
	public void setMaximumRedeliveries(final int maximumRedeliveries) {
		this.maximumRedeliveries = maximumRedeliveries;
	}
	
	public void setRedeliveryDelay(final int redeliveryDelay) {
		this.redeliveryDelay = redeliveryDelay;
	}
	
	private String getForwardExpressHandlerUrl() {
		return internalDirectUri("forward-express-handler");
	}
	
	private String getForwardExpressAggregatorUrl() {
		return internalDirectUri("forward-express-aggregator");
	}
	
	private String getEbxmlAckProcessorUrl() {
		return internalDirectUri("ebxml-ack-processor");
	}
	
	@Override
	public void configure() throws Exception {
		configureMultipartMessageSender();
		configureForwardExpressSender();
		configureForwardAckReceiver();
		configureForwardExpressMessageAggregator();
		configureEbxmlAckProcessor();
	}
	
	/**
	 * Configures the message sender
	 * <p>
	 * The route is split via an internal direct route to allow easier
	 * configuration of the forward-express handler component
	 * 
	 * @see #configureForwardExpressHandler()
	 */
	private void configureMultipartMessageSender() throws Exception {
		from(multipartMessageSenderUri)
			.id("trunk-request-sender")
			.errorHandler(new TransactionErrorHandlerBuilder()
				.maximumRedeliveries(0)) // redeliveries are handled by the onException clause
			.onException(Exception.class)
				.maximumRedeliveries(maximumRedeliveries)
				.redeliveryDelay(redeliveryDelay)
				.logExhausted(true)
				.useOriginalMessage()
				.handled(true)
				
				// Out of redelivery attempts - publish a generated failure notification
				.convertBodyTo(MultipartBody.class)
				.setBody().spel("#{body.parts[0].body}")
				.convertBodyTo(EbxmlEnvelope.class)
				.setBody().spel("#{body.generateDeliveryFailureNotification(\"Maximum redelivery attempts exhausted\")}")
				.to(ExchangePattern.InOnly, ebxmlAckDestinationUri)
			.end()
			.transacted("PROPAGATION_NOT_SUPPORTED")

			/*
			 * do all handling in a separate route - on retry all logic will be retried
			 * see http://camel.apache.org/how-do-i-retry-processing-a-message-from-a-certain-point-back-or-an-entire-route.html
			 */
			.to(getForwardExpressHandlerUrl())
		.end();
	}

	/**
	 * Route to send an HTTP request/response to spine and wait
	 * for a related asynchronous ack message.
	 * <p>
	 * The thread sending a message to this route will <strong>block</strong> until:
	 * <ul>
	 * <li>the original request-response fails
	 * <li>or a timeout occurs while waiting for the ack
	 * <li>or the ack is received
	 */
	@SuppressWarnings("deprecation")
	private void configureForwardExpressSender() throws Exception {
		from(getForwardExpressHandlerUrl())
			.routeId(getInternalRoutePrefix())
			.errorHandler(noErrorHandler()) // disable error handler (the transaction handler from the top-level caller will be used)
			.doTry()
				.setHeader(ForwardExpressMessageExchange.MESSAGE_TYPE, constant(ForwardExpressMessageExchange.REQUEST_MESSAGE))
				.setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.POST))
		
				// TODO: Work out how to maintain the original request body - but also examine the out response from the HTTP request-response call
				.multicast(AggregationStrategies.useOriginal())
					.bean(inprogressIds, "add(${header.CamelCorrelationId})")
					
					.doTry()
						// TODO: Is a pipeline + checking of out message required here?
						.to(ExchangePattern.InOut, multipartMessageDestinationUri)
					.doCatch(HttpOperationFailedException.class)
						// TODO: check status code + body for SOAPFault and retry behaviour
						.log(LoggingLevel.DEBUG, "HTTP error received: ${exception}")
						.handled(false)
					.endDoTry()
					.end()
				.end()
				.setProperty(ForwardExpressMessageExchange.ACK_FUTURE, method(SettableFuture.class, "create"))
				.to(getForwardExpressAggregatorUrl())
				.process(new ForwardExpressMessageExchange.WaitForAck(aggregatorTimeout + 1000)) // timeout is slightly higher than the corresponding value in the aggregate
				.validate().simple("${body.isComplete()}")
				.setBody().simple("${body.getAckBody()}")
				
				.to(getEbxmlAckProcessorUrl())
			.endDoTry()
			.doFinally()
				.process(new Processor() {
					@Override
					public void process(final Exchange exchange) throws Exception {
						// Mark the ID as no longer in-progress
						inprogressIds.remove(exchange.getIn().getHeader(Exchange.CORRELATION_ID));
					}
				})
			.end()
		.end();
	}
	
	private void configureForwardAckReceiver() throws Exception {
		final String messageIdHeader = "JMSMessageID";
		final String correlationIdHeader = "JMSCorrelationID";
		
		from(ebxmlAckReceiverUri)
			.routeId(getInternalRoutePrefix() + "-ack")
			// multiple threads may be running - only process each incoming ack once
			.idempotentConsumer(header(messageIdHeader), new MemoryIdempotentRepository())
			
			// only handle IDs currently active in this process
			.filter(method(inprogressIds, "contains(${header." + correlationIdHeader + "})"))
			.setHeader(Exchange.CORRELATION_ID, header(correlationIdHeader))
			.setHeader(ForwardExpressMessageExchange.MESSAGE_TYPE, constant(ForwardExpressMessageExchange.ACK_MESSAGE))
			.log("Incoming ebxml ack for ${header.CamelCorrelationId}")
			.to(getForwardExpressAggregatorUrl())
		.end();
	}
	
	/**
	 * Configures a direct route which aggregates incoming request messages with associated asynchronous ebXML acks
	 */
	private void configureForwardExpressMessageAggregator() throws Exception {
		// Correlate original requests with incoming acks
		// acks may be received with no corresponding open request (i.e.
		// the request originated in another process)
		from(getForwardExpressAggregatorUrl())
			.routeId(getInternalRoutePrefix() + "-aggregator")
			.aggregate(header(Exchange.CORRELATION_ID), new ForwardExpressMessageExchange.AggregationStrategy())
				.completionPredicate(method(ForwardExpressMessageExchange.class, "isComplete(${body})"))
				.completionTimeout(aggregatorTimeout)
			.log("Completed forward-express request-response aggregate: ${header.CamelCorrelationId}")
			.bean(ForwardExpressMessageExchange.class, "notifyCompletion(${body})")
		.end();
	}
	
	/**
	 * <p>
	 * From ebMS_v2_0.pdf:
	 * 
	 * 6.5.7 Failed Message Delivery
	 * If a message sent with an AckRequested element cannot be delivered, the MSH or process handling the
	 * message (as in the case of a routing intermediary) SHALL send a delivery failure notification to the From
	 * Party. The delivery failure notification message is an Error Message with <code>errorCode</code> of
	 * <code>DeliveryFailure</code> and a <code>severity</code> of:
	 * <ul>
	 * <li><code>Error</code> if the party who detected the problem could not transmit the message (e.g. the communications
	 * transport was not available)
	 * <li><code>Warning</code> if the message was transmitted, but an Acknowledgment Message was not received. This means
	 * the message probably was not delivered.
	 * 
	 * <p>
	 * From EIS11.6 (2.5.2):
	 * <p>
	 * Should an MHS receive an ebXML ErrorList with a highestSeverity of “Error” it MUST assume that the message in error can not
	 * be re-presented.  That is, the problem MUST be handled by the sender of the message in error.
	 * Should an MHS receive an ebXML ErrorList with a highestSeverity of “Warning” it MAY assume that the error is recoverable
	 * and that the message in error can be re-presented.
	 */
	private void configureEbxmlAckProcessor() throws Exception {
		from(getEbxmlAckProcessorUrl())
			.id(getInternalRoutePrefix() + "ebxml-ack-processor")
			.errorHandler(noErrorHandler()) // disable error handler (the transaction handler from the top-level caller will be used)
			.log(LoggingLevel.DEBUG, LOGGER, "Processing ebXml acknowledgment for ${header.CamelCorrelationId}")
			.convertBodyTo(EbxmlEnvelope.class)
			.choice()
				.when().simple("${body.errorMessage}")
					.pipeline()
						.choice()
							.when().simple("${body.error.warning}")
								.log(LoggingLevel.INFO, LOGGER, "ebXml delivery failure (warning) received - refToMessageId: ${body.messageData.refToMessageId} - will retry (if applicable)")
								.throwException(new Exception("ebXml delivery failure (warning) received - will retry (if applicable)"))
							.endChoice()
							.otherwise()
								.log(LoggingLevel.INFO, LOGGER, "ebXml delivery failure (error) received - refToMessageId: ${body.messageData.refToMessageId} - will not retry")
								.to(ExchangePattern.InOnly, ebxmlAckDestinationUri)
								.stop()
							.endChoice()
						.end()
					.end()
				.endChoice()
				.otherwise()
					.log(LoggingLevel.INFO, LOGGER, "ebXml ack received - refToMessageId: ${body.messageData.refToMessageId}")
					.to(ExchangePattern.InOnly, ebxmlAckDestinationUri)
				.endChoice()
			.end()
		.end();
	}
}
