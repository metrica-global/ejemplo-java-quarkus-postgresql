package com.zonacards.giftcards.api;

import com.zonacards.giftcards.GiftCardEstado;
import com.zonacards.giftcards.GiftCardRequests.EmitirGiftCardRequest;
import com.zonacards.giftcards.GiftCardRequests.ExtenderVigenciaRequest;
import com.zonacards.giftcards.srv.GiftCardService;
import com.zonacards.kernel.GiftCardId;
import com.zonacards.kernel.LoteId;
import com.zonacards.kernel.Paginated;
import com.zonacards.kernel.PaginationOptions;
import com.zonacards.kernel.Validation;
import com.zonacards.server.CurrentAuth;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/** REST adapter of the giftcards domain. No business rule lives here. */
@Path("/api/v1/giftcards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GiftCardResource {

  private final GiftCardService service;
  private final CurrentAuth auth;

  public GiftCardResource(GiftCardService service, CurrentAuth auth) {
    this.service = service;
    this.auth = auth;
  }

  /**
   * Issues one card. The response is the only place its code and CVV ever appear; it must not be
   * cached, and the caller is responsible for delivering them.
   */
  @POST
  public Response emitir(EmitirGiftCardRequest request) {
    GiftCardService.Emision emision = service.emitir(auth.get(), auth.tenantId(), request);
    GiftCardResponse body = GiftCardResponse.of(emision.card(), emision.codigo(), emision.cvv());
    return Response.created(URI.create("/api/v1/giftcards/" + body.id())).header("Cache-Control", "no-store").entity(body).build();
  }

  @GET
  @Path("{id}")
  public GiftCardResponse obtener(@PathParam("id") String id) {
    return GiftCardResponse.of(service.obtener(auth.get(), auth.tenantId(), giftCardId(id)));
  }

  @GET
  public Paginated<GiftCardResponse> listar(
      @QueryParam("loteId") String loteId,
      @QueryParam("estado") String estado,
      @QueryParam("page") Integer page,
      @QueryParam("pageSize") Integer pageSize) {
    LoteId lote = loteId == null || loteId.isBlank() ? null : LoteId.of(Validation.uuid(loteId, "loteId"));
    return service
        .listar(auth.get(), auth.tenantId(), lote, estado(estado), PaginationOptions.of(page, pageSize))
        .map(GiftCardResponse::of);
  }

  @PUT
  @Path("{id}")
  public GiftCardResponse extenderVigencia(@PathParam("id") String id, ExtenderVigenciaRequest request) {
    return GiftCardResponse.of(service.extenderVigencia(auth.get(), auth.tenantId(), giftCardId(id), request));
  }

  @POST
  @Path("{id}/activar")
  public GiftCardResponse activar(@PathParam("id") String id) {
    return GiftCardResponse.of(service.activar(auth.get(), auth.tenantId(), giftCardId(id)));
  }

  /** Annuls the card. There is no physical delete. */
  @DELETE
  @Path("{id}")
  public GiftCardResponse anular(@PathParam("id") String id, @QueryParam("motivo") String motivo) {
    return GiftCardResponse.of(service.anular(auth.get(), auth.tenantId(), giftCardId(id), motivo));
  }

  private static GiftCardId giftCardId(String raw) {
    return GiftCardId.of(Validation.uuid(raw, "id"));
  }

  private static GiftCardEstado estado(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return GiftCardEstado.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw Validation.invalid("estado", "must be one of GENERADA, ACTIVA, ANULADA");
    }
  }
}
