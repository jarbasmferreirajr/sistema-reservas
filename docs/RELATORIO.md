# Relatório técnico — Sistema de Reservas

**Disciplina:** Programação Concorrente e Distribuída — IFSC Câmpus Gaspar

**Integrante:** Jarbas de Morais Ferreira Junior

**Repositório:** https://github.com/jarbasmferreirajr/sistema-reservas

---

## 1. Visão geral

O sistema vende 50 assentos de um evento. Os clientes falam com o servidor por TCP; o servidor roda em **duas réplicas**, cada uma um processo Java separado, com o mesmo estado. Cada réplica tem:

- um `ServerSocket` que aceita conexões e cria **uma thread por cliente** (`Atendente`);
- o **recurso compartilhado** `Assentos` (vetor `dono[50]` + contador de reservas), acessado por todas as threads;
- um `DatagramSocket` que responde `STATUS` via **UDP**;
- um **detector de falhas** que pergunta `STATUS` às outras réplicas a cada 500 ms.

![Arquitetura](arquitetura.png)

A replicação é **primária-backup síncrona**: só a primária executa escritas; cada escrita é enviada à backup e confirmada (ACK) **antes** de o cliente receber o OK. Uma backup que recebe `RESERVAR`/`CANCELAR` encaminha para a primária (`FWD`). Leituras (`LISTAR`) são respondidas localmente.

Classes principais (`src/main/java/reservas/`):

| Classe | Papel |
|---|---|
| `Servidor` | Réplica: aceita conexões, UDP, replicação, detector de falhas e failover |
| `Atendente` | Thread que atende uma conexão TCP e interpreta o protocolo |
| `Assentos` | Recurso compartilhado e **região crítica** |
| `ClienteReservas` | Lado cliente do protocolo, com failover automático |
| `Cliente` | Cliente interativo (terminal) |
| `TesteCarga` | Experimento: 3 clientes × 10 requisições simultâneas |
| `ConsultaStatus` | Consulta STATUS por UDP e compara UDP × TCP |

O protocolo completo está em [`PROTOCOLO.md`](PROTOCOLO.md).

---

## 2. Experimento de concorrência

### 2.1 Como foi feito

- 3 clientes (`cli1`, `cli2`, `cli3`), cada um com sua conexão TCP e, portanto, sua thread no servidor.
- Todos conectam primeiro e esperam uma **largada** comum (`CountDownLatch`); só então os três disparam juntos suas 10 requisições → **30 requisições concorrentes**.
- Cada cliente reserva 10 assentos só seus (1–10, 11–20, 21–30). O resultado correto é **30 reservas registradas**.
- Entre ler e escrever, a região crítica faz uma pausa de 3 ms. Ela só aumenta a probabilidade de duas threads se intercalarem; **a pausa é a mesma nos dois experimentos** — o que muda é apenas a trava.
- Entre um pedido e outro, cada cliente espera um tempo aleatório de 0 a 30 ms, para os encontros entre threads acontecerem ao acaso, como com usuários reais.
- Ao final, o comando `CONTAGEM` compara, em cada réplica, o **contador** de reservas com os **assentos realmente ocupados** no vetor.

O mesmo código roda nos dois experimentos. A diferença é uma linha, em `Assentos.reservar()`:

```java
public String reservar(int n, String usuario) {
    if (sincronizado) {
        synchronized (this) {                 // COM sincronização: uma thread por vez na RC
            return reservarRC(n, usuario, true);
        }
    }
    return reservarRC(n, usuario, true);      // SEM sincronização: várias threads ao mesmo tempo
}
```

### 2.2 Resultados

Experimento 1 — **sem** sincronização (5 rodadas seguidas):

| Rodada | Requisições | Respostas OK | Contador (registradas) | Assentos ocupados | Atualizações perdidas | Resultado |
|---|---|---|---|---|---|---|
| 1 | 30 | 30 | **23** | 30 | 7 | inconsistente |
| 2 | 30 | 30 | **19** | 30 | 11 | inconsistente |
| 3 | 30 | 30 | **22** | 30 | 8 | inconsistente |
| 4 | 30 | 30 | **20** | 30 | 10 | inconsistente |
| 5 | 30 | 30 | **21** | 30 | 9 | inconsistente |

