# Decisiones de arquitectura

Cada decisión con su contexto, la alternativa descartada y lo que cuesta. Las versiones se
verificaron el 18 de noviembre de 2026.

## 1. Monolito modular: una aplicación, y la configuración decide qué atiende cada despliegue

**Decisión.** `lotes` y `giftcards` son módulos de **una sola aplicación**, que se construye en
**una sola imagen**. Cada despliegue declara en `ZC_MODULES` qué módulos atiende. Por defecto un
servicio, el **núcleo**, los atiende todos. Un módulo que necesita escalar por su cuenta se
**promueve** a servicio propio cambiando configuración: la misma imagen, sin compilar y sin
versión nueva.

**Alternativa.** Microservicios: una aplicación, una imagen, un pipeline y un servicio por
dominio desde el primer día.

**Por qué.**

- Con microservicios el costo de operar se paga por dominio y por adelantado: N pipelines, N
  imágenes que versionar y promover, N despliegues que coordinar cuando un cambio toca dos
  dominios. Aquí hay una versión, un pipeline y un despliegue, tenga la aplicación dos módulos o
  veinte.
- Lo que sí se necesita de los microservicios se conserva: **fronteras que no dependen de la
  disciplina** (decisiones 2 y 3) y **escalado independiente del módulo que lo requiere**
  (decisión 4).
- La forma de desplegar deja de ser una decisión de diseño que hay que acertar al principio:
  es un dato de infraestructura que se cambia cuando la carga lo pide, y se puede deshacer.

**Costo.**

- **Se despliega y se vuelve atrás entera.** Todos los módulos corren la misma versión; un
  módulo no puede quedarse en la anterior.
- **Una sola tecnología y un solo ciclo de entrega.** Un equipo no puede publicar su módulo por
  su cuenta ni escribirlo en otro lenguaje.
- **Dentro del núcleo los módulos comparten CPU y memoria.** Un módulo pesado afecta a los
  demás hasta que se promueve; es exactamente lo que mide `make demo-promocion`.
- **Las fronteras hay que verificarlas**, porque el compilador ya no las impone por estar en
  repositorios distintos. Lo hacen ArchUnit, los roles de PostgreSQL y el gestor de
  transacciones.

## 2. Fronteras entre módulos que impone la plataforma, no la convención

**Decisión.** Tres barreras independientes separan los módulos aunque compartan proceso:

| Barrera | Qué impide | Dónde se comprueba |
|---|---|---|
| ArchUnit | Que un módulo importe código de otro, y que la raíz conozca de un módulo algo más que su contenedor | `ArchitectureTest` de cada módulo, `AppArchitectureTest` |
| Un rol de PostgreSQL y un *datasource* por módulo | Que un módulo lea o escriba el esquema de otro: la base responde `42501` | `AppSagaIT.sharingAProcessDoesNotShareTheDatabase`, `make e2e aislamiento` |
| Dos *datasources* sin XA | Que una transacción abarque dos módulos: el gestor de transacciones rechaza la segunda conexión | la misma prueba |

**Alternativa.** Un solo *datasource* y un solo rol para toda la aplicación, con las fronteras
como convención de paquetes.

**Por qué.** Un monolito se degrada cuando un módulo empieza a leer las tablas de otro "solo
esta vez". Aquí esa consulta no se puede escribir: no compila contra las reglas de arquitectura
y, si se saltaran, la base de datos la rechaza. Es también lo que hace posible la promoción: un
módulo que no toca nada ajeno se puede mover sin buscar dependencias ocultas.

**Costo.** Un *pool* de conexiones por módulo en cada tarea del núcleo, y un presupuesto de
conexiones por rol que hay que dimensionar (lo verifica Terraform).

## 3. Entre módulos, solo eventos por el broker, también dentro del mismo proceso

**Decisión.** Un módulo llega a otro únicamente publicando un evento en su topic SNS, que el
otro recibe en su cola SQS. Es así **también cuando los dos corren en el mismo proceso**: no
hay llamadas directas ni un bus en memoria para el caso en que comparten núcleo.

