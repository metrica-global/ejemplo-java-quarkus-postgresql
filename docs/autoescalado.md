# Escalado de un solo módulo

Por defecto toda la aplicación corre en un servicio, el **núcleo**, con un número fijo de
tareas. Un módulo que necesita capacidad propia se **promueve** a servicio propio: ese servicio
escala entre un mínimo y un máximo según su carga, y el núcleo no cambia. Este documento
explica por qué `giftcards` es ese módulo, qué se gana al promoverlo, cómo escala y qué parte
de todo ello está comprobada.

## Por qué `giftcards`

| | `lotes` | `giftcards` |
|---|---|---|
| Trabajo | Recibir solicitudes, llevar el estado del lote | Generar miles de códigos y proteger cada CVV con Argon2id |
| Costo por operación | Milisegundos, sobre todo base de datos | Decenas de milisegundos de CPU **por gift card** |
| Carga | Estable | A ráfagas: nada, y de pronto un lote de 200 000 |
| Qué lo limita | Nada, en la práctica | La CPU de las tareas que lo atienden |

Un lote grande son horas de CPU. Dentro del núcleo ese trabajo comparte la CPU de cada tarea
con las peticiones de los demás módulos, y agregar tareas al núcleo agrega también generadores.
Con tareas fijas habría que elegir entre pagar capacidad ociosa casi siempre o hacer esperar
horas a cada lote.

## Qué se gana al promover: la medición

```bash
make demo-promocion               # un lote de 6 000 gift cards
scripts/demo-promocion.sh 12000
```

El guion mide los tiempos de respuesta de `GET /api/v1/lotes`, vistos por un cliente que hace
una petición tras otra, mientras se genera un lote grande, en cuatro situaciones. Cada réplica
está limitada a 1 vCPU; antes de cada medición corre un calentamiento. Empieza y termina con
todo en el núcleo.

Corrida del 19 de noviembre de 2026 (lote de 6 000 gift cards en 24 fragmentos):

