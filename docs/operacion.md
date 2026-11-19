# Operación

Guía para quien opera la aplicación: qué mirar, qué significa y qué hacer. Los ejemplos de
`aws` usan los nombres que crea Terraform (`zonacards-<ambiente>-…`); en el entorno local las
mismas colas se llaman `zonacards-local-…` y se inspeccionan con `scripts/lib.sh`.

Dos palabras que aquí no son sinónimos: un **módulo** es una parte de la aplicación (`lotes`,
`giftcards`); un **servicio** es un despliegue en ECS. Siempre existe el servicio `nucleo`, que
atiende todos los módulos que no tienen servicio propio.

## Qué corre dónde

```bash
# AWS: qué servicios tiene el ambiente y qué módulos atiende cada uno
aws ssm get-parameter --name /zonacards/<ambiente>/deploy --query Parameter.Value --output text | jq .services
# AWS: qué versión está desplegada
aws ssm get-parameter --name /zonacards/<ambiente>/release --query Parameter.Value --output text
# Local
make topologia
```

El primer parámetro dice lo que Terraform declaró. Lo que una tarea **está** atendiendo lo dice
ella misma: al arrancar escribe `modules started` con los módulos atendidos y los que no
(`modules` y `not_served`), y su *liveness* lista los bucles de cada módulo. Los dos pueden
diferir entre un `apply` y el redespliegue que lo sigue; ver
[Promover un módulo](#promover-un-módulo-a-servicio-propio).

## Qué mirar

| Señal | Dónde | Normal | Qué indica si no |
|---|---|---|---|
| `GET /q/health/live` | cada tarea (lo consultan el balanceador y ECS) | `UP`, con `<módulo>/outbox-relay` y cada `<módulo>/consumer-<cola>` en `alive`, para cada módulo que la tarea atiende | Un bucle lleva más de 90 s sin dar señal, o un módulo atendido no está corriendo: ECS reemplaza la tarea |
| `GET /q/health/ready` | cada tarea | `UP` | Informativo (incluye la base de los módulos atendidos). No reinicia tareas a propósito: una caída breve de la base no debe reiniciarlas todas |
| `zonacards_outbox_pending{module=…}` | `/q/metrics` | cerca de 0 | Eventos escritos y aún no publicados: el broker no responde o el *relay* de ese módulo está detenido |
| `zonacards_outbox_oldest_pending_seconds{module=…}` | `/q/metrics` | segundos | Minutos: los eventos de ese módulo no están saliendo |
| `zonacards_outbox_parked{module=…}` | `/q/metrics` | 0 | El broker rechazó un mensaje de forma permanente; requiere revisión manual |
| `zonacards_messages_consumed_seconds_count{outcome=…}` | `/q/metrics` | `handled`, algún `duplicate` | `failed` sostenido: un manejador está fallando |
| Mensajes visibles en `…-giftcards-generacion` | CloudWatch / SQS | sube con un lote y baja | No baja: quien atiende `giftcards` no procesa, o su servicio está en el máximo de tareas |
| Latencia de la API de `lotes` mientras se genera un lote | balanceador / trazas | milisegundos | Sube con cada lote grande: `giftcards` comparte CPU con `lotes` en el núcleo. Es la señal para promoverlo |
| Mensajes en cualquier `…-dlq` | CloudWatch (alarma) | 0 | Un mensaje agotó sus reintentos |
| Antigüedad del mensaje más viejo | CloudWatch (alarma) | menor al umbral de la cola | El consumidor no da abasto o está caído |
| `404` con código `MODULE_NOT_SERVED` | respuestas de la API | ninguno | La ruta de un módulo llega a un servicio que no lo atiende: una promoción a medio hacer o un paso saltado |

Cada línea de log es JSON con `request_id`, `tenant_id`, `traceId` y los campos del caso
(`lote_id`, `fragmento`, `queue`, `message_id`, `receive_count`). Una traza une la petición HTTP
en `lotes` con el consumo en `giftcards`, estén en el mismo servicio o en dos; cada servicio se
identifica en las trazas con su propio nombre (`OTEL_SERVICE_NAME`).

## Un mensaje llegó a la DLQ

Un mensaje pasa a la DLQ de su cola cuando su manejador falló `maxReceiveCount` veces (5),
con espera creciente entre intentos. Mientras tanto el resto de la cola sigue fluyendo.

1. **Identificar.** El log del consumidor tiene una entrada `message handling failed; it will be
   redelivered` por intento, con `message_id`, `type`, `receive_count` y la excepción.
2. **Decidir.**
   - *Defecto o dependencia caída* (permiso faltante, error de código, base inaccesible):
     corregir y desplegar, y luego reenviar.
   - *Mensaje que nunca podrá procesarse* (contrato roto por el productor): conservarlo como
     evidencia y eliminarlo de la DLQ tras acordar la corrección con el dominio que lo emitió.
3. **Reenviar (*redrive*).** Devuelve los mensajes a su cola de origen:

   ```bash
   aws sqs start-message-move-task \
     --source-arn arn:aws:sqs:<region>:<cuenta>:zonacards-<ambiente>-giftcards-inbox-dlq
   ```

Reenviar es seguro aunque hayan pasado días: los manejadores son idempotentes sobre los datos,
no solo sobre el registro de mensajes vistos. `make e2e dlq` ejecuta este procedimiento
completo: introduce un defecto, espera a que el mensaje llegue a la DLQ, lo corrige, reenvía y
comprueba que el lote termina.

## Eventos que no salen (outbox)

Síntoma: `zonacards_outbox_pending` crece o `zonacards_outbox_oldest_pending_seconds` supera
unos minutos.

```sql
-- Con el rol del módulo, en su esquema (lotes o giftcards)
SELECT destination, message_type, count(*), min(created_at) AS mas_antiguo, max(attempts) AS intentos
FROM outbox WHERE published_at IS NULL AND parked_at IS NULL
GROUP BY 1, 2 ORDER BY 4;

SELECT id, destination, message_type, attempts, last_error, parked_at
FROM outbox WHERE parked_at IS NOT NULL ORDER BY parked_at DESC;
```

- **Pendientes con `last_error`**: el *relay* intenta y el broker falla. Revisar permisos IAM
  del rol de la tarea sobre el topic o la cola, la llave KMS y el estado de SNS/SQS. Se
  publican solos cuando la causa desaparece.
- **Pendientes sin intentos**: el *relay* de ese módulo no corre. Revisar qué servicio atiende
  el módulo (`ZC_MODULES`), `ZC_RELAY_ENABLED` y el *liveness*.
- **Apartados (`parked_at`)**: el broker rechazó el mensaje de forma permanente (por ejemplo,
  demasiado grande o destino inexistente). No se reintentan solos. Tras corregir la causa:

  ```sql
  UPDATE outbox SET parked_at = NULL, attempts = 0, available_at = now(), last_error = NULL
  WHERE id = '<id>';
  ```

  Es una escritura manual con el rol del módulo: debe quedar registrada como intervención.

Las filas publicadas se purgan pasado su periodo de retención; el *inbox*, igual.

## Un lote no avanza

```sql
-- esquema lotes
SELECT id, estado, fragmentos_completados, fragmentos_totales, generadas, cantidad, actualizado_en
FROM lote WHERE estado IN ('SOLICITADO', 'EN_GENERACION') AND actualizado_en < now() - interval '30 minutes';

-- esquema giftcards: qué fragmentos faltan
SELECT estado, count(*) FROM plan_fragmento WHERE lote_id = '<id>' GROUP BY 1;
```

| Estado del lote | Qué mirar |
|---|---|
| `SOLICITADO` y sin plan en `giftcards` | El evento `lote-solicitado` no llegó o falla: outbox de `lotes`, cola `giftcards-inbox` y su DLQ |
| `EN_GENERACION` con fragmentos `PENDIENTE` | Cola `giftcards-generacion`: si tiene mensajes, falta capacidad o nadie está atendiendo `giftcards`; si está vacía, revisar su DLQ |
| `EN_GENERACION` con todos los fragmentos `GENERADO` | Los eventos `fragmento-generado` no llegaron: outbox de `giftcards`, cola `lotes-inbox` y su DLQ |
| `FALLIDO` | Un fragmento falló de forma terminal; el motivo está en el lote. Anular el lote y solicitar otro |

Un lote atascado nunca se corrige editando su estado: se corrige la causa y los mensajes
pendientes lo completan.

## Despliegue

El orden lo ejecuta el pipeline (`docs/cicd.md`), y es el mismo a mano:

1. Leer qué servicios tiene el ambiente (`/zonacards/<ambiente>/deploy`).
2. Registrar una revisión de la *task definition* de migración y de la de cada servicio con la
   imagen nueva.
3. Ejecutar la tarea `…-nucleo-migrate` y comprobar que termina con código 0. Migra el esquema
   de **todos** los módulos y es la única que tiene las credenciales de los dueños.
4. Actualizar los servicios **uno por uno**: primero los de módulos promovidos, al final el
   núcleo. ECS hace un despliegue *rolling*; si las tareas nuevas no quedan sanas, el *circuit
   breaker* vuelve a la revisión anterior y los servicios que faltaban no se tocan.
5. Anotar la etiqueta en `/zonacards/<ambiente>/release`.

Las migraciones y los eventos deben ser compatibles con la versión anterior de la aplicación,
porque durante el despliegue conviven ambas, dentro de un servicio y entre servicios (agregar
columnas con valor por defecto; retirar en una versión posterior).

**Volver atrás**: desplegar la etiqueta de imagen anterior con el mismo procedimiento. Las
imágenes son inmutables y se identifican por el SHA del commit. La aplicación vuelve atrás
entera: todos los módulos corren la misma versión.

## Promover un módulo a servicio propio

Cuándo: el módulo necesita más capacidad que el resto, o su carga perjudica a los demás módulos
del núcleo. No hay desarrollo, ni compilación, ni versión nueva: la imagen es la misma.

Son **dos cambios de infraestructura y un redespliegue**. Nunca uno solo: en un solo paso el
balanceador cambiaría de destino antes de que el servicio nuevo tuviera tareas sanas.

| | Qué se hace | Cómo queda | Comprobar antes de seguir |
|---|---|---|---|
| 1 | `terraform apply` con el módulo en `promoted_modules` y `transition = "traffic-in-nucleo"` | Existe el servicio propio, con la versión desplegada. El núcleo y él atienden el módulo; la API sigue yendo al núcleo | Que el servicio nuevo está estable: `aws ecs wait services-stable`. Terraform no lo espera |
| 2 | Quitar `transition` y `terraform apply` | La API del módulo va al servicio propio. El núcleo aún lo atiende (sus tareas no han cambiado) | Que la API responde por el servicio propio |
| 3 | Redesplegar la versión actual (`deploy.yml` manual con la etiqueta de `/zonacards/<ambiente>/release`) | El núcleo arranca sin el módulo | `modules started` del núcleo ya no lista el módulo |

Cualquiera de los estados intermedios es seguro para quedarse en él. Mientras dos servicios
atienden el módulo no se pierde ni se duplica nada: compiten por las mismas colas, los
manejadores son idempotentes y el *relay* usa un *lease*.

En un ambiente real el cambio de `promoted_modules` se hace en `terraform.tfvars`, en un
*pull request* con su plan revisado, un paso por *pull request*. El archivo
`promocion-giftcards.tfvars` de cada ambiente es el ejemplo listo para copiar.

## Devolver un módulo al núcleo

Son **tres cambios y un redespliegue**. Hay un paso más porque retirar un servicio lo destruye
de inmediato, y eso no puede ocurrir en el mismo cambio que le quita el tráfico.

| | Qué se hace | Cómo queda | Comprobar antes de seguir |
|---|---|---|---|
| 1 | `transition = "traffic-in-service"`, `terraform apply` y redesplegar la versión actual | El núcleo vuelve a atender el módulo; la API sigue en el servicio propio | Que el núcleo ya corre la revisión nueva: su `modules started` lista el módulo |
| 2 | `transition = "traffic-in-nucleo"`, `terraform apply` | La API vuelve al núcleo. El servicio propio sigue ahí, por si hay que deshacer | Que la API responde por el núcleo |
| 3 | Quitar el módulo de `promoted_modules`, `terraform apply` | Se retira el servicio propio y todo lo que se creó para él | |

Lo que Terraform rechaza al planificar y lo que **no puede** comprobar (que el servicio nuevo
esté sano, que el núcleo ya se haya redesplegado, que nadie se salte un estado) está en
`infra/terraform/README.md`. Saltarse un estado es lo único peligroso: pasar de "promovido" a
"fuera de la lista" en un solo cambio enviaría la API a un núcleo que no atiende el módulo, y
se vería como respuestas `404 MODULE_NOT_SERVED`.

**En local** es el mismo recorrido con la misma imagen, en dos etapas (la segunda mueve el
tráfico y redespliega el núcleo):

```bash
make promover MODULO=giftcards        # o por etapas: ETAPA=expandir, luego ETAPA=contraer
make topologia
make reintegrar MODULO=giftcards
```

`make e2e` lo ejercita con un cliente llamando a las dos API mientras el tráfico cambia de
despliegue y el núcleo se redespliega, y comprueba que ninguna petición falla, que todos los
contenedores corren la misma imagen, que un lote generado mientras dos despliegues atienden el
módulo queda exacto (sin pérdidas ni duplicados) y que los datos siguen ahí después. En AWS el
procedimiento está validado por las pruebas de Terraform, por el plan contra una cuenta real y
por un ensayo contra un emulador; **no se ha ejecutado en AWS**.

## Apagado y reducción de tareas

Al recibir SIGTERM una tarea deja de pedir mensajes, devuelve a la cola los que no empezó,
termina los que tiene en curso y detiene el *relay*, todo dentro de `ZC_SHUTDOWN_TIMEOUT`
(60 s), que debe ser menor que el `stopTimeout` de ECS (90 s). Los módulos de la tarea lo hacen
a la vez y comparten ese plazo. Lo que no alcance a terminar
vuelve a la cola al vencer su *visibility timeout* y lo procesa otra tarea.

## Primer arranque de un ambiente

1. `terraform apply` en `infra/terraform/bootstrap` (estado remoto) y luego en `envs/<ambiente>`.
2. Ejecutar una vez la tarea `…-db-bootstrap`: crea los roles y el esquema de cada módulo.
3. Publicar una imagen y desplegar (migración y servicios).

El detalle está en `infra/terraform/README.md`.

## Variables de un despliegue

Un despliegue recibe las variables generales y las de los módulos que atiende; las de los demás
pueden no existir.

| Variable | Para qué | Por defecto |
|---|---|---|
| `ZC_MODULES` | Módulos que atiende este despliegue. Obligatoria; un nombre desconocido impide arrancar | — |
| `ZC_MODE` | `serve` o `migrate` | `serve` |
| `ZC_DB_URL` | La base de datos, común a todos los módulos | — |
| `ZC_DB_<MÓDULO>_APP_USER`, `ZC_DB_<MÓDULO>_APP_PASSWORD` | Rol de ejecución de ese módulo | `<módulo>_app` / — |
| `ZC_DB_<MÓDULO>_POOL_MAX` | Conexiones de ese módulo por tarea | 10 |
| `ZC_DB_<MÓDULO>_OWNER_USER`, `ZC_DB_<MÓDULO>_OWNER_PASSWORD` | Solo en la tarea de migración | `<módulo>_owner` / — |
| `ZC_OIDC_ISSUER`, `ZC_OIDC_AUDIENCE` | Realm de Keycloak y audiencia esperada | — / `zonacards-api` |
| `ZC_TOPIC_*_ARN`, `ZC_QUEUE_*_URL` | Topics y colas creados por Terraform, de los módulos atendidos | — |
| `ZC_RELAY_ENABLED`, `ZC_CONSUMER_ENABLED` | Apagar un bucle (por ejemplo, para separar API y *workers*) | `true` |
| `ZC_CONSUMER_CONCURRENCY` | Mensajes en proceso por cola y tarea | 4 |
| `ZC_SHUTDOWN_TIMEOUT` | Tiempo para drenar al apagar | `PT60S` |
| `ZC_GIFTCARDS_CODIGO_KEY`, `ZC_GIFTCARDS_CODIGO_HMAC_KEY` | Llaves de 32 bytes en base64 (secretos) | — |
| `ZC_GIFTCARDS_FRAGMENT_SIZE` | Gift cards por fragmento | 1000 |
| `ZC_GIFTCARDS_GENERACION_CONCURRENCY` | Fragmentos en proceso por tarea | 2 |
| `ZC_GIFTCARDS_GENERACION_VISIBILITY` | *Visibility timeout* de un fragmento | `PT5M` |
| `ZC_GIFTCARDS_ARGON2_MEMORY_KIB`, `ZC_GIFTCARDS_ARGON2_ITERATIONS` | Costo del hash del CVV | 19456 / 2 |
| `OTEL_SERVICE_NAME` | Nombre del despliegue en las trazas (`nucleo`, `giftcards`…) | `zonacards` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Colector de trazas | `http://localhost:4317` |
| `ZC_AWS_ENDPOINT` | Solo para el emulador local | — |
