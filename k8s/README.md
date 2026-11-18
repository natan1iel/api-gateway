# Deploy em Kubernetes (Minikube)

Trabalho Prático 3 — os quatro microsserviços do **Sistema de Reserva de Quadras de Vôlei** rodando em um cluster Minikube, com duas réplicas cada, PostgreSQL no cluster e observabilidade com Prometheus e Grafana.

---

## Arquitetura no cluster

```
                         ┌───────────────────────── Minikube ─────────────────────────┐
                         │                                                            │
  Cliente ──▶ Ingress ───┼──▶ api-gateway (2 pods) ──┬──▶ auth-service     (2 pods) ─┐ │
             (nginx)     │                           ├──▶ quadras-service  (2 pods) ─┼─┼─▶ postgres-service ──▶ PostgreSQL
                         │                           └──▶ reservas-service (2 pods) ─┘ │        (authdb, quadrasdb, reservasdb)
                         │                                       │                      │
                         │   /grafana ──▶ Grafana ◀── Prometheus ◀── coleta de cada pod │
                         └────────────────────────────────────────────────────────────┘
```

- Toda requisição externa entra pelo **Ingress** e vai para o **api-gateway**, que valida o JWT.
- Os serviços conversam pelo nome do **Service** (`http://quadras-service:8080`), resolvido pelo DNS interno do Kubernetes. Nenhum IP é fixo.
- Os Services de auth, quadras e reservas são `ClusterIP`: não existe caminho de fora do cluster até eles sem passar pelo gateway.
- O **Prometheus** descobre cada pod sozinho, pela API do Kubernetes, e coleta `/actuator/prometheus` de todas as réplicas.

---

## Estrutura

```
k8s/
├── api-gateway/
│   ├── configmap.yaml      URLs dos serviços, ambiente, opções da JVM
│   ├── secret.yaml         JWT_SECRET
│   ├── deployment.yaml     2 réplicas, probes, limites de recurso
│   ├── service.yaml
│   ├── ingress.yaml        rota "/" → gateway
│   └── hpa.yaml            escala de 2 a 3 réplicas por CPU
├── auth-service/           mesmos arquivos (ingress: /auth → gateway)
├── quadras-service/        mesmos arquivos (ingress: /quadras → gateway)
├── reservas-service/       mesmos arquivos (ingress: /reservas → gateway)
├── database/
│   ├── postgres-secret.yaml
│   ├── postgres-configmap.yaml   script que cria um banco e um usuário por serviço
│   ├── postgres-pvc.yaml         volume persistente
│   ├── postgres-deployment.yaml
│   └── postgres-service.yaml
└── monitoring/
    ├── prometheus-rbac.yaml       permissão para descobrir os pods
    ├── prometheus-configmap.yaml  prometheus.yml
    ├── prometheus-deployment.yaml
    ├── prometheus-service.yaml
    ├── grafana-secret.yaml        login do Grafana
    ├── grafana-configmap.yaml     configuração + fonte de dados Prometheus
    ├── grafana-dashboard.yaml     painel já pronto
    ├── grafana-deployment.yaml
    ├── grafana-service.yaml
    └── monitoring-ingress.yaml    /grafana e /prometheus
```

### Por que o Ingress de cada serviço aponta para o gateway

Os serviços de domínio não validam JWT: eles confiam nos headers `X-Usuario-Id`, `X-Usuario-Nome` e `X-Usuario-Papel`, que só o gateway preenche depois de validar o token. Se o Ingress do `quadras-service` apontasse direto para ele, qualquer pessoa poderia mandar `X-Usuario-Papel: ADMIN` e cadastrar quadras sem login. Por isso cada serviço tem o seu `ingress.yaml` declarando a sua rota pública, mas o destino é sempre o `api-gateway`.

### Configuração

| Onde | O que fica |
|---|---|
| **ConfigMap** | `DB_URL`, `DB_USER`, URLs dos serviços, `AMBIENTE`, `SWAGGER_ENABLED`, `JAVA_TOOL_OPTIONS` |
| **Secret** | `DB_PASSWORD`, `JWT_SECRET`, senha do superusuário do banco, login do Grafana |

Cada senha existe em um único lugar. O Deployment do PostgreSQL lê o usuário e a senha de cada serviço direto do ConfigMap e do Secret daquele serviço para criar os bancos, então não há uma segunda cópia que possa ficar diferente. O `JWT_SECRET` é a exceção: aparece no Secret do `auth-service` (que assina o token) e no do `api-gateway` (que valida), e os dois valores precisam ser iguais.

Nenhum valor sensível fica no código: o `application.yml` dos quatro serviços não tem mais valor padrão para `DB_PASSWORD` nem para `JWT_SECRET`. Sem o Secret, o serviço não sobe, em vez de subir com uma senha conhecida.

