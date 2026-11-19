# Operación

Guía para quien opera los servicios: qué mirar, qué significa y qué hacer. Los ejemplos de
`aws` usan los nombres que crea Terraform (`zonacards-<ambiente>-…`); en el entorno local las
mismas colas se llaman `zonacards-local-…` y se inspeccionan con `scripts/lib.sh`.

## Qué mirar

| Señal | Dónde | Normal | Qué indica si no |
|---|---|---|---|
| `GET /q/health/live` | cada tarea (lo consultan el balanceador y ECS) | `UP`, con `outbox-relay` y cada `consumer-<cola>` en `alive` | Un bucle lleva más de 90 s sin dar señal: ECS reemplaza la tarea |
| `GET /q/health/ready` | cada tarea | `UP` | Informativo (incluye la base). No reinicia tareas a propósito: una caída breve de la base no debe reiniciarlas todas |
| `zonacards_outbox_pending` | `/q/metrics` | cerca de 0 | Eventos escritos y aún no publicados: el broker no responde o el *relay* está detenido |
| `zonacards_outbox_oldest_pending_seconds` | `/q/metrics` | segundos | Minutos: los eventos no están saliendo |
| `zonacards_outbox_parked` | `/q/metrics` | 0 | El broker rechazó un mensaje de forma permanente; requiere revisión manual |
| `zonacards_messages_consumed_seconds_count{outcome=…}` | `/q/metrics` | `handled`, algún `duplicate` | `failed` sostenido: un manejador está fallando |
| Mensajes visibles en `…-giftcards-generacion` | CloudWatch / SQS | sube con un lote y baja | No baja: `giftcards` no procesa o está en su máximo de tareas |
| Mensajes en cualquier `…-dlq` | CloudWatch (alarma) | 0 | Un mensaje agotó sus reintentos |
| Antigüedad del mensaje más viejo | CloudWatch (alarma) | menor al umbral de la cola | El consumidor no da abasto o está caído |

Cada línea de log es JSON con `request_id`, `tenant_id`, `traceId` y los campos del caso
(`lote_id`, `fragmento`, `queue`, `message_id`, `receive_count`). Una traza une la petición HTTP
en `lotes` con el consumo en `giftcards`.

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
-- Con el rol del servicio, en su esquema (lotes o giftcards)
SELECT destination, message_type, count(*), min(created_at) AS mas_antiguo, max(attempts) AS intentos
FROM outbox WHERE published_at IS NULL AND parked_at IS NULL
GROUP BY 1, 2 ORDER BY 4;

