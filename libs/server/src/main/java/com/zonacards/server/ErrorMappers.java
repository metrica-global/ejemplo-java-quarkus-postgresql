package com.zonacards.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.zonacards.errx.ErrorType;
import com.zonacards.errx.Errx;
import com.zonacards.logx.Logx;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns every failure into the same JSON body. Domain errors carry their own code and status;
 * anything unexpected becomes a 500 with a generic message, and the detail goes to the log, not
 * to the client.
 */
public class ErrorMappers {

  private static final Logx LOG = Logx.get(ErrorMappers.class);

  @ServerExceptionMapper
  public Response domainError(Errx error, ContainerRequestContext request) {
    if (error.httpStatus() >= 500) {
      LOG.withError(error).with("code", error.code()).error("request failed");
    }
    return respond(error.httpStatus(), error.getMessage(), error.code(), error.type().name(), error.details(), request);
  }

  @ServerExceptionMapper
  public Response malformedJson(JsonProcessingException error, ContainerRequestContext request) {
    return invalidBody(request);
  }

  /** Declared separately: Quarkus ships its own mapper for this subtype and the closest type wins. */
  @ServerExceptionMapper
  public Response mismatchedJson(MismatchedInputException error, ContainerRequestContext request) {
    return invalidBody(request);
  }

  @ServerExceptionMapper
  public Response webError(WebApplicationException error, ContainerRequestContext request) {
    int status = error.getResponse().getStatus();
    if (error.getCause() instanceof JsonProcessingException) {
      return invalidBody(request);
    }
    if (status >= 500) {
      LOG.withError(error).error("request failed");
    }
    Response.Status known = Response.Status.fromStatusCode(status);
    String message = known != null ? known.getReasonPhrase() : "Request failed";
    ErrorType type = status == 404 ? ErrorType.NOT_FOUND : status >= 500 ? ErrorType.INTERNAL : ErrorType.VALIDATION;
    return respond(status, message, "HTTP_" + status, type.name(), Map.of(), request);
  }

  @ServerExceptionMapper
  public Response unexpected(Throwable error, ContainerRequestContext request) {
    Errx wrapped = Errx.find(error);
    if (wrapped != null) {
      return domainError(wrapped, request);
    }
    LOG.withError(error).error("unexpected failure");
    return respond(500, "Internal server error", "INTERNAL_ERROR", ErrorType.INTERNAL.name(), Map.of(), request);
  }

  private static Response invalidBody(ContainerRequestContext request) {
    return respond(400, "Invalid request body", "REQUEST_INVALID_BODY", ErrorType.VALIDATION.name(), Map.of(), request);
  }

  private static Response respond(
      int status, String message, String code, String type, Map<String, Object> details, ContainerRequestContext request) {
    Object requestId = request == null ? null : request.getProperty(RequestContextFilters.REQUEST_ID_PROPERTY);
    ErrorBody body = new ErrorBody(message, code, type, status, details, requestId == null ? null : requestId.toString());
    return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
  }
}
