package com.zonacards.server;

import com.zonacards.eventx.CorrelationContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.MDC;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Gives every request an id: taken from {@code X-Request-ID} when the caller sends a sane one,
 * generated otherwise. The id goes to the response header, to every log line of the request and,
 * as the correlation id, to every event the request produces.
 */
public class RequestContextFilters {

  public static final String HEADER = "X-Request-ID";
  public static final String REQUEST_ID_PROPERTY = "zonacards.request_id";
  private static final String MDC_KEY = "request_id";
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  @ServerRequestFilter
  public void assignRequestId(ContainerRequestContext request) {
    String incoming = request.getHeaderString(HEADER);
    String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    request.setProperty(REQUEST_ID_PROPERTY, id);
    MDC.put(MDC_KEY, id);
    CorrelationContext.set(id);
  }

  @ServerResponseFilter
  public void exposeRequestId(ContainerRequestContext request, ContainerResponseContext response) {
    Object id = request.getProperty(REQUEST_ID_PROPERTY);
    if (id != null) {
      response.getHeaders().putSingle(HEADER, id.toString());
    }
    MDC.remove(MDC_KEY);
    CorrelationContext.set(null);
  }
}
