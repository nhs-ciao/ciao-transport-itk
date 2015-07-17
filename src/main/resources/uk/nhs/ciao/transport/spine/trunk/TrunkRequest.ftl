--${body.mimeBoundary}
Content-Id: ${body.ebxmlContentId}
Content-Type: text/xml
Content-Transfer-Encoding: 8bit

<?xml version="1.0" encoding="UTF-8"?>
<SOAP:Envelope xmlns:xsi="http://www.w3c.org/2001/XML-Schema-Instance" xmlns:SOAP="http://schemas.xmlsoap.org/soap/envelope/" xmlns:eb="http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd" xmlns:hl7ebxml="urn:hl7-org:transport/ebxml/DSTUv1.0" xmlns:xlink="http://www.w3.org/1999/xlink">
	<SOAP:Header>
		<eb:MessageHeader SOAP:mustUnderstand="1" eb:version="2.0">
			<eb:From>
				<eb:PartyId eb:type="urn:nhs:names:partyType:ocs+serviceInstance">${body.senderPartyId}</eb:PartyId>
			</eb:From>
			<eb:To>
				<eb:PartyId eb:type="urn:nhs:names:partyType:ocs+serviceInstance">${body.receiverPartyId}</eb:PartyId>
			</eb:To>
			<eb:CPAId>${body.receiverCPAId}</eb:CPAId>
			<eb:ConversationId>${body.ebxmlCorrelationId}</eb:ConversationId>
			<eb:Service>urn:nhs:names:services:itk</eb:Service>
			<eb:Action>${body.interactionId}</eb:Action>
			<eb:MessageData>
				<eb:MessageId>${body.ebxmlCorrelationId}</eb:MessageId>
				<eb:Timestamp>${body.ebxmlTimestamp}</eb:Timestamp>
			</eb:MessageData>
			<eb:DuplicateElimination/>
		</eb:MessageHeader>
		<eb:AckRequested eb:version="2.0" SOAP:mustUnderstand="1" SOAP:actor="urn:oasis:names:tc:ebxml-msg:actor:toPartyMSH" eb:signed="false"/>
	</SOAP:Header>
	<SOAP:Body>
		<eb:Manifest eb:version="2.0">
			<eb:Reference xlink:href="cid:${body.hl7ContentId}">
				<eb:Schema eb:location="http://www.nhsia.nhs.uk/schemas/HL7-Message.xsd" eb:version="1.0"/>
				<eb:Description xml:lang="en">HL7 payload</eb:Description>
				<hl7ebxml:Payload style="HL7" encoding="XML" version="3.0"/>
			</eb:Reference>
			<eb:Reference xlink:href="cid:${body.itkContentId}">
				<eb:Description xml:lang="en">ITK Trunk Message</eb:Description>
			</eb:Reference>
		</eb:Manifest>
	</SOAP:Body>
</SOAP:Envelope>

--${body.mimeBoundary}
Content-Id: <${body.hl7ContentId}>
Content-Type: application/xml; charset=UTF-8
Content-Transfer-Encoding: 8bit

<?xml version="1.0" encoding="UTF-8"?>
<${body.interactionId} xmlns="urn:hl7-org:v3">
   <id root="${body.hl7RootId}"/>
   <creationTime value="${body.hl7CreationTime}"/>
   <versionCode code="V3NPfIT4.2.00"/>
   <interactionId extension="${body.interactionId}" root="2.16.840.1.113883.2.1.3.2.4.12"/>
   <processingCode code="P"/>
   <processingModeCode code="T"/>
   <acceptAckCode code="NE"/>
   <communicationFunctionRcv>
      <device classCode="DEV" determinerCode="INSTANCE">
         <id extension="${body.receiverAsid}" root="1.2.826.0.1285.0.2.0.107"/>
      </device>
   </communicationFunctionRcv>
   <communicationFunctionSnd>
      <device classCode="DEV" determinerCode="INSTANCE">
         <id extension="${body.senderAsid}" root="1.2.826.0.1285.0.2.0.107"/>
      </device>
   </communicationFunctionSnd>
   
   <ControlActEvent classCode="CACT" moodCode="EVN">
      <author1 typeCode="AUT">
         <AgentSystemSDS classCode="AGNT">
            <agentSystemSDS classCode="DEV" determinerCode="INSTANCE">
               <id extension="${body.senderAsid}" root="1.2.826.0.1285.0.2.0.107"/>
            </agentSystemSDS>
         </AgentSystemSDS>
      </author1>
	</ControlActEvent>
</${body.interactionId}>

--${body.mimeBoundary}
Content-Id: <${body.itkContentId}>
Content-Type: text/xml
Content-Transfer-Encoding: 8bit


<itk:DistributionEnvelope xmlns:itk="urn:nhs-itk:ns:201005" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
	<itk:header service="urn:nhs-itk:services:201005:SendCDADocument-v2-0" trackingid="${body.itkCorrelationId}">
		<itk:addresslist>
			<itk:address uri="urn:nhs-uk:addressing:ods:${body.receiverODSCode}"/>
		</itk:addresslist>
		<itk:auditIdentity>
			<itk:id uri="urn:nhs-uk:identity:ods:${body.auditODSCode}"/>
		</itk:auditIdentity>
		<itk:manifest count="1">
			<itk:manifestitem mimetype="text/xml" id="uuid_${body.itkDocumentId}" profileid="${body.itkProfileId}"/>
		</itk:manifest>
		<itk:senderAddress uri="urn:nhs-uk:addressing:${body.senderODSCode}"/>
		<itk:handlingSpecification>
			<itk:spec value="true" key="urn:nhs-itk:ns:201005:ackrequested"/>
			<itk:spec value="${body.itkHandlingSpec}" key="urn:nhs-itk:ns:201005:interaction"/>
		</itk:handlingSpecification>
	</itk:header>
	<itk:payloads count="1">
		<itk:payload id="uuid_${body.itkDocumentId}">${body.itkDocumentBody}</itk:payload>
	</itk:payloads>
</itk:DistributionEnvelope>

--${body.mimeBoundary}--