Experimento 2 — **com** sincronização (5 rodadas seguidas):

| Rodada | Requisições | Respostas OK | Contador (registradas) | Assentos ocupados | Resultado |
|---|---|---|---|---|---|
| 1 a 5 | 30 | 30 | **30** | 30 | consistente (30/30) nas 5 rodadas |

Tabela comparativa pedida no enunciado:

| Experimento | Sincronização | Requisições | Resultado |
|---|---|---|---|
| 1 | Não | 30 | Condição de corrida demonstrada: de 19 a 23 reservas registradas em vez de 30 (média 21,0) |
| 2 | Sim (`synchronized`) | 30 | Resultado consistente: 30/30 em todas as rodadas |

Tempo das 30 requisições: 237–266 ms sem sincronização e 248–297 ms com sincronização. Com só 3 clientes, o tempo total é dominado pela pausa aleatória entre os pedidos, então a trava custou pouco; com muitos clientes disputando, a fila na entrada do `synchronized` pesaria mais.

Um detalhe que o teste também mostra: nas rodadas sem sincronização, a réplica 2 (backup) terminou com o contador em 30. A backup recebe as escritas da primária uma de cada vez, pela conexão de replicação, então nela não há corrida. Sem a trava, as duas réplicas ficaram com estados diferentes.

### 2.3 O que o log mostra

Trecho do `logs/replica-1.log` na largada do experimento 1 (sem sincronização), em 05/10. Cada linha traz a hora, a **thread** e o valor lido/gravado:

```
18:17:46.891 R1 [atendente-8] RC RESERVAR assento=21 user=cli3 | le: assento=livre contador=0
18:17:46.891 R1 [atendente-10] RC RESERVAR assento=11 user=cli2 | le: assento=livre contador=0
18:17:46.891 R1 [atendente-9] RC RESERVAR assento=1 user=cli1 | le: assento=livre contador=0
18:17:46.896 R1 [atendente-9] RC RESERVAR assento=1 user=cli1 | grava: contador 0 -> 1 | OK
18:17:46.896 R1 [atendente-10] RC RESERVAR assento=11 user=cli2 | grava: contador 0 -> 1 | OK
18:17:46.902 R1 [atendente-8] !! CORRIDA: contador mudou de 0 para 1 durante a janela; esta thread vai gravar 1 por cima (atualizacao perdida)
18:17:46.903 R1 [atendente-8] RC RESERVAR assento=21 user=cli3 | grava: contador 0 -> 1 | OK
18:17:46.922 R1 [atendente-8] RC RESERVAR assento=22 user=cli3 | le: assento=livre contador=1
18:17:46.923 R1 [atendente-9] RC RESERVAR assento=2 user=cli1 | le: assento=livre contador=1
18:17:46.926 R1 [atendente-8] RC RESERVAR assento=22 user=cli3 | grava: contador 1 -> 2 | OK
18:17:46.928 R1 [atendente-9] !! CORRIDA: contador mudou de 1 para 2 durante a janela; esta thread vai gravar 2 por cima (atualizacao perdida)
18:17:46.928 R1 [atendente-9] RC RESERVAR assento=2 user=cli1 | grava: contador 1 -> 2 | OK
```

As três threads (uma por cliente) leram `contador=0` no mesmo milissegundo, antes de qualquer uma gravar. Cada uma gravou `0 + 1 = 1`: foram três reservas (assentos 1, 11 e 21), mas o contador só subiu uma vez — **duas atualizações perdidas**. Logo depois, `atendente-8` e `atendente-9` leem 1 e as duas gravam 2: mais uma perdida. Nessa rodada, o contador terminou em **20 de 30** (10 atualizações perdidas).

As linhas `!! CORRIDA` são um diagnóstico do próprio código e não pegam todos os casos: as gravações da `atendente-9` e da `atendente-10` aconteceram no mesmo milissegundo, e cada uma conferiu o contador antes de a outra gravar. Por isso o número oficial vem do `CONTAGEM`, que compara o contador com os assentos ocupados no fim.

No experimento 2 (com sincronização), as leituras e escritas nunca se intercalam: cada thread lê o valor que a anterior acabou de gravar. Trecho do mesmo log:

