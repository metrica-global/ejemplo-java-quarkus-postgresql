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
| `make images` | La imagen de la aplicación: una sola, con todos los módulos |
| `make up` / `make down` | Sistema local completo, con todo en el núcleo |
| `make topologia` | Qué despliegue atiende cada módulo |
| `make promover MODULO=giftcards` / `make reintegrar MODULO=giftcards` | Pasa un módulo a servicio propio, o lo devuelve al núcleo, solo con configuración. `ETAPA=expandir\|contraer` ejecuta una etapa |
| `make e2e` | Escenarios de punta a punta contra el sistema local, promoción y reintegración incluidas. Uno o varios: `scripts/e2e.sh saga dlq` |
| `make token` | Token de un usuario de demostración (`USUARIO=ana\|luis\|eva`) |
| `make logs` | Logs de la aplicación (núcleo y servicios propios) |
| `make demo-promocion`, `make demo-escalado`, `make demo-autoescalado` | Ver `docs/autoescalado.md` |
| `make ensayo-terraform` | Ensaya la promoción y la reintegración en el Terraform de `dev`, contra un emulador desechable. Ver `infra/terraform/README.md` |
| `AWS_PROFILE=<perfil> scripts/tf-plan.sh dev` | `terraform plan` de un ambiente contra una cuenta real, con estado local desechable (nunca aplica). `qa` y `prod` necesitan además `-var certificate_arn=<arn>` |

Una prueba concreta:

```bash
scripts/dockerw ./mvnw -B -ntp -pl app -am verify \
  -Dit.test=GeneracionSagaIT -Dfailsafe.failIfNoSpecifiedTests=false
```

## Tipos de prueba

| Sufijo | Corre con | Ejemplos |
|---|---|---|
| `*Test` | surefire, sin Docker | Agregados, casos de uso con repositorios falsos, autorización antes del repositorio, criptografía, reglas de arquitectura, qué módulos atiende un despliegue |
| `*IT` | failsafe, con Testcontainers | Repositorios y saga contra PostgreSQL 18, *outbox* e *inbox*, adaptador SNS/SQS contra el emulador, API con JWT firmados |

Las pruebas de integración arrancan la aplicación en tres configuraciones, que son las tres
formas en que se despliega:

| Perfil de prueba | `ZC_MODULES` | Qué prueba |
|---|---|---|
| `LotesTestResource.OnlyLotes` | `lotes` | La API y los eventos de `lotes`; y que `giftcards`, no atendido, responde `404 MODULE_NOT_SERVED`, no arranca sus bucles y no necesita credenciales |
| `GiftcardsTestResource.OnlyGiftcards` | `giftcards` | Lo mismo desde el otro lado, más la saga de generación |
| `AppTestResource.EveryModule` | `lotes,giftcards` | La saga de punta a punta con los dos módulos en un proceso, la salud y las métricas por módulo, y que compartir proceso no comparte la base |

Las pruebas de API firman tokens reales con una llave generada para la corrida; la aplicación
los verifica como verificaría los de Keycloak. El sistema local usa Keycloak de verdad.

## Agregar un caso de uso

1. La regla va en el agregado (`Lote`, `GiftCard`), con su prueba unitaria.
2. Si necesita un permiso nuevo, se declara en `<Módulo>Scopes`.
3. El método en `srv/<Módulo>Service`: `Authz.require(...)` en la primera línea, luego
   `validate()` del *request*, luego `uow.inTx(...)`.
4. Se añade el caso a la prueba `*AuthzTest`: demuestra que sin permiso no se toca nada.
5. El punto de entrada en `api/<Módulo>Resource`, que solo convierte tipos.

## Agregar un evento

1. Declarar el tipo y su *record* en `events/` del módulo que lo publica. Nombre:
   `zonacards.<módulo>.<hecho-en-pasado>.v1`.
2. Añadir un ejemplo en `docs/eventos/ejemplos/<tipo>.json` y describirlo en
   `docs/eventos/asyncapi.yaml`.
3. Publicarlo dentro de la transacción del caso de uso: `outbox.publish(TOPIC, events.create(...))`.
   En la prueba del productor, `ContractExamples.assertMatches(tipo, json)`.
4. En el módulo que lo consume: declarar **su propia vista** del evento en su paquete `events/`
   (solo los campos que usa), registrar el manejador y probarlo con el mismo ejemplo
   (`ContractExamples.instance(...)`). No se comparte código entre módulos, aunque estén en el
   mismo artefacto: ArchUnit lo impide.
5. Añadir el tipo al filtro de la cola del consumidor en
   `infra/terraform/modules/messaging/topology/outputs.tf`. Vale para AWS y para el entorno local.

