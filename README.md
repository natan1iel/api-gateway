# api-gateway

[![CI](https://github.com/natan1iel/api-gateway/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/natan1iel/api-gateway/actions/workflows/ci.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=natan1iel_api-gateway&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=natan1iel_api-gateway)

Ponto único de entrada do **Sistema de Reserva de Quadras de Vôlei**. Este repositório também concentra a documentação geral do projeto e o `docker-compose.yml` para execução local.

---

## Visão geral da arquitetura

Quatro microsserviços Spring Boot, cada um em seu próprio repositório, container e banco de dados.

```
                    ┌──────────────────┐
  Cliente ─────────▶│   api-gateway    │  valida o JWT e injeta
  (Postman)         │                  │  X-Usuario-Id / Nome / Papel
                    └────────┬─────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
  ┌──────────────┐   ┌───────────────┐   ┌────────────────┐
  │ auth-service │   │quadras-service│◀──│reservas-service│
  │              │   │               │   │     (CQRS)     │
  └──────┬───────┘   └───────┬───────┘   └────────┬───────┘
         │                   │                    │
      authdb             quadrasdb            reservasdb
```

| Serviço | Responsabilidade | Repositório |
|---|---|---|
| **api-gateway** | Roteamento e autenticação centralizada | [api-gateway](https://github.com/natan1iel/api-gateway) |
| **auth-service** | Cadastro, login, emissão de JWT | [auth-service](https://github.com/natan1iel/auth-service) |
| **quadras-service** | Cadastro de quadras, grade de funcionamento, intervalo | [quadras-service](https://github.com/natan1iel/quadras-service) |
| **reservas-service** | Agenda, horários livres, regra de sobreposição | [reservas-service](https://github.com/natan1iel/reservas-service) |

### Padrões aplicados

**API Gateway** — a autenticação acontece uma única vez, neste serviço. O filtro valida a assinatura e a expiração do JWT e traduz os claims em headers (`X-Usuario-Id`, `X-Usuario-Nome`, `X-Usuario-Papel`). Os serviços de domínio não conhecem JWT nem possuem a chave secreta.

**CQRS** — dentro do `reservas-service`. A escrita passa por handlers de comando que aplicam toda a regra de negócio e persistem na entidade normalizada; a leitura consulta uma tabela desnormalizada (`reserva_consulta`), já com o código da quadra e o nome do usuário. A listagem de reservas não faz nenhuma chamada ao `quadras-service`.

### Comunicação entre serviços

REST sobre HTTP, em um único sentido. Existe apenas uma chamada entre microsserviços de domínio:

```
GET /quadras/{id}/disponibilidade?data=YYYY-MM-DD
```

Feita pelo `reservas-service` para validar a reserva e calcular os horários livres.

---

## Ambientes

Dois ambientes no Render, alimentados por branches diferentes.

| Ambiente | Branch | Gateway |
|---|---|---|
| **DEV** | `develop` | https://api-gateway-dev-uytc.onrender.com |
| **HOMOL** | `main` | https://api-gateway-homol-devops.onrender.com |

URLs dos serviços:

| Serviço | DEV | HOMOL |
|---|---|---|
| auth-service | https://auth-service-dev-3aq6.onrender.com | https://auth-homol-devops.onrender.com |
| quadras-service | https://quadras-service-dev.onrender.com | https://quadras-service-homol-devops.onrender.com |
| reservas-service | https://reservas-service-dev.onrender.com | https://reservas-service-homol-devops.onrender.com |

Cada ambiente tem seus próprios bancos (`authdb_dev` / `authdb_homol`, e assim por diante) e sua própria tag de ambiente nas métricas (`AMBIENTE=dev` ou `AMBIENTE=homol`), o que permite separar os dois no painel do Grafana.

> Os serviços rodam no plano gratuito do Render e hibernam após 15 minutos sem tráfego. A primeira requisição após a hibernação pode levar até 60 segundos.

> O plano gratuito do Render não oferece *Private Services*, então os quatro recebem URL pública. No ambiente local com Docker, apenas o gateway publica porta — o isolamento de rede existe lá e é demonstrável.

---

## Pipeline

Cada repositório tem seu próprio workflow em `.github/workflows/ci.yml`:

```
                           ┌──▶ deploy        (develop ──▶ DEV)
build ──▶ imagem ──────────┤
                           └──▶ deploy-homol  (main ──▶ HOMOL)
```

| Job | O que faz | Quando roda |
|---|---|---|
| `build` | Compila, roda os testes, gera o relatório do JaCoCo e envia a análise ao SonarCloud | push e pull request |
| `imagem` | Constrói a imagem Docker e publica no GitHub Container Registry | apenas push |
| `deploy` | Dispara o deploy hook do ambiente de DEV | push na `develop` |
| `deploy-homol` | Dispara o deploy hook do ambiente de HOMOL | push na `main` |

O ambiente de destino é escolhido pela branch, não por configuração manual: a mesma imagem que foi testada em DEV é a que sobe em HOMOL quando a `develop` é mesclada na `main`.

O job `build` é **status check obrigatório** nas branches protegidas: um pull request com build quebrado não pode ser mesclado.

### Imagens publicadas

```
ghcr.io/natan1iel/api-gateway:latest
ghcr.io/natan1iel/auth-service:latest
ghcr.io/natan1iel/quadras-service:latest
ghcr.io/natan1iel/reservas-service:latest
```

Cada imagem é publicada com duas tags: `latest` e o SHA do commit que a gerou. O Render consome a imagem já construída, sem recompilar o código.

---

## Git Flow

| Branch | Papel |
|---|---|
| `main` | Código estável |
| `develop` | Branch padrão, integração contínua |
| `feature/*` | Uma por tarefa, mesclada via pull request |

Ambas as branches principais são protegidas por ruleset: pull request obrigatório, push direto bloqueado, force push bloqueado, e o check `build` exigido antes do merge.

---

## Qualidade

Análise estática e cobertura no [SonarCloud](https://sonarcloud.io/organizations/natan1iel/projects), com quality gate avaliado a cada push.

| Serviço | Cobertura |
|---|---|
| quadras-service | 77,3% |
| reservas-service | 77,0% |

O relatório do JaCoCo também é publicado como artefato em cada execução do workflow.

### Dependabot

Configurado em `.github/dependabot.yml` nos quatro repositórios, com varredura semanal em três ecossistemas:

| Ecossistema | O que acompanha |
|---|---|
| `maven` | Dependências do `pom.xml` |
| `github-actions` | Versões das actions usadas no workflow |
| `docker` | Imagem base do `Dockerfile` |

Cada atualização chega como pull request contra a `develop` e só pode ser mesclada se o job `build` passar — a mesma regra que vale para qualquer outra mudança.

---

## Executando localmente

Pré-requisitos: Docker e Docker Compose.

```bash
git clone https://github.com/natan1iel/api-gateway.git
cd api-gateway
docker compose up --build
```

O compose sobe os quatro serviços e o PostgreSQL. Apenas o gateway publica porta no host (`8080`).

### Usuários criados automaticamente

| E-mail | Senha | Papel |
|---|---|---|
| admin@volei.com | admin123 | ADMIN |
| joao@volei.com | joao123 | USUARIO |
| maria@volei.com | maria123 | USUARIO |

---

## Testando a API

A collection do Postman (`reserva-quadras.postman_collection.json`) cobre o fluxo completo. O login salva o token automaticamente nas variáveis da collection.

Sequência de demonstração:

```bash
# 1. Login — devolve o JWT
curl -X POST https://api-gateway-dev-uytc.onrender.com/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@volei.com","senha":"admin123"}'

# 2. Listar quadras
curl https://api-gateway-dev-uytc.onrender.com/quadras \
  -H "Authorization: Bearer $TOKEN"

# 3. Horários livres de uma quadra numa data
curl "https://api-gateway-dev-uytc.onrender.com/reservas/horarios-livres?quadraId=1&data=2026-11-10" \
  -H "Authorization: Bearer $TOKEN"

# 4. Criar reserva
curl -X POST https://api-gateway-dev-uytc.onrender.com/reservas \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"quadraId":1,"data":"2026-11-10","horaInicio":"10:00"}'

# 5. Consultar os livres de novo — o bloco reservado desaparece
# 6. Tentar reservar em cima — retorna 409 com a reserva conflitante
```

---

## Observabilidade

Todos os serviços expõem Spring Boot Actuator com Micrometer:

| Endpoint | Para que serve |
|---|---|
| `/actuator/health` | Health check consumido pelo Render |
| `/actuator/prometheus` | Métricas no formato Prometheus |

### Métricas centralizadas no Grafana Cloud

Como os serviços rodam no Render, não há um Prometheus fazendo *scrape* das instâncias. O caminho é o inverso: cada serviço **empurra** suas métricas para o Grafana Cloud via OTLP, usando o `micrometer-registry-otlp`, a cada 60 segundos.

```
auth · quadras · reservas · gateway ──OTLP──▶ Grafana Cloud ──▶ dashboard
```

Toda métrica carrega duas tags que permitem fatiar o painel:

| Tag | Valores |
|---|---|
| `application` | `api-gateway`, `auth-service`, `quadras-service`, `reservas-service` |
| `ambiente` | `dev`, `homol`, `local` |

O envio é desligado por padrão (`OTLP_ENABLED=false`), para que o ambiente local não precise de credencial.

Queries usadas no dashboard:

```promql
sum by (application) (rate(http_server_requests_seconds_count[5m]))
max by (application, uri) (http_server_requests_seconds_max)
sum by (application) (jvm_memory_used_bytes)
sum by (application) (hikaricp_connections_active)
```

---

---

## Documentação da API

O Swagger é habilitado por variável de ambiente (`SWAGGER_ENABLED`), ligado em DEV e desligado em ambientes de homologação.

```
https://quadras-service-dev.onrender.com/swagger-ui.html
https://reservas-service-dev.onrender.com/swagger-ui.html
https://auth-service-dev-3aq6.onrender.com/swagger-ui.html
```

---

## Configuração

Nenhum valor sensível está versionado. Toda a configuração vem de variáveis de ambiente:

| Variável | Onde é usada |
|---|---|
| `PORT` | Porta injetada pelo Render |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Conexão com o PostgreSQL |
| `JWT_SECRET` | Assinatura e validação do token |
| `SWAGGER_ENABLED` | Liga ou desliga a documentação por ambiente |
| `AUTH_SERVICE_URL`, `QUADRAS_SERVICE_URL`, `RESERVAS_SERVICE_URL` | Roteamento do gateway |
| `AMBIENTE` | Tag aplicada às métricas (`dev`, `homol`, `local`) |
| `OTLP_ENABLED`, `OTLP_URL`, `OTLP_AUTH` | Envio de métricas ao Grafana Cloud |

Os secrets do pipeline (`SONAR_TOKEN`, `RENDER_DEPLOY_HOOK`, `RENDER_DEPLOY_HOOK_HOMOL`) ficam em *GitHub Secrets*, por repositório. O token do registry é o `GITHUB_TOKEN` gerado pela própria execução do workflow.

---

## Stack

Java 21 · Spring Boot 3.3 · Spring Cloud Gateway · Spring Data JPA · PostgreSQL · jjwt · JUnit 5 · Mockito · JaCoCo · Maven · Docker · GitHub Actions · GitHub Container Registry · SonarCloud · Dependabot · Render · Grafana Cloud (OTLP)
