# Infraestructura de Zonacards (Terraform)

Una aplicación —un monolito modular, **una sola imagen**— desplegada en ECS Fargate, eventos por
SNS → SQS y una instancia PostgreSQL 18 con un esquema por módulo. Por defecto un único servicio,
el **núcleo**, atiende todos los módulos. Lo que este código demuestra: **dar a un módulo su
propio servicio, con su ruta y su autoescalado, es cambiar una variable**. No hay desarrollo, no
hay compilación y no hay versión nueva: Terraform crea el servicio a partir de la imagen que ya
está desplegada.

Nada de esto se ha aplicado en AWS. Está validado de forma estática, con pruebas que planifican
contra proveedores simulados, con `terraform plan` contra una cuenta real y con un ensayo que
aplica la promoción completa, con el proveedor real, contra un emulador. Qué cubre cada cosa y
qué no: [Cómo validar](#cómo-validar).

## Estructura

```
bootstrap/            bucket S3 del estado remoto (se aplica una vez por cuenta)
modules/
  network/            VPC, subredes públicas y privadas, NAT (único módulo comunitario)
  ecs-cluster/        cluster con Container Insights, ALB con respuesta 404 por defecto
  ecs-service/        UN despliegue de la aplicación: tareas, roles, autoescalado OPCIONAL
  messaging/          topics, colas, DLQ, suscripciones con filtro, KMS, alarmas
    topology/         la topología (qué topics, qué colas, qué filtros), declarada una sola vez
  database/           RDS PostgreSQL 18 y la tarea que crea roles y esquemas
  generated-secrets/  secretos generados que nunca quedan en el estado
  ecr/                el repositorio de la imagen
  github-oidc/        proveedor OIDC y roles para GitHub Actions
  platform/           la composición completa de un ambiente
    catalog.tf          los módulos de la aplicación y dónde está cada uno
    services.tf         el núcleo, un servicio por módulo promovido, presupuesto de conexiones
    routing.tf          un target group por servicio y una regla del balanceador por módulo
    deploy.tf           lo que lee el pipeline: qué desplegar y qué versión está desplegada
envs/{dev,qa,prod}/   una llamada a modules/platform + terraform.tfvars
                      + promocion-giftcards.tfvars (el ejemplo de promoción)
local/                solo modules/messaging, contra un emulador local
tests/                pruebas nativas de los módulos (*.tftest.hcl) y sus mocks
```

### Por qué existe `modules/platform`

Los tres ambientes llaman a un único módulo de composición. `main.tf`, `variables.tf`,
`outputs.tf`, `providers.tf`, `versions.tf` y `tests/` son **idénticos byte a byte** en `dev`,
`qa` y `prod`; lo único que cambia son los `.tfvars` (y `backend.hcl`). Así el cableado que no
debe variar —qué cola alimenta a qué módulo, qué rol publica dónde, qué secreto llega a qué
contenedor— vive en un solo lugar y los ambientes no pueden divergir. Para comprobarlo:
`diff envs/dev/main.tf envs/prod/main.tf` no debe mostrar nada.

## Un servicio o varios: `promoted_modules`

`modules/platform/catalog.tf` es el lado de Terraform de la lista de módulos de la aplicación:
por cada módulo, las rutas de su API, sus topics, sus colas y sus secretos. No dice dónde corre.
Eso lo deciden tres variables:

```hcl
nucleo = { cpu = 1024, memory = 2048, desired_count = 2 }   # el servicio que siempre existe

modules = {                                                  # por módulo, corra donde corra
  lotes     = { db_pool_max = 10, db_connection_limit = 40 }
  giftcards = { db_pool_max = 10, db_connection_limit = 240 }
}

promoted_modules = {}                                        # módulos con servicio propio
```

Con `promoted_modules` vacío hay un servicio, `nucleo`, con `ZC_MODULES=giftcards,lotes`. Por
cada módulo que se agrega a ese mapa, Terraform crea **16 recursos** (contados en el plan real):

| Qué | Recursos |
|---|---|
| El servicio y su definición de tarea, con la imagen desplegada | 3 (incluye el registro de la imagen inicial) |
| Su *target group* y su lugar en la regla del módulo | 1 (la regla ya existía: cambia en el sitio) |
| Autoescalado: un *target* y dos políticas | 3 |
| Roles de ejecución y de tarea, con sus políticas | 4 |
| *Security group* y sus reglas, más el acceso a la base | 4 |
| Grupo de logs | 1 |

El servicio propio recibe **solo** lo de su módulo: sus colas, su topic, su rol de base de datos
y sus secretos. No podría atender otro módulo aunque se le pidiera. El núcleo, en cambio, recibe
siempre lo de **todos** los módulos, también los que no está atendiendo: así un módulo puede
volver con un cambio de `ZC_MODULES` y nada más, y una promoción no le quita nada a las tareas
del núcleo que siguen corriendo con la configuración anterior.

### Los cuatro estados de un módulo

| `promoted_modules` | Servicio propio | El núcleo lo atiende | Su API va a |
|---|:---:|:---:|---|
| el módulo no aparece | no | sí | núcleo |
| `transition = "traffic-in-nucleo"` | sí | sí | núcleo |
| sin `transition` (promovido) | sí | no | servicio propio |
| `transition = "traffic-in-service"` | sí | sí | servicio propio |

Los dos estados con `transition` son aquellos en los que el módulo se atiende **dos veces**.
Existen para que todo cambio sea un paso entre dos filas vecinas, y en todas las filas la API
del módulo va a un despliegue que lo atiende. Cualquier estado es seguro para quedarse en él.
Mientras el módulo se atiende dos veces no se pierde ni se duplica nada: los dos despliegues
son consumidores en competencia de las mismas colas, los *handlers* son idempotentes y el
*relay* del outbox usa un *lease* (probado en local por `make e2e`, escenario `promocion`).

### Promover un módulo

Dos `apply` y un redespliegue de la versión que ya está desplegada.

```bash
cd infra/terraform/envs/prod

# 1. Expandir. promocion-giftcards.tfvars trae transition = "traffic-in-nucleo".
terraform apply -var-file=promocion-giftcards.tfvars
aws ecs wait services-stable --cluster zonacards-prod-cluster --services zonacards-prod-giftcards

# 2. Contraer. Quitar la línea `transition` del archivo y aplicar.
terraform apply -var-file=promocion-giftcards.tfvars

# 3. Redesplegar la versión actual: workflow `deploy`, manual, con la etiqueta que devuelve
aws ssm get-parameter --name /zonacards/prod/release --query Parameter.Value --output text
```

| Paso | Qué cambia | Qué no cambia |
|---|---|---|
| 1 | Se crea el servicio del módulo con la imagen desplegada y empieza a tomar su parte de las colas. Su *target group* entra en la regla del módulo con peso 0. | El núcleo, exactamente igual: no hay nada que redesplegar. La API del módulo sigue yendo al núcleo. |
| 2 | La regla del módulo pasa a enviar todo al servicio propio (cambio en el sitio). Terraform registra una revisión de la definición de tarea del núcleo con `ZC_MODULES` sin el módulo. | Las tareas del núcleo que están corriendo: siguen atendiendo el módulo hasta el paso 3. |
| 3 | El pipeline despliega primero el servicio propio y al final el núcleo, que arranca sin el módulo. | La imagen: es la misma etiqueta. |

El `wait` del paso 1 no es opcional: Terraform da por creado el servicio en cuanto ECS lo
acepta, no cuando sus tareas están sanas, y el paso 2 le envía el tráfico.

`promocion-giftcards.tfvars` es un ejemplo para probar el recorrido. En un ambiente real el
contenido de `promoted_modules` va en el `terraform.tfvars` del ambiente, cada paso en su
propio *pull request* con su plan revisado: así el estado declarado es siempre el que está en
el repositorio, y un `apply` posterior sin el archivo no retira el servicio por descuido.

La propuesta resume la promoción como "se agrega a la lista y se aplica". Hecha en un solo
paso cortaría el servicio del módulo: el balanceador cambiaría de destino antes de que el
servicio nuevo tuviera tareas sanas. Por eso aquí son **dos cambios de infraestructura y un
redespliegue**; sigue siendo cierto lo esencial: sin desarrollo, sin compilar y sin versión
nueva.

### Devolver un módulo al núcleo

Tres `apply` y un redespliegue. Es un paso más que promover, a propósito: quitar un servicio lo
destruye en el acto, así que el `apply` que lo quita no puede ser el mismo que le retira el
tráfico. (Al promover no hace falta, porque que el núcleo deje de atender un módulo solo tiene
efecto en su siguiente despliegue.)

1. `transition = "traffic-in-service"`, `apply` y redesplegar la versión actual: el núcleo
   vuelve a atender el módulo. Antes de seguir, comprobar que el núcleo ya corre la revisión
   nueva (`aws ecs describe-services` y `describe-task-definition`: `ZC_MODULES` debe incluir
   el módulo).
2. `transition = "traffic-in-nucleo"`, `apply`: la API del módulo vuelve al núcleo. El servicio
   propio sigue ahí por si hay que deshacer.
3. Dejar de pasar el archivo (o quitar el módulo del mapa), `apply`: se destruyen los 16
   recursos de la promoción.

### Lo que Terraform comprueba y lo que no

Se rechaza al planificar, antes de tocar nada: un módulo que la aplicación no tiene, un valor
de `transition` desconocido, un núcleo que se quedaría sin módulos, un mapa `modules` que no
coincide con los módulos de la aplicación y cualquier cambio que rompa el
[presupuesto de conexiones](#presupuesto-de-conexiones).

Lo que Terraform **no puede** comprobar, porque depende de lo que está corriendo y no de lo
que está declarado:

- que el servicio nuevo esté sano antes del paso 2 de la promoción (por eso el `wait`);
- que el núcleo ya se haya redesplegado antes del paso 2 de la reintegración;
- que alguien no se salte un estado. Pasar de "promovido" a "no aparece" en un solo `apply`
  enviaría la API a un núcleo que no atiende el módulo.

### La imagen de un servicio que se crea después

Terraform crea los servicios; las versiones las publica el pipeline. Un servicio que nace meses
después —el de un módulo que se promueve— tiene que arrancar con la versión que corre el resto
del ambiente, no con la del primer día. Para eso el pipeline anota la etiqueta desplegada en un
parámetro (`/zonacards/<ambiente>/release`) después de cada despliegue correcto, y Terraform lo
lee al crear un servicio. Nadie tiene que pasar la etiqueta a mano en ese momento, y una
versión nueva no aparece como cambio en ningún plan: cada servicio recuerda la imagen con la
que fue creado (`terraform_data.first_image` en `modules/ecs-service`).

## Dónde se ve el autoescalado

El servicio de un módulo promovido escala si su entrada trae `autoscaling`; el núcleo, en los
valores de este ejemplo, corre un número fijo de tareas:

```hcl
promoted_modules = {
  giftcards = {
    autoscaling = {
      min        = 2
      max        = 10
      cpu_target = 60
      queue      = { name = "giftcards-generacion", backlog_per_task_target = 10 }
    }
  }
}
```

Sin `autoscaling`, `modules/ecs-service` no crea ningún `aws_appautoscaling_target` ni política.
Con él crea un *target* y dos políticas de *target tracking*:

1. CPU promedio del servicio (`ECSServiceAverageCPUUtilization`).
2. **Backlog por tarea**, con *metric math*: mensajes visibles de la cola
   (`AWS/SQS ApproximateNumberOfMessagesVisible`) ÷ tareas en ejecución
   (`ECS/ContainerInsights RunningTaskCount`). Es el patrón que documenta AWS para escalar por
   cola y necesita Container Insights, que `ecs-cluster` siempre activa.

Como `ignore_changes` no admite condiciones, el módulo tiene dos recursos `aws_ecs_service`
(`fixed` y `autoscaled`) y crea exactamente uno. En ambos Terraform ignora `task_definition`
(las revisiones las publica el pipeline); solo en `autoscaled` ignora también `desired_count`.
Pasar un servicio existente de un tipo al otro exige un `terraform state mv` previo.

### Presupuesto de conexiones

Escalar más allá de lo que admite la base no agrega capacidad: las tareas nuevas no logran
conectarse. Cada módulo tiene su rol de PostgreSQL (`<módulo>_app`) con un `CONNECTION LIMIT`, y
ese límite tiene que alcanzar para **todos los despliegues que pueden atender el módulo a la
vez**: el núcleo siempre (es donde vive por defecto y adonde puede volver) y su servicio propio
cuando lo tiene. Un `precondition` en `modules/platform/services.tf` rechaza el plan si:

- (tareas pico del núcleo + tareas pico del servicio propio) × `ZC_DB_<MÓDULO>_POOL_MAX` supera
  el `CONNECTION LIMIT` del rol, o
- la suma de esos límites más la reserva supera `max_connections` de la instancia.

"Tareas pico" es el máximo × `deployment_maximum_percent` (200 %): durante un despliegue
conviven las tareas viejas y las nuevas. `max_connections` se fija explícitamente en el
*parameter group* para que la cuenta sea exacta.

Los `terraform.tfvars` ya traen el límite de `giftcards` dimensionado para el día en que se
promueva (en `dev`: 40 del núcleo + 200 de su servicio a 10 tareas = 240), de modo que promover
no exige tocar la base. Si no alcanzara, el plan de la promoción falla con la cuenta completa
en el mensaje.

## Qué hace cada módulo

| Módulo | Qué crea | Decisiones que conviene conocer |
|---|---|---|
| `network` | VPC `/16`, subredes públicas y privadas en N zonas, NAT, endpoint de S3 | Un NAT en `dev`/`qa`, uno por zona en `prod`. Los *security groups* no viven aquí: cada módulo es dueño del suyo. |
| `ecs-cluster` | Cluster Fargate, ALB, listener, grupo de logs de Container Insights | El listener responde 404; las reglas de ruta las agrega `platform`. Con certificado: HTTPS y redirección desde el 80. |
| `ecs-service` | Definición de tarea, servicio, tres roles IAM, *security group*, autoescalado opcional y, si se le pide, la tarea de migración | Es el mismo módulo para el núcleo y para cada servicio propio. Puerto 8080 solo desde el ALB. *Circuit breaker* con rollback. `stopTimeout` de 90 s. El contenedor se llama `app` en todos. No crea su *target group* ni reglas: los recibe. Nunca da de baja una revisión (`skip_destroy`). |
| `messaging` | Topics, colas con su DLQ (14 días, 5 reintentos, *long polling* de 20 s), suscripciones con filtro por atributo `type`, llave KMS, alarmas | Dirigido por datos: no sabe nada de `lotes` ni `giftcards`. `enable_encryption` y `enable_alarms` permiten aplicarlo en un emulador. |
| `messaging/topology` | Nada: solo devuelve la topología | La usan por igual `modules/platform` y `local/`. |
| `database` | RDS PostgreSQL 18 cifrado, `rds.force_ssl=1`, contraseña maestra gestionada por RDS, secretos por módulo, tarea de *bootstrap* | Solo aceptan conexiones los *security groups* de los servicios y el de la tarea de *bootstrap*. |
| `generated-secrets` | Secretos de Secrets Manager con valor generado | Ver [Secretos](#secretos). |
| `ecr` | `zonacards/app` | Un repositorio: una imagen. Tags inmutables, escaneo al subir, política de ciclo de vida. |
| `github-oidc` | Proveedor OIDC y roles `build`, `deploy` y `plan` | Sin llaves de acceso. `deploy` solo confía en el *environment* de GitHub del mismo nombre. |
| `platform` | Todo lo anterior, cableado | Contiene el catálogo de módulos, la topología, las reglas de ruta y el contrato con el pipeline. |

### Una regla del balanceador por módulo, no por servicio

La regla de un módulo existe siempre y siempre coincide con las mismas rutas. Lo que cambia al
promoverlo es adónde reenvía, y eso es un cambio **en el sitio**: no se destruye la regla ni
cambian su prioridad ni sus rutas. El reenvío es ponderado entre el *target group* del núcleo y
el del servicio propio (100/0 o 0/100).

El peso 0 es lo que permite que el servicio nuevo exista antes de recibir tráfico: ECS solo
acepta un servicio cuyo *target group* esté asociado a un balanceador, y uno con peso 0 en una
regla lo está y no recibe nada. Es el mismo arreglo que usan los despliegues *blue/green* de
ECS, según su documentación ([pesos de 0 a 999](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/rule-action-types.html),
["set the weight to zero for any target group that should not be receiving traffic"](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/troubleshooting-blue-green.html)).
Está verificado en la documentación y en el emulador, no en AWS.

Los *target groups* viven en `platform` y no en `ecs-service` porque una regla necesita los de
dos servicios, y un servicio tiene que esperar a las reglas que asocian el suyo. Llevan
`create_before_destroy` para que, al retirar un servicio, Terraform lo saque de la regla
**antes** de borrarlo: AWS no deja borrar un *target group* que una regla todavía usa. Ese
defecto lo encontró el [ensayo](#ensayo-de-la-promoción-con-el-proveedor-real); las pruebas con
proveedor simulado no podían verlo.

### Chequeos de salud: *liveness*, no *readiness*

El *target group* del ALB y el contenedor consultan `GET /q/health/live` en el puerto 8080. Es
deliberado: en ECS un chequeo fallido del ALB hace que el planificador reemplace la tarea, y el
*readiness* incluye la base de datos, de modo que una caída breve de la base reiniciaría todas
las tareas a la vez. `/q/health/ready` queda como endpoint informativo. El chequeo del contenedor
usa `curl`, que debe existir en la imagen (se puede cambiar con `container_health_check_command`).

### Secretos

Ningún secreto llega a un contenedor como variable de entorno en claro: todos entran por
`secrets` de ECS desde Secrets Manager.

- **Contraseña maestra de RDS**: la genera y rota RDS (`manage_master_user_password`). Solo la
  lee la tarea de *bootstrap*.
- **Contraseñas de los roles** (`<módulo>_owner`, `<módulo>_app`) y **llaves de `giftcards`**
  (32 bytes aleatorios en base64): las genera Terraform con recursos *efímeros*
  (`ephemeral "random_password"`) y las escribe con argumentos de solo escritura
  (`secret_string_wo`). **No quedan en el plan ni en el estado.** Solo se reenvían cuando cambia
  la versión (`password_version`); las llaves de `giftcards` no deben regenerarse nunca, porque
  los códigos ya cifrados quedarían ilegibles.
- El rol de ejecución de un servicio propio solo puede leer los secretos de su módulo; el del
  núcleo, los de todos. La contraseña del dueño de un esquema solo existe en la tarea de
  migración, que tiene su propio rol de ejecución: ningún servicio en ejecución la recibe.

### Base de datos: *bootstrap* y migraciones

Terraform no se conecta a la base. Registra dos tareas que se ejecutan con `aws ecs run-task`:

- `zonacards-<ambiente>-db-bootstrap` (una vez por ambiente, y otra vez si cambia un límite de
  conexiones): imagen pública de `postgres:18` que corre `psql` sobre
  `deploy/db/bootstrap-service.sql`, una vez por módulo. El script va embebido en la definición
  de la tarea. Si el archivo no existe al planificar, se embebe un marcador que falla al
  ejecutarse (salida `db_bootstrap.sql_found = false`).
- `zonacards-<ambiente>-nucleo-migrate`: **una sola tarea de migración para toda la
  aplicación**. Es la misma imagen con `ZC_MODE=migrate` y las credenciales del dueño del
  esquema de cada módulo. El pipeline la ejecuta antes de actualizar ningún servicio.

Que la migración sea una tiene un costo que conviene conocer: concentra las credenciales de
dueño de todos los módulos en una tarea, y una migración que falla en un módulo detiene el
despliegue de todos.

## El contrato con el pipeline

El pipeline despliega "la aplicación" en un ambiente. Qué servicios significa eso depende de la
topología, y la topología cambia cuando se promueve un módulo. Por eso la lista no está escrita
en el workflow ni copiada en variables de GitHub: Terraform la publica en un parámetro de SSM
y el pipeline la lee al empezar cada despliegue. **Después de una promoción no hay nada que
actualizar a mano.**

| Parámetro | Lo escribe | Contenido |
|---|---|---|
| `/zonacards/<ambiente>/deploy` | Terraform | Cluster, repositorio, nombre del contenedor, subredes, tarea de migración y los servicios **en el orden en que se despliegan**: primero los de módulos promovidos, el núcleo al final |
| `/zonacards/<ambiente>/release` | El pipeline (Terraform solo lo crea) | La etiqueta de imagen desplegada |

El orden importa: el núcleo es el que puede dejar de atender un módulo, y no debe hacerlo antes
de que el servicio que lo releva esté corriendo la versión. Ninguno de los dos parámetros
contiene secretos. El rol de despliegue puede leer ambos y escribir solo el segundo; la lista
de servicios que puede actualizar se amplía en el mismo `apply` que crea un servicio nuevo.

En GitHub quedan dos variables por ambiente: `AWS_REGION` y `AWS_DEPLOY_ROLE_ARN`.

## Cómo validar

Todo lo de esta sección, salvo el plan real, corre sin credenciales y sin llamar a AWS.

```bash
cd infra/terraform
terraform fmt -check -recursive

# Sintaxis y tipos de cada raíz
for d in bootstrap local envs/dev envs/qa envs/prod; do
  (cd "$d" && terraform init -backend=false && terraform validate)
done

# Pruebas de módulos (38) y de cada ambiente (3, con y sin el ejemplo de promoción)
(cd tests && terraform init -backend=false && terraform test)
for e in dev qa prod; do
  (cd "envs/$e" && terraform test && terraform test -var-file=promocion-giftcards.tfvars)
done

# Linters
docker run --rm -v "$PWD:/data:ro" --entrypoint /bin/sh ghcr.io/terraform-linters/tflint \
  -c 'tflint --init --config /data/.tflint.hcl && tflint --recursive --config /data/.tflint.hcl'
docker run --rm -v "$PWD:/src:ro" -w /src/bootstrap aquasec/trivy config .
for e in dev qa prod; do
  docker run --rm -v "$PWD:/src:ro" -w "/src/envs/$e" aquasec/trivy config . --tf-vars terraform.tfvars
done

# La promoción y la reintegración, con el proveedor real, contra un emulador (unos 3 minutos)
make -C ../.. ensayo-terraform
```

`trivy` debe correrse por ambiente y con `--tf-vars`: si se escanea el árbol completo no lee los
`terraform.tfvars` y reporta para `prod` hallazgos que sus valores ya resuelven.

### Qué prueban las pruebas

Usan `mock_provider`: planifican (y en dos archivos, aplican) la configuración real sin
proveedor real.

| Archivo | Afirma |
|---|---|
| `tests/ecs_service.tftest.hcl` | Servicio fijo: cero `aws_appautoscaling_target` y cero políticas. Servicio autoescalado: un *target* con el mínimo y máximo declarados y dos políticas, una con la fórmula `m1 / m2` sobre SQS y `RunningTaskCount`. Chequeo de salud en `/q/health/live`. La tarea de migración no hereda nada del servicio. El rol de la tarea solo contiene las sentencias armadas con los topics y colas que recibe. Un servicio conserva la imagen con la que fue creado aunque después se publique otra, y un cambio de configuración sí llega a su definición de tarea. |
| `tests/messaging.tftest.hcl` | Una DLQ por cola, política de reenvío, los dos filtros por tipo de evento, cada *inbox* suscrito al topic del otro módulo, cifrado y alarmas; y que en modo emulador no se crea KMS ni alarmas. |
| `tests/platform.tftest.hcl` | Por defecto, un servicio atiende todos los módulos y recibe exactamente las variables y secretos de todos. Un módulo promovido obtiene un servicio de la misma imagen que recibe solo lo suyo, y solo ese servicio escala. En cada estado, quién atiende el módulo y adónde va su API. El pipeline recibe los servicios en orden. Se rechazan las topologías sin sentido (4 casos) y los cambios que rompen el presupuesto de conexiones (4 casos). No se acepta HTTP sin pedirlo. |
| `tests/promotion.tftest.hcl` | La promoción y la vuelta **aplicadas** etapa por etapa sobre un mismo estado: la regla del módulo y el servicio del núcleo son los mismos recursos del principio al final, la primera etapa no toca el núcleo, y al terminar el ambiente es idéntico al inicial. |
| `envs/*/tests/composition.tftest.hcl` | Con los `.tfvars` reales de cada ambiente, con y sin el ejemplo de promoción: un servicio por módulo promovido más el núcleo, cada módulo atendido y enrutado a un despliegue que lo atiende, el autoescalado que declara el ejemplo y el presupuesto de conexiones cumplido. |

Límites de esta evidencia, sin adornos:

- Un proveedor simulado no ejecuta la lógica de plan del proveedor real ni consulta AWS:
  planifica todo cambio como una actualización y acepta toda llamada. Estas pruebas demuestran
  qué recursos se declaran, con qué argumentos y que sus direcciones no dependen de la
  topología; no demuestran qué hace el proveedor con un argumento que cambia ni en qué orden.
  Eso lo cubre el ensayo de abajo.
- Terraform 1.16 no puede simular recursos efímeros. En las pruebas de `platform` y de los
  ambientes, `modules/generated-secrets` se reemplaza por sus salidas (`override_module`); ese
  módulo está cubierto por `terraform validate`, por el plan real y por el ensayo.
- El JSON de las políticas IAM lo genera un *data source* que el simulador no evalúa. Las
  pruebas leen las sentencias que el módulo declara (acciones y recursos), no el JSON final.

### Ensayo de la promoción con el proveedor real

`scripts/tf-ensayo-promocion.sh` (`make ensayo-terraform`) toma el Terraform de `dev` tal como
está, lo aplica completo en un emulador desechable y recorre la promoción y la vuelta. Cada
etapa la planifica el **proveedor real de AWS** y el guion falla si el plan no es el esperado.
No interviene ninguna cuenta: el proveedor recibe credenciales ficticias y todos sus *endpoints*
apuntan al emulador, que el guion arranca y retira.

Corrida del 19 de noviembre de 2026 (Terraform 1.16.2, proveedor `aws` 6.73.0, emulador
`floci/floci:2.5.0`). Ambiente base: 105 recursos.

| `apply` | Crea | Cambia en el sitio | Reemplaza | Destruye | Regla de `giftcards` | Definición de tarea del núcleo |
|---|---:|---:|---:|---:|---|---|
| Promover 1: `traffic-in-nucleo` | 16 | 5 | 0 | 0 | cambia en el sitio | sin cambios |
| Promover 2: sin `transition` | 0 | 3 | 1 | 0 | cambia en el sitio | revisión nueva |
| Reintegrar 1: `traffic-in-service` | 0 | 2 | 1 | 0 | sin cambios | revisión nueva |
| Reintegrar 2: `traffic-in-nucleo` | 0 | 2 | 0 | 0 | cambia en el sitio | sin cambios |
| Reintegrar 3: fuera de `promoted_modules` | 0 | 5 | 0 | 16 | cambia en el sitio | sin cambios |

Lo que se lee en la tabla:

- **La regla del módulo nunca se reemplaza.** En las cuatro etapas en que cambia, el proveedor
  planifica `update in-place`, y lo único que cambia dentro es el reenvío.
- **Lo único que se reemplaza es la definición de tarea del núcleo**, dos veces, y la única
  diferencia es `ZC_MODULES`. "Reemplazar" aquí es registrar una revisión: las definiciones de
  tarea son inmutables. El plan lo cuenta como `1 to add, 1 to destroy`, pero la revisión
  anterior no se da de baja (`skip_destroy`) y el servicio sigue en ella hasta el redespliegue.
- Los "cambios en el sitio" restantes son el parámetro que lee el pipeline, la política del rol
  de despliegue (cuando aparece o desaparece un servicio) y dos registros internos de la
  topología y del presupuesto.
- La vuelta destruye exactamente los 16 recursos que creó la promoción y un plan posterior no
  encuentra nada que cambiar.

El ensayo comprueba además dos cosas del contrato con el pipeline: que anotar una versión nueva
en el parámetro `release` no produce ningún cambio en el plan, y que un servicio creado después
nace con esa versión (`app:1.1.0` en la corrida).

Qué **no** demuestra:

- El emulador no ejecuta tareas. Nada de lo anterior habla de tráfico, chequeos de salud,
  despliegues ni autoescalado. El comportamiento de la aplicación durante una promoción se
  ejercita en local con `make e2e` (ver el README de la raíz).
- Un emulador no es AWS: que acepte una llamada no prueba que AWS la acepte. Sí aplica las dos
  reglas que importan aquí (un servicio necesita su *target group* asociado a un balanceador, y
  no se puede borrar un *target group* en uso), y fue así como apareció el defecto de orden que
  hoy corrige `create_before_destroy`.
- Tres adaptaciones, todas por el emulador y ninguna en el repositorio: el puerto de la base en
  la copia de trabajo (el emulador publica el suyo), planes sin relectura de recursos (el
  emulador devuelve las referencias a *security groups* en otro formato) y no contar la
  reescritura de la descripción de un parámetro (el emulador la pierde al sobrescribirlo).
  Están explicadas al inicio del guion.

### Hallazgos de los linters

`tflint`: sin hallazgos. Una regla desactivada en `.tflint.hcl`
(`terraform_standard_module_structure`), porque `tests/` y `messaging/topology` no son módulos
convencionales a propósito.

`trivy config`: sin hallazgos altos ni críticos, con y sin el ejemplo de promoción. Cuatro
supresiones en línea, cada una explicada junto al recurso:

| Regla | Dónde | Por qué se acepta |
|---|---|---|
| AWS-0104 | salida del *security group* de los servicios | Subredes privadas detrás del NAT; las API de AWS, Keycloak y el colector OTLP no tienen un rango fijo. |
| AWS-0104 | salida HTTPS de la tarea de *bootstrap* | Solo el puerto 443, para el registro de imágenes, Secrets Manager y logs. |
| AWS-0053 | ALB público | Es la entrada pública del API; `alb_ingress_cidrs` lo restringe. |
| AWS-0054 | listener HTTP | Con certificado solo redirige. Sin certificado, `platform` rechaza el plan salvo que el ambiente declare `alb_allow_plain_http = true` (solo `dev`). |

Quedan sin suprimir, como decisiones conscientes de un ejemplo de referencia: grupos de logs,
repositorio y secretos cifrados con llaves gestionadas por AWS en vez de llaves propias
(AWS-0017, AWS-0033, AWS-0098); RDS sin autenticación IAM, porque el contrato usa roles con
contraseña (AWS-0176); protección de borrado y *flow logs* apagados en `dev` y `qa`, activos en
`prod` (AWS-0177, AWS-0178); y el bucket de estado sin registro de accesos (AWS-0089).

## Cómo planificar

Requiere credenciales de AWS con permisos de lectura. El plan no crea nada.

```bash
cd infra/terraform/envs/dev

# Opción A: estado remoto (bucket creado por ../../bootstrap)
cp backend.hcl.example backend.hcl   # y poner el nombre real del bucket
terraform init -backend-config=backend.hcl

# Opción B: estado local, sin depender del bucket
printf 'terraform {\n  backend "local" {}\n}\n' > backend_override.tf   # ignorado por git
terraform init

terraform plan                                          # todos los módulos en el núcleo
terraform plan -var-file=promocion-giftcards.tfvars     # con giftcards en su servicio
```

`envs/dev/terraform.tfvars` trae valores para todas las variables, así que el plan de `dev`
corre tal cual. Tres de ellos son marcadores que hay que cambiar antes de aplicar de verdad:

| Variable | Valor de ejemplo | Qué es |
|---|---|---|
| `oidc_issuer` | `https://keycloak.example.com/realms/zonacards` | URL del *realm* de Keycloak del cliente |
| `github_repository` | `example-org/zonacards` | Repositorio que puede asumir los roles (`null` = sin roles de GitHub) |
| `image_tag` | `bootstrap` | Etiqueta de la primera imagen (ver abajo) |

`qa` y `prod` **no planifican sin certificado**: hay que descomentar `certificate_arn` en su
`terraform.tfvars` o pasarlo con `-var`. Es intencional, para que ninguno quede en HTTP por
omisión.

Para ver el plan de un ambiente antes de que exista el bucket de estado, sin tocar esta carpeta:

```bash
AWS_PROFILE=<perfil> scripts/tf-plan.sh dev
AWS_PROFILE=<perfil> scripts/tf-plan.sh dev -var-file=promocion-giftcards.tfvars
AWS_PROFILE=<perfil> scripts/tf-plan.sh qa -var certificate_arn=<arn del certificado>
```

El guion planifica una copia con estado local desechable; solo lee de AWS y nunca aplica.

### Plan contra una cuenta real

Ejecutado el 19 de noviembre de 2026 en `us-east-1`, con Terraform 1.16.2 y el proveedor
`aws` 6.73.0, sobre una cuenta sin recursos de Zonacards. Todos los planes salieron sin errores
ni advertencias.

| Raíz | Todos los módulos en el núcleo | Con `promocion-giftcards.tfvars` | Autoescalado del servicio de `giftcards` |
|---|---|---|---|
| `bootstrap` | 7 por crear | no aplica | no aplica |
| `envs/dev` | 105 por crear, 0 por cambiar, 0 por destruir | 121 por crear | de 2 a 10 tareas |
| `envs/qa` | 102 por crear | 118 por crear | de 2 a 12 |
| `envs/prod` | 124 por crear | 140 por crear | de 3 a 30 |

En los tres ambientes la promoción agrega los mismos 16 recursos, y el núcleo queda fijo (2
tareas en `dev` y `qa`, 3 en `prod`). En `dev` se planificaron además los dos estados de la
promoción por separado: con `transition = "traffic-in-nucleo"` la regla de `giftcards` reparte
100/0 entre el núcleo y el servicio nuevo y el núcleo conserva `ZC_MODULES=giftcards,lotes`; sin
`transition`, 0/100 y `ZC_MODULES=lotes`. El presupuesto de conexiones de `giftcards` queda en
(4 + 20) × 10 = 240, justo su límite.

`qa` y `prod` se planificaron con un ARN de certificado de marcador (`-var certificate_arn=…`);
sin él, el plan se rechaza con `Invalid value for variable`, como está previsto. `qa` tiene
menos recursos que `dev` porque no crea los de nivel de cuenta.

Un plan sobre un estado vacío solo dice qué se crearía: no puede mostrar qué cambia al pasar de
un estado a otro (eso lo muestra el ensayo) ni llama a las API de creación. Se comprobó aparte,
con consultas de solo lectura a la misma cuenta, lo que el plan no valida:

| Comprobación | Resultado |
|---|---|
| PostgreSQL 18 en RDS | Disponible (18.1, 18.2, 18.3, 18.4 y 18.6); `engine_version = "18"` resuelve hoy a 18.3, con la familia `postgres18` |
| Clases `db.t4g.medium` (`dev`, `qa`) y `db.m7g.large` (`prod`) con PostgreSQL 18 | Se pueden pedir con `gp3`, Multi-AZ y Performance Insights |
| Parámetros `rds.force_ssl` y `max_connections` | Existen y son modificables; el primero es dinámico y el segundo estático, como los declara el módulo |
| Política TLS del balanceador y servicio del *endpoint* de S3 | Existen en la región |
| Políticas conocidas en el plan (6 de confianza, 2 de identidad y la de la llave KMS) | IAM Access Analyzer (`validate-policy`): sin hallazgos |
| Las 43 acciones IAM que usan los módulos, incluidas las dos de SSM del rol de despliegue | Todas existen (Access Analyzer no reporta ninguna acción inválida) |
| Nombres que el plan crearía (repositorio, parámetros, roles, colas, topics, secretos, alias KMS, *log groups*, *target groups*, RDS, balanceador, proveedor OIDC de GitHub) | Ninguno existe ya en la cuenta |

Lo que sigue sin comprobarse, porque solo se sabe al aplicar en AWS:

- Siete políticas IAM en línea, tres de confianza, las dos políticas de cola y las definiciones
  de contenedor dependen de ARN que no existen hasta crear los recursos (`known after apply`),
  así que su JSON final no pasó por AWS. Las pruebas nativas cubren qué sentencias declaran.
- Que AWS acepte un servicio de ECS cuyo *target group* solo está asociado con peso 0. Lo
  sostiene la documentación citada arriba y lo acepta el emulador.
- Las cuotas de la cuenta más allá de VPC y direcciones elásticas, y los permisos de quien
  aplique.
- El comportamiento del escalado y el de una promoción con tráfico real.

`create_account_level_resources` debe ser `true` en un solo ambiente por cuenta de AWS: ese
ambiente es dueño del repositorio ECR, del proveedor OIDC de GitHub y del rol de *build*. Los
valores del ejemplo asumen que `dev` y `qa` comparten una cuenta y `prod` usa otra.

### Primer despliegue

Los servicios necesitan una imagen antes de que el pipeline haya publicado alguna:

1. `bootstrap/`: `terraform apply` (bucket de estado), una vez por cuenta.
2. Repositorio: `terraform apply -target=module.zonacards.module.ecr`.
3. Construir y subir la imagen con la etiqueta de `image_tag`.
4. `terraform apply` completo.
5. Ejecutar la tarea de *bootstrap* de la base (salida `db_bootstrap`) y luego la tarea de
   migración (salida `migrate`).
6. Forzar un despliegue del núcleo, o dejar que lo haga el primer despliegue del pipeline.

Entre los pasos 4 y 5 las tareas del núcleo arrancan contra una base sin roles ni esquemas y
fallan; el *circuit breaker* puede dar por fallido ese primer despliegue. El paso 6 lo resuelve. A partir de ahí Terraform no toca la imagen de ningún
servicio ni, en los autoescalados, el número de tareas. `image_tag` no vuelve a leerse: desde
el primer despliegue manda el parámetro `release`.

### Emulador local

`local/` aplica únicamente `modules/messaging` con la misma topología que AWS, sin KMS ni
alarmas, contra `http://localhost:4566` (variable `endpoint`). Usa credenciales ficticias y
estado local. Sus salidas (`topic_arns`, `queue_urls`) son mapas por nombre lógico. Es lo que
usa `make up`; el ensayo de la promoción usa un emulador aparte y no toca el sistema local.

## Qué NO se aprovisiona

- **Keycloak**: es del cliente. Entra como `oidc_issuer` y `oidc_audience`.
- **Certificado ACM y DNS**: `certificate_arn` es una variable; no hay registros de Route 53. No
  se usan *data sources* que exijan recursos preexistentes.
- **Colector de OpenTelemetry**: si `otel_exporter_otlp_endpoint` está vacío, los servicios
  arrancan con `QUARKUS_OTEL_SDK_DISABLED=true`.
- **Destino de las alarmas**: `alarm_topic_arn` es opcional; sin él las alarmas existen pero no
  notifican a nadie.
- **Imágenes y revisiones de las tareas**: las publica el pipeline. Terraform registra la
  primera revisión de cada servicio y una más cada vez que cambia su configuración, pero no
  mueve ningún servicio a ella.
- **Roles, esquemas y migraciones de la base**: los crean las tareas descritas arriba, no
  Terraform.
- **WAF, CloudFront, copias entre regiones y paneles de observabilidad**: fuera del alcance de
  este ejemplo.
