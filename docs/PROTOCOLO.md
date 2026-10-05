# Protocolo de aplicação

Texto puro em UTF-8, **uma mensagem por linha** (terminada em `\n`), campos separados por `|`.
Todo comando TCP recebe exatamente uma linha de resposta (requisição → resposta), na mesma conexão.

| Réplica | TCP (reservas) | UDP (STATUS) |
|---|---|---|
| 1 | 5001 | 6001 |
| 2 | 5002 | 6002 |

Regras gerais:

- `<assento>` é um número de 1 a 50.
- `<usuario>` aceita letras, números, `.`, `_` e `-` (1 a 32 caracteres), para não conflitar com os separadores.
- Respostas de sucesso começam com `OK`; de falha, com `ERRO|<motivo>`.
- **RESERVAR é idempotente por usuário**: pedir de novo um assento que já é seu devolve `OK|JA_ERA_SEU|<n>` e não conta duas vezes. É isso que permite ao cliente reenviar um pedido depois de uma queda sem duplicar a reserva.

## 1. Comandos do cliente (TCP)

| Comando | Função | Resposta de sucesso | Respostas de erro |
|---|---|---|---|
| `OLA\|<usuario>` | Apresentação ao conectar (opcional; faz o cliente aparecer em `CLIENTES` no STATUS) | `OLA\|REPLICA=1\|PAPEL=PRIMARIA\|PRIMARIA=1\|ASSENTOS=50` | — |
| `LISTAR` | Lista os assentos livres | `LISTA\|1,4,7,...` (vazio se lotado: `LISTA\|`) | `ERRO\|REPLICA_INICIALIZANDO` |
| `RESERVAR\|<assento>\|<usuario>` | Reserva um assento | `OK\|RESERVADO\|<n>`<br>`OK\|JA_ERA_SEU\|<n>` | `ERRO\|ASSENTO_OCUPADO\|<n>`<br>`ERRO\|ASSENTO_INEXISTENTE\|<n>`<br>`ERRO\|ASSENTO_INVALIDO\|<x>`<br>`ERRO\|USUARIO_INVALIDO\|...`<br>`ERRO\|FORMATO_INVALIDO\|...`<br>`ERRO\|SEM_PRIMARIA\|...` (failover em andamento) |
| `CANCELAR\|<assento>\|<usuario>` | Cancela uma reserva do próprio usuário | `OK\|CANCELADO\|<n>` | `ERRO\|ASSENTO_NAO_RESERVADO\|<n>`<br>`ERRO\|RESERVADO_POR_OUTRO_USUARIO\|<n>`<br>e os mesmos de formato do RESERVAR |
| `SAIR` | Encerra a conexão | `BYE` | — |
| *(qualquer outro)* | — | — | `ERRO\|COMANDO_DESCONHECIDO\|<cmd>` |

## 2. Consulta de estado (UDP)

| Datagrama enviado | Resposta (um datagrama) |
|---|---|
| `STATUS` | `STATUS\|ID=1\|PAPEL=PRIMARIA\|PRIMARIA=1\|CLIENTES=3\|REQ=27\|LIVRES=20\|SYNC=ON\|REPLICAS=2\|ATIVO` |
| `STATUS\|<seq>` | a mesma linha + `\|SEQ=<seq>` (para casar pergunta e resposta) |
| outro | `ERRO\|COMANDO_UDP_DESCONHECIDO\|use STATUS` |

Campos: `ID` réplica que respondeu · `PAPEL` PRIMARIA, BACKUP ou INICIALIZANDO · `PRIMARIA` id da primária segundo ela · `CLIENTES` clientes TCP conectados agora · `REQ` requisições de cliente atendidas · `LIVRES` assentos livres · `SYNC` sincronização da região crítica ligada/desligada · `REPLICAS` backups que estão recebendo a replicação (só na primária).

Se a réplica estiver fora do ar, **não há resposta nenhuma**: o cliente só descobre pelo timeout (1 s). É a diferença prática mais visível entre UDP e TCP.

## 3. Comandos usados pelos programas de teste (TCP)

O `TesteCarga` usa `MODO`, `RESET` e `CONTAGEM`; o `ConsultaStatus` usa `STATUS` por TCP para comparar com o UDP.

| Comando | Função | Resposta |
|---|---|---|
| `CONTAGEM` | Compara o contador com o vetor de assentos desta réplica | `CONTAGEM\|REGISTRADAS=27\|OCUPADOS=30\|LIVRES=20\|CONSISTENTE=NAO\|PAPEL=PRIMARIA` |
| `RESET\|<rotulo>` | Libera todos os assentos (vai para a primária e é replicado) | `OK\|RESET` |
| `MODO\|SYNC\|ON` / `MODO\|SYNC\|OFF` | Liga/desliga a proteção da região crítica **nesta réplica** | `OK\|MODO\|SYNC=ON` |
| `STATUS` | O mesmo STATUS, por TCP (só para comparar com o UDP) | `STATUS\|...` |

## 4. Mensagens internas entre réplicas

| Mensagem | Sentido | Função | Resposta |
|---|---|---|---|
| `JOIN\|<id>` | backup → primária (TCP) | Backup entra (ao subir ou ao voltar): pede o estado completo e passa a receber as escritas | `SNAPSHOT\|3:ana;7:bruno` (`-` se vazio) ou `ERRO\|NAO_SOU_PRIMARIA\|<id>` |
| `REPL\|RESERVAR\|<n>\|<u>`<br>`REPL\|CANCELAR\|<n>\|<u>`<br>`REPL\|RESET\|<rotulo>` | primária → backup (TCP, conexão persistente) | Replicação síncrona de cada escrita, na ordem em que foi feita | `ACK\|OK\|...` (ou `ACK\|ERRO\|...` se as réplicas divergiram) |
| `FWD\|<comando>` | backup → primária (TCP) | Backup encaminha uma escrita que recebeu de um cliente | A mesma resposta do comando, ou `ERRO\|NAO_SOU_PRIMARIA\|<id>` |
| `STATUS\|<seq>\|HB<id>` | réplica → réplica (UDP, a cada 500 ms) | Heartbeat do detector de falhas | `STATUS\|...\|SEQ=<seq>` |

## 5. Exemplos de troca de mensagens

Reserva feita por um cliente conectado na **backup**:

```
cliente  -> réplica 2 : RESERVAR|12|carla
réplica 2 -> réplica 1 : FWD|RESERVAR|12|carla
réplica 1 -> réplica 2 : REPL|RESERVAR|12|carla        (antes de responder!)
réplica 2 -> réplica 1 : ACK|OK|RESERVADO|12
réplica 1 -> réplica 2 : OK|RESERVADO|12               (resposta do FWD)
réplica 2 -> cliente   : OK|RESERVADO|12
```

Queda da primária no meio de um pedido:

```
cliente   -> réplica 1 : RESERVAR|7|ana
                         (réplica 1 cai; a conexão fecha sem resposta)
cliente   -> réplica 2 : OLA|ana
cliente   -> réplica 2 : RESERVAR|7|ana                (mesmo pedido, reenviado)
réplica 2              : não consegue falar com a 1 -> se promove a PRIMARIA
réplica 2 -> cliente   : OK|RESERVADO|7   (ou OK|JA_ERA_SEU|7, se a 1 já tinha replicado)
```
