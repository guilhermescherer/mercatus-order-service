# mercatus-order-service

Criação e consulta de pedidos com snapshots imutáveis de Catalog e Customer.
O serviço segue arquitetura hexagonal em `com.mercatus.order`, herda o parent
publicado `com.mercatus:mercatus-parent:1.0.0` e usa Java 25, Spring Boot 4.1,
PostgreSQL 17, Flyway, JPA, Kafka, Spring Security (OAuth2 Resource Server e
OAuth2 Client) e DTOs JSON próprios.

## Execução local

Inicie PostgreSQL, Kafka e Keycloak pelo repositório Platform:

```bash
docker compose -f ../mercatus-platform/compose/docker-compose.yml --profile core --profile events --profile security up -d --wait
```

O database `order` precisa existir. Em volume antigo, siga o procedimento do
[Platform](../mercatus-platform/README.md#bancos-e-volumes-existentes), que
preserva os outros bancos. Flyway cria as tabelas ao iniciar o serviço;
`ddl-auto: validate` verifica o schema e `open-in-view` está desabilitado.

O parent é consumido do GitHub Packages, com `relativePath` vazio.
Configure `MAVEN_USERNAME` e `MAVEN_PASSWORD` no ambiente com acesso de leitura
ao pacote e use `.mvn/settings.xml`; não coloque tokens em arquivos versionados.
O CI usa `github.actor` e `GITHUB_TOKEN` com `packages: read`.

```bash
mvn -s .mvn/settings.xml verify
mvn -s .mvn/settings.xml spring-boot:run
```

Como os demais serviços, o Order inicia na `8080`. Para executar vários serviços ao
mesmo tempo, defina uma porta para cada um com `SERVER_PORT`; os exemplos deste README
usam Catalog `8081`, Customer `8082`, Inventory `8083` e Order `8084`
(`SERVER_PORT=8084`). Cada serviço usa seu próprio banco.

| Variável | Padrão |
|---|---|
| `SERVER_PORT` | `8080` |
| `ORDER_DB_URL` | `jdbc:postgresql://localhost:5432/order` |
| `ORDER_DB_USER` | `mercatus` |
| `ORDER_DB_PASSWORD` | `mercatus` |
| `CATALOG_BASE_URL` | `http://localhost:8081/api/v1` |
| `CUSTOMER_BASE_URL` | `http://localhost:8082/api/v1` |
| `CATALOG_CONNECT_TIMEOUT`, `CUSTOMER_CONNECT_TIMEOUT` | `2s` |
| `CATALOG_READ_TIMEOUT`, `CUSTOMER_READ_TIMEOUT` | `5s` |
| `MERCATUS_ISSUER_URI` | `http://localhost:8180/realms/mercatus` |
| `ORDER_CLIENT_ID` | `order-service` |
| `ORDER_CLIENT_SECRET` | vazio (obrigatório para chamar Catalog e Customer) |
| `IDENTITY_PROVIDER_CONNECT_TIMEOUT` | `2s` |
| `IDENTITY_PROVIDER_READ_TIMEOUT` | `5s` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `INVENTORY_COMMANDS_TOPIC` | `inventory.commands.v1` |
| `INVENTORY_EVENTS_TOPIC` | `inventory.events.v1` |
| `OUTBOX_POLL_INTERVAL_MS` | `500` |
| `OUTBOX_BATCH_SIZE` | `100` |
| `OUTBOX_SEND_TIMEOUT` | `10s` |

Mensagens para o Kafka são gravadas na tabela `outbox_message`, na mesma
transação do dado de negócio, e enviadas por um publicador periódico; se o
broker estiver fora, ficam pendentes e saem quando ele volta. Mensagens
recebidas são registradas em `inbox_message` pelo `messageId`, para que uma
repetição não tenha efeito. O serviço sobe mesmo sem Kafka. Para executar
localmente com mensageria, suba também o perfil `events` do Platform.

Os timeouts aceitam durações Spring; devem ser positivos. URLs e timeouts
também podem ser sobrescritos em `order.clients.catalog` e
`order.clients.customer`. Não há retries automáticos ou cache.

- Ping: http://localhost:8084/api/v1/ping
- Health: http://localhost:8084/api/v1/actuator/health
- OpenAPI: http://localhost:8084/api/v1/v3/api-docs
- Swagger UI: http://localhost:8084/api/v1/swagger-ui/index.html

Logs da aplicação são JSON ECS em inglês, com `X-Correlation-Id` recebido ou
gerado na borda e propagado às chamadas HTTP. Não são registrados corpo,
endereço, e-mail ou chave de idempotência.

## Saga do pedido

Ao criar um pedido novo, o Order grava na mesma transação o pedido, o estado da
saga (`order_saga`) e o comando `ReserveInventory` na outbox. Comandos vão para
`inventory.commands.v1` e respostas chegam em `inventory.events.v1`, sempre com
chave `orderId`, o que mantém a ordem das mensagens de um mesmo pedido.

| Mensagem recebida | Pedido |
|---|---|
| `InventoryReserved` | `CREATED → AWAITING_PAYMENT` |
| `InventoryReservationFailed` (`OUT_OF_STOCK`) | `CREATED → CANCELLED`, motivo `OUT_OF_STOCK` |
| `ReservationExpired` com pedido aguardando pagamento | `AWAITING_PAYMENT → CANCELLING → CANCELLED`, motivo `RESERVATION_EXPIRED` |
| `ReservationReleased` / `ReservationExpired` com pedido em cancelamento | `CANCELLING → CANCELLED`, motivo mantido |

### Cancelamento

`POST /api/v1/orders/{id}/cancel` não tem corpo nem exige `Idempotency-Key`:
repetir o pedido devolve o estado atual sem nova compensação.

| Estado | Efeito | HTTP |
|---|---|---|
| `AWAITING_PAYMENT` | `CANCELLING` e comando `ReleaseReservation` na mesma transação | `202` |
| `CREATED` com reserva em andamento | `CANCELLING`; aguarda a resposta da reserva. Se ela for aceita, a reserva é liberada; se faltar estoque, o pedido termina sem liberar | `202` |
| `CREATED` sem saga (pedidos anteriores à saga) | `CANCELLING → CANCELLED` de imediato | `200` |
| `CANCELLING` / `CANCELLED` | nenhum | `202` / `200` |

O motivo é `CUSTOMER_REQUEST` e a transição registra o ator `CUSTOMER`. O pedido só
chega a `CANCELLED` quando o Inventory confirma a liberação ou informa que a reserva
já expirou. Conflito de versão com a saga responde `409`; repetir é seguro.

Respostas repetidas ou atrasadas não fazem o pedido voltar: nada sai de
`CANCELLED`. Uma reserva confirmada depois de o pedido já estar cancelado é
liberada automaticamente.

### Prazos e reenvio

Cada passo que espera resposta (reserva ou liberação) tem prazo. Uma varredura
periódica reenvia o comando do passo vencido com novo `messageId` e o mesmo
`correlationId`; o Inventory responde de forma idempotente. Se o comando anterior
ainda não saiu da outbox (Kafka fora), só o prazo é estendido. Esgotadas as
tentativas, a saga recebe `stalled_at` e um log `ERROR` pede ação do operador; uma
resposta tardia ainda é aplicada normalmente. Prazo vencido nunca cancela o pedido:
demora não é falta de estoque.

| Variável | Padrão |
|---|---|
| `ORDER_RESERVE_TIMEOUT` | `PT5M` (prazo da reserva) |
| `ORDER_RELEASE_TIMEOUT` | `PT5M` (prazo da liberação) |
| `ORDER_SAGA_MAX_RETRIES` | `5` |
| `ORDER_SAGA_RECOVERY_INTERVAL_MS` | `30000` |
| `ORDER_SAGA_RECOVERY_BATCH_SIZE` | `100` |

O Inventory precisa estar numa versão que entenda `ReleaseReservation` antes de o
Order enviar esse comando. Para executar o fluxo completo localmente, suba os perfis
`core` e `events` do Platform e os quatro serviços (Catalog 8081, Customer 8082,
Inventory 8083, Order 8084).

## API e regras

```bash
curl -i http://localhost:8084/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: purchase-example-1' \
  -H 'X-Correlation-Id: example-flow' \
  -d '{"customerId":"019a0000-0000-7000-8000-000000000001","addressId":"019a0000-0000-7000-8000-000000000002","paymentMethod":"PIX","items":[{"skuCode":"SHIRT-M","quantity":2}]}'

curl -H "Authorization: Bearer $TOKEN" http://localhost:8084/api/v1/orders/ID_DO_PEDIDO

curl -i -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8084/api/v1/orders/ID_DO_PEDIDO/cancel
```

Substitua os ids e o código pelos registros existentes em Customer e Catalog.
Cliente precisa estar ativo; o endereço deve estar na resposta desse cliente.
O Catalog decide `availableForSale` e fornece preço BRL, nome e variações.
Cada SKU é consultado uma vez por criação nova, via `GET /skus?code=...`
no Catalog. A query preserva o código exato, inclusive barras, contrabarras,
pontos e caracteres reservados. Não há consulta ao Inventory.

A entrada exige `CARD` ou `PIX` e ao menos um item com quantidade inteira
positiva (`long`). Campos desconhecidos, preços, totais e dados de cartão
são rejeitados. SKU duplicado é violação de negócio. Money tem escala 2 e
capacidade `NUMERIC(19,2)`; preço zero é válido, e overflow de subtotal/total
é rejeitado antes de gravar. O total é a soma dos subtotais, sem frete.

A resposta contém UUID v7, número `ORD-` com ao menos 12 dígitos, snapshots,
itens, preços, subtotais, total, método, estado, motivo de cancelamento,
histórico, datas UTC e versão. O instante de criação tem precisão de
microssegundos para preservar igualdade com o PostgreSQL. A numeração usa
sequência global; lacunas são esperadas após rollback ou disputa.

`Idempotency-Key` é global, sensível a caixa, opaca, sem espaços nas pontas,
com 1 a 128 caracteres. A identidade inclui cliente, endereço, método e pares
SKU/quantidade, independentemente da ordem dos itens. Mesma chave e conteúdo
retornam o pedido persistido sem consultar dependências; conteúdo diferente
retorna `409`. Primeira criação responde `201`, replay `200`; ambas incluem
`Location: /api/v1/orders/{id}`. Chave e conteúdo original ficam no banco e
não aparecem na resposta.

A transação `READ_COMMITTED` grava raiz, chave, conteúdo canônico JSONB, itens
e histórico atomicamente. `INSERT ... ON CONFLICT DO NOTHING` escolhe um
vencedor; os demais leem o pedido confirmado em outra instrução SQL. Falhas
revertem todos os registros e permitem reutilizar a chave. Replays e consultas
preservam snapshots mesmo após mudança ou indisponibilidade das fontes.

O SQL fica nos repositórios de infraestrutura: `OrderJpaRepository` obtém a
sequência e tenta inserir a raiz; `OrderItemJpaRepository` e
`OrderTransitionJpaRepository` gravam os filhos. Os comandos usam `@Query`
nativa com `@Modifying`. O adapter faz o mapeamento e delega essas operações,
gravando itens e histórico apenas quando a raiz é inserida. A transação
continua definida no caso de uso.

| Situação | HTTP |
|---|---|
| JSON, campos, tipos, UUID ou cabeçalho inválidos | `400` |
| Sem token, token inválido, expirado ou de outro issuer | `401` |
| Papel sem permissão para a rota | `403` |
| Pedido, cliente ou SKU inexistente | `404` |
| Chave usada com outro conteúdo ou conflito de persistência | `409` |
| Regra de compra ou limite monetário violado | `422` |
| Resposta remota incompatível ou incompleta; credencial do serviço recusada pelo Keycloak; Catalog ou Customer recusam o token do serviço (`401`/`403`) | `502` |
| Timeout, erro técnico ou dependência indisponível, inclusive o Keycloak (token do serviço ou chaves para validar o token recebido) | `503` |

Erros usam Problem Details e mensagens sem corpos remotos ou dados pessoais.
Status numérico na resposta do Customer é incompatível e retorna `502`;
apenas os nomes `ACTIVE` e `INACTIVE` são aceitos.

## Segurança

Toda rota exige `Authorization: Bearer <token>`, exceto `GET /ping`,
`/actuator/health`, `/actuator/info` e o OpenAPI (`/v3/api-docs`, `/swagger-ui`).
O token é um JWT do realm `mercatus` do Keycloak: o serviço confere assinatura
(chaves públicas do realm, obtidas em `<issuer>/protocol/openid-connect/certs` e
mantidas em cache), issuer e validade, e lê os papéis de `realm_access.roles`.

| Método e rota | Papéis |
|---|---|
| `POST /orders` | `CUSTOMER` |
| `GET /orders/{id}` | `CUSTOMER`, `OPERATOR` |
| `POST /orders/{id}/cancel` | `CUSTOMER` |

`ADMIN` é composto no realm e inclui `OPERATOR`. Qualquer outra rota é negada.
Respostas `401`, `403` e `503` de segurança usam Problem Details e trazem
`X-Correlation-Id`. Os papéis controlam o tipo de operação, não o dono do dado:
um token `CUSTOMER` ainda alcança o pedido de outro cliente pelo id.

### Chamadas a Catalog e Customer

O Order se autentica em Catalog e Customer com o próprio token, obtido no
Keycloak pelo fluxo *client credentials* do cliente `order-service` (papel
`SERVICE`). O secret vem de `ORDER_CLIENT_SECRET`; no Keycloak local, o valor de
desenvolvimento está no realm descrito no
[README do Platform](../mercatus-platform/README.md). O token fica em memória e é
reaproveitado até perto do vencimento; se Catalog ou Customer responderem `401`,
ele é descartado e um novo é pedido na chamada seguinte. O pedido do token usa
os timeouts `IDENTITY_PROVIDER_*`.

Os comandos e eventos Kafka da saga não passam por essa verificação: a
segurança se aplica à API HTTP.

Para obter um token de cliente no Keycloak local:

```bash
TOKEN=$(curl -s http://localhost:8180/realms/mercatus/protocol/openid-connect/token \
  -d grant_type=password -d client_id=mercatus-cli -d username=customer1 -d password=customer1 \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
```

## Validação

`mvn verify` executa testes locais de domínio, aplicação, geração UUID,
adapters HTTP, controller e ArchUnit. Os adapters são exercitados também sem
rede por `MockRestServiceServer`; MockMvc usa o mesmo configurador JSON da
aplicação. Os mocks usam o mecanismo por subclassificação, sem anexar um
agente à JVM.

Os ITs de saga (`OrderSagaIT`, `OrderCancellationIT`, `ReservationClosureIT`,
`SagaRecoveryIT`) usam Kafka `apache/kafka:3.9.1` e PostgreSQL 17 reais e fazem o
papel do Inventory publicando as respostas no tópico de eventos.

Os ITs de API (`OrderCreationIT`, `OrderValidationIT`, `OrderPersistenceIT`,
`OrderDependencyFailureIT`) e `OrderRestartIT` usam PostgreSQL 17 via Testcontainers,
aplicação real e servidor HTTP controlável do JDK. Cobrem snapshots,
contratos, validações, falhas remotas, timeout, concorrência entre oito
requisições, rollback depois de gravar itens, constraints, migration,
OpenAPI e replay após reiniciar o contexto com o mesmo banco. Docker é
obrigatório e os testes não são ignorados automaticamente quando falta.

`OrderSecurityIT` cobre rotas públicas, ausência de token, token malformado,
assinado por chave desconhecida, expirado e de outro issuer, `403` por papel,
rotas sem regra e a matriz de papéis; `OrderIdentityProviderOutageIT`, o serviço
com o Keycloak inacessível. `ClientCredentialsIT` cobre o token do serviço:
enviado a Catalog e Customer, reaproveitado, descartado após `401`, e as falhas
(Keycloak inalcançável ou com erro → `503`; credencial recusada ou token recusado
pela dependência → `502`), sem criar pedido. Os testes não usam o Keycloak:
`TestTokens` assina JWTs com chaves geradas em memória e as publica num servidor
HTTP local (JWKS), e o servidor controlável do JDK simula o endpoint de token.

## Limite da entrega

A saga cobre reserva, cancelamento, liberação e expiração de reserva, além do
reenvio por prazo. Pagamento, listagem e edição de pedidos ainda não existem. A varredura de prazos assume uma instância do Order; com várias, a seleção
das sagas vencidas precisa de bloqueio por linha. Não há endpoint de operador para
sagas travadas (o aviso é o log e a coluna `stalled_at`). O isolamento local de
credenciais de banco permanece como dívida técnica.