Un cambio incompatible no modifica `v1`: se publica `v2` en paralelo hasta que no queden
consumidores de `v1`.

## Agregar un módulo

Un módulo nuevo nace **dentro del núcleo**. No necesita servicio, pipeline ni imagen propios;
si algún día necesita escalar por su cuenta, se promueve.

En la aplicación:

1. Su paquete `com.zonacards.<nuevo>` en `app/`, con la forma de los demás, y su
   `container/<Nuevo>Container` (constantes `MODULE` y `API`, comprobación previa, adaptadores,
   casos de uso, bucles).
2. **Una línea** en `com.zonacards.app.AppContainer`. `AppArchitectureTest` falla si un módulo
   existe en el código y no está en esa lista.
3. Un `ArchitectureTest` con `HexagonRules.check("com.zonacards.<nuevo>", <los demás>)`, y el
   módulo nuevo en la lista de "los demás" de los otros.
4. En `application.properties`: su *datasource* (`quarkus.datasource.<nuevo>.*`, con
   `active=${zonacards.module.<nuevo>.active}`), su Flyway (`quarkus.flyway.<nuevo>.*` y las
   credenciales de dueño bajo `%migrate`), sus topics y colas, y su nombre en las listas de
   módulos de los perfiles de desarrollo, prueba y migración.
5. Sus migraciones en `app/src/main/resources/db/<nuevo>/`, incluida la de las tablas de
   `eventx`.

En la infraestructura:

6. Su esquema y sus roles: una llamada más en `deploy/local/postgres/10-bootstrap-services.sh`
   y una entrada en la variable `modules` de cada ambiente de Terraform (tamaño del *pool* y
   límite de conexiones).
7. Su topic y sus colas en `infra/terraform/modules/messaging/topology/outputs.tf`.
8. **Una entrada en el catálogo** (`infra/terraform/modules/platform/catalog.tf`): rutas del
   balanceador, variables de sus topics y colas, y sus secretos. Con eso el núcleo recibe su
   configuración y sus permisos, existe su regla en el balanceador y el módulo ya **se puede**
   promover agregándolo a `promoted_modules`.
9. En local: su nombre en `MODULOS` (`scripts/lib.sh`) y sus variables en
   `docker-compose.yml`; para poder promoverlo en local, además, un servicio como el de
   `giftcards`. La ruta del gateway se genera sola.

No hay que tocar el `Dockerfile`, los workflows ni las variables de GitHub: la imagen es la
misma y el despliegue lee de Terraform qué servicios existen.

## Convenciones

- Lenguaje del dominio en español (`Lote`, `anular`, `vigenciaHasta`); términos técnicos en
  inglés. Comentarios de código en inglés; documentación en español.
- `get` falla si no existe, `find` devuelve opcional, `list` pagina.
- Los errores salen de `<Módulo>Errors`; ningún caso de uso lanza excepciones genéricas.
- Toda configuración que cambia entre ambientes o entre despliegues es una variable `ZC_*`.
  La que es de un módulo lleva su nombre (`ZC_DB_LOTES_POOL_MAX`, `ZC_GIFTCARDS_…`): un
  despliegue solo recibe las de los módulos que atiende.
- "Módulo" es una parte de la aplicación; "servicio" es un despliegue en ECS (el núcleo o el de
  un módulo promovido). No son sinónimos.

## Problemas frecuentes

| Síntoma | Causa |
|---|---|
| `make up` falla al construir con un *builder* de buildx distinto del predeterminado | El `Makefile` fija `BUILDX_BUILDER=default`; con `docker compose` a mano hay que exportarlo |
| Un puerto local está ocupado | `ZC_PORT_API`, `ZC_PORT_KEYCLOAK`, `ZC_PORT_GRAFANA`, `ZC_PORT_POSTGRES`, `ZC_PORT_FLOCI` |
| `docker compose up` pide `ZC_GIFTCARDS_CODIGO_KEY` | Falta `.env`: lo genera `make up` (`scripts/local-env.sh`) |
| La primera vez `make up` tarda | Descarga dependencias de Maven y el proveedor de Terraform; después quedan en caché |
| La aplicación no arranca: `ZC_MODULES is not set` | Fuera de desarrollo y pruebas un despliegue debe decir qué módulos atiende |
| La aplicación no arranca: `module '…' cannot be served by this deployment` | Al despliegue le falta algo de ese módulo (una cola, un topic, una llave); el mensaje los lista todos |
| Una demo dice que la topología está a medio cambiar, o `make promover` que no hay nada que expandir | Una promoción anterior quedó en su primera etapa: `make topologia` muestra el estado. Se termina con `ETAPA=contraer`, o se empieza de cero con `make down` y `make up` |