Os Secrets estão em base64, como no exemplo da aula. Base64 não é criptografia: em produção eles viriam de um cofre (Vault, AWS KMS, Sealed Secrets). Os valores daqui só servem para este cluster local.

---

## Pré-requisitos

- Docker Desktop ou Docker Engine
- kubectl
- Minikube
- Os quatro repositórios clonados lado a lado:

```
pasta-qualquer/
├── api-gateway/
├── auth-service/
├── quadras-service/
└── reservas-service/
```

---

## 1. Subir o cluster

São 8 JVMs (4 serviços × 2 réplicas) mais banco, Prometheus e Grafana. O cluster precisa de pelo menos 4 CPUs e 6 GB de memória:

```bash
minikube start --cpus 4 --memory 6144
minikube addons enable ingress
minikube addons enable metrics-server
```

> O `metrics-server` alimenta o `kubectl top` e o autoscaling (HPA).

Para demonstrar vários nodes, como na aula (precisa de mais memória na máquina, cerca de 12 GB livres):

```bash
minikube start --nodes 3 --cpus 2 --memory 4096
```

Os Deployments têm `topologySpreadConstraints`, então as duas réplicas de cada serviço vão para nodes diferentes sempre que possível.

## 2. Imagens

Os Deployments usam as imagens que o pipeline de CI publica no GitHub Container Registry:

```
ghcr.io/natan1iel/api-gateway:latest
ghcr.io/natan1iel/auth-service:latest
ghcr.io/natan1iel/quadras-service:latest
ghcr.io/natan1iel/reservas-service:latest
```

**Opção A — usar as imagens do CI.** Se os pacotes no GHCR estiverem públicos, não há nada a fazer: o cluster baixa as imagens no deploy.

**Opção B — construir dentro do Minikube** (método da aula, não depende de registry). A imagem fica com o mesmo nome, e como os Deployments usam `imagePullPolicy: IfNotPresent`, o cluster usa a local em vez de baixar:

Linux, macOS ou Git Bash:

```bash
eval $(minikube docker-env)

docker build -t ghcr.io/natan1iel/api-gateway:latest      ../api-gateway
docker build -t ghcr.io/natan1iel/auth-service:latest     ../auth-service
docker build -t ghcr.io/natan1iel/quadras-service:latest  ../quadras-service
docker build -t ghcr.io/natan1iel/reservas-service:latest ../reservas-service

eval $(minikube docker-env --unset)
```

Windows (PowerShell):

```powershell
& minikube -p minikube docker-env --shell powershell | Invoke-Expression
docker build -t ghcr.io/natan1iel/api-gateway:latest      ../api-gateway
docker build -t ghcr.io/natan1iel/auth-service:latest     ../auth-service
docker build -t ghcr.io/natan1iel/quadras-service:latest  ../quadras-service
docker build -t ghcr.io/natan1iel/reservas-service:latest ../reservas-service
```

> Se o `kubectl get pods` mostrar `ErrImagePull` ou `ImagePullBackOff`, os pacotes do GHCR estão privados: use a opção B, ou torne os pacotes públicos em *GitHub → Packages → Package settings → Change visibility*.

## 3. Deploy

Os comandos rodam a partir da raiz do repositório `api-gateway`, na ordem recomendada na aula: Secrets e ConfigMaps, depois o banco, depois os serviços e o Ingress.

```bash
# 1. Secrets e ConfigMaps
kubectl apply -f k8s/database/postgres-secret.yaml -f k8s/database/postgres-configmap.yaml
kubectl apply -f k8s/auth-service/secret.yaml     -f k8s/auth-service/configmap.yaml
kubectl apply -f k8s/quadras-service/secret.yaml  -f k8s/quadras-service/configmap.yaml
kubectl apply -f k8s/reservas-service/secret.yaml -f k8s/reservas-service/configmap.yaml
kubectl apply -f k8s/api-gateway/secret.yaml      -f k8s/api-gateway/configmap.yaml

# 2. PostgreSQL
kubectl apply -f k8s/database/
kubectl rollout status deployment/postgres

# 3. Microsserviços (Deployment, Service, Ingress e HPA de cada um)
kubectl apply -f k8s/auth-service/ -f k8s/quadras-service/ -f k8s/reservas-service/ -f k8s/api-gateway/

# 4. Prometheus e Grafana
kubectl apply -f k8s/monitoring/
```

> Atalho: `kubectl apply -R -f k8s/` aplica tudo de uma vez. Funciona, porque o Kubernetes tenta de novo até as dependências existirem, mas alguns pods passam por `CreateContainerConfigError` no caminho.