SELECT id, destination, message_type, attempts, last_error, parked_at
FROM outbox WHERE parked_at IS NOT NULL ORDER BY parked_at DESC;
```

- **Pendientes con `last_error`**: el *relay* intenta y el broker falla. Revisar permisos IAM
  del rol de la tarea sobre el topic o la cola, la llave KMS y el estado de SNS/SQS. Se
  publican solos cuando la causa desaparece.
- **Pendientes sin intentos**: el *relay* no corre. Revisar `ZC_RELAY_ENABLED` y el *liveness*.
- **Apartados (`parked_at`)**: el broker rechazó el mensaje de forma permanente (por ejemplo,
  demasiado grande o destino inexistente). No se reintentan solos. Tras corregir la causa:

  ```sql
  UPDATE outbox SET parked_at = NULL, attempts = 0, available_at = now(), last_error = NULL
  WHERE id = '<id>';
  ```

  Es una escritura manual con el rol del servicio: debe quedar registrada como intervención.

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
| `EN_GENERACION` con fragmentos `PENDIENTE` | Cola `giftcards-generacion`: si tiene mensajes, falta capacidad o `giftcards` está caído; si está vacía, revisar su DLQ |
| `EN_GENERACION` con todos los fragmentos `GENERADO` | Los eventos `fragmento-generado` no llegaron: outbox de `giftcards`, cola `lotes-inbox` y su DLQ |
| `FALLIDO` | Un fragmento falló de forma terminal; el motivo está en el lote. Anular el lote y solicitar otro |

Un lote atascado nunca se corrige editando su estado: se corrige la causa y los mensajes
pendientes lo completan.

## Despliegue

El orden lo ejecuta el pipeline (`docs/cicd.md`), y es el mismo a mano:

1. Registrar una revisión de la *task definition* del servicio y de la de migración con la
   imagen nueva.
2. Ejecutar la tarea `<servicio>-migrate` y comprobar que termina con código 0. Es la única
   que tiene las credenciales del dueño del esquema.
3. Actualizar el servicio. ECS hace un despliegue *rolling*; si las tareas nuevas no quedan
   sanas, el *circuit breaker* vuelve a la revisión anterior.

Las migraciones deben ser compatibles con la versión anterior del servicio, porque durante el
despliegue conviven ambas (agregar columnas con valor por defecto; retirar en una versión
posterior).

**Volver atrás**: desplegar la etiqueta de imagen anterior con el mismo procedimiento. Las
imágenes son inmutables y se identifican por el SHA del commit.

## Apagado y reducción de tareas

Al recibir SIGTERM una tarea deja de pedir mensajes, devuelve a la cola los que no empezó,
termina los que tiene en curso y detiene el *relay*, todo dentro de `ZC_SHUTDOWN_TIMEOUT`
(60 s), que debe ser menor que el `stopTimeout` de ECS (90 s). Lo que no alcance a terminar
vuelve a la cola al vencer su *visibility timeout* y lo procesa otra tarea.

## Primer arranque de un ambiente

1. `terraform apply` en `infra/terraform/bootstrap` (estado remoto) y luego en `envs/<ambiente>`.
2. Ejecutar una vez la tarea `…-db-bootstrap`: crea los roles y el esquema de cada servicio.
3. Publicar una imagen y desplegar (migración y servicio).

El detalle está en `infra/terraform/README.md`.

## Variables de un servicio

| Variable | Para qué | Por defecto |
|---|---|---|
| `ZC_MODE` | `serve` o `migrate` | `serve` |
| `ZC_DB_URL`, `ZC_DB_APP_USER`, `ZC_DB_APP_PASSWORD` | Conexión con el rol de ejecución | — |
| `ZC_DB_OWNER_USER`, `ZC_DB_OWNER_PASSWORD` | Solo en la tarea de migración | — |
| `ZC_DB_POOL_MAX` | Conexiones por tarea | 10 |
| `ZC_OIDC_ISSUER`, `ZC_OIDC_AUDIENCE` | Realm de Keycloak y audiencia esperada | — / `zonacards-api` |
| `ZC_TOPIC_*_ARN`, `ZC_QUEUE_*_URL` | Topics y colas creados por Terraform | — |
| `ZC_RELAY_ENABLED`, `ZC_CONSUMER_ENABLED` | Apagar un bucle (por ejemplo, para separar API y *workers*) | `true` |
| `ZC_CONSUMER_CONCURRENCY` | Mensajes en proceso por cola y tarea | 4 |
| `ZC_SHUTDOWN_TIMEOUT` | Tiempo para drenar al apagar | `PT60S` |
| `ZC_GIFTCARDS_CODIGO_KEY`, `ZC_GIFTCARDS_CODIGO_HMAC_KEY` | Llaves de 32 bytes en base64 (secretos) | — |
| `ZC_GIFTCARDS_FRAGMENT_SIZE` | Gift cards por fragmento | 1000 |
| `ZC_GIFTCARDS_GENERACION_CONCURRENCY` | Fragmentos en proceso por tarea | 2 |
| `ZC_GIFTCARDS_GENERACION_VISIBILITY` | *Visibility timeout* de un fragmento | `PT5M` |
| `ZC_GIFTCARDS_ARGON2_MEMORY_KIB`, `ZC_GIFTCARDS_ARGON2_ITERATIONS` | Costo del hash del CVV | 19456 / 2 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Colector de trazas | `http://localhost:4317` |
| `ZC_AWS_ENDPOINT` | Solo para el emulador local | — |