```
18:19:25.452 R1 [atendente-16] RC RESERVAR assento=1 user=cli1 | le: assento=livre contador=0
18:19:25.458 R1 [atendente-16] RC RESERVAR assento=1 user=cli1 | grava: contador 0 -> 1 | OK
18:19:25.459 R1 [atendente-15] RC RESERVAR assento=21 user=cli3 | le: assento=livre contador=1
18:19:25.463 R1 [atendente-15] RC RESERVAR assento=21 user=cli3 | grava: contador 1 -> 2 | OK
18:19:25.465 R1 [atendente-17] RC RESERVAR assento=11 user=cli2 | le: assento=livre contador=2
18:19:25.470 R1 [atendente-17] RC RESERVAR assento=11 user=cli2 | grava: contador 2 -> 3 | OK
```

---

## 3. Sistema distribuído

### 3.1 Estratégia de replicação: primária-backup síncrona

**Como funciona.** A réplica que sobe primeiro vira primária. Uma réplica que sobe depois faz `JOIN`, recebe o estado completo (`SNAPSHOT`) e passa a receber cada escrita (`REPL`). A primária só responde OK ao cliente depois do ACK da backup. Com a sincronização ligada, a replicação acontece **dentro** do `synchronized`, então a backup recebe as escritas exatamente na ordem em que foram feitas na primária.

**Por que essa e não as outras:**

- **Quorum de leitura/escrita** precisa de pelo menos 3 réplicas para tolerar uma falha com maioria (com 2 réplicas, a maioria é 2, e qualquer queda para o sistema). Também exigiria versionar cada assento.
- **Consenso (Raft)** resolve o que a primária-backup não resolve (partição de rede), mas precisa de eleição por votos, log replicado e 3+ nós. É complexidade demais para 2 réplicas numa mesma máquina.
- **Primária-backup síncrona** dá o que o enunciado exige com 2 réplicas: todas as escritas passam por um único ponto (a primária), então a regra "no máximo um cliente por assento" continua sendo garantida por um único `synchronized`; e, por ser síncrona, quando o cliente recebe OK a reserva já existe nas duas réplicas — se a primária cair logo depois, nada se perde.

O custo é que cada escrita espera uma ida e volta até a backup e que todas as escritas passam por uma réplica só (a primária é o gargalo).

### 3.2 Falhas

**Detecção.** Cada réplica envia `STATUS` via UDP às outras a cada 500 ms. Três sem resposta seguidos (~1,5 s) = réplica fora do ar. Há também um atalho: se a backup não consegue encaminhar uma escrita à primária (nem reconectando uma vez), ela já conclui que a primária caiu.

**Failover.** Quando a primária cai, a backup viva de menor id se promove a primária. Ela já tem todo o estado, porque a replicação é síncrona.

**Recuperação do cliente.** O `ClienteReservas` conhece a lista das réplicas. Se a conexão cai no meio de um pedido, ele conecta na próxima réplica e **reenvia o mesmo pedido**. Não há duplicação porque `RESERVAR` é idempotente por usuário: se a reserva já tinha sido gravada e replicada antes da queda, a nova primária responde `OK|JA_ERA_SEU` sem contar de novo.

**Volta da réplica.** A réplica que volta encontra uma primária ativa, faz `JOIN`, copia o estado e entra como backup (não retoma o papel de primária, para não voltar com dados velhos).

**O que aparece no TESTE 6:**

| Ação | O que o sistema mostra |
|---|---|
| Cliente `bruno` encerrado | Na réplica 1: `cliente bruno caiu` (ou `fechou a conexao`) e `clientes conectados agora: ... (o servidor continua atendendo os demais)`. Os outros clientes seguem funcionando. |
| Réplica 1 (primária) derrubada | Na réplica 2, em cerca de 1,5 s: `nao respondeu 3 heartbeats seguidos -> considerada FORA DO AR` e `*** FAILOVER ... PROMOVIDA a PRIMARIA ***`. |
| Cliente `ana`, que estava na réplica 1, faz uma reserva | `[failover] conexao ... perdida ... reenviando`, `[conectado] 127.0.0.1:5002 replica 2` e `OK\|RESERVADO`. As reservas feitas antes da queda continuam lá. |
| Réplica 1 religada | `JOIN ok: estado copiado da replica 2 ... Papel: BACKUP`. Ela volta como backup, com o estado atual. |

