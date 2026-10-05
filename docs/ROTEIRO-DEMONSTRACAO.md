# Roteiro de execução — passo a passo

Tudo roda no VS Code pelo menu **Terminal → Executar Tarefa...**. A lista tem 6 tarefas numeradas, na ordem da apresentação:

1. `1. TESTE 1 - subir réplicas 1 e 2 e clientes ana, bruno e carla`
2. `2. TESTES 2 e 3 - 30 reservas SEM sincronização`
3. `3. TESTE 4 - 30 reservas COM sincronização`
4. `4. TESTE 5 - STATUS via UDP e comparação TCP x UDP`
5. `5. TESTE 6 - derrubar a réplica 1`
6. `6. TESTE 6 - religar a réplica 1`

**Como rodar uma tarefa (vale para todos os passos):** menu **Terminal → Executar Tarefa...** → clique na tarefa. (Atalho: `Ctrl+Shift+P`, digite `executar tarefa` e aperte Enter.)

Cada réplica e cada cliente abre num terminal com o próprio nome. Os terminais ficam listados à direita do painel de baixo; clique no nome para ver cada um.

---

## 0. Preparação (antes da aula)

1. **Feche o VS Code**, para encerrar o que estiver rodando.
2. **Use só a pasta nova.** Apague a pasta antiga do projeto e descompacte o `.zip` novo.
3. **Abra a pasta no VS Code:** *Arquivo → Abrir Pasta...* e escolha a pasta `sistema-reservas` que contém `pom.xml`, `src` e `docs`. Se ela ficou dentro de outra com o mesmo nome (`sistema-reservas\sistema-reservas`), abra a de dentro.

---

## Passo 1 — TESTE 1: réplicas + 3 clientes

Rode a tarefa **1**, **uma vez só**. Se aparecer o aviso de que a tarefa já está ativa, feche o aviso: tudo já está rodando.

Primeiro aparece o terminal **Compilar** com `compilado em out`. Logo depois abrem mais 5: **Réplica 1**, **Réplica 2**, **Cliente ana**, **Cliente bruno** e **Cliente carla**.

Espere uns 5 segundos e confira:

| Terminal | Deve aparecer |
|---|---|
| Réplica 1 | `Replica 1 PRONTA como PRIMARIA` e `JOIN da replica 2` |
| Réplica 2 | `JOIN ok: estado copiado da replica 1 ... Papel: BACKUP` |
| Cliente ana e Cliente bruno | `[conectado] 127.0.0.1:5001 replica 1` e `pronto: digite um comando` |
| Cliente carla | `[conectado] 127.0.0.1:5002 replica 2` e `pronto: digite um comando` |

**O cliente fica parado em `ana>` (ou `bruno>`, `carla>`): é normal.** Ele está esperando você digitar. Nada acontece sozinho nos terminais dos clientes.

Agora faça as reservas. Clique dentro do terminal do cliente, digite o comando e aperte Enter:

| Terminal | Digite | Resposta esperada |
|---|---|---|
| Cliente ana | `reservar 5` | `<- OK\|RESERVADO\|5` |
| Cliente bruno | `reservar 5` | `<- ERRO\|ASSENTO_OCUPADO\|5` |
| Cliente carla | `reservar 12` | `<- OK\|RESERVADO\|12` (e no terminal Réplica 2 aparece `encaminhado a primaria`) |
| Cliente ana | `listar` | `<- LISTA\|1,2,3,4,6,...` (48 livres) |

**O que mostrar ao professor:** no terminal Réplica 1 aparece `clientes conectados: 2`, e no Réplica 2, `clientes conectados: 1`. São 3 clientes simultâneos, cada um atendido por sua própria thread (`atendente-N` no log).

---

## Passo 2 — TESTES 2 e 3: 30 reservas SEM sincronização

Deixe tudo do passo 1 aberto e rode a tarefa **2**.

Abre um terminal novo com o nome da tarefa. Em cerca de 1 segundo aparece o resultado (os números mudam a cada vez):

```
replica 127.0.0.1:5001 (PRIMARIA): contador REGISTRADAS=21  assentos OCUPADOS=30  <-- INCONSISTENTE
replica 127.0.0.1:5002 (BACKUP): contador REGISTRADAS=30  assentos OCUPADOS=30  ok
atualizacoes perdidas no contador da primaria: 9 (21 de 30)
RESULTADO: INCONSISTENTE (condicao de corrida)
```

**O que mostrar ao professor (TESTE 2 = logs das threads):** abra o terminal **Réplica 1** e role para cima. Aparecem linhas `RC RESERVAR` de threads diferentes (`atendente-4`, `atendente-5`, `atendente-6`) lendo o **mesmo** `contador=` antes de qualquer uma gravar, e linhas `!! CORRIDA`.

**Frase-chave:** "os 30 pedidos receberam OK, mas o contador perdeu atualizações: duas threads leram o mesmo valor e cada uma gravou valor+1 por cima da outra".

**Se o professor pedir para repetir:** rode a tarefa 2 de novo.

---

## Passo 3 — TESTE 4: o mesmo teste COM sincronização

Rode a tarefa **3**. Resultado:

