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
images: ## Construye las imágenes de los dos servicios
	docker compose build lotes-migrate giftcards-migrate

.PHONY: up
up: ## Construye y levanta todo: PostgreSQL, SNS/SQS, Keycloak, Grafana, gateway y los dos servicios
	scripts/local-env.sh
	docker compose up -d --build --wait
	@echo
	@echo "API      http://localhost:$${ZC_PORT_API:-8080}/api/v1   (token: make token)"
	@echo "Keycloak http://localhost:$${ZC_PORT_KEYCLOAK:-8081}      (admin / admin)"
	@echo "Grafana  http://localhost:$${ZC_PORT_GRAFANA:-3000}"

.PHONY: down
down: ## Detiene el sistema local y borra sus datos (volúmenes de docker compose)
	docker compose down --volumes --remove-orphans

.PHONY: ps
ps: ## Estado de los contenedores del sistema local
	docker compose ps -a

.PHONY: logs
logs: ## Sigue los logs de los dos servicios
	docker compose logs -f lotes giftcards

.PHONY: token
token: ## Imprime un token de acceso de un usuario de demostración (USUARIO=ana|luis|eva)
	@bash -c 'source scripts/lib.sh && token_de "$${USUARIO:-ana}"'

.PHONY: e2e
e2e: ## Corre los escenarios de punta a punta contra el sistema local (requiere make up)
	scripts/e2e.sh

.PHONY: demo-escalado
demo-escalado: ## Mide el mismo lote con 1, 2 y 4 réplicas de giftcards; lotes no cambia
	scripts/demo-escalado.sh

.PHONY: demo-autoescalado
demo-autoescalado: ## SIMULA la política de autoescalado sobre la cola real del sistema local
	scripts/demo-autoescalado.sh