Acompanhe até todos ficarem `Running` e `READY 1/1` (as JVMs levam de 1 a 3 minutos para subir):

```bash
kubectl get pods -w
```

## 4. Acessar

**Windows e macOS** (driver Docker): abra um terminal separado, deixe rodando, e use `http://127.0.0.1`:

```bash
minikube tunnel
```

**Linux**: use o IP do cluster:

```bash
minikube ip        # ex.: 192.168.49.2
```

Nos exemplos abaixo, troque `$HOST` por `127.0.0.1` ou pelo IP do Minikube.

| O quê | Endereço |
|---|---|
| API (via gateway) | `http://$HOST/auth`, `/quadras`, `/reservas` |
| Grafana | `http://$HOST/grafana` (usuário `admin`, senha abaixo) |
| Prometheus | `http://$HOST/prometheus` |

Senha do Grafana:

```bash
kubectl get secret grafana-secret -o jsonpath='{.data.GF_SECURITY_ADMIN_PASSWORD}' | base64 -d
```

O painel fica em *Dashboards → Reserva de Quadras → Reserva de Quadras — Kubernetes*.

## 5. Testar a aplicação

```bash
# Login (usuários criados automaticamente: admin@volei.com / admin123)
TOKEN=$(curl -s -X POST http://$HOST/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@volei.com","senha":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

# Listar quadras
curl http://$HOST/quadras -H "Authorization: Bearer $TOKEN"

# Horários livres
curl "http://$HOST/reservas/horarios-livres?quadraId=1&data=2026-12-01" -H "Authorization: Bearer $TOKEN"

# Criar reserva
curl -X POST http://$HOST/reservas \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"quadraId":1,"data":"2026-12-01","horaInicio":"10:00"}'

# Sem token: 401 vindo do gateway
curl -i http://$HOST/quadras
```

A collection do Postman também funciona: basta trocar a URL base para `http://$HOST`.

---

## Evidências para a avaliação

### Múltiplos pods em execução (item 4.2)

```bash
kubectl get deployments
kubectl get pods -o wide
kubectl get endpoints
```

Saída esperada do `get deployments`:

```
NAME               READY   UP-TO-DATE   AVAILABLE
api-gateway        2/2     2            2
auth-service       2/2     2            2
grafana            1/1     1            1
postgres           1/1     1            1
prometheus         1/1     1            1
quadras-service    2/2     2            2
reservas-service   2/2     2            2
```

O `get endpoints` mostra dois IPs atrás de cada Service, que é o balanceamento entre as réplicas.

No Grafana, o painel **Réplicas no ar por serviço** mostra 2 para cada um, e a tabela **Pods monitorados** lista os oito pods com o node de cada um. No Prometheus, *Status → Targets* mostra os oito alvos `UP`.

### Balanceamento entre réplicas

Gere algumas requisições e veja o painel **Requisições por segundo, por pod**: o tráfego de cada serviço aparece dividido entre os dois pods.

```bash
for i in $(seq 1 50); do curl -s -o /dev/null http://$HOST/quadras -H "Authorization: Bearer $TOKEN"; done
```

### Auto healing

Em um terminal, `kubectl get pods -w`. Em outro, apague uma das réplicas:

```bash
kubectl get pods -l app=quadras-service
kubectl delete pod <nome-de-um-dos-pods>
```

O Deployment cria um pod novo na hora. Enquanto ele sobe, a outra réplica continua atendendo, e o `curl http://$HOST/quadras` segue respondendo.

### Escalabilidade manual

```bash
kubectl scale deployment quadras-service --replicas=3
kubectl get pods -l app=quadras-service
```

No Grafana, o painel de réplicas passa para 3 em até 15 segundos, sem nenhuma mudança na configuração do Prometheus.

> Com o HPA ativo, ele devolve o número de réplicas para a faixa configurada depois de alguns minutos. Para testar só o `scale`, remova o HPA antes: `kubectl delete hpa quadras-service-hpa`.

### Autoscaling (HPA)

```bash
kubectl get hpa
```

Para gerar carga de dentro do cluster:

```bash
kubectl run carga --rm -it --image=curlimages/curl --restart=Never -- sh -c '
TOKEN=$(curl -s -X POST http://api-gateway:8080/auth/login -H "Content-Type: application/json" \
  -d "{\"email\":\"admin@volei.com\",\"senha\":\"admin123\"}" | sed -E "s/.*\"token\":\"([^\"]+)\".*/\1/")
while true; do curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" http://api-gateway:8080/quadras; done'
```

Em outro terminal, `kubectl get hpa -w`: quando a CPU passa de 70% do pedido, o HPA sobe o gateway e o quadras-service para 3 réplicas. `Ctrl+C` encerra a carga, e as réplicas voltam para 2 depois de cerca de 2 minutos.

