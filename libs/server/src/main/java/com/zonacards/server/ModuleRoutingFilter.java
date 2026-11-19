package com.zonacards.server;

import com.zonacards.errx.ErrorType;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Answers 404 for the API of a module this deployment does not serve. The load balancer already
 * sends each path to a deployment that serves it; this is what a request gets if it arrives at
 * the wrong one anyway. It runs before the resource is created, so nothing of that module is
 * assembled.
 */
public class ModuleRoutingFilter {

  private final ActiveModules modules;

  public ModuleRoutingFilter(ActiveModules modules) {
    this.modules = modules;
  }

  // After RequestContextFilters, so the response carries the request id.
  @ServerRequestFilter(priority = Priorities.USER + 100)
  public Response onlyServedModules(ContainerRequestContext request) {
    AppModule owner = modules.ownerNotServed(request.getUriInfo().getPath());
    if (owner == null) {
      return null;
    }
    Object requestId = request.getProperty(RequestContextFilters.REQUEST_ID_PROPERTY);
    ErrorBody body =
        new ErrorBody(
            "Module not served by this deployment",
            "MODULE_NOT_SERVED",
            ErrorType.NOT_FOUND.name(),
            404,
            Map.of("module", owner.name()),
            requestId == null ? null : requestId.toString());
    return Response.status(404).type(MediaType.APPLICATION_JSON).entity(body).build();
  }
}