**Alternativa.** Entregar los eventos en memoria cuando emisor y receptor comparten proceso, y
por el broker solo cuando están separados.

**Por qué.**

- Es la condición para promover **sin tocar código**: el módulo ya recibe y emite por el broker,
  así que le da igual en qué proceso corre.
- Hay **una sola semántica**: al menos una vez, sin orden, con *outbox*, *inbox* y DLQ. Con dos
  caminos habría que probar los dos, y el que se usa en producción el primer día (en memoria)
  no sería el que se usa después de promover.
- Durante una promoción el núcleo y el servicio propio consumen la misma cola a la vez. Con
  consumidores idempotentes eso es un caso normal, no uno especial.

**Costo.** Un evento entre dos módulos del mismo proceso hace un viaje de ida y vuelta al
broker (milisegundos), y la aplicación depende de SNS/SQS aun cuando es un solo servicio.

## 4. Promoción por etapas: el tráfico nunca se mueve en el mismo cambio que crea o retira un servicio

**Decisión.** Promover un módulo son **dos cambios de infraestructura y un redespliegue de la
misma versión**; devolverlo al núcleo, **tres y un redespliegue**. En Terraform el estado
intermedio se declara con el campo `transition` de `promoted_modules`; en local son las etapas
`expandir` y `contraer` de `make promover`.

| | Promover | Reintegrar |
|---|---|---|
| 1 | Se crea el servicio propio; el núcleo sigue atendiendo y recibiendo el tráfico | El núcleo vuelve a atender el módulo (redespliegue); el tráfico sigue en el servicio propio |
| 2 | El tráfico pasa al servicio propio y el núcleo suelta el módulo (redespliegue) | El tráfico vuelve al núcleo; el servicio propio sigue existiendo |
| 3 | | Se retira el servicio propio |

**Alternativa.** Un solo cambio: agregar el módulo a la lista y aplicar.

**Por qué.** En un solo paso el balanceador cambia de destino antes de que el servicio nuevo
tenga tareas sanas, y el núcleo sigue con su configuración anterior hasta el siguiente
despliegue: el módulo queda sin quien lo atienda durante ese intervalo. La vuelta tiene un paso
más porque retirar un servicio lo destruye de inmediato y no puede coincidir con el cambio que
le quita el tráfico. Este último caso lo encontró el ensayo contra el emulador: el proveedor
intentaba borrar un *target group* que la regla aún usaba.

**Costo.** El procedimiento tiene más pasos que "cambiar una variable", y durante el solape el
módulo abre conexiones desde dos servicios: el presupuesto de conexiones se dimensiona para
eso. Sigue siendo cierto lo esencial: sin desarrollo, sin compilar y sin versión nueva.

## 5. Hexágono con composición explícita

**Decisión.** Dominio y casos de uso sin anotaciones ni dependencias de framework. El grafo de
cada módulo se arma a mano, con `new`, en su `container/*Container`; el contenedor raíz
(`com.zonacards.app.AppContainer`) lista los módulos.

**Alternativa.** Anotar cada clase (`@ApplicationScoped`, `@Inject`, `@Transactional`), que es
lo habitual en Quarkus.

**Por qué.** Es la forma de Manifesto, que es lo que se pidió reproducir, y tiene dos efectos
prácticos: las reglas del negocio se prueban con `new`, sin contenedor, y un módulo que no se
atiende **no se construye**, así que el despliegue no necesita sus credenciales, sus colas ni
sus llaves. Con un bean por clase, CDI resolvería el grafo de todos los módulos en todos los
despliegues.

**Costo.** Unas decenas de líneas de cableado por módulo. Que el despliegue trae lo que el
módulo necesita (sus topics, sus colas alcanzables, sus llaves) se comprueba al arrancar
(`Preflight`), no al compilar: un despliegue mal cableado falla en el arranque y ECS lo revierte.
La conexión a la base de datos no forma parte de esa comprobación.

## 6. SNS + SQS en lugar de Kafka

