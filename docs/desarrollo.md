# Desarrollo

## Requisitos

Docker con Compose, `make`, `curl` y `jq`. El build corre dentro de un contenedor con JDK 25
(`scripts/dockerw`), con la caché de Maven en un volumen de Docker. Con un JDK 25 local se
puede prescindir del contenedor añadiendo `RUN=` a cualquier comando de `make`.

## Comandos

| Comando | Qué hace |
|---|---|
| `make verify` | Todo: compilar, pruebas unitarias, de integración (levantan PostgreSQL 18 y el emulador SNS/SQS con Testcontainers), arquitectura y cobertura. Es lo que corre CI |
| `make test` | Solo unitarias y de arquitectura; no levanta dependencias |
| `make up` / `make down` | Sistema local completo |
| `make e2e` | Escenarios de punta a punta contra el sistema local. Uno o varios: `scripts/e2e.sh saga dlq` |
| `make token` | Token de un usuario de demostración (`USUARIO=ana\|luis\|eva`) |
| `make logs` | Logs de los dos servicios |
| `make demo-escalado`, `make demo-autoescalado` | Ver `docs/autoescalado.md` |
| `AWS_PROFILE=<perfil> scripts/tf-plan.sh dev` | `terraform plan` de un ambiente contra una cuenta real, con estado local desechable (nunca aplica). `qa` y `prod` necesitan además `-var certificate_arn=<arn>` |

Una prueba concreta:

```bash
scripts/dockerw ./mvnw -B -ntp -pl services/giftcards-service -am verify \
  -Dit.test=GeneracionSagaIT -Dfailsafe.failIfNoSpecifiedTests=false
```

## Tipos de prueba

| Sufijo | Corre con | Ejemplos |
|---|---|---|
| `*Test` | surefire, sin Docker | Agregados, casos de uso con repositorios falsos, autorización antes del repositorio, criptografía, reglas de arquitectura |
| `*IT` | failsafe, con Testcontainers | Repositorios y saga contra PostgreSQL 18, *outbox* e *inbox*, adaptador SNS/SQS contra el emulador, API con JWT firmados |

Las pruebas de API firman tokens reales con una llave generada para la corrida; la aplicación
los verifica como verificaría los de Keycloak. El sistema local usa Keycloak de verdad.

## Agregar un caso de uso

1. La regla va en el agregado (`Lote`, `GiftCard`), con su prueba unitaria.
2. Si necesita un permiso nuevo, se declara en `<Dominio>Scopes`.
3. El método en `srv/<Dominio>Service`: `Authz.require(...)` en la primera línea, luego
   `validate()` del *request*, luego `uow.inTx(...)`.
4. Se añade el caso a la prueba `*AuthzTest`: demuestra que sin permiso no se toca nada.
5. El punto de entrada en `api/<Dominio>Resource`, que solo convierte tipos.

## Agregar un evento

1. Declarar el tipo y su *record* en `events/` del dominio que lo publica. Nombre:
   `zonacards.<dominio>.<hecho-en-pasado>.v1`.
2. Añadir un ejemplo en `docs/eventos/ejemplos/<tipo>.json` y describirlo en
   `docs/eventos/asyncapi.yaml`.
3. Publicarlo dentro de la transacción del caso de uso: `outbox.publish(TOPIC, events.create(...))`.
   En la prueba del productor, `ContractExamples.assertMatches(tipo, json)`.
4. En el dominio que lo consume: declarar **su propia vista** del evento en su paquete `events/`
   (solo los campos que usa), registrar el manejador y probarlo con el mismo ejemplo
   (`ContractExamples.instance(...)`). No se comparte código entre dominios.
5. Añadir el tipo al filtro de la cola del consumidor en
   `infra/terraform/modules/messaging/topology/outputs.tf`. Vale para AWS y para el entorno local.

Un cambio incompatible no modifica `v1`: se publica `v2` en paralelo hasta que no queden
consumidores de `v1`.

## Agregar un dominio

1. Copiar la estructura de `services/lotes-service` y añadir el módulo al `pom.xml` raíz.
2. Añadir un `ArchitectureTest` con `HexagonRules.check("com.zonacards.<nuevo>", <los demás>)`.
3. Sus migraciones en `src/main/resources/db/migration`, incluida la de las tablas de `eventx`.
4. Su esquema y roles: una llamada más en `deploy/local/postgres/10-bootstrap-services.sh` y una
   entrada más en `modules/platform` de Terraform.
5. Su topic y su cola en la topología; su bloque en `modules/platform/services.tf`, con o sin
   `autoscaling`.
6. Su servicio en `docker-compose.yml` y su ruta en `deploy/local/gateway/nginx.conf`.

## Convenciones

- Lenguaje del dominio en español (`Lote`, `anular`, `vigenciaHasta`); términos técnicos en
  inglés. Comentarios de código en inglés; documentación en español.
- `get` falla si no existe, `find` devuelve opcional, `list` pagina.
- Los errores salen de `<Dominio>Errors`; ningún caso de uso lanza excepciones genéricas.
- Toda configuración que cambia entre ambientes es una variable `ZC_*`.

## Problemas frecuentes

| Síntoma | Causa |
|---|---|
| `make up` falla al construir con un *builder* de buildx distinto del predeterminado | El `Makefile` fija `BUILDX_BUILDER=default`; con `docker compose` a mano hay que exportarlo |
| Un puerto local está ocupado | `ZC_PORT_API`, `ZC_PORT_KEYCLOAK`, `ZC_PORT_GRAFANA`, `ZC_PORT_POSTGRES`, `ZC_PORT_FLOCI` |
| `docker compose up` pide `ZC_GIFTCARDS_CODIGO_KEY` | Falta `.env`: lo genera `make up` (`scripts/local-env.sh`) |
| La primera vez `make up` tarda | Descarga dependencias de Maven y el proveedor de Terraform; después quedan en caché |
