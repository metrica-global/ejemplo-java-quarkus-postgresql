package com.zonacards.kernel;

/**
 * Authorization check used as the first line of every service method, before validation and
 * before any repository call. Identity, tenant ownership and permission are checked
 * independently: even the tenant wildcard cannot cross the tenant boundary.
 *
 * <p>It does not authenticate credentials and it does not replace tenant-scoped queries.
 */
public final class Authz {

  private Authz() {}

  public static void require(AuthContext auth, TenantId tenantId, String scope) {
    if (auth == null || !auth.isValid()) {
      throw KernelErrors.unauthorized();
    }
    if (tenantId == null
        || tenantId.isEmpty()
        || !tenantId.equals(auth.tenantId())
        || scope == null
        || scope.isBlank()
        || !auth.hasScope(scope)) {
      throw KernelErrors.accessDenied();
    }
  }
}