**Decisión.** Un topic SNS por módulo y una cola SQS por consumidor, con DLQ y filtro por tipo.

**Alternativa.** Kafka administrado (MSK).

**Por qué.** El problema es coordinar módulos con decenas de eventos por lote, no procesar
un flujo ordenado ni reproducir historia. SNS/SQS no tiene clúster que dimensionar ni operar,
cobra por uso, da DLQ y reintentos de fábrica, y la profundidad de una cola es exactamente la
señal de autoescalado que ECS sabe usar.

**Costo.** No hay orden ni relectura del historial. El diseño no los necesita: todos los
manejadores toleran desorden y duplicados. Si un día hiciera falta, cambiaría el adaptador
`eventx-aws`, no los módulos.

## 7. Outbox por sondeo en lugar de captura de cambios

**Decisión.** Un *relay* por módulo, en cada tarea que lo atiende, lee la tabla `outbox` de su
esquema con `FOR UPDATE SKIP LOCKED`.

**Alternativa.** Debezium leyendo el WAL de PostgreSQL.

**Por qué.** No agrega infraestructura (ni conectores, ni *slots* de replicación que vigilar),
funciona igual en local y en RDS, y el volumen de eventos es bajo. La latencia añadida es el
intervalo de sondeo (250 ms).

**Costo.** Consultas periódicas a la base aunque no haya eventos. Con un índice parcial sobre
las filas pendientes es una lectura de índice vacío.

## 8. Idempotencia en los datos, no solo en un registro de mensajes

**Decisión.** Además del *inbox*, cada manejador es idempotente por construcción: inserciones con
`ON CONFLICT`, reclamo condicional de fragmentos, progreso como conjunto.

**Alternativa.** Confiar solo en el *inbox*.

**Por qué.** El *inbox* se purga. Un *redrive* desde la DLQ puede traer un mensaje semanas
después; debe seguir siendo inofensivo. Es además lo que permite que dos servicios consuman la
misma cola durante una promoción.

**Costo.** Cada manejador se piensa dos veces. Las pruebas lo exigen: reentregan con el *inbox*
vacío.

## 9. JDBC explícito en lugar de un ORM

**Decisión.** SQL escrito a mano sobre una capa mínima (`dbx`) y migraciones con Flyway.

**Alternativa.** Hibernate ORM con Panache.

**Por qué.** La corrección de la saga depende de sentencias concretas (`SKIP LOCKED`,
`FOR SHARE`, `ON CONFLICT … RETURNING`, inserción masiva con `unnest`). Un ORM las oculta o las
complica, y obliga a anotar el dominio. Es además la equivalencia directa de `sqlx` en Manifesto.

**Costo.** Los mapeos se escriben a mano.

## 10. ECS Fargate en lugar de Kubernetes

**Decisión.** Un servicio de ECS para el núcleo y uno más por cada módulo promovido, este
último con Application Auto Scaling. Todos corren la misma imagen.

**Alternativa.** EKS con HPA o KEDA.

**Por qué.** Un puñado de servicios no justifica operar un clúster. Fargate da aislamiento por
tarea, despliegue *rolling* con *rollback* y escalado por métrica sin nodos que administrar. Es
lo que plantea la propuesta.

**Costo.** Menos control sobre el planificador y un costo por vCPU mayor que con nodos propios a
gran escala. El código no depende de ECS: la imagen y las variables son las mismas en cualquier
orquestador.

## 11. Quarkus 3.40 LTS, no 4.0

**Decisión.** La versión se fija en el BOM en 3.40 LTS.

**Alternativa.** Quarkus 4.0, cuyo lanzamiento está previsto para el 25 de noviembre de 2026.

**Por qué.** Un ejemplo para un cliente va sobre una versión con soporte extendido. 4.0 trae
cambios mayores (entre ellos Jackson 3) que conviene adoptar cuando el ecosistema de extensiones
lo acompañe.

**Costo.** Una migración futura. El alcance es acotado porque solo `libs/server` y los
`container` conocen Quarkus.

