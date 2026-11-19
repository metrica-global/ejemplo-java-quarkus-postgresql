# Infraestructura de Zonacards (Terraform)

Dos servicios Quarkus en ECS Fargate (`lotes` y `giftcards`), eventos por SNS → SQS y una
instancia PostgreSQL 18 con un esquema por servicio. Lo que este código demuestra: **solo
`giftcards` autoescala**; `lotes` corre un número fijo de tareas, y la diferencia entre ambos en
la composición del ambiente es un bloque `autoscaling` de unas 15 líneas.

Nada de esto se ha aplicado en AWS. Está validado de forma estática y con pruebas que planifican
contra proveedores simulados (ver [Cómo validar](#cómo-validar)).

## Estructura

```
bootstrap/            bucket S3 del estado remoto (se aplica una vez por cuenta)
modules/
  network/            VPC, subredes públicas y privadas, NAT (único módulo comunitario)
  ecs-cluster/        cluster con Container Insights, ALB con respuesta 404 por defecto
  ecs-service/        servicio Fargate: tareas, roles, target group, autoescalado OPCIONAL
  messaging/          topics, colas, DLQ, suscripciones con filtro, KMS, alarmas
    topology/         la topología (qué topics, qué colas, qué filtros), declarada una sola vez
  database/           RDS PostgreSQL 18 y la tarea que crea roles y esquemas
  generated-secrets/  secretos generados que nunca quedan en el estado
  ecr/                repositorios de imágenes
  github-oidc/        proveedor OIDC y roles para GitHub Actions
  platform/           la composición completa de un ambiente
envs/{dev,qa,prod}/   una llamada a modules/platform + terraform.tfvars
local/                solo modules/messaging, contra un emulador local
tests/                pruebas nativas de los módulos (*.tftest.hcl) y sus mocks
```

### Por qué existe `modules/platform`

Los tres ambientes llaman a un único módulo de composición. `main.tf`, `variables.tf`,
`outputs.tf`, `providers.tf`, `versions.tf` y `tests/` son **idénticos byte a byte** en `dev`,
`qa` y `prod`; lo único que cambia es `terraform.tfvars` (y `backend.hcl`). Así el cableado que
no debe variar —qué cola alimenta a qué servicio, qué rol publica dónde, qué secreto llega a qué
contenedor— vive en un solo lugar y los ambientes no pueden divergir. Para comprobarlo:
`diff envs/dev/main.tf envs/prod/main.tf` no debe mostrar nada.

## Dónde se ve el autoescalado de un solo dominio

En `envs/<ambiente>/main.tf` los dos servicios están uno junto al otro:

```hcl
lotes = {
  ...
  desired_count = var.lotes_tasks # fijo
}

giftcards = {
  ...
  autoscaling = {
    min        = var.giftcards_min_tasks
    max        = var.giftcards_max_tasks
    cpu_target = var.giftcards_cpu_target
    queue = {
      name                    = "giftcards-generacion"
      backlog_per_task_target = var.giftcards_backlog_per_task_target
    }
    ...
  }
}
```

Sin `autoscaling`, `modules/ecs-service` no crea ningún `aws_appautoscaling_target` ni política.
Con él crea un target y dos políticas de *target tracking*:

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
conectarse. Un `precondition` en `modules/platform/services.tf` rechaza el plan si:

- tareas pico × `ZC_DB_POOL_MAX` supera el `CONNECTION LIMIT` del rol `<servicio>_app`, o
- la suma de esos límites más la reserva supera `max_connections` de la instancia.

"Tareas pico" es el máximo × `deployment_maximum_percent` (200 %): durante un despliegue
conviven las tareas viejas y las nuevas. `max_connections` se fija explícitamente en el
*parameter group* para que la cuenta sea exacta.

## Qué hace cada módulo

| Módulo | Qué crea | Decisiones que conviene conocer |
|---|---|---|
| `network` | VPC `/16`, subredes públicas y privadas en N zonas, NAT, endpoint de S3 | Un NAT en `dev`/`qa`, uno por zona en `prod`. Los *security groups* no viven aquí: cada módulo es dueño del suyo. |
| `ecs-cluster` | Cluster Fargate, ALB, listener, grupo de logs de Container Insights | El listener responde 404; cada servicio agrega su regla de ruta. Con certificado: HTTPS y redirección desde el 80. |
| `ecs-service` | Task definition, servicio, tres roles IAM, *security group*, target group, regla de ruta, autoescalado opcional | Puerto 8080 solo desde el ALB. *Circuit breaker* con rollback. `stopTimeout` de 90 s. Permisos armados desde `publish_topic_arns`, `consume_queue_arns` y `send_queue_arns`. |
| `messaging` | Topics, colas con su DLQ (14 días, 5 reintentos, *long polling* de 20 s), suscripciones con filtro por atributo `type`, llave KMS, alarmas | Dirigido por datos: no sabe nada de `lotes` ni `giftcards`. `enable_encryption` y `enable_alarms` permiten aplicarlo en un emulador. |
| `messaging/topology` | Nada: solo devuelve la topología | La usan por igual `modules/platform` y `local/`. |
| `database` | RDS PostgreSQL 18 cifrado, `rds.force_ssl=1`, contraseña maestra gestionada por RDS, secretos por servicio, tarea de *bootstrap* | Solo aceptan conexiones los *security groups* de los servicios y el de la tarea de *bootstrap*. |
| `generated-secrets` | Secretos de Secrets Manager con valor generado | Ver [Secretos](#secretos). |
| `ecr` | `zonacards/lotes-service`, `zonacards/giftcards-service` | Tags inmutables, escaneo al subir, política de ciclo de vida. |
| `github-oidc` | Proveedor OIDC y roles `build`, `deploy` y `plan` | Sin llaves de acceso. `deploy` solo confía en el *environment* de GitHub del mismo nombre. |
| `platform` | Todo lo anterior, cableado | Contiene el contrato con los servicios: variables, secretos, rutas y permisos de cada uno. |

### Chequeos de salud: *liveness*, no *readiness*

El target group del ALB y el contenedor consultan `GET /q/health/live` en el puerto 8080. Es
deliberado: en ECS un chequeo fallido del ALB hace que el planificador reemplace la tarea, y el
*readiness* incluye la base de datos, de modo que una caída breve de la base reiniciaría todas
las tareas a la vez. `/q/health/ready` queda como endpoint informativo. El chequeo del contenedor
usa `curl`, que debe existir en la imagen (se puede cambiar con `container_health_check_command`).

### Secretos

Ningún secreto llega a un contenedor como variable de entorno en claro: todos entran por
`secrets` de ECS desde Secrets Manager.

- **Contraseña maestra de RDS**: la genera y rota RDS (`manage_master_user_password`). Solo la
  lee la tarea de *bootstrap*.
- **Contraseñas de los roles** (`<servicio>_owner`, `<servicio>_app`) y **llaves de
  `giftcards`** (32 bytes aleatorios en base64): las genera Terraform con recursos *efímeros*
  (`ephemeral "random_password"`) y las escribe con argumentos de solo escritura
  (`secret_string_wo`). **No quedan en el plan ni en el estado.** Solo se reenvían cuando cambia
  la versión (`password_version`); las llaves de `giftcards` no deben regenerarse nunca, porque
  los códigos ya cifrados quedarían ilegibles.
- El rol de ejecución de cada servicio solo puede leer los secretos de ese servicio. La
  contraseña del dueño del esquema solo existe en la tarea `<servicio>-migrate`, que tiene su
  propio rol de ejecución: el servicio en ejecución nunca la recibe.

### Base de datos: *bootstrap* y migraciones

Terraform no se conecta a la base. Registra dos tipos de tarea que se ejecutan con
`aws ecs run-task`:

- `zonacards-<ambiente>-db-bootstrap` (una vez por ambiente): imagen pública de `postgres:18`
  que corre `psql` sobre `deploy/db/bootstrap-service.sql`, una vez por servicio. El script va
  embebido en la definición de la tarea. Si el archivo no existe al planificar, se embebe un
  marcador que falla al ejecutarse (salida `db_bootstrap.sql_found = false`).
- `zonacards-<ambiente>-<servicio>-migrate`: la misma imagen del servicio con `ZC_MODE=migrate`
  y las credenciales del dueño del esquema. El pipeline la ejecuta antes de actualizar el
  servicio.

## Cómo validar

Todo esto corre sin credenciales y sin llamar a AWS.

```bash
cd infra/terraform
terraform fmt -check -recursive

# Sintaxis y tipos de cada raíz
for d in bootstrap local envs/dev envs/qa envs/prod; do
  (cd "$d" && terraform init -backend=false && terraform validate)
done

# Pruebas de módulos (19) y de cada ambiente (2 por ambiente)
(cd tests && terraform init -backend=false && terraform test)
for e in dev qa prod; do (cd "envs/$e" && terraform test); done

# Linters
docker run --rm -v "$PWD:/data:ro" --entrypoint /bin/sh ghcr.io/terraform-linters/tflint \
  -c 'tflint --init --config /data/.tflint.hcl && tflint --recursive --config /data/.tflint.hcl'
docker run --rm -v "$PWD:/src:ro" -w /src/bootstrap aquasec/trivy config .
for e in dev qa prod; do
  docker run --rm -v "$PWD:/src:ro" -w "/src/envs/$e" aquasec/trivy config . --tf-vars terraform.tfvars
done
```

`trivy` debe correrse por ambiente y con `--tf-vars`: si se escanea el árbol completo no lee los
`terraform.tfvars` y reporta para `prod` hallazgos que sus valores ya resuelven.

### Qué prueban las pruebas

Usan `command = plan` con `mock_provider`: planifican la configuración real sin proveedor real.

| Archivo | Afirma |
|---|---|
| `tests/ecs_service.tftest.hcl` | Servicio fijo: cero `aws_appautoscaling_target` y cero políticas. Servicio autoescalado: un target con el mínimo y máximo declarados y dos políticas, una con la fórmula `m1 / m2` sobre SQS y `RunningTaskCount`. Chequeos de salud en `/q/health/live`. La tarea de migración no hereda nada del servicio. El rol de la tarea solo contiene las sentencias armadas con los topics y colas que recibe. |
| `tests/messaging.tftest.hcl` | Una DLQ por cola, política de reenvío, los dos filtros por tipo de evento, cada *inbox* suscrito al topic del otro dominio, cifrado y alarmas; y que en modo emulador no se crea KMS ni alarmas. |
| `tests/platform.tftest.hcl` | Solo escala el servicio que declara `autoscaling`. Cada contenedor recibe exactamente las variables y secretos del contrato. El presupuesto de conexiones rechaza el plan en tres casos (`expect_failures`). No se acepta HTTP sin pedirlo. |
| `envs/*/tests/composition.tftest.hcl` | Con los `terraform.tfvars` reales de cada ambiente: `lotes` sin autoescalado, `giftcards` con el rango configurado y dos políticas, y el presupuesto de conexiones cumplido. |

Límites de esta evidencia, sin adornos:

- Un proveedor simulado no ejecuta la lógica de plan del proveedor real ni consulta AWS. Estas
  pruebas demuestran qué recursos se declaran y con qué argumentos, no que AWS los acepte. Eso
  lo cubre el `terraform plan` real ([resultado](#plan-contra-una-cuenta-real)), y el
  comportamiento del escalado solo se observa aplicando.
- Terraform 1.16 no puede simular recursos efímeros. En las pruebas de `platform` y de los
  ambientes, `modules/generated-secrets` se reemplaza por sus salidas (`override_module`); ese
  módulo solo está cubierto por `terraform validate`.
- El JSON de las políticas IAM lo genera un *data source* que el simulador no evalúa. Las
  pruebas leen las sentencias que el módulo declara (acciones y recursos), no el JSON final.

### Hallazgos de los linters

`tflint`: sin hallazgos. Una regla desactivada en `.tflint.hcl`
(`terraform_standard_module_structure`), porque `tests/` y `messaging/topology` no son módulos
convencionales a propósito.

`trivy config`: sin hallazgos altos ni críticos. Cuatro supresiones en línea, cada una explicada
junto al recurso:

| Regla | Dónde | Por qué se acepta |
|---|---|---|
| AWS-0104 | salida del *security group* de los servicios | Subredes privadas detrás del NAT; las API de AWS, Keycloak y el colector OTLP no tienen un rango fijo. |
| AWS-0104 | salida HTTPS de la tarea de *bootstrap* | Solo el puerto 443, para el registro de imágenes, Secrets Manager y logs. |
| AWS-0053 | ALB público | Es la entrada pública del API; `alb_ingress_cidrs` lo restringe. |
| AWS-0054 | listener HTTP | Con certificado solo redirige. Sin certificado, `platform` rechaza el plan salvo que el ambiente declare `alb_allow_plain_http = true` (solo `dev`). |

Quedan sin suprimir, como decisiones conscientes de un ejemplo de referencia: grupos de logs,
repositorios y secretos cifrados con llaves gestionadas por AWS en vez de llaves propias
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

terraform plan
```

`envs/dev/terraform.tfvars` trae valores para todas las variables, así que el plan de `dev`
corre tal cual. Tres de ellos son marcadores que hay que cambiar antes de aplicar de verdad:

| Variable | Valor de ejemplo | Qué es |
|---|---|---|
| `oidc_issuer` | `https://keycloak.example.com/realms/zonacards` | URL del *realm* de Keycloak del cliente |
| `github_repository` | `example-org/zonacards` | Repositorio que puede asumir los roles (`null` = sin roles de GitHub) |
| `image_tag` | `bootstrap` | Tag de la primera imagen (ver abajo) |

`qa` y `prod` **no planifican sin certificado**: hay que descomentar `certificate_arn` en su
`terraform.tfvars` o pasarlo con `-var`. Es intencional, para que ninguno quede en HTTP por
omisión.

Para ver el plan de un ambiente antes de que exista el bucket de estado, sin tocar esta carpeta:

```bash
AWS_PROFILE=<perfil> scripts/tf-plan.sh dev
AWS_PROFILE=<perfil> scripts/tf-plan.sh qa -var certificate_arn=<arn del certificado>
```

El guion planifica una copia con estado local desechable; solo lee de AWS y nunca aplica.

### Plan contra una cuenta real

Ejecutado el 19 de noviembre de 2026 en `us-east-1`, con Terraform 1.16.2 y el proveedor
`aws` 6.73.0, sobre una cuenta sin recursos de Zonacards. No hizo falta cambiar ningún archivo
de Terraform: los cuatro planes salieron sin errores ni advertencias al primer intento.

| Raíz | Resultado | Autoescalado planificado |
|---|---|---|
| `bootstrap` | 7 por crear | no aplica |
| `envs/dev` | 121 por crear, 0 por cambiar, 0 por destruir | `giftcards` de 2 a 10 tareas; `lotes` fijo en 2 |
| `envs/qa` | 116 por crear | `giftcards` de 2 a 12; `lotes` fijo en 2 |
| `envs/prod` | 140 por crear | `giftcards` de 3 a 30; `lotes` fijo en 3 |

`qa` y `prod` se planificaron con un ARN de certificado de marcador (`-var certificate_arn=…`);
sin él, el plan se rechaza con `Invalid value for variable`, como está previsto. `qa` tiene
menos recursos que `dev` porque no crea los de nivel de cuenta.

Un plan sobre un estado vacío no llama a las API de creación, así que se comprobó aparte, con
consultas de solo lectura a la misma cuenta, lo que el plan no valida:

| Comprobación | Resultado |
|---|---|
| PostgreSQL 18 en RDS | Disponible (18.1, 18.2, 18.3, 18.4 y 18.6); `engine_version = "18"` resuelve hoy a 18.3, con la familia `postgres18` |
| Clases `db.t4g.medium` (`dev`, `qa`) y `db.m7g.large` (`prod`) con PostgreSQL 18 | Se pueden pedir con `gp3`, Multi-AZ y Performance Insights |
| Parámetros `rds.force_ssl` y `max_connections` | Existen y son modificables; el primero es dinámico y el segundo estático, como los declara el módulo |
| Política TLS del balanceador y servicio del *endpoint* de S3 | Existen en la región |
| Políticas conocidas en el plan (7 de confianza, 2 de identidad y la de la llave KMS) | IAM Access Analyzer (`validate-policy`): sin hallazgos |
| Las 43 acciones IAM que usan los módulos | Todas existen |
| Nombres que el plan crearía (roles, colas, topics, secretos, alias KMS, *log groups*, RDS, balanceador, proveedor OIDC de GitHub) | Ninguno existe ya en la cuenta |

Lo que sigue sin comprobarse, porque solo se sabe al aplicar:

- Ocho políticas IAM en línea, tres de confianza, las dos políticas de cola y las definiciones
  de contenedor dependen de ARN que no existen hasta crear los recursos (`known after apply`),
  así que su JSON final no pasó por AWS. Las pruebas nativas cubren qué sentencias declaran.
- Las cuotas de la cuenta más allá de VPC y direcciones elásticas, y los permisos de quien
  aplique.
- El comportamiento del escalado, que solo se observa desplegando.

`create_account_level_resources` debe ser `true` en un solo ambiente por cuenta de AWS: ese
ambiente es dueño de los repositorios ECR, del proveedor OIDC de GitHub y del rol de *build*.
Los valores del ejemplo asumen que `dev` y `qa` comparten una cuenta y `prod` usa otra.

### Primer despliegue

Los servicios necesitan una imagen antes de que el pipeline haya publicado alguna:

1. `bootstrap/`: `terraform apply` (bucket de estado), una vez por cuenta.
2. Repositorios: `terraform apply -target=module.zonacards.module.ecr`.
3. Construir y subir ambas imágenes con el tag de `image_tag`.
4. `terraform apply` completo.
5. Ejecutar la tarea de *bootstrap* de la base (salida `db_bootstrap`) y luego las tareas
   `<servicio>-migrate` (salida `services`).
6. Forzar un despliegue de cada servicio, o dejar que lo haga el primer despliegue del pipeline.

Entre los pasos 4 y 5 las tareas de los servicios arrancan contra una base sin roles ni
esquemas y fallan; el *circuit breaker* puede dar por fallido ese primer despliegue. El paso 6
lo resuelve. A partir de ahí Terraform no toca la *task definition* ni, en `giftcards`, el número
de tareas.

### Emulador local

`local/` aplica únicamente `modules/messaging` con la misma topología que AWS, sin KMS ni
alarmas, contra `http://localhost:4566` (variable `endpoint`). Usa credenciales ficticias y
estado local. Sus salidas (`topic_arns`, `queue_urls`) son mapas por nombre lógico.

## Qué NO se aprovisiona

- **Keycloak**: es del cliente. Entra como `oidc_issuer` y `oidc_audience`.
- **Certificado ACM y DNS**: `certificate_arn` es una variable; no hay registros de Route 53. No
  se usan *data sources* que exijan recursos preexistentes.
- **Colector de OpenTelemetry**: si `otel_exporter_otlp_endpoint` está vacío, los servicios
  arrancan con `QUARKUS_OTEL_SDK_DISABLED=true`.
- **Destino de las alarmas**: `alarm_topic_arn` es opcional; sin él las alarmas existen pero no
  notifican a nadie.
- **Imágenes y revisiones de las tareas**: las publica el pipeline. Terraform solo crea la
  primera revisión.
- **Roles, esquemas y migraciones de la base**: los crean las tareas descritas arriba, no
  Terraform.
- **WAF, CloudFront, copias entre regiones y paneles de observabilidad**: fuera del alcance de
  este ejemplo.
