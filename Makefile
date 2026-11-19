SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

# Maven runs inside the build container (JDK 25) by default, so the only local
# requirement is Docker. With a local JDK 25 use:  make verify RUN=
RUN ?= scripts/dockerw
MVN := $(RUN) ./mvnw -B -ntp

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
