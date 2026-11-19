# Decisiones de arquitectura

Cada decisión con su contexto, la alternativa descartada y lo que cuesta. Las versiones se
verificaron el 18 de noviembre de 2026.

## 1. Hexágono con composición explícita

**Decisión.** Dominio y casos de uso sin anotaciones ni dependencias de framework; el cableado
se escribe a mano en `container/*Container` con `@Produces @Singleton`.

**Alternativa.** Anotar cada clase (`@ApplicationScoped`, `@Inject`, `@Transactional`), que es
lo habitual en Quarkus.

**Por qué.** Es la forma de Manifesto, que es lo que se pidió reproducir, y tiene un efecto
práctico: las reglas del negocio se prueban con `new`, sin contenedor, y el grafo de
dependencias se lee en un archivo. Quarkus valida ese grafo al compilar.

**Costo.** Unas decenas de líneas de cableado por servicio.

## 2. SNS + SQS en lugar de Kafka

**Decisión.** Un topic SNS por dominio y una cola SQS por consumidor, con DLQ y filtro por tipo.

**Alternativa.** Kafka administrado (MSK).

**Por qué.** El problema es coordinar dos dominios con decenas de eventos por lote, no procesar
un flujo ordenado ni reproducir historia. SNS/SQS no tiene clúster que dimensionar ni operar,
cobra por uso, da DLQ y reintentos de fábrica, y la profundidad de una cola es exactamente la
señal de autoescalado que ECS sabe usar.

**Costo.** No hay orden ni relectura del historial. El diseño no los necesita: todos los
manejadores toleran desorden y duplicados. Si un día hiciera falta, cambiaría el adaptador
`eventx-aws`, no los dominios.

## 3. Outbox por sondeo en lugar de captura de cambios

**Decisión.** Un *relay* en cada tarea lee la tabla `outbox` con `FOR UPDATE SKIP LOCKED`.

**Alternativa.** Debezium leyendo el WAL de PostgreSQL.

**Por qué.** No agrega infraestructura (ni conectores, ni *slots* de replicación que vigilar),
funciona igual en local y en RDS, y el volumen de eventos es bajo. La latencia añadida es el
intervalo de sondeo (250 ms).

**Costo.** Consultas periódicas a la base aunque no haya eventos. Con un índice parcial sobre
las filas pendientes es una lectura de índice vacío.

## 4. Idempotencia en los datos, no solo en un registro de mensajes

**Decisión.** Además del *inbox*, cada manejador es idempotente por construcción: inserciones con
`ON CONFLICT`, reclamo condicional de fragmentos, progreso como conjunto.

**Alternativa.** Confiar solo en el *inbox*.

**Por qué.** El *inbox* se purga. Un *redrive* desde la DLQ puede traer un mensaje semanas
después; debe seguir siendo inofensivo.

**Costo.** Cada manejador se piensa dos veces. Las pruebas lo exigen: reentregan con el *inbox*
vacío.

## 5. JDBC explícito en lugar de un ORM

**Decisión.** SQL escrito a mano sobre una capa mínima (`dbx`) y migraciones con Flyway.

**Alternativa.** Hibernate ORM con Panache.

**Por qué.** La corrección de la saga depende de sentencias concretas (`SKIP LOCKED`,
`FOR SHARE`, `ON CONFLICT … RETURNING`, inserción masiva con `unnest`). Un ORM las oculta o las
complica, y obliga a anotar el dominio. Es además la equivalencia directa de `sqlx` en Manifesto.

**Costo.** Los mapeos se escriben a mano.

## 6. ECS Fargate en lugar de Kubernetes

**Decisión.** Un servicio ECS por dominio, con Application Auto Scaling.

**Alternativa.** EKS con HPA o KEDA.

**Por qué.** Dos servicios no justifican operar un clúster. Fargate da aislamiento por tarea,
despliegue *rolling* con *rollback* y escalado por métrica sin nodos que administrar. Es lo que
plantea la propuesta.

**Costo.** Menos control sobre el planificador y un costo por vCPU mayor que con nodos propios a
gran escala. El código no depende de ECS: la imagen y las variables son las mismas en cualquier
orquestador.

## 7. Quarkus 3.40 LTS, no 4.0

**Decisión.** La versión se fija en el BOM en 3.40 LTS.

**Alternativa.** Quarkus 4.0, cuyo lanzamiento está previsto para el 25 de noviembre de 2026.

**Por qué.** Un ejemplo para un cliente va sobre una versión con soporte extendido. 4.0 trae
cambios mayores (entre ellos Jackson 3) que conviene adoptar cuando el ecosistema de extensiones
lo acompañe.

**Costo.** Una migración futura. El alcance es acotado porque solo `libs/server` y los
`container` conocen Quarkus.

## 8. Un servicio por dominio, con API y consumidores en el mismo proceso

**Decisión.** Cada tarea atiende HTTP, consume sus colas y publica su *outbox*.

**Alternativa.** Separar cada dominio en un servicio `api` y otro `worker`.

**Por qué.** Menos piezas que desplegar y observar. La separación queda disponible sin tocar
código: `ZC_CONSUMER_ENABLED` y `ZC_RELAY_ENABLED` apagan cada bucle.

**Costo.** La API de `giftcards` escala junto con sus *workers*. Con el tráfico previsto no
importa; si importara, es un cambio de despliegue.

## 9. Trabajo de CPU en hilos de plataforma

**Decisión.** Los manejadores corren en hilos virtuales; el hash Argon2id corre en un pool de
hilos de plataforma del tamaño de los núcleos.

**Alternativa.** Todo en hilos virtuales.

**Por qué.** Los hilos virtuales no se interrumpen en medio de un cálculo. En una tarea de
1 vCPU, varios hashes simultáneos dejarían sin CPU al *polling* y a la renovación de
visibilidad, y los mensajes volverían a la cola mientras aún se procesan.

## 10. Emulador local Floci

**Decisión.** SNS y SQS se emulan en local y en las pruebas con Floci, y su topología se crea
con el mismo módulo de Terraform que en AWS.

**Alternativa.** LocalStack, o dobles en memoria.

**Por qué.** Las pruebas de integración ejercitan el SDK real, los filtros de suscripción, la
entrega cruda, las DLQ y el *redrive*. Floci tiene licencia MIT y no exige una cuenta.

**Costo.** Es un proyecto joven. Se encontró una diferencia de comportamiento (entrega a
conexiones de *long polling* ya cerradas), documentada en `docs/autoescalado.md`. El adaptador se
prueba además con dobles en memoria que inyectan fallos que un emulador no produce.
