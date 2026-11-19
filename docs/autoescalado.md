# Autoescalado de un solo dominio

`lotes` y `giftcards` se despliegan con el mismo módulo de Terraform y la misma imagen base.
`lotes` corre un número fijo de tareas. `giftcards` escala entre un mínimo y un máximo según su
carga. Este documento explica por qué, cómo y qué parte de ello está comprobada.

## Por qué solo `giftcards`

| | `lotes` | `giftcards` |
|---|---|---|
| Trabajo | Recibir solicitudes, llevar el estado del lote | Generar miles de códigos y proteger cada CVV con Argon2id |
| Costo por operación | Milisegundos, sobre todo base de datos | Decenas de milisegundos de CPU **por gift card** |
| Carga | Estable | A ráfagas: nada, y de pronto un lote de 200 000 |
| Qué lo limita | Nada, en la práctica | La CPU de sus tareas |

Un lote grande son horas de CPU. Con tareas fijas habría que elegir entre pagar capacidad
ociosa casi siempre o hacer esperar horas a cada lote. Como el trabajo llega por una cola
compartida, la capacidad se agrega arrancando más copias del mismo servicio, sin tocar el otro.

## Qué hace posible escalar horizontalmente

No es una propiedad de la infraestructura sino del diseño del servicio:

- **El trabajo se reparte por una cola**, no por peticiones. Cualquier tarea puede tomar
  cualquier fragmento; no hay afinidad ni estado en memoria.
- **Cada fragmento es idempotente y se reclama en la base de datos.** Dos tareas que reciban el
  mismo fragmento no generan dos veces.
- **El trabajo pesado ocurre fuera de la transacción.** Las tareas no compiten por bloqueos
  mientras calculan; la transacción solo inserta.
- **Las conexiones están presupuestadas.** Más tareas son más conexiones: el máximo de tareas ×
  el pool debe caber en el límite del rol y en la instancia (lo verifica Terraform).
- **Apagado ordenado.** Al reducir, una tarea termina lo que tiene o lo devuelve a la cola.

## En AWS

`infra/terraform/modules/ecs-service` recibe un bloque `autoscaling` opcional
(`infra/terraform/envs/*/main.tf`):

```hcl
lotes = {
  desired_count = var.lotes_tasks
}

giftcards = {
  autoscaling = {
    min        = var.giftcards_min_tasks
    max        = var.giftcards_max_tasks
    cpu_target = var.giftcards_cpu_target
    queue = {
      name                    = "giftcards-generacion"
      backlog_per_task_target = var.giftcards_backlog_per_task_target
    }
    scale_out_cooldown = 60
    scale_in_cooldown  = 300
  }
}
```

Sin el bloque no se crea ningún recurso de escalado. Con él:

1. Un `aws_appautoscaling_target` sobre `DesiredCount` del servicio, con el mínimo y el máximo.
2. Una política de *target tracking* sobre la **CPU promedio** del servicio.
3. Una política de *target tracking* sobre el **backlog por tarea**, con *metric math*:

   ```
   m1 = AWS/SQS  ApproximateNumberOfMessagesVisible   (cola giftcards-generacion)
   m2 = ECS/ContainerInsights  RunningTaskCount       (servicio giftcards)
   e1 = m1 / m2                                       <- el valor que se mantiene en el objetivo
   ```

Con dos políticas sobre el mismo target, Application Auto Scaling agrega tareas cuando
**cualquiera** lo pide y las retira solo cuando **ambas** lo permiten. Se agregan rápido (60 s
de enfriamiento) y se retiran despacio (300 s).

Terraform ignora `desired_count` en el servicio autoescalado (lo gobierna la política) y lo
respeta en el fijo. Las pruebas nativas afirman: `lotes`, cero targets y cero políticas;
`giftcards`, un target y dos políticas, una con la expresión `m1 / m2`.

### Calibrar el objetivo de backlog

El objetivo responde a una pregunta de negocio: *¿cuánto puede esperar un fragmento en la cola?*

```
backlog por tarea objetivo = espera aceptable ÷ tiempo de proceso de un mensaje
```

Un mensaje es un fragmento de `ZC_GIFTCARDS_FRAGMENT_SIZE` gift cards (1 000 por defecto). En
las mediciones locales una gift card costó entre 36 y 60 ms de CPU, es decir, del orden de 40 a
60 s por fragmento en una tarea de 1 vCPU. Con una espera aceptable de 5 minutos el objetivo
sería de 5 a 7. El valor `10` de `terraform.tfvars` es un punto de partida, no una medición:
**debe ajustarse con el tiempo por fragmento medido en Fargate**, que dependerá del tamaño de
tarea elegido.

Dos consecuencias del mismo número: el *visibility timeout* de la cola (300 s) debe superar con
holgura el tiempo de un fragmento, y el `stopTimeout` de la tarea (90 s) debe dar tiempo a
terminar el fragmento en curso al reducir; si no alcanza, el fragmento vuelve a la cola y otra
tarea lo repite sin efectos duplicados.

### Presupuesto de conexiones

Cada tarea abre hasta `ZC_DB_POOL_MAX` conexiones. Un `precondition` rechaza el plan si:

- tareas pico × pool supera el `CONNECTION LIMIT` del rol `<servicio>_app`, o
- la suma de los límites más la reserva supera `max_connections` de la instancia.

"Tareas pico" es el máximo × 200 %: en un despliegue conviven las tareas viejas y las nuevas.
Subir el máximo de tareas sin revisar la base no pasa el plan.

## En local