| Topología | vCPU en total | Lote (s) | Peticiones a `lotes` | Fallidas | p50 (ms) | p95 (ms) | p99 (ms) | Máx (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| núcleo ×2, sin generar | 2 | - | 478 | 0 | 5 | 6 | 8 | 15 |
| núcleo ×2, generando | 2 | 180,4 | 2 405 | 0 | 5 | 26 | 49 | 89 |
| núcleo ×4, generando | 4 | 138,2 | 1 478 | 0 | 11 | 50 | 83 | 176 |
| núcleo ×2 + `giftcards` ×2, generando | 4 | 183,6 | 2 434 | 0 | 5 | 16 | 20 | 69 |

Cómo leerlo:

- **Generar dentro del núcleo no rompe la API, pero la hace irregular.** Ninguna petición
  falla y la mediana no se mueve; la cola de la distribución sí: el p99 pasa de 8 a 49 ms.
- **Escalar el núcleo entero no lo arregla.** Con el doble de vCPU el lote puede terminar antes
  (138 s en esta corrida; 179 s, sin mejora, en la anterior), pero cada réplica nueva también
  genera, así que la API sigue compartiendo su CPU. En este equipo quedó peor que con dos
  réplicas: mediana de 11 ms y p99 de 83 ms.
- **Promover sí.** Con los mismos 4 vCPU, repartidos en dos réplicas que solo atienden `lotes`
  y dos que solo generan, el p99 baja de 49 a 20 ms y la mediana vuelve a 5 ms.
- **No vuelve del todo al valor en reposo** (p99 de 8 ms). En un portátil las réplicas siguen
  compartiendo el procesador físico, la base de datos y el gateway; es la explicación más
  probable, no una que este guion aísle.
- **Promover no acelera el lote**: con las mismas dos réplicas generando tarda lo mismo (180 s
  frente a 184 s). Lo que hace es que ese trabajo deje de afectar a los demás módulos y que su
  capacidad se pueda aumentar por separado, que es lo que mide la sección siguiente.

Una corrida anterior del mismo día dio el mismo cuadro: p99 de 48 ms generando en el núcleo, 90
con el núcleo ×4 y 21 con el módulo promovido. Los límites de la medición: un solo cliente, a
unas 13 peticiones por segundo, en un portátil, y diferencias de decenas de milisegundos. Es
una demostración del mecanismo, no una prueba de carga.

## Qué hace posible escalar horizontalmente

No es una propiedad de la infraestructura sino del diseño del módulo:

- **El trabajo se reparte por una cola**, no por peticiones. Cualquier tarea que atienda el
  módulo puede tomar cualquier fragmento; no hay afinidad ni estado en memoria.
- **Cada fragmento es idempotente y se reclama en la base de datos.** Dos tareas que reciban el
  mismo fragmento no generan dos veces.
- **El trabajo pesado ocurre fuera de la transacción.** Las tareas no compiten por bloqueos
  mientras calculan; la transacción solo inserta.
- **Las conexiones están presupuestadas.** Más tareas son más conexiones: las tareas pico de
  todos los servicios que pueden atender el módulo × su *pool* deben caber en el límite del rol
  y en la instancia (lo verifica Terraform).
- **Apagado ordenado.** Al reducir, una tarea termina lo que tiene o lo devuelve a la cola.

Son las mismas propiedades que permiten promover el módulo sin corte: durante la promoción el
núcleo y el servicio propio son, sencillamente, más consumidores de la misma cola.

## En AWS

El servicio de un módulo promovido trae su bloque `autoscaling`
(`infra/terraform/envs/*/promocion-giftcards.tfvars`); el núcleo, en los valores de este
ejemplo, es fijo:

```hcl
nucleo_tasks = 2                    # fijo: sin target de escalado, sin políticas

promoted_modules = {
  giftcards = {
    autoscaling = {
      min        = 2
      max        = 10
      cpu_target = 60
      queue = {                     # backlog por tarea de la cola de trabajo
        name                    = "giftcards-generacion"
        backlog_per_task_target = 10
      }
      scale_out_cooldown = 60
      scale_in_cooldown  = 300
    }
  }
}
```

Sin el bloque no se crea ningún recurso de escalado. Con él:

1. Un `aws_appautoscaling_target` sobre `DesiredCount` del servicio, con el mínimo y el máximo.
2. Una política de *target tracking* sobre la **CPU promedio** del servicio.
3. Una política de *target tracking* sobre el **backlog por tarea**, con *metric math*:

   ```
   m1 = AWS/SQS  ApproximateNumberOfMessagesVisible   (cola giftcards-generacion)
   m2 = ECS/ContainerInsights  RunningTaskCount       (servicio de giftcards)
   e1 = m1 / m2                                       <- el valor que se mantiene en el objetivo
   ```

Con dos políticas sobre el mismo target, Application Auto Scaling agrega tareas cuando
**cualquiera** lo pide y las retira solo cuando **ambas** lo permiten. Se agregan rápido (60 s
de enfriamiento) y se retiran despacio (300 s).

Terraform ignora `desired_count` en el servicio autoescalado (lo gobierna la política) y lo
respeta en el fijo. Las pruebas nativas afirman: el núcleo, cero targets y cero políticas; el
servicio de un módulo promovido, un target y dos políticas, una con la expresión `m1 / m2`.

El núcleo admite el mismo bloque (`nucleo = { autoscaling = { … } }`) si algún día debe crecer
entero; aquí se deja fijo para que se vea que **solo escala el módulo promovido**. El servicio
de `giftcards` tampoco baja a cero: además de consumir su cola atiende su API.

### Calibrar el objetivo de backlog

El objetivo responde a una pregunta de negocio: *¿cuánto puede esperar un fragmento en la cola?*

```
backlog por tarea objetivo = espera aceptable ÷ tiempo de proceso de un mensaje
```

Un mensaje es un fragmento de `ZC_GIFTCARDS_FRAGMENT_SIZE` gift cards (1 000 por defecto; el
entorno local usa 250 para que las demos tengan más fragmentos). En las mediciones locales una
réplica de 1 vCPU tardó entre 30 y 57 ms por gift card según la corrida, es decir, del orden de
30 a 60 s por fragmento de 1 000. Con una espera aceptable de 5 minutos el objetivo estaría
entre 5 y 10. El valor `10` del ejemplo es un punto de partida, no una medición: **debe
ajustarse con el tiempo por fragmento medido en Fargate**, que dependerá del tamaño de tarea
elegido.

Dos consecuencias del mismo número: el *visibility timeout* de la cola (300 s) debe superar con
holgura el tiempo de un fragmento, y el `stopTimeout` de la tarea (90 s) debe dar tiempo a
terminar el fragmento en curso al reducir; si no alcanza, el fragmento vuelve a la cola y otra
tarea lo repite sin efectos duplicados.

### Presupuesto de conexiones

Cada tarea abre hasta `ZC_DB_<MÓDULO>_POOL_MAX` conexiones por módulo que atiende. Un
`precondition` rechaza el plan si:

- (tareas pico del núcleo + tareas pico del servicio propio) × *pool* supera el
  `CONNECTION LIMIT` del rol `<módulo>_app`, o
- la suma de los límites más la reserva supera `max_connections` de la instancia.

"Tareas pico" es el máximo × 200 %: en un despliegue conviven las tareas viejas y las nuevas. Y
se cuentan los dos servicios porque durante una promoción ambos atienden el módulo. Subir el
máximo de tareas sin revisar la base no pasa el plan.

## En local

Application Auto Scaling no existe fuera de AWS. Lo que sí se puede hacer en un portátil:

### 1. Medir que más réplicas del módulo promovido drenan más rápido

```bash
make demo-escalado               # 4 000 gift cards con 1, 2 y 4 réplicas de giftcards
scripts/demo-escalado.sh 8000 1 2 4 8
```

El guion promueve `giftcards` si vive en el núcleo y lo reintegra al terminar. Todo lo que
imprime está medido en esa corrida. Cada réplica de `giftcards` está limitada a 1 vCPU; el
núcleo conserva sus réplicas y el guion falla si cambian. Antes de cada medición corre un lote
de calentamiento, para no medir al compilador JIT.

La última columna dice cuántos fragmentos guardó cada réplica, tomada de los logs de la propia
aplicación: es el reparto real del trabajo.

Corrida del 19 de noviembre de 2026 (Argon2id `m=19456 KiB, t=2`, la configuración por defecto):

| Réplicas de giftcards | Réplicas del núcleo | Tiempo (s) | Gift cards/s | Aceleración | Fragmentos por réplica |
|---:|---:|---:|---:|---:|---|
| 1 | 2 | 118,5 | 34 | 1,00x | 16 |
| 2 | 2 | 97,2 | 41 | 1,22x | 9 + 7 |
| 4 | 2 | 81,9 | 49 | 1,45x | 4 + 4 + 4 + 4 |

**Qué demuestra.** Las réplicas que se agregan toman trabajo de la misma cola sin que nadie se
lo reparta, cada fragmento lo guarda una sola réplica (la última columna suma siempre 16), el
lote termina antes con cada réplica que se suma y el núcleo no cambia. Eso es lo que depende
del diseño.

**Qué no demuestra: una aceleración lineal.** Con cuatro réplicas el lote fue 1,45 veces más
rápido, no cuatro. Lo que se sabe de por qué, y lo que no:

- **El equipo no entrega cuatro veces más.** El portátil de la prueba (Intel Core Ultra 7 155H)
  tiene núcleos de dos velocidades y trabaja en su tope térmico (100 °C). En una prueba de CPU
  pura y sin relación con la aplicación (`openssl speed sha256` durante 60 s, cada proceso en un
  contenedor limitado a 1 vCPU), cuatro procesos a la vez rindieron en conjunto 2,9 veces lo
  que uno solo: cada uno entre el 58 % y el 92 % de lo que rinde cuando corre solo. Ese es el
  techo de este equipo.
- **La cifra cambia de una corrida a otra.** El mismo guion, en el mismo equipo y con la misma
  configuración del hash, dio 1,65x y 1,80x con dos y cuatro réplicas en la versión anterior
  de este ejemplo. Los tiempos absolutos también cambian: con una réplica, 118 s en esta
  corrida y 226 s en aquella. El procesador ajusta su frecuencia según la temperatura, y eso
  pesa aquí tanto como cualquier otro factor.
- **El hash es caro en memoria a propósito, y es un candidato.** Argon2id usa 19 MiB por CVV;
  varias réplicas calculando a la vez compiten por la caché y por el ancho de banda de memoria
  del mismo procesador. Se repitió la demo con el mismo trabajo y poca memoria (`m=1024 KiB,
  t=38`: el mismo producto de memoria por pasadas; solo para esta comparación, no es una
  configuración recomendada):

  | Réplicas de giftcards | Tiempo (s) | Gift cards/s | Aceleración |
  |---:|---:|---:|---:|
  | 1 | 98,7 | 41 | 1,00x |
  | 2 | 63,0 | 63 | 1,57x |
  | 4 | 51,3 | 78 | 1,92x |

  Es más que 1,22x y 1,45x, pero queda dentro de lo que la cifra varía entre corridas con la
  configuración por defecto, y un control anterior con `m=7168 KiB, t=5` no había mostrado
  diferencia (1,15x y 1,55x). Apunta a la memoria; no lo demuestra.

**Estas mediciones no aíslan la causa.** Lo que dicen es que en este portátil, con cuatro
réplicas, la aceleración ha estado entre 1,45x y 1,92x según la corrida y la configuración, bajo
un techo de 2,9x, y que el resto del sistema local (el núcleo, PostgreSQL, el emulador, Keycloak
y Grafana) comparte ese mismo procesador.

En Fargate cada tarea tiene su propia asignación de CPU en hosts distintos; la cifra local
muestra el mecanismo y es un piso, no una predicción. El costo del hash se puede cambiar en
local con `ZC_GIFTCARDS_ARGON2_MEMORY_KIB` y `ZC_GIFTCARDS_ARGON2_ITERATIONS`.

### 2. Ver el ciclo completo de la política, simulado

```bash
make demo-autoescalado
```

**Es una simulación y así se anuncia al ejecutarse.** El guion hace de controlador: lee de la
cola real los mensajes en espera, los divide entre las réplicas en ejecución, aplica la regla
de *target tracking* (`réplicas deseadas = réplicas actuales × métrica ÷ objetivo`, entre el
mínimo y el máximo) y cambia las réplicas de `giftcards` con docker compose. Como la política
pertenece al servicio propio del módulo, el guion lo promueve antes y lo reintegra al final.

| Real | Simulado |
|---|---|
| La cola, su *backlog* y los mensajes | El controlador (lo hace un guion, no AWS) |
| Las réplicas que arrancan, toman trabajo y se retiran | Los tiempos: evalúa cada 5 s y no cada 60; enfría 10 s y 30 s en lugar de 60 s y 300 s |
| El tiempo que tarda el lote | La fórmula exacta de AWS, que no es pública: se usa la regla proporcional documentada |

Corrida del 19 de noviembre de 2026 (`make demo-autoescalado`: 12 000 gift cards, objetivo de 4
mensajes en espera por réplica, entre 2 y 6 réplicas; abreviada):

```
 t (s)  en espera  en vuelo  giftcards  backlog/tarea  núcleo    decisión
     0          0         0          2            0.0  2          -
     5         44         4          2           22.0  2          escalar a 6
    25         34        12          6            5.7  2          -
    62         32        12          6            5.3  2          -
    85         22        12          6            3.7  2          -
    91         20        12          6            3.3  2          (esperando para reducir)
   122         17        12          6            2.8  2          reducir a 5
   182          3        10          5            0.6  2          (esperando para reducir)
   205          0         9          5            0.0  2          (esperando para reducir)
   216          0         4          5            0.0  2          reducir a 2
        -> lote GENERADO a los 230s; ahora la cola está vacía y la política retira réplicas

Lote generado en 230s. giftcards volvió a 2 réplicas; el núcleo tuvo 2 en todo momento.
```

Lo que enseña, más allá de que el ciclo funciona:

- Al llegar el lote el *backlog* por tarea (22) supera el objetivo (4) y `giftcards` pasa de 2 a
  6 réplicas, su máximo; el núcleo no se mueve.
- Entre `t=25` y `t=62` la cola en espera apenas baja, aunque hay doce fragmentos en curso:
  cada réplica procesa dos a la vez y no toma otro hasta terminar uno, y con seis réplicas en
  un portátil (cuatro de ellas recién arrancadas) un fragmento tarda decenas de segundos. La
  métrica refleja el trabajo que falta por tomar, no el que ya está en proceso.
- En `t=85` la métrica cae bajo el objetivo y, pasado el enfriamiento, la política retira una
  réplica (`6 × 2,8 ÷ 4`, redondeado hacia arriba, es 5). **Retirarla tomó cerca de un
  minuto**: es el apagado ordenado, en el que la réplica deja de pedir mensajes y termina o
  devuelve lo que tiene. Mientras tanto las otras cinco siguieron drenando la cola.
- Al vaciarse la cola vuelve al mínimo. El lote terminó con sus 12 000 gift cards; nada se
  perdió ni se duplicó al agregar o retirar réplicas.
- La simulación reduce réplicas con trabajo aún en curso porque **solo modela la política de
  *backlog***. En AWS hay además una política de CPU, y Application Auto Scaling retira tareas
  solo cuando **todas** las políticas lo permiten: con las tareas al 100 % de CPU no reduciría
  todavía. Es también un recordatorio de que el objetivo de *backlog* y los tiempos de
  enfriamiento se calibran con mediciones reales.

### Una particularidad del emulador

El emulador de SQS entrega mensajes a una conexión de *long polling* aunque el cliente ya se
haya ido. Cuando una réplica se detiene, un mensaje que llegue en los segundos siguientes puede
quedar invisible hasta que venza su *visibility timeout* (30 s en local) antes de que otra
réplica lo reciba. No se pierde nada, pero puede añadir esa demora a una medición hecha justo
después de reducir réplicas. Está comprobado con una prueba directa contra el emulador.

## Qué está comprobado y qué no

| | |
|---|---|
| Que solo el servicio de un módulo promovido declara escalado, y con qué políticas | Pruebas nativas de Terraform |
| Que promover un módulo no cambia nada del núcleo salvo los módulos que atiende, y que la regla del balanceador cambia en el sitio | Pruebas nativas y ensayo contra el emulador (`make ensayo-terraform`) |
| Que réplicas adicionales de `giftcards` toman trabajo de la misma cola sin duplicar ni perder, y que el núcleo no cambia | `make e2e escalado`, `make demo-escalado` |
| Que mientras el núcleo y el servicio propio atienden el módulo a la vez no se pierde ni se duplica nada | `make e2e promocion` (un lote generado durante el solape, con fragmentos de los dos) |
| Que una tarea que desaparece a mitad de su trabajo no pierde ni duplica nada | `make e2e caida` (`kill -9` con fragmentos en proceso) |
| Que el proveedor real de AWS acepta la definición | `terraform plan` contra una cuenta real: sin errores, y los tres recursos de escalado aparecen solo en el servicio de `giftcards` (ver abajo) |
| Que la API de AWS crea cada recurso tal como está planificado | **No ejecutado**: eso ocurre al aplicar, y no se aplicó nada |
| El comportamiento real de la política en ECS (tiempos de reacción, oscilación) | **No observado**: requiere desplegar |
| La latencia de la API con el módulo promovido, en AWS | **No medido**: la medición de este documento es local |

Lo que muestra el plan real de `dev` con `promocion-giftcards.tfvars` (19 de noviembre de 2026,
`scripts/tf-plan.sh`), igual en los tres ambientes salvo los números:

```
module.zonacards.module.nucleo.aws_ecs_service.fixed[0]                                desired_count = 2
module.zonacards.module.promoted["giftcards"].aws_ecs_service.autoscaled[0]            desired_count = 2
module.zonacards.module.promoted["giftcards"].aws_appautoscaling_target.this[0]        min 2, max 10
module.zonacards.module.promoted["giftcards"].aws_appautoscaling_policy.cpu[0]         ECSServiceAverageCPUUtilization, objetivo 60
module.zonacards.module.promoted["giftcards"].aws_appautoscaling_policy.backlog[0]     m1 / m2, objetivo 10
    m1 = AWS/SQS  ApproximateNumberOfMessagesVisible  (cola zonacards-dev-giftcards-generacion, Sum)
    m2 = ECS/ContainerInsights  RunningTaskCount       (servicio zonacards-dev-giftcards, Average)
```

No hay ningún recurso `aws_appautoscaling_*` bajo `module.nucleo`, y sin el archivo de
promoción el plan no contiene ninguno. En `qa` el rango planificado es de 2 a 12 tareas y en
`prod` de 3 a 30.

Para observarlo en una cuenta de pruebas: aplicar `envs/dev`, promover `giftcards`
(`docs/operacion.md`), solicitar un lote grande y seguir en CloudWatch
`ApproximateNumberOfMessagesVisible` de la cola junto a `RunningTaskCount` de los dos
servicios. El de `giftcards` debe subir y volver al mínimo; el del núcleo no debe moverse.