```
replica 127.0.0.1:5001 (PRIMARIA): contador REGISTRADAS=30  assentos OCUPADOS=30  ok
replica 127.0.0.1:5002 (BACKUP): contador REGISTRADAS=30  assentos OCUPADOS=30  ok
RESULTADO: CONSISTENTE (30/30)
```

**O que mostrar ao professor:**
- no terminal Réplica 1, o contador sobe em sequência (`0 -> 1`, `1 -> 2`, `2 -> 3`...), sem duas threads lendo o mesmo valor;
- em `src/main/java/reservas/Assentos.java`, o método `reservar()`: a única diferença entre os dois testes é passar ou não por `synchronized (this)`.

---

## Passo 4 — TESTE 5: STATUS via UDP e comparação TCP × UDP

Rode a tarefa **4**. Aparecem 3 blocos:

1. **STATUS via UDP:** uma linha por réplica. A réplica 1 aparece com `PAPEL=PRIMARIA`, a réplica 2 com `PAPEL=BACKUP`, e cada uma mostra `CLIENTES=...` e `REQ=...`.
2. **Tabela de tempos** (300 consultas cada). Na sua execução deu:
   - UDP: cerca de 0,2 ms;
   - TCP abrindo conexão a cada consulta: cerca de 2,6 ms;
   - TCP com a conexão já aberta: cerca de 0,1 ms.
3. **Porta sem servidor:** o TCP mostra `Connection refused` em poucos ms; o UDP mostra `nenhuma resposta apos ~1000 ms`.

**Frase-chave:** "UDP para o que pode se perder (STATUS, heartbeat); TCP para o que não pode (reservas, replicação)".

---

## Passo 5 — TESTE 6: queda de um cliente e de uma réplica

**a) Encerrar um cliente**
- Clique no terminal **Cliente bruno** e aperte `Ctrl+C`. O terminal mostra que o processo terminou; é o esperado.
- No terminal Réplica 1 aparece `cliente bruno ...` e `clientes conectados agora: ... (o servidor continua atendendo os demais)`.
- No terminal Cliente ana, digite `listar`: continua funcionando.

**b) Derrubar a réplica 1 (a primária)**
- Rode a tarefa **5**. Aparece `replica 1 derrubada`.
- O terminal Réplica 1 mostra que o processo terminou.

**c) Ver o failover na réplica 2**
- No terminal **Réplica 2**, em cerca de 2 segundos, aparece `considerada FORA DO AR` e depois `*** FAILOVER ... PROMOVIDA a PRIMARIA ***`.

**d) Ver o cliente se recuperar**
- No terminal **Cliente ana**, digite `reservar 40`. Deve aparecer, em sequência:
  - `[failover] conexao com 127.0.0.1:5001 replica 1 perdida ... reenviando 'RESERVAR|40|ana'`;
  - `[conectado] 127.0.0.1:5002 replica 2`;
  - `<- OK|RESERVADO|40`.

**e) Conferir que nada se perdeu**
- Digite `listar` no **Cliente ana** e no **Cliente carla**: a lista é a mesma nos dois.

**f) Religar a réplica 1**
- Rode a tarefa **6**. A réplica 1 sobe de novo no mesmo terminal **Réplica 1**.
- Aparece `JOIN ok: estado copiado da replica 2 ... Papel: BACKUP`. Ela volta como backup, com o estado atual.

---

## Para recomeçar do zero

Feche o VS Code e abra de novo. Isso encerra todas as réplicas e clientes. Depois, volte ao **passo 1**.

## Se algo sair diferente

| O que apareceu | O que fazer |
|---|---|
| `ERRO: a porta TCP 5001 ja esta em uso` | Ficou uma réplica de antes rodando. Feche o VS Code e abra de novo. Se continuar, abra o Gerenciador de Tarefas e finalize os processos Java. |
| No terminal Compilar: `javac não é reconhecido` | O JDK não está no PATH. Confira com `javac -version` num terminal. |
| O teste diz `Nenhuma replica respondeu` | O passo 1 não está rodando. Rode a tarefa 1 antes. |
| O cliente mostra `[aguardando] ERRO\|SEM_PRIMARIA` | Normal por cerca de 2 segundos depois de derrubar a réplica 1; ele tenta de novo sozinho. |
| No passo 2 deu `CONSISTENTE` | Raro: as threads não se cruzaram naquela vez. Rode a tarefa 2 de novo. |
| Ao final de uma tarefa: "O terminal será reutilizado pelas tarefas" | Normal: a tarefa terminou. Pode deixar o terminal aberto. |
| A tarefa 6 "não responde" | A réplica 1 já está no ar (a tarefa 6 continua ativa enquanto ela roda). Ela só faz algo depois da tarefa 5. Confira no terminal Réplica 1 a linha `Papel: BACKUP`. |

## Onde ficam os logs para entregar

Na pasta `logs` do projeto:

- `replica-1.log` e `replica-2.log`: tudo o que cada réplica fez, com as threads e os valores lidos e gravados;
- `teste-sem-sync-<data>.log`: o experimento 1 (sem sincronização);
- `teste-com-sync-<data>.log`: o experimento 2 (com sincronização).
