package uk.nhs.ciao.transport.spine.multipart;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;

/**
 * Represents a Multipart body
 * 
 * @see http://www.w3.org/Protocols/rfc1341/7_2_Multipart.html
 */
public class MultipartBody {
	public static final String CONTENT_ID = "Content-Id";
	public static final String CONTENT_TYPE = "Content-Type";
	public static final String CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";
	
	private static final String CRLF = "\r\n";
	private static final String DEFAULT_CONTENT_TRANSFER_ENCODING = "8bit";
	private static final String DEFAULT_BOUNDARY = "--=_MIME-Boundary";
	
	private String boundary;
	private String preamble;
	private final List<Part> parts;
	private String epilogue;
	
	public MultipartBody() {
		boundary = DEFAULT_BOUNDARY;
		preamble = "";
		parts = Lists.newArrayList();
		epilogue = "";
	}
	
	public String getBoundary() {
		return boundary;
	}
	
	public void setBoundary(final String boundary) {
		this.boundary = Preconditions.checkNotNull(boundary);
	}
	
	public String getPreamble() {
		return preamble;
	}
	
	public void setPreamble(final String preamble) {
		this.preamble = Strings.nullToEmpty(preamble);
	}
	
	public List<Part> getParts() {
		return parts;
	}
	
	public Part findPartByContentId(final String contentId) {
		if (contentId == null) {
			return null;
		}
		
		final String rawContentId = ContentId.encodeRawValue(contentId);
		return findPartByRawContentId(rawContentId);
	}
	
	public Part findPartByRawContentId(final String rawContentId) {
		if (rawContentId == null) {
			return null;
		}
		
		for (final Part part: parts) {
			if (rawContentId.equals(part.getRawContentId())) {
				return part;
			}
		}
		
		return null;
	}
	
	public void addPart(final Part part) {
		if (part != null) {
			parts.add(part);
		}
	}
	
	public Part addPart(final String contentType, final Object body) {
		if (body == null) {
			return null;
		}
		
		final Part part = new Part();
		part.setContentId(generateContentId());
		part.setContentType(contentType);
		part.setContentTransferEncoding(DEFAULT_CONTENT_TRANSFER_ENCODING);		
		part.setBody(body);
		parts.add(part);
		
		return part;
	}
	
	public String getEpilogue() {
		return epilogue;
	}
	
	public void setEpilogue(final String epilogue) {
		this.epilogue = Strings.nullToEmpty(epilogue);
	}

	
	public void write(final OutputStream out) throws IOException {
		out.write(preamble.getBytes());
		
		boolean writeCrlfBeforeDelimiter = !preamble.isEmpty();
		for (final Part part: parts) {
			if (writeCrlfBeforeDelimiter) {
				out.write(CRLF.getBytes());
			}

			out.write("--".getBytes());
			out.write(boundary.getBytes());
			out.write(CRLF.getBytes());
			
			part.write(out);
			
			// Only the initial part needs special handling
			// All other parts need CRLF
			writeCrlfBeforeDelimiter = true;
		}
		
		out.write(CRLF.getBytes());
		out.write("--".getBytes());
		out.write(boundary.getBytes());
		out.write("--".getBytes());
		out.write(CRLF.getBytes());
		
		out.write(epilogue.getBytes());
		out.flush();
	}
	
	protected String generateContentId() {
		return UUID.randomUUID().toString();
	}
}
