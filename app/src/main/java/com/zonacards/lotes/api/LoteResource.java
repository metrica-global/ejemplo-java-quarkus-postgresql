package com.zonacards.lotes.api;

import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.Validation;
import com.zonacards.lotes.LoteEstado;
import com.zonacards.lotes.LoteRequests.ActualizarLoteRequest;
import com.zonacards.lotes.LoteRequests.CrearLoteRequest;
import com.zonacards.lotes.srv.LoteService;
import com.zonacards.server.CurrentAuth;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * REST adapter of the lotes domain. It translates HTTP into a call to the service and back:
 * ids are parsed here, the caller's identity is taken from the verified token, and there is no
 * business rule in this class.
 */
@Path("/api/v1/lotes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LoteResource {

  private final LoteService service;
  private final CurrentAuth auth;

  public LoteResource(LoteService service, CurrentAuth auth) {
    this.service = service;
    this.auth = auth;
  }

  /**
   * 201 with the lote in SOLICITADO; generation continues in the background. A repeated request
   * with the same {@code Idempotency-Key} returns 200 and the original lote.
   */
  @POST
  public Response crear(@HeaderParam("Idempotency-Key") String idempotencyKey, CrearLoteRequest request) {
    LoteService.Creacion creacion = service.crear(auth.get(), auth.tenantId(), request, idempotencyKey);
    LoteResponse body = LoteResponse.of(creacion.lote());
    if (!creacion.nuevo()) {
      return Response.ok(body).build();
    }
    return Response.created(URI.create("/api/v1/lotes/" + body.id())).entity(body).build();
  }

  @GET
  @Path("{id}")
  public LoteResponse obtener(@PathParam("id") String id) {
    return LoteResponse.of(service.obtener(auth.get(), auth.tenantId(), loteId(id)));
  }

  @GET
  public Paginated<LoteResponse> listar(
      @QueryParam("estado") String estado, @QueryParam("page") Integer page, @QueryParam("pageSize") Integer pageSize) {
    return service
        .listar(auth.get(), auth.tenantId(), estado(estado), PaginationOptions.of(page, pageSize))
        .map(LoteResponse::of);
  }

  @PUT
  @Path("{id}")
  public LoteResponse actualizar(@PathParam("id") String id, ActualizarLoteRequest request) {
    return LoteResponse.of(service.actualizar(auth.get(), auth.tenantId(), loteId(id), request));
  }

  /** Annuls the lote. There is no physical delete: annulment is a business fact with consequences. */
  @DELETE
  @Path("{id}")
  public LoteResponse anular(@PathParam("id") String id, @QueryParam("motivo") String motivo) {
    return LoteResponse.of(service.anular(auth.get(), auth.tenantId(), loteId(id), motivo));
  }

  private static LoteId loteId(String raw) {
    return LoteId.of(Validation.uuid(raw, "id"));
  }

  private static LoteEstado estado(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LoteEstado.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw Validation.invalid("estado", "must be one of SOLICITADO, EN_GENERACION, GENERADO, ANULADO, FALLIDO");
    }
  }
}