---

## 4. TCP × UDP

| Característica | TCP | UDP |
|---|---|---|
| Conexão | Orientado à conexão (handshake antes de enviar) | Sem conexão: cada datagrama é independente |
| Confiabilidade | Garante entrega (retransmite) | Não garante: um datagrama pode se perder |
| Ordenação | Preserva a ordem | Não garante ordem |
| Falha do servidor | Erro imediato (`Connection refused`) | Silêncio: só o timeout indica |
| Uso no projeto | Reservas, cancelamentos, listagem, replicação e encaminhamento | `STATUS` (consulta rápida) e heartbeat entre réplicas |

Medição com 300 consultas `STATUS` idênticas em cada modo, na réplica 1 (TESTE 5):

| Método | Respondidas | Média | Mediana | Máximo |
|---|---|---|---|---|
| UDP (1 datagrama de ida + 1 de volta) | 300/300 | 0,197 ms | 0,160 ms | 0,82 ms |
| TCP abrindo conexão a cada consulta | 300/300 | 2,620 ms | 2,473 ms | 5,40 ms |
| TCP com conexão já aberta | 300/300 | 0,124 ms | 0,099 ms | 0,90 ms |

E contra uma porta sem servidor: TCP falhou em 6 ms com `Connection refused`; UDP ficou 1009 ms esperando e só o timeout mostrou que não havia ninguém.

Leitura: para uma consulta avulsa, o UDP foi cerca de 13 a 15 vezes mais rápido que o TCP (pela média e pela mediana), porque não paga o handshake de conexão. Com uma conexão TCP já aberta e reaproveitada, o TCP empata ou ganha. Por isso o STATUS e o heartbeat (mensagens curtas, avulsas, que podem se perder sem prejuízo — o próximo heartbeat chega em 500 ms) usam UDP, e as reservas (que não podem se perder nem chegar fora de ordem) usam TCP.

---

## 5. Respostas às perguntas do enunciado

**1. O que caracteriza este sistema como distribuído?**
São vários processos independentes (duas réplicas do servidor e vários clientes), sem memória compartilhada, que só se coordenam trocando mensagens pela rede (TCP e UDP). O estado (os assentos) está replicado em processos diferentes, cada componente pode falhar sozinho (falha parcial) e o sistema continua funcionando. Aqui eles rodam na mesma máquina, em portas diferentes, mas só conversam pela rede: rodariam igual em máquinas separadas trocando os endereços configurados.

**2. Qual é o recurso compartilhado e qual é, exatamente, a região crítica no código?**
O recurso compartilhado é o objeto `Assentos` de cada réplica: o vetor `dono[50]` e o contador `reservasRegistradas`, acessados ao mesmo tempo por todas as threads `Atendente`. A região crítica é o corpo de `Assentos.reservarRC()` (linhas 90–122) e de `Assentos.cancelarRC()` (linhas 124–159): a sequência **ler** o assento e o contador → **decidir** → **gravar** o assento e `contador = valorLido ± 1`. Ela é protegida por `synchronized (this)` em `reservar()` e `cancelar()` (linhas 60–76).

**3. O que é uma condição de corrida e como ela foi produzida no experimento?**
É quando o resultado depende da ordem em que as threads se intercalam no acesso a um dado compartilhado. Foi produzida disparando 3 clientes × 10 reservas ao mesmo tempo contra a região crítica sem trava: duas ou três threads leem o mesmo valor do contador antes de qualquer uma gravar, e cada uma grava "valor lido + 1", apagando o incremento das outras (atualização perdida). A pausa de 3 ms entre ler e gravar só alarga a janela em que isso pode acontecer.

**4. Qual foi a inconsistência numérica observada?**
Nas 5 rodadas sem sincronização, o contador terminou em 23, 19, 22, 20 e 21 reservas registradas, em vez de 30 — embora os 30 pedidos tenham recebido OK e os 30 assentos estivessem ocupados no vetor (de 7 a 11 atualizações perdidas por rodada). Com sincronização, 30 de 30 nas 5 rodadas.

