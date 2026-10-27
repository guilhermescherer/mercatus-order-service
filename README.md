# mercatus-order-service

Criação e consulta de pedidos com snapshots imutáveis de Catalog e Customer.
O serviço segue arquitetura hexagonal em `com.mercatus.order`, herda o parent
publicado `com.mercatus:mercatus-parent:1.0.0` e usa Java 25, Spring Boot 4.1,
PostgreSQL 17, Flyway, JPA e DTOs JSON próprios.

## Execução local

Inicie o PostgreSQL pelo repositório Platform:

```bash
docker compose -f ../mercatus-platform/compose/docker-compose.yml --profile core up -d
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

Execute Catalog na porta `8081` e Customer na `8082`, conforme seus READMEs.
O Order inicia na `8084`; cada serviço usa seu próprio banco.

| Variável | Padrão |
|---|---|
| `ORDER_PORT` | `8084` |
| `ORDER_DB_URL` | `jdbc:postgresql://localhost:5432/order` |
| `ORDER_DB_USER` | `mercatus` |
| `ORDER_DB_PASSWORD` | `mercatus` |
| `CATALOG_BASE_URL` | `http://localhost:8081/api/v1` |
| `CUSTOMER_BASE_URL` | `http://localhost:8082/api/v1` |
| `CATALOG_CONNECT_TIMEOUT`, `CUSTOMER_CONNECT_TIMEOUT` | `2s` |
| `CATALOG_READ_TIMEOUT`, `CUSTOMER_READ_TIMEOUT` | `5s` |

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

## API e regras

```bash
curl -i http://localhost:8084/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: purchase-example-1' \
  -H 'X-Correlation-Id: example-flow' \
  -d '{"customerId":"019a0000-0000-7000-8000-000000000001","addressId":"019a0000-0000-7000-8000-000000000002","paymentMethod":"PIX","items":[{"skuCode":"SHIRT-M","quantity":2}]}'

curl http://localhost:8084/api/v1/orders/ID_DO_PEDIDO
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
| Pedido, cliente ou SKU inexistente | `404` |
| Chave usada com outro conteúdo ou conflito de persistência | `409` |
| Regra de compra ou limite monetário violado | `422` |
| Resposta remota incompatível ou incompleta | `502` |
| Timeout, erro técnico ou dependência indisponível | `503` |

Erros usam Problem Details e mensagens sem corpos remotos ou dados pessoais.
Status numérico na resposta do Customer é incompatível e retorna `502`;
apenas os nomes `ACTIVE` e `INACTIVE` são aceitos.

## Validação

`mvn verify` executa testes locais de domínio, aplicação, geração UUID,
adapters HTTP, controller e ArchUnit. Os adapters são exercitados também sem
rede por `MockRestServiceServer`; MockMvc usa o mesmo configurador JSON da
aplicação. Os mocks usam o mecanismo por subclassificação, sem anexar um
agente à JVM.

`OrderApiIT` e `OrderRestartIT` usam PostgreSQL 17 via Testcontainers,
aplicação real e servidor HTTP controlável do JDK. Cobrem snapshots,
contratos, validações, falhas remotas, timeout, concorrência entre oito
requisições, rollback depois de gravar itens, constraints, migration,
OpenAPI e replay após reiniciar o contexto com o mesmo banco. Docker é
obrigatório e os testes não são ignorados automaticamente quando falta.

Para validar contratos com serviços reais em ambiente local de teste, inicie
Catalog, Customer e Order e execute:

```bash
python3 scripts/verify-real-services.py
```

O script cria dados próprios identificados por UUID, registra duas compras e
altera preço, nome, variação, cliente e endereço das fontes para comprovar que
GET e replay mantêm a compra original, enquanto outra chave usa os dados novos.
Não apaga registros. Também verifica códigos com barras, contrabarras e
caracteres reservados, e a compatibilidade da rota antiga do Catalog.
Usa apenas a biblioteca padrão do Python 3 e encerra com código diferente de
zero se alguma verificação falhar. As URLs, incluindo `/api/v1`, podem ser
ajustadas com `--catalog`, `--customer` e `--order`; `--timeout` define o limite
por chamada em segundos (padrão: 15).

Resultados e impedimentos desta entrega estão no
[registro de validação](../.kiro/specs/order-creation-query/validation.md).

## Limite da entrega

Pedidos permanecem em `CREATED`, com histórico inicial `ORDER_CREATED` e
origem `HTTP_REQUEST`. Kafka, outbox, inbox, OrderSaga, reserva, cancelamento,
expiração, listagem e edição de pedidos pertencem aos próximos incrementos.
A fase 5 continua em andamento. O isolamento local de credenciais segue a
dívida registrada no ADR-0014.