## 12. La API y los consumidores de un módulo corren en el mismo proceso

**Decisión.** La unidad que se despliega y se escala es el **módulo entero**: su API, sus
consumidores y su *relay*.

**Alternativa.** Separar cada módulo en un despliegue `api` y otro `worker`.

**Por qué.** Menos piezas que desplegar y observar, y una sola regla para decidir dónde corre
algo: el módulo. La separación queda disponible sin tocar código: `ZC_CONSUMER_ENABLED` y
`ZC_RELAY_ENABLED` apagan cada bucle.

**Costo.** La API de `giftcards` escala junto con sus *workers*. Con el tráfico previsto no
importa; si importara, es un cambio de despliegue.

## 13. Una sola tarea de migración para toda la aplicación

**Decisión.** Antes de cada despliegue, **una** tarea (`ZC_MODE=migrate`) aplica las
migraciones de todos los módulos, cada una con el rol dueño de ese módulo. Es la única tarea que
recibe esas credenciales.

**Alternativa.** Una tarea de migración por módulo, o migrar al arrancar cada servicio.

**Por qué.** La imagen es una y trae las migraciones de todos; migrar por módulo exigiría un
orden entre tareas que la versión no necesita, y migrar al arrancar daría a los servicios
permiso para alterar tablas. Con una tarea hay una compuerta: si falla, no se toca ningún
servicio.

**Costo.** Una migración que falla en un módulo detiene el despliegue de todos, y la tarea
concentra las credenciales de dueño de todos los esquemas.

## 14. El pipeline lee de un parámetro qué servicios tiene el ambiente

**Decisión.** Terraform publica en un parámetro de SSM el descriptor del despliegue (cluster,
tarea de migración y servicios, en orden) y en otro la versión desplegada. El workflow lee el
primero al empezar y escribe el segundo al terminar.

**Alternativa.** Escribir los servicios en el workflow o copiarlos a variables de GitHub.

**Por qué.** Los servicios de un ambiente cambian cuando se promueve un módulo. Si la lista
viviera fuera de Terraform, cada promoción dejaría un segundo lugar que actualizar a mano, y un
olvido sería un servicio que no recibe versiones nuevas. El parámetro de versión resuelve el
otro lado: un servicio que se crea después nace con la versión que ya corre el resto.

**Costo.** El pipeline depende de un parámetro que debe existir, y el rol de despliegue
necesita leerlo y escribir el de versión.

## 15. Trabajo de CPU en hilos de plataforma

**Decisión.** Los manejadores corren en hilos virtuales; el hash Argon2id corre en un pool de
hilos de plataforma del tamaño de los núcleos.

**Alternativa.** Todo en hilos virtuales.

**Por qué.** Los hilos virtuales no se interrumpen en medio de un cálculo. En una tarea de
1 vCPU, varios hashes simultáneos dejarían sin CPU al *polling* y a la renovación de
visibilidad, y los mensajes volverían a la cola mientras aún se procesan.

**Costo.** Dentro del núcleo ese pool compite con la API de los demás módulos. Es el motivo
para promover `giftcards`, y está medido.

## 16. Emulador local Floci

**Decisión.** SNS y SQS se emulan en local y en las pruebas con Floci, y su topología se crea
con el mismo módulo de Terraform que en AWS. El ensayo de la promoción aplica además toda la
infraestructura de un ambiente en un emulador desechable.

**Alternativa.** LocalStack, o dobles en memoria.

**Por qué.** Las pruebas de integración ejercitan el SDK real, los filtros de suscripción, la
entrega cruda, las DLQ y el *redrive*. Floci tiene licencia MIT y no exige una cuenta.

**Costo.** Es un proyecto joven. Se encontró una diferencia de comportamiento (entrega a
conexiones de *long polling* ya cerradas), documentada en `docs/autoescalado.md`, y tres
diferencias con AWS al aplicar Terraform, documentadas en `infra/terraform/README.md`. El
adaptador se prueba además con dobles en memoria que inyectan fallos que un emulador no produce.