**5. Como o mecanismo de sincronização escolhido resolve o problema?**
`synchronized (this)` usa o monitor do objeto `Assentos`: só uma thread por vez executa a região crítica, e as outras esperam na entrada. Assim, "ler-decidir-gravar" vira uma operação indivisível (atômica) do ponto de vista das outras threads: cada uma lê o valor que a anterior já gravou. O monitor também garante visibilidade (quem entra enxerga as escritas de quem saiu). Com a mesma pausa de 3 ms, o resultado passou a ser 30/30 em todas as rodadas. Escolhemos `synchronized` em vez de `AtomicInteger` porque a operação envolve dois dados (o assento e o contador) e uma decisão entre a leitura e a escrita — um contador atômico sozinho não impediria dois clientes de reservarem o mesmo assento.

**6. Qual estratégia de replicação foi usada entre as réplicas e por quê?**
Primária-backup síncrona (seção 3.1): todas as escritas passam pela primária, que as aplica sob o `synchronized` e as envia à backup esperando o ACK antes de responder ao cliente. Foi escolhida porque funciona com 2 réplicas, mantém a garantia de "um cliente por assento" num único ponto de sincronização e não perde reservas confirmadas quando a primária cai.

**7. O que acontece quando uma réplica cai? Como o cliente se recupera?**
Se cai a **backup**, a primária percebe no próximo envio de replicação ou pelos heartbeats, tira a backup da replicação e segue sozinha. Se cai a **primária**, a backup percebe (3 heartbeats UDP sem resposta, ~1,5 s, ou na hora, se falhar um encaminhamento) e se promove, já com todo o estado. O cliente que estava conectado na réplica que caiu recebe erro de conexão, conecta na próxima réplica da lista e reenvia o mesmo pedido; a idempotência do RESERVAR impede duplicação. Quando a réplica volta, ela entra como backup e copia o estado da primária atual.

**8. Qual é a diferença entre TCP e UDP, e onde cada um foi usado no projeto?**
TCP é orientado à conexão, confiável e ordenado; UDP envia datagramas independentes, sem garantia de entrega nem de ordem e sem aviso de falha. TCP foi usado em tudo que não pode se perder: reservas, cancelamentos, listagem, replicação (`REPL`), encaminhamento (`FWD`) e `JOIN`. UDP foi usado no `STATUS` (consulta rápida de estado) e no heartbeat entre as réplicas, onde uma mensagem perdida não causa dano. Medições na seção 4.

**9. Quais são as limitações conhecidas da solução implementada?**
Ver seção 6.

---

## 6. Limitações conhecidas

- **Partição de rede (split-brain).** Se as duas réplicas perderem contato entre si mas continuarem vivas, as duas podem se considerar primárias e aceitar escritas. Quando voltam a se ver, a de maior id se rebaixa e copia o estado da outra, **descartando** o que aceitou nesse intervalo. Resolver isso de verdade exige quorum/consenso com 3+ réplicas.
- **Sem persistência.** O estado fica só em memória. Se as duas réplicas caírem ao mesmo tempo, as reservas se perdem.
- **Primária é gargalo.** Todas as escritas passam por ela e, com a sincronização ligada, uma de cada vez (incluindo a ida e volta até a backup).
- **Detecção por timeout.** Uma réplica lenta (não morta) pode ser dada como fora do ar; ela volta a sincronizar sozinha, mas pode haver um failover desnecessário.
- **Idempotência parcial.** RESERVAR é idempotente por usuário; CANCELAR não: um CANCELAR reenviado após failover, se o primeiro já tinha sido aplicado, responde `ERRO|ASSENTO_NAO_RESERVADO` (o estado fica correto, mas a mensagem confunde). Um id por requisição resolveria.
- **Sem autenticação.** Qualquer cliente pode usar qualquer nome de usuário.
- **Janela artificial.** A pausa de 3 ms na região crítica existe só para tornar a corrida visível na demonstração; em produção ela seria removida (a corrida continuaria possível, só mais rara).
- **Configuração estática.** Os endereços das réplicas (127.0.0.1, portas 5001/5002 e 6001/6002) são fixos; não dá para adicionar réplicas com o sistema rodando.