### Rolling update e rollback

```bash
kubectl rollout restart deployment/quadras-service
kubectl rollout status deployment/quadras-service
kubectl rollout history deployment/quadras-service
kubectl rollout undo deployment/quadras-service
```

Com `maxUnavailable: 0`, o Kubernetes só derruba um pod antigo depois que o novo passou no `readinessProbe`.

### Recursos criados

```bash
kubectl get pods,svc,ingress,configmap,secret,hpa,pvc
kubectl top pods
minikube dashboard
```

---

## CI/CD

O pipeline de cada repositório continua o mesmo do trabalho anterior: build, testes, JaCoCo, SonarCloud e publicação da imagem no GHCR com duas tags, `latest` e o SHA do commit.

No `api-gateway` foi acrescentado o job `manifests`, que valida todos os arquivos de `k8s/` contra o schema da API do Kubernetes a cada push e pull request. Um manifest com erro de digitação ou campo inválido quebra o pipeline antes de chegar ao cluster.

Para subir no cluster uma versão específica gerada pelo CI, use a tag do commit:

```bash
kubectl set image deployment/quadras-service quadras-service=ghcr.io/natan1iel/quadras-service:<sha-do-commit>
kubectl rollout status deployment/quadras-service
```

O deploy no Minikube é feito a partir da máquina local: um runner do GitHub Actions na nuvem não alcança um cluster que roda no notebook.

---

## Observabilidade

```
pods (8) ──/actuator/prometheus──▶ Prometheus ──▶ Grafana
          a cada 15 s, descobertos
          pela API do Kubernetes
```

O Prometheus usa `kubernetes_sd_configs` com `role: pod` e coleta só os pods que têm a anotação `prometheus.io/scrape: "true"`. Cada métrica ganha os rótulos `app`, `pod` e `node`, além dos que a aplicação já envia (`application` e `ambiente=k8s`). Uma réplica nova entra no monitoramento sozinha, e uma réplica que morre sai.

Painéis do dashboard:

| Painel | Consulta |
|---|---|
| Réplicas no ar por serviço | `count by (app) (up{job="microsservicos"} == 1)` |
| Pods monitorados | `up{job="microsservicos"}` |
| Requisições/s por serviço | `sum by (application) (rate(http_server_requests_seconds_count[1m]))` |
| Requisições/s por pod | `sum by (pod) (rate(http_server_requests_seconds_count[1m]))` |
| Tempo médio de resposta | `rate(..._sum) / rate(..._count)` por serviço |
| Respostas 4xx e 5xx | `status=~"4.."` e `status=~"5.."` por serviço |
| CPU, heap e threads por pod | `process_cpu_usage`, `jvm_memory_used_bytes`, `jvm_threads_live_threads` |
| Conexões com o banco | `hikaricp_connections_active` |

As chamadas a `/actuator/*` (probes e coleta) ficam de fora dos painéis de tráfego.

O envio OTLP para o Grafana Cloud do trabalho anterior continua no código, mas fica desligado no cluster (`OTLP_ENABLED=false`): aqui a observabilidade roda dentro do Minikube, como pede o enunciado.

---

## Problemas comuns

| Sintoma | Causa e solução |
|---|---|
| Pod em `Pending` | Falta CPU ou memória no Minikube. `kubectl describe pod <pod>` mostra o motivo. Recrie com mais recursos: `minikube delete` e `minikube start --cpus 4 --memory 6144`. |
| `ErrImagePull` / `ImagePullBackOff` | Imagem não encontrada. Veja a opção B do passo 2. |
| `Init:0/1` por muito tempo | O pod está esperando o PostgreSQL. `kubectl logs deploy/postgres`. |
| Um pod do auth ou do quadras reinicia uma vez no primeiro deploy | As duas réplicas sobem juntas e tentam criar os dados iniciais ao mesmo tempo. A segunda esbarra na restrição de unicidade, reinicia, encontra os dados já criados e segue normal. |
| Mudou senha no Secret e o serviço não conecta no banco | O script do banco só roda com o volume vazio. Recrie: `kubectl delete -f k8s/database/postgres-deployment.yaml -f k8s/database/postgres-pvc.yaml` e aplique de novo. |
| Mudou um ConfigMap e nada aconteceu | Variáveis de ambiente são lidas na criação do pod: `kubectl rollout restart deployment/<nome>`. |
| `http://127.0.0.1` não responde (Windows/macOS) | O `minikube tunnel` precisa ficar aberto em outro terminal. |

## Remover tudo

```bash
kubectl delete -R -f k8s/
minikube stop
```
