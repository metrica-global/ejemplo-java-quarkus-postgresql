# CI/CD

Qué hace cada workflow de `.github/workflows`, en qué orden corren, qué detiene una versión, qué
hay que configurar en GitHub y cómo volver atrás.

**Estado de esta evidencia.** Los workflows están validados de forma estática con `actionlint`
(que incluye `shellcheck` para los scripts) y **no se han ejecutado en GitHub**: el repositorio
aún no se ha publicado y la infraestructura de AWS no se ha aplicado. Lo que sí se corrió
localmente y lo que queda por confirmar está en [Qué se validó y qué no](#qué-se-validó-y-qué-no).

## Recorrido de un cambio

```
pull request ──► ci.yml            build, pruebas, arquitectura, cobertura
             ├─► security.yml      CodeQL, dependencias, secretos, IaC, (SonarQube), (Snyk)
             └─► terraform.yml     solo si cambia infra/terraform: fmt, validate, tflint,
                                   escaneo, pruebas y plan por ambiente

merge a main ──► ci.yml ──► build-push.yml ──► deploy.yml
                            imagen por servicio   dev ──► qa ──► prod
                            afectado              (aprobación en qa y en prod)
```

`build-push.yml` arranca cuando `ci.yml` termina bien en `main`, y `deploy.yml` cuando
`build-push.yml` termina bien. Ninguno de los dos se dispara con un *pull request*.

## Qué hace cada workflow

| Archivo | Cuándo corre | Qué hace |
|---|---|---|
| `ci.yml` | *pull request* y *push* a `main` | `make verify RUN=`: compila, pruebas unitarias y de integración (Testcontainers), ArchUnit y JaCoCo. Publica los reportes de cobertura como artefacto y los de pruebas si algo falla |
| `security.yml` | *pull request*, *push* a `main`, semanal y manual | CodeQL para Java, revisión de dependencias del *pull request*, secretos, análisis de IaC (cada raíz de Terraform y el `Dockerfile`), y SonarQube y Snyk si existe su token |
| `build-push.yml` | después de `ci.yml` en `main`, y manual | Construye la imagen de los servicios afectados para `linux/amd64` y `linux/arm64`, la escanea, genera el SBOM, la firma, la atestigua y la etiqueta con el SHA del commit en ECR |
| `deploy.yml` | después de `build-push.yml`, y manual | Despliega en `dev`, luego `qa` y luego `prod` los servicios cuya imagen se publicó. No construye nada |
| `deploy-environment.yml` | solo lo llama `deploy.yml` | Los pasos de un ambiente: comprobar la imagen, registrar revisiones, migrar, actualizar el servicio y esperar |
| `terraform.yml` | cambios en `infra/terraform/**` (y en `deploy/db/bootstrap-service.sql`, que Terraform embebe) | `fmt -check`, `validate` de cada raíz, `tflint`, escaneo, `terraform test` y, en *pull requests*, `terraform plan` por ambiente. Nunca aplica |
| `dependabot.yml` | semanal | Actualizaciones agrupadas de Maven, GitHub Actions, Docker y Terraform |

Todas las acciones de terceros están fijadas a un SHA de commit completo, con la versión en un
comentario; Dependabot actualiza ambos. Cada workflow declara `permissions: contents: read` y
amplía permisos solo en el job que los necesita.

### `security.yml`

| Job | Herramienta | Bloquea cuando |
|---|---|---|
| `codeql` | CodeQL (`java-kotlin`, sin compilar, consultas `security-extended`) | No falla por hallazgos: los publica en *code scanning*, donde una regla de rama puede exigirlos |
| `dependency-review` | `dependency-review-action` | El *pull request* agrega una dependencia con una vulnerabilidad `high` o `critical` |
| `secret-scan` | gitleaks sobre los commits | Encuentra un secreto |
| `iac` | Trivy (`config`) | Hay un hallazgo `HIGH` o `CRITICAL` en Terraform o en el `Dockerfile` |
| `sonarqube` | SonarQube Server o Cloud | El *quality gate* falla (`sonar.qualitygate.wait=true`) |
| `snyk` | Snyk Open Source | Hay una vulnerabilidad `high` o `critical` en las dependencias |

- **SonarQube y Snyk son opcionales.** Sus jobs corren solo si existe el secreto `SONAR_TOKEN`
  o `SNYK_TOKEN`. En un *fork*, en un *pull request* de Dependabot o en un repositorio sin esos
  secretos aparecen como omitidos, no como fallidos.
- **SonarQube** vuelve a correr `make verify RUN=` porque el análisis de Java necesita las clases
  compiladas y los reportes de JaCoCo. Usa el escáner genérico con las rutas del build
  multi-módulo; SonarSource recomienda su escáner de Maven, que exigiría agregar el plugin al POM.
- **gitleaks** pide una licencia (gratuita) cuando el repositorio pertenece a una organización.
  Sin el secreto `GITLEAKS_LICENSE`, el job usa en su lugar el escáner de secretos de Trivy, que
  revisa los archivos del commit pero no el historial.
- **CodeQL, la revisión de dependencias y la carga de reportes SARIF** están disponibles en
  repositorios públicos y, en privados, con GitHub Code Security. Donde no lo estén, la variable
  `CODE_SECURITY_ENABLED=false` omite esos pasos; el análisis de IaC sigue bloqueando igual.
- El análisis de IaC se hace **por ambiente y con su `terraform.tfvars`**, como indica
  `infra/terraform/README.md`.

### `build-push.yml`

1. **Servicios afectados.** Para cada servicio busca en ECR la última imagen publicada desde un
   commit anterior y compara con `git diff`. Un cambio en `libs/**`, `pom.xml`, `Dockerfile`,
   `.dockerignore`, `mvnw` o `.mvn/**` afecta a los dos; un cambio en `services/<x>-service/**`,
   solo a ese. Se compara contra la última imagen y no contra el commit anterior para que un
   commit cuyo CI o build falló no deje cambios sin construir. Si ningún servicio está afectado
   (por ejemplo, un commit que solo toca `docs/` o `infra/`), no se construye ni se despliega nada.
2. **Build y push por digest.** Una sola construcción para `linux/amd64` y `linux/arm64`; la
   etapa de compilación del `Dockerfile` corre una vez. La imagen se sube **sin etiqueta**.
3. **Escaneo de vulnerabilidades (bloqueante).** Trivy revisa las dos plataformas directamente
   en el registro. Falla con vulnerabilidades `HIGH` o `CRITICAL` **que tengan corrección
   disponible** (`ignore-unfixed`); las que aún no tienen parche no bloquean.
4. **SBOM** en formato SPDX, de la variante `linux/arm64` (la que corre en Fargate). Queda como
   artefacto del workflow.
5. **Firma** sin llaves (`cosign`, identidad OIDC del workflow) y **atestaciones** de
   procedencia (SLSA) y del SBOM con `actions/attest`.
6. **Etiqueta.** Solo entonces la imagen recibe la etiqueta con el SHA del commit.

La etiqueta va al final porque las etiquetas de ECR son inmutables: **una etiqueta que existe es
una imagen que pasó el escaneo y fue firmada**. Si un paso falla, queda una imagen sin etiqueta
que la política de ciclo de vida del repositorio elimina, y el mismo commit se puede volver a
construir. Si el workflow se repite para un commit que ya tiene imagen, la reutiliza.

El último job publica el artefacto `release-manifest` (`release.json`): la etiqueta y, por
servicio, el digest. Es la entrada de `deploy.yml`.

Una ejecución manual construye, desde `main`, los servicios que se elijan aunque no tengan
cambios.

Para comprobar una imagen publicada (registro, digest y repositorio según el caso):

```bash
cosign verify \
  --certificate-identity "https://github.com/<org>/<repo>/.github/workflows/build-push.yml@refs/heads/main" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  <registro>/zonacards/lotes-service@<digest>

gh attestation verify "oci://<registro>/zonacards/lotes-service@<digest>" --repo <org>/<repo>
```

**Producción en otra cuenta de AWS.** Los repositorios de ECR existen una vez por cuenta, y las
*task definitions* de cada ambiente apuntan al registro de su propia cuenta. Si `prod` vive en
otra cuenta (el caso que asumen los `terraform.tfvars` de ejemplo), el job `replicate` copia la
imagen de registro a registro, **con el mismo digest**, y la firma también allí. Se activa
definiendo la variable `AWS_BUILD_ROLE_ARN_PROD`.

### `deploy.yml` y `deploy-environment.yml`

Por cada servicio de `release.json`, en cada ambiente:

1. **Comprobar la imagen.** La etiqueta debe existir en el registro del ambiente y resolver al
   digest que publicó el build. La promoción es de la misma imagen; aquí no hay `docker build`.
2. **Registrar revisiones.** Toma la última revisión de la *task definition* de migración y de la
   del servicio, cambia la imagen del contenedor y registra una revisión nueva de cada una. Todo
   lo demás (roles, variables, secretos, tamaño, etiquetas) se conserva, de modo que un cambio que
   Terraform haya hecho en la familia llega al servicio en este paso.
3. **Migrar.** Ejecuta la tarea `<servicio>-migrate` en las subredes privadas y espera a que
   termine. Si el código de salida del contenedor no es `0` (o el contenedor no llegó a
   arrancar), el job falla y **el servicio no se toca**. El log de la tarea queda en el job.
4. **Actualizar el servicio y esperar.** Sigue el despliegue que inició hasta que ECS lo marca
   `COMPLETED`. Si el *circuit breaker* lo marca `FAILED`, ECS vuelve solo a la revisión anterior
   y el job falla: un *rollback* nunca se reporta como versión entregada.

`dev` se despliega sin aprobación. `qa` empieza cuando `dev` terminó bien y sus revisores
aprueban; `prod`, cuando `qa` terminó bien y los suyos aprueban. Los dos servicios se despliegan
en paralelo y de forma independiente; un ambiente se considera bien desplegado solo si ambos
terminan bien.

Hay un despliegue a la vez por servicio y ambiente. Si llega una ejecución más nueva, espera a la
que está en curso y reemplaza a una anterior que siguiera en espera.

El despliegue **no verifica la firma** de la imagen: el rol `deploy` puede consultar el registro
por la API de AWS, pero no autenticarse en él para leer la firma. Lo que comprueba es que la
etiqueta exista (solo la reciben las imágenes que pasaron el escaneo y la firma) y que su digest
sea el que publicó el build.

### `terraform.yml`

| Job | Qué corre |
|---|---|
| `fmt` | `terraform fmt -check -recursive` |
| `validate` | `terraform init -backend=false` y `terraform validate` en `bootstrap`, `local`, `envs/dev`, `envs/qa` y `envs/prod` |
| `tflint` | `tflint --recursive` con `infra/terraform/.tflint.hcl` |
| `iac-scan` | Trivy (`config`) en `bootstrap` y en cada ambiente con su `terraform.tfvars`; bloquea con `HIGH` o `CRITICAL` |
| `test` | `terraform test` en `infra/terraform/tests` y en cada ambiente (proveedores simulados, sin credenciales) |
| `plan` | Solo en *pull requests* del propio repositorio: `terraform plan` de `dev`, `qa` y `prod` con el rol de solo lectura, publicado en el resumen del job |

`init` usa `-lockfile=readonly`: los proveedores deben ser los del archivo de bloqueo.

El plan de un ambiente se omite, sin fallar, mientras no existan sus variables (los roles se
crean al aplicar la infraestructura por primera vez). `qa` y `prod` no planifican sin
`certificate_arn` en su `terraform.tfvars`. El archivo del plan no se guarda como artefacto
porque puede contener secretos en claro.

**Ningún workflow ejecuta `terraform apply`.** Aplicar es una acción de una persona con sus
propias credenciales.

## Qué bloquea una versión

Antes de fusionar (como verificaciones obligatorias de la rama `main`, ver
[Protección de `main`](#protección-de-main)):

- `ci.yml`: compilación, cualquier prueba, las reglas de arquitectura.
- `security.yml`: dependencia vulnerable nueva, secreto, hallazgo de IaC alto o crítico, y el
  *quality gate* de SonarQube y Snyk si están configurados.
- `terraform.yml`: formato, validación, `tflint`, escaneo o pruebas, cuando cambia la
  infraestructura.

Después de fusionar:

| Compuerta | Dónde | Efecto si falla |
|---|---|---|
| CI en `main` | `ci.yml` | `build-push.yml` no arranca |
| Vulnerabilidad `HIGH`/`CRITICAL` con corrección | `build-push.yml` | La imagen no recibe etiqueta; no hay despliegue |
| Firma o atestación | `build-push.yml` | Igual |
| La etiqueta no existe o su digest no coincide | `deploy-environment.yml` | El ambiente no se toca |
| Migración con código de salida distinto de `0` | `deploy-environment.yml` | El servicio sigue en la revisión anterior |
| El despliegue no llega a `COMPLETED` | `deploy-environment.yml` | ECS vuelve a la revisión anterior; no se promueve |
| Aprobación de `qa` y de `prod` | *environments* de GitHub | El job espera; si se rechaza, no se despliega |

`security.yml` también corre en `main`, pero `build-push.yml` solo espera a `ci.yml`. Los
controles de seguridad detienen un cambio **antes de fusionarlo**, y eso depende de que la rama
esté protegida.

## Roles de AWS y ambientes

No hay llaves de acceso guardadas en GitHub. Cada job que habla con AWS pide un token OIDC y
asume un rol de `infra/terraform/modules/github-oidc`; solo esos jobs tienen `id-token: write`.

| Rol | Nombre en AWS | Confía en el sujeto | Lo asume | Puede |
|---|---|---|---|---|
| `build` | `zonacards-<amb>-github-build` | `repo:<org>/<repo>:ref:refs/heads/main` | `build-push.yml` | Subir y leer imágenes en los dos repositorios de ECR |
| `deploy` | `zonacards-<amb>-github-deploy` | `repo:<org>/<repo>:environment:<amb>` | `deploy-environment.yml` | Registrar *task definitions*, ejecutar las tareas de migración, actualizar los dos servicios y leer sus logs, solo en ese ambiente |
| `plan` | `zonacards-<amb>-github-plan` | `repo:<org>/<repo>:pull_request` | `terraform.yml` (`plan`) | Solo lectura |

Consecuencias que conviene tener presentes:

- El rol `build` existe una vez por cuenta de AWS: lo crea el ambiente que tiene
  `create_account_level_resources = true`.
- El rol `deploy` de un ambiente confía en **cualquier job del repositorio que declare ese
  *environment***. Lo que impide que una rama cualquiera despliegue es la configuración del
  *environment* en GitHub: revisores y ramas permitidas.
- El sujeto de un job que declara un *environment* deja de ser `pull_request` o
  `ref:refs/heads/main`. Por eso los jobs de build y de plan no declaran ninguno, y por eso las
  variables del plan son del repositorio y no del *environment*.
- El rol `plan` lo puede asumir cualquier *pull request* abierto desde una rama del repositorio,
  con el workflow tal como venga en esa rama. Además de `ReadOnlyAccess` puede leer los secretos
  `zonacards-<amb>-*`. **Dar permiso de escritura en el repositorio equivale a dar esa lectura en
  las cuentas de AWS**, incluida la de producción. Los *forks* no reciben token OIDC.

## Qué hay que configurar en GitHub

### Environments

Crear tres *environments* con estos nombres exactos (son parte del sujeto OIDC):

| Environment | Revisores requeridos | Ramas permitidas |
|---|---|---|
| `dev` | ninguno | solo `main` |
| `qa` | sí | solo `main` |
| `prod` | sí, sin autoaprobación | solo `main` |

### Variables de cada environment

Los mismos nombres en `dev`, `qa` y `prod`; el valor sale de las salidas de Terraform de ese
ambiente (`infra/terraform/envs/<ambiente>`).

| Variable | Salida de Terraform |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `github_roles.deploy` |
| `ECS_CLUSTER` | `cluster_name` |
| `ECS_SERVICES` | `services`, completa, en JSON. De ahí se leen por servicio `service_name`, `container_name`, `task_definition_family`, `migrate_task_definition_family`, `security_group_id`, `log_group_name` y `repository_url` |
| `ECS_PRIVATE_SUBNET_IDS` | `private_subnet_ids`, en JSON |
| `AWS_REGION` (solo si difiere de la del repositorio) | `region` |

### Variables del repositorio

| Variable | Valor | Obligatoria |
|---|---|---|
| `AWS_REGION` | Salida `region` | Sí |
| `AWS_BUILD_ROLE_ARN` | Salida `github_roles.build` del ambiente dueño de los recursos de la cuenta (`dev` en el ejemplo). Sin ella, `build-push.yml` se omite | Sí |
| `AWS_BUILD_ROLE_ARN_PROD` | Salida `github_roles.build` de `prod`, **solo** si `prod` está en otra cuenta de AWS | No |
| `AWS_PLAN_ROLE_ARN_DEV`, `AWS_PLAN_ROLE_ARN_QA`, `AWS_PLAN_ROLE_ARN_PROD` | Salida `github_roles.plan` de cada ambiente | Para el plan |
| `TF_STATE_BUCKET_DEV`, `TF_STATE_BUCKET_QA`, `TF_STATE_BUCKET_PROD` | Salida `state_bucket_name` de `infra/terraform/bootstrap` en la cuenta de cada ambiente | Para el plan |
| `TF_PLAN_LOCK` | `true` si los ambientes se aplicaron con `state_bucket_arn` (el rol de plan puede tomar el bloqueo del estado). Por defecto el plan corre con `-lock=false` | No |
| `SONAR_PROJECT_KEY` | Llave del proyecto en SonarQube | Con SonarQube |
| `SONAR_HOST_URL` | URL de SonarQube Server; vacía para SonarQube Cloud | Con SonarQube Server |
| `SONAR_ORGANIZATION` | Organización en SonarQube Cloud | Con SonarQube Cloud |
| `CODE_SECURITY_ENABLED` | `false` en un repositorio privado sin GitHub Code Security | No |
| `ARTIFACT_ATTESTATIONS_ENABLED` | `false` en un repositorio privado fuera de GitHub Enterprise Cloud | No |

El plan lee el estado en `zonacards/<ambiente>/terraform.tfstate` del bucket, en la región
`AWS_REGION` (la misma disposición que `backend.hcl.example`).

### Secretos del repositorio

| Secreto | Para qué | Obligatorio |
|---|---|---|
| `SONAR_TOKEN` | Activa el job de SonarQube | No |
| `SNYK_TOKEN` | Activa el job de Snyk | No |
| `GITLEAKS_LICENSE` | gitleaks en repositorios de una organización | No |

No hay ningún secreto de AWS.

### Cargar las variables desde Terraform

```bash
# Variables del repositorio (dev es dueño de los recursos de la cuenta no productiva)
cd infra/terraform/envs/dev
gh variable set AWS_REGION         --body "$(terraform output -raw region)"
gh variable set AWS_BUILD_ROLE_ARN --body "$(terraform output -json github_roles | jq -r .build)"

# Variables por ambiente
for amb in dev qa prod; do
  cd "../$amb"
  sufijo="$(printf '%s' "$amb" | tr '[:lower:]' '[:upper:]')"
  gh variable set "AWS_PLAN_ROLE_ARN_${sufijo}" --body "$(terraform output -json github_roles | jq -r .plan)"
  gh variable set AWS_DEPLOY_ROLE_ARN    --env "$amb" --body "$(terraform output -json github_roles | jq -r .deploy)"
  gh variable set ECS_CLUSTER            --env "$amb" --body "$(terraform output -raw cluster_name)"
  gh variable set ECS_SERVICES           --env "$amb" --body "$(terraform output -json services)"
  gh variable set ECS_PRIVATE_SUBNET_IDS --env "$amb" --body "$(terraform output -json private_subnet_ids)"
done

# Bucket de estado: salida de infra/terraform/bootstrap en la cuenta de cada ambiente
gh variable set TF_STATE_BUCKET_DEV --body "<state_bucket_name>"
```

`github_roles` es `null` si el ambiente se aplicó sin `github_repository`. `ECS_SERVICES` hay que
volver a cargarla si cambian los nombres o los *security groups* de los servicios.

### Protección de `main`

Los controles de los *pull requests* solo bloquean si la rama los exige. Como mínimo: *pull
request* obligatorio y, como verificaciones requeridas, `build, tests, architecture` (de
`ci.yml`) y los jobs de `security.yml`. Los jobs de `terraform.yml` solo existen cuando cambia la
infraestructura; marcarlos como requeridos dejaría en espera a los *pull requests* que no la
tocan, salvo que se use una regla que lo contemple.

## Cómo volver atrás

**Automático.** Si las tareas de una versión nueva no quedan sanas, el *circuit breaker* de ECS
vuelve a la revisión anterior sin intervención. El job de despliegue termina en error y la
versión no se promueve al siguiente ambiente.

**Manual.** `deploy.yml` se puede ejecutar a mano, desde `main`, con tres datos: ambiente,
servicio (`all`, `lotes` o `giftcards`) y etiqueta de imagen. Para volver a una versión anterior
se indica el SHA del commit de esa versión:

```bash
gh workflow run deploy.yml --ref main \
  -f environment=prod -f service=giftcards -f image_tag=<sha-del-commit-anterior>
```

Hace exactamente lo mismo que un despliegue normal, con la imagen indicada: comprueba que la
etiqueta exista en el registro del ambiente, registra revisiones nuevas, ejecuta la tarea de
migración y actualiza el servicio. En `qa` y `prod` pide la misma aprobación.

- La etiqueta y el digest de cada despliegue quedan en el resumen de su ejecución; ahí se
  encuentra la versión anterior.
- **El esquema no retrocede.** La tarea de migración de la imagen anterior no deshace las
  migraciones más nuevas. Por eso las migraciones deben ser compatibles con la versión anterior
  del servicio (ver `docs/operacion.md`).
- Solo se puede volver a imágenes que sigan en ECR; la política de ciclo de vida conserva las
  más recientes (`max_image_count` en `infra/terraform/modules/ecr`).
- La misma ejecución manual sirve para desplegar en un solo ambiente una imagen ya publicada, y
  para llevar a un servicio un cambio que Terraform hizo en su *task definition* (variables,
  secretos, tamaño): Terraform registra la revisión pero no actualiza el servicio, así que el
  cambio entra con el siguiente despliegue de ese servicio o con una ejecución manual que indique
  la etiqueta que ya está corriendo.

## Equivalencia con un pipeline clásico de Jenkins

| Etapa del pipeline de Jenkins | Aquí | Detalle |
|---|---|---|
| Checkout | todos | `actions/checkout`, sin dejar credenciales en el clon |
| Compilación | `ci.yml` | `make verify RUN=` (Maven, JDK 25) |
| Pruebas unitarias y de integración | `ci.yml` | surefire y failsafe con Testcontainers, más ArchUnit |
| Cobertura | `ci.yml` | JaCoCo; reportes como artefacto `coverage-reports` |
| SonarQube y *quality gate* | `security.yml` | job `sonarqube` |
| Snyk | `security.yml` | job `snyk`; además `dependency-review` y CodeQL |
| Análisis de IaC | `security.yml` | job `iac`; el mismo escaneo en `terraform.yml` |
| Construcción de la imagen | `build-push.yml` | una construcción, dos arquitecturas |
| Escaneo de la imagen | `build-push.yml` | Trivy, bloqueante |
| Publicación en ECR | `build-push.yml` | por digest, etiqueta con el SHA al final, SBOM, firma y atestaciones |
| Despliegue en desarrollo | `deploy.yml` | job `dev`, automático |
| Aprobación manual y promoción | `deploy.yml` | jobs `qa` y `prod`, con los revisores del *environment* |
| *Rollback* | `deploy.yml` | *circuit breaker* de ECS, o ejecución manual con la etiqueta anterior |
| Infraestructura como código | `terraform.yml` | validación, pruebas y plan; sin `apply` |
| Credenciales del pipeline | roles OIDC | sin llaves de larga duración que guardar o rotar |

## Qué se validó y qué no

Validado:

- `actionlint` (con `shellcheck`) sobre los seis workflows, sin hallazgos:
  `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:latest -color`.
- Cada acción fijada corresponde, por SHA, a la etiqueta de versión de su comentario, y las
  entradas usadas existen en la versión fijada.
- Los comandos que corren `terraform.yml` y el job `iac` se ejecutaron localmente con las mismas
  versiones (Terraform 1.16.2, tflint 0.66.0, Trivy 0.70.0) y terminan sin error. El análisis de
  secretos sobre los archivos del repositorio tampoco reporta hallazgos.
- La lógica de los scripts (servicios afectados, manifiesto, registro de revisiones, migración,
  seguimiento del despliegue) se ejercitó localmente contra un AWS CLI simulado, incluidos los
  casos de fallo.

No validado, y pendiente de la primera ejecución real:

- **Nada se ha ejecutado en GitHub Actions ni contra AWS.** No están probados la confianza OIDC
  de los tres roles, el encadenamiento `ci` → `build-push` → `deploy`, las aprobaciones de los
  *environments* ni la construcción multi-arquitectura en un *runner*.
- Que los permisos de los roles de `modules/github-oidc` alcancen para todo lo que hacen los
  workflows. En particular, la firma y las atestaciones se guardan en ECR como artefactos
  asociados a la imagen; hay que confirmar que el rol `build` puede escribirlos y que la
  inmutabilidad de etiquetas y la política de ciclo de vida no interfieren con ellos.
- El comportamiento de la tarea de migración cuando se despliega una imagen anterior a un
  esquema más nuevo.
- Los jobs de SonarQube y Snyk: dependen de un servidor y de cuentas que este repositorio no
  tiene.

### Observaciones abiertas sobre la infraestructura

Salieron al contrastar los workflows con `infra/terraform`. No bloquean el ejemplo, pero deben
decidirse antes de usarlo con una cuenta real:

| Observación | Dónde | Qué hacer |
|---|---|---|
| El rol `plan` puede leer los secretos del ambiente desde cualquier *pull request* de una rama del repositorio | `modules/github-oidc` (confianza `pull_request`, lectura de `zonacards-<amb>-*`) | Para producción, limitar el plan a `main` o a un *environment* con revisores, o planificar producción fuera de los PR |
| El rol `deploy` no puede detener tareas | `modules/github-oidc` (sin `ecs:StopTask`) | Si una migración supera el tiempo límite, el job falla pero la tarea sigue; añadir el permiso o detenerla a mano |
| El rol `deploy` no puede autenticarse en ECR | `modules/github-oidc` (sin `ecr:GetAuthorizationToken`) | Necesario si se quiere verificar la firma de la imagen antes de desplegar |
| Producción en otra cuenta no recibe las imágenes por sí sola | `modules/platform/main.tf` (registro por cuenta) | Usar el job `replicate` o la replicación de ECR entre cuentas |
| Un cambio de Terraform en una *task definition* no llega al servicio hasta el siguiente despliegue | `modules/ecs-service` (`ignore_changes`) | Tras un `apply` que cambie variables o secretos, ejecutar `deploy.yml` a mano con la etiqueta vigente |
| Una llave KMS propia en el bucket de estado rompería el plan | `bootstrap/main.tf` | El rol `plan` no tiene `kms:Decrypt`; con la llave administrada por AWS no hay problema |
| No está confirmado cómo trata la política de ciclo de vida de ECR a firmas y atestaciones | `modules/ecr/main.tf` | Comprobarlo con la primera imagen firmada |
| Dependabot probablemente no actualiza la imagen base | `Dockerfile` (la etiqueta se arma con un `ARG`) | Revisarla a mano o fijar la etiqueta completa |

Dos cosas a saber antes de usarlo en un repositorio privado: la firma con `cosign` registra la
identidad del workflow (organización, repositorio y ruta del workflow) en el registro público de
transparencia de Sigstore; y la versión de la CLI de Snyk no está fijada (se instala la última).