Application Auto Scaling no existe fuera de AWS. Lo que sí se puede hacer en un portátil:

### 1. Medir que más réplicas de un dominio drenan más rápido

```bash
make demo-escalado               # 4 000 gift cards con 1, 2 y 4 réplicas de giftcards
scripts/demo-escalado.sh 8000 1 2 4 8
```

Todo lo que imprime está medido en esa corrida. Cada réplica de `giftcards` está limitada a
1 vCPU; `lotes` conserva sus réplicas y el guion falla si cambian. Antes de cada medición corre
un lote de calentamiento, para no medir al compilador JIT.

La última columna dice cuántos fragmentos guardó cada réplica, tomada de los logs de los
propios servicios: es el reparto real del trabajo.

Corrida del 19 de noviembre de 2026 (Argon2id `m=19456 KiB, t=2`, la configuración por defecto):

| Réplicas de giftcards | Réplicas de lotes | Tiempo (s) | Gift cards/s | Aceleración | Fragmentos por réplica |
|---:|---:|---:|---:|---:|---|
| 1 | 2 | 226,5 | 18 | 1,00x | 16 |
| 2 | 2 | 137,0 | 29 | 1,65x | 8 + 8 |
| 4 | 2 | 125,8 | 32 | 1,80x | 4 + 4 + 4 + 4 |

**Qué demuestra y qué no.** El reparto es exacto y, medido aparte con `docker stats`, las cuatro
réplicas estuvieron al 100 % de su vCPU durante toda la corrida: el sistema usa toda la
capacidad que se le da. Lo que no hay en un solo equipo es cuatro veces más capacidad:

- El portátil de la prueba (Intel Core Ultra 7 155H) tiene núcleos de dos velocidades y estaba
  en su tope térmico (100 °C). En una prueba de CPU pura (`openssl speed sha256`), sin relación
  con la aplicación, cada proceso rindió entre el 48 % y el 98 % de lo que rinde uno solo cuando
  corrían cuatro a la vez: el hardware ya entrega solo unas 2,7 veces más con cuatro procesos.
- El hash es costoso en memoria a propósito, así que las réplicas compiten además por la caché
  y el ancho de banda de memoria. Se repitió la medición con la configuración equivalente de
  menor memoria que lista OWASP (`m=7168 KiB, t=5`) y la aceleración fue similar (1,15x y 1,55x),
  lo que indica que la memoria no es el factor principal.
- Los tiempos absolutos variaron mucho con la temperatura: el mismo guion dio 127 s y 71 s para
  1 y 2 réplicas (1,78x) media hora antes.

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
mínimo y el máximo) y cambia las réplicas de `giftcards` con docker compose.

| Real | Simulado |
|---|---|
| La cola, su *backlog* y los mensajes | El controlador (lo hace un guion, no AWS) |
| Las réplicas que arrancan, toman trabajo y se retiran | Los tiempos: evalúa cada 5 s y no cada 60; enfría 10 s y 30 s en lugar de 60 s y 300 s |
| El tiempo que tarda el lote | La fórmula exacta de AWS, que no es pública: se usa la regla proporcional documentada |

Una corrida del 19 de noviembre de 2026 (`ZC_SIM_MAX=4 scripts/demo-autoescalado.sh 6000`,
abreviada):

```
 t (s)  en espera  en vuelo  giftcards  backlog/tarea  lotes      decisión
     0          0         0          2            0.0  2          -
     5         20         4          2           10.0  2          escalar a 4
    17         14         8          4            3.5  2          -
    28         11         8          4            2.8  2          (esperando para reducir)
    58          7         8          4            1.8  2          reducir a 2
   115          2         6          2            1.0  2          -
   137          2         4          2            1.0  2          -
   193          0         0          2            0.0  2          -
        -> lote GENERADO a los 193s
Lote generado en 193s. giftcards volvió a 2 réplicas; lotes tuvo 2 en todo momento.
```

Lo que enseña, más allá de que el ciclo funciona:

- Al llegar el lote el *backlog* por tarea (10) supera el objetivo (4) y `giftcards` pasa de 2 a
  4 réplicas; `lotes` no se mueve.
- Las dos réplicas retiradas en `t=58` tenían fragmentos en curso. Los que no terminaron dentro
  del tiempo de apagado **volvieron a la cola al vencer su *visibility timeout*** (reaparecen
  como "en espera" en `t=137`) y los completaron las réplicas restantes. Nada se perdió ni se
  duplicó: el lote terminó con sus 6 000 gift cards.
- La simulación redujo réplicas con trabajo aún pendiente porque **solo modela la política de
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
| Que solo `giftcards` declara escalado y con qué políticas | Pruebas nativas de Terraform |
| Que réplicas adicionales de `giftcards` toman trabajo de la misma cola sin duplicar ni perder, y que `lotes` no cambia | `make e2e escalado`, `make demo-escalado` |
| Que una tarea que desaparece a mitad de su trabajo no pierde ni duplica nada | `make e2e caida` (`kill -9` con fragmentos en proceso) |
| Que AWS acepta la definición | **Pendiente**: requiere `terraform plan` con credenciales |
| El comportamiento real de la política en ECS (tiempos de reacción, oscilación) | **No observado**: requiere desplegar |

Para observarlo en una cuenta de pruebas: aplicar `envs/dev`, solicitar un lote grande y seguir
en CloudWatch `ApproximateNumberOfMessagesVisible` de la cola junto a `RunningTaskCount` de los
dos servicios. El de `giftcards` debe subir y volver al mínimo; el de `lotes` no debe moverse.
