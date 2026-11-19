SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

# Maven runs inside the build container (JDK 25) by default, so the only local
# requirement is Docker. With a local JDK 25 use:  make verify RUN=
RUN ?= scripts/dockerw
MVN := $(RUN) ./mvnw -B -ntp

# Images are loaded into the local Docker engine, whatever builder is selected on this machine.
export BUILDX_BUILDER ?= default

.PHONY: help
help: ## Lista los comandos disponibles
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

.PHONY: verify
verify: ## Compila y corre todo: unitarias, integración (Docker), arquitectura y cobertura. Es lo que corre CI
	$(MVN) verify

.PHONY: test
test: ## Solo pruebas unitarias y de arquitectura (sin Docker para dependencias)
	$(MVN) verify -DskipITs

.PHONY: clean
clean: ## Borra los directorios target
	$(MVN) clean

# --- Sistema local (docker compose) -----------------------------------------------------------

.PHONY: images
images: ## Construye la imagen de la aplicación (una sola, con todos los módulos)
	docker compose build migrate

.PHONY: up
up: ## Construye y levanta todo: PostgreSQL, SNS/SQS, Keycloak, Grafana, gateway y la aplicación
	scripts/local-env.sh
	scripts/topologia.sh rutas
	docker compose up -d --build --wait
	@echo
	@echo "API      http://localhost:$${ZC_PORT_API:-8080}/api/v1   (token: make token)"
	@echo "Keycloak http://localhost:$${ZC_PORT_KEYCLOAK:-8081}      (admin / admin)"
	@echo "Grafana  http://localhost:$${ZC_PORT_GRAFANA:-3000}"
	@echo
	@scripts/topologia.sh estado

.PHONY: down
down: ## Detiene el sistema local, borra sus datos (volúmenes) y olvida la topología
	docker compose down --volumes --remove-orphans
	scripts/topologia.sh restablecer

.PHONY: ps
ps: ## Estado de los contenedores del sistema local
	docker compose ps -a

.PHONY: logs
logs: ## Sigue los logs de la aplicación (núcleo y servicios propios)
	docker compose logs -f nucleo giftcards

.PHONY: token
token: ## Imprime un token de acceso de un usuario de demostración (USUARIO=ana|luis|eva)
	@bash -c 'source scripts/lib.sh && token_de "$${USUARIO:-ana}"'

# --- Topología: qué despliegue atiende cada módulo --------------------------------------------

.PHONY: topologia
topologia: ## Muestra qué despliegue atiende cada módulo
	@scripts/topologia.sh estado

.PHONY: promover
promover: ## Pasa un módulo a servicio propio, solo con configuración (MODULO=giftcards, opcional ETAPA=expandir|contraer)
	scripts/topologia.sh promover "$(MODULO)" $(ETAPA)

.PHONY: reintegrar
reintegrar: ## Devuelve un módulo promovido al núcleo (MODULO=giftcards, opcional ETAPA=expandir|contraer)
	scripts/topologia.sh reintegrar "$(MODULO)" $(ETAPA)

# --- Escenarios y demostraciones --------------------------------------------------------------

.PHONY: e2e
e2e: ## Corre los escenarios de punta a punta, promoción y reintegración incluidas (requiere make up)
	scripts/e2e.sh

.PHONY: demo-promocion
demo-promocion: ## Mide la API de lotes mientras se genera un lote grande, con giftcards en el núcleo y promovido
	scripts/demo-promocion.sh

.PHONY: demo-escalado
demo-escalado: ## Mide el mismo lote con 1, 2 y 4 réplicas de giftcards promovido; el núcleo no cambia
	scripts/demo-escalado.sh

.PHONY: demo-autoescalado
demo-autoescalado: ## SIMULA la política de autoescalado sobre la cola real del sistema local
	scripts/demo-autoescalado.sh
