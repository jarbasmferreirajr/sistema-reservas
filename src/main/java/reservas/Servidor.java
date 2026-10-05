package reservas;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uma RÉPLICA do servidor de reservas (um processo independente).
 *
 *  - TCP: aceita clientes; cada conexão ganha uma thread própria (Atendente).
 *  - UDP: responde STATUS (consulta rápida e heartbeat entre réplicas).
 *  - Replicação PRIMÁRIA-BACKUP síncrona: só a primária executa escritas; cada escrita
 *    é enviada às backups e confirmada (ACK) antes de responder ao cliente.
 *    Uma backup que recebe RESERVAR/CANCELAR encaminha para a primária.
 *  - Detector de falhas: a cada 500 ms pergunta STATUS às outras réplicas por UDP;
 *    3 silêncios seguidos = réplica fora do ar. Se a primária cair, a backup viva de
 *    menor id é promovida (failover).
 *
 * Uso: java -cp out reservas.Servidor --id 1
 *        [--replicas 127.0.0.1:5001:6001,127.0.0.1:5002:6002] [--assentos 50]
 *        [--sync on|off] [--janela 3] [--log-dir logs]
 *  --replicas: host:portaTcp:portaUdp de cada réplica; o id é a posição na lista (1, 2, ...)
 */
public final class Servidor {

    static final int INTERVALO_HEARTBEAT_MS = 500;
    static final int TIMEOUT_HEARTBEAT_MS = 300;
    static final int FALHAS_PARA_DECLARAR_QUEDA = 3;

    final Par eu;
    final List<Par> outros = new ArrayList<>();
    final Assentos assentos;

    volatile int primariaId = -1;
    volatile boolean pronto = false;                 // false enquanto procura/sincroniza com as outras
    private volatile boolean sincronizadoComPrimaria = false;
    private volatile long primariaDefinidaEm = 0;    // quando esta réplica escolheu a primária atual

    final AtomicInteger clientesAtivos = new AtomicInteger();
    final AtomicLong requisicoes = new AtomicLong();
    private final AtomicInteger seqAtendentes = new AtomicInteger();
    private final AtomicLong seqHeartbeat = new AtomicLong();

    Servidor(int id, List<Par> todas, int qtdAssentos, boolean sync, int janelaMs) {
        Par meu = null;
        for (Par p : todas) {
            if (p.id == id) {
                meu = p;
            } else {
                outros.add(p);
            }
        }
        if (meu == null) {
            throw new IllegalArgumentException("a replica " + id + " nao existe em --replicas (ha " + todas.size() + ")");
        }
        this.eu = meu;
        this.assentos = new Assentos(qtdAssentos, sync, janelaMs);
        this.assentos.setAposEscrita(this::propagar);
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Map<String, String> a = Args.parse(args);
        int id = Args.inteiro(a, "id", 1);
        String config = a.getOrDefault("replicas", "127.0.0.1:5001:6001,127.0.0.1:5002:6002");
        int qtd = Args.inteiro(a, "assentos", 50);
        boolean sync = !"off".equalsIgnoreCase(a.getOrDefault("sync", "on"));
        int janela = Args.inteiro(a, "janela", 3);
        String dirLogs = a.getOrDefault("log-dir", "logs");

        Log.configurar("R" + id + " ", dirLogs + "/replica-" + id + ".log");
        Servidor s = new Servidor(id, Par.parseConfig(config), qtd, sync, janela);
        s.iniciar(dirLogs);
    }

    boolean souPrimaria() {
        return primariaId == eu.id;
    }

    String papel() {
        if (!pronto) {
            return "INICIALIZANDO";
        }
        return souPrimaria() ? "PRIMARIA" : "BACKUP";
    }

    Par par(int id) {
        for (Par p : outros) {
            if (p.id == id) {
                return p;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- partida

    private void iniciar(String dirLogs) throws IOException {
        InetAddress bind = enderecoDeBind(eu.tcp.host);
        ServerSocket servidorTcp;
        DatagramSocket servidorUdp;
        try {
            servidorTcp = new ServerSocket(eu.tcp.porta, 50, bind);
        } catch (BindException e) {
            Log.info("ERRO: a porta TCP " + eu.tcp.porta + " ja esta em uso. A replica " + eu.id + " ja esta rodando?");
            System.exit(1);
            return;
        }
        try {
            servidorUdp = new DatagramSocket(new InetSocketAddress(bind, eu.udp.porta));
        } catch (IOException e) {
            Log.info("ERRO: a porta UDP " + eu.udp.porta + " ja esta em uso. A replica " + eu.id + " ja esta rodando?");
            System.exit(1);
            return;
        }
        gravarPid(dirLogs);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Log.info("replica " + eu.id + " encerrada"), "encerramento"));

        Log.info("=== Replica " + eu.id + " | TCP " + eu.tcp + " | UDP " + eu.udp
                + " | assentos=" + assentos.quantidade()
                + " | sincronizacao=" + (assentos.isSincronizado() ? "ON" : "OFF")
                + " | janela=" + assentos.getJanelaMs() + " ms ===");

        iniciarThread("aceitador-tcp", () -> loopAceitar(servidorTcp), false);
        iniciarThread("udp-status", () -> loopUdp(servidorUdp), true);

        descobrirPapel();
        pronto = true;
        Log.info("Replica " + eu.id + " PRONTA como " + papel() + " (primaria = replica " + primariaId + ")");

        iniciarThread("detector-falhas", this::loopDetector, true);
    }

    private static InetAddress enderecoDeBind(String host) throws IOException {
        InetAddress a = InetAddress.getByName(host);
        // 127.0.0.1: escuta só localmente (evita o aviso do Firewall do Windows).
        // IP da rede: escuta em todas as interfaces (demonstração entre máquinas).
        return a.isLoopbackAddress() ? a : null;
    }

    private void gravarPid(String dirLogs) {
        try {
            Path dir = Path.of(dirLogs);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("replica-" + eu.id + ".pid"), String.valueOf(ProcessHandle.current().pid()));
        } catch (IOException e) {
            Log.info("aviso: nao foi possivel gravar o arquivo .pid: " + e.getMessage());
        }
    }

    private static void iniciarThread(String nome, Runnable r, boolean daemon) {
        Thread t = new Thread(r, nome);
        t.setDaemon(daemon);
        t.start();
    }

    // ------------------------------------------------------- TCP: 1 thread/cliente

    private void loopAceitar(ServerSocket ss) {
        while (true) {
            try {
                Socket s = ss.accept();                                   // bloqueia até alguém conectar
                String nome = "atendente-" + seqAtendentes.incrementAndGet();
                new Thread(new Atendente(this, s), nome).start();         // uma thread por conexão
            } catch (IOException e) {
                Log.info("erro no accept: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------ UDP: STATUS

    private void loopUdp(DatagramSocket ds) {
        byte[] buf = new byte[1024];
        while (true) {
            try {
                DatagramPacket pedido = new DatagramPacket(buf, buf.length);
                ds.receive(pedido);
                String msg = new String(pedido.getData(), 0, pedido.getLength(), StandardCharsets.UTF_8).trim();
                String[] p = msg.split("\\|");
                String resposta;
                if ("STATUS".equalsIgnoreCase(p[0])) {
                    resposta = status();
                    if (p.length > 1) {
                        resposta += "|SEQ=" + p[1];        // permite casar pergunta e resposta
                    }
                    if (p.length <= 2) {                   // heartbeats e medições não poluem o log
                        Log.info("UDP STATUS de " + pedido.getSocketAddress() + " -> " + resposta);
                    }
                } else {
                    resposta = "ERRO|COMANDO_UDP_DESCONHECIDO|use STATUS";
                }
                byte[] dados = resposta.getBytes(StandardCharsets.UTF_8);
                ds.send(new DatagramPacket(dados, dados.length, pedido.getSocketAddress()));
            } catch (IOException e) {
                // No Windows, um ICMP "porta inalcancavel" referente a um datagrama anterior pode
                // aparecer aqui como erro. UDP nao tem conexao: basta seguir atendendo.
            }
        }
    }

    String status() {
        return "STATUS|ID=" + eu.id
                + "|PAPEL=" + papel()
                + "|PRIMARIA=" + primariaId
                + "|CLIENTES=" + clientesAtivos.get()
                + "|REQ=" + requisicoes.get()
                + "|LIVRES=" + assentos.contarLivres()
                + "|SYNC=" + (assentos.isSincronizado() ? "ON" : "OFF")
                + "|REPLICAS=" + replicasEmReplicacao()
                + "|ATIVO";
    }

    private String replicasEmReplicacao() {
        StringBuilder sb = new StringBuilder();
        for (Par p : outros) {
            if (p.naReplicacao) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(p.id);
            }
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    // ------------------------------------------------ replicação (primária)

    /**
     * Chamado pela Assentos logo após uma escrita local bem-sucedida. Com sincronização
     * ligada, roda dentro do synchronized: as backups recebem as escritas na mesma ordem.
     */
    private void propagar(String op) {
        if (!souPrimaria()) {
            return;
        }
        for (Par p : outros) {
            if (!p.naReplicacao) {
                continue;
            }
            try {
                String r = p.replicar("REPL|" + op);
                if (!r.startsWith("ACK|OK")) {
                    Log.info("!! a " + p + " respondeu '" + r + "' para " + op
                            + " -> as replicas DIVERGIRAM (so acontece sem sincronizacao)");
                }
            } catch (IOException e) {
                p.naReplicacao = false;
                Log.info("FALHA ao replicar para " + p + " (" + e.getMessage()
                        + ") -> removida da replicacao; o sistema segue com as replicas restantes");
            }
        }
    }

    /** Uma backup pede para entrar: recebe o estado completo e passa a receber as escritas. */
    String tratarJoin(int idPar) {
        Par p = par(idPar);
        if (p == null) {
            return "ERRO|REPLICA_DESCONHECIDA|" + idPar;
        }
        if (!pronto || !souPrimaria()) {
            return "ERRO|NAO_SOU_PRIMARIA|" + primariaId;
        }
        // Trava o recurso: nenhuma escrita acontece entre tirar a "foto" e incluir a backup.
        synchronized (assentos) {
            p.fecharLink();
            p.naReplicacao = true;
            p.viva = true;
            p.falhasSeguidas = 0;
            p.papel = "BACKUP";                 // descarta o que se sabia dela antes de cair
            p.primariaInformada = eu.id;
            String snap = assentos.snapshot();
            Log.info("JOIN da " + p + ": estado atual enviado e replica incluida na replicacao");
            return "SNAPSHOT|" + snap;
        }
    }

    // --------------------------------------------------- replicação (backup)

    /** Pede JOIN à primária e carrega o estado dela. */
    private boolean entrarNaReplicacao(Par prim) {
        if (prim == null) {
            return false;
        }
        // Segura o recurso local: escritas replicadas que chegarem esperam o estado ser carregado.
        synchronized (assentos) {
            try (Conexao c = Conexao.abrir(prim.tcp, 1000, 3000)) {
                String r = c.pedir("JOIN|" + eu.id);
                if (!r.startsWith("SNAPSHOT|")) {
                    Log.info("JOIN recusado pela " + prim + ": " + r);
                    return false;
                }
                assentos.carregar(r.substring("SNAPSHOT|".length()));
                definirPrimaria(prim.id);
                sincronizadoComPrimaria = true;
                Log.info("JOIN ok: estado copiado da " + prim + " ("
                        + (assentos.quantidade() - assentos.contarLivres()) + " assentos ocupados). Papel: BACKUP");
                return true;
            } catch (IOException e) {
                Log.info("JOIN falhou com a " + prim + ": " + e.getMessage());
                return false;
            }
        }
    }

    /** Na partida: se já existe primária, entra como backup; senão assume como primária. */
    private void descobrirPapel() {
        if (outros.isEmpty()) {
            definirPrimaria(eu.id);
            sincronizadoComPrimaria = true;
            return;
        }
        Log.info("procurando outras replicas ativas (STATUS via UDP)...");
        // Se ninguém responde, a réplica de id maior espera mais antes de assumir como primária:
        // assim, subindo tudo junto (ex.: VS Code), a réplica 1 é quem vira primária.
        int tentativasAntesDeAssumir = 2 + 4 * (eu.id - 1);
        for (int tentativa = 1; tentativa <= 12 + tentativasAntesDeAssumir; tentativa++) {
            sondarTodos();
            for (Par p : outros) {
                if (p.viva && "PRIMARIA".equals(p.papel) && entrarNaReplicacao(p)) {
                    return;
                }
            }
            boolean alguemViva = false;
            boolean todasIniciando = true;
            boolean souMenorId = true;
            for (Par p : outros) {
                if (p.viva) {
                    alguemViva = true;
                    todasIniciando &= "INICIALIZANDO".equals(p.papel);
                    souMenorId &= eu.id < p.id;
                }
            }
            if (!alguemViva && tentativa >= tentativasAntesDeAssumir) {
                definirPrimaria(eu.id);
                sincronizadoComPrimaria = true;
                Log.info("nenhuma outra replica respondeu: assumindo papel de PRIMARIA");
                return;
            }
            if (alguemViva && todasIniciando && souMenorId && tentativa >= 2) {
                definirPrimaria(eu.id);
                sincronizadoComPrimaria = true;
                Log.info("replicas subindo ao mesmo tempo: a de menor id (esta) assume como PRIMARIA");
                return;
            }
            dormir(300);
        }
        definirPrimaria(eu.id);
        sincronizadoComPrimaria = true;
        Log.info("nenhuma primaria encontrada a tempo: assumindo papel de PRIMARIA");
    }

    // ------------------------------------------------- detector de falhas

    private void loopDetector() {
        while (true) {
            try {
                sondarTodos();
                verificarPapel();
            } catch (RuntimeException e) {
                Log.info("erro no detector de falhas: " + e);
            }
            dormir(INTERVALO_HEARTBEAT_MS);
        }
    }

    /** Heartbeat: STATUS via UDP para cada réplica; atualiza viva/papel. */
    private void sondarTodos() {
        for (Par p : outros) {
            long enviadoEm = System.currentTimeMillis();
            String r = sondar(p);
            if (r != null) {
                Map<String, String> c = campos(r);
                p.papel = c.getOrDefault("PAPEL", "?");
                p.primariaInformada = inteiro(c.get("PRIMARIA"), -1);
                p.replicasInformadas = c.getOrDefault("REPLICAS", "-");
                p.statusPedidoEm = enviadoEm;
                p.falhasSeguidas = 0;
                if (!p.viva) {
                    p.viva = true;
                    Log.info("detector: " + p + " esta ATIVA (papel " + p.papel + ")");
                }
            } else {
                p.falhasSeguidas++;
                if (p.viva && p.falhasSeguidas >= FALHAS_PARA_DECLARAR_QUEDA) {
                    p.viva = false;
                    p.papel = "?";                 // informação antiga não vale mais
                    Log.info("detector: " + p + " nao respondeu " + p.falhasSeguidas
                            + " heartbeats seguidos -> considerada FORA DO AR");
                    if (p.naReplicacao) {
                        p.naReplicacao = false;
                        p.fecharLink();
                        Log.info("  " + p + " removida da replicacao");
                    }
                }
            }
        }
    }

    /** Um socket UDP novo por sondagem: um erro ICMP de uma réplica caída não afeta as outras. */
    private String sondar(Par p) {
        long seq = seqHeartbeat.incrementAndGet();
        byte[] dados = ("STATUS|" + seq + "|HB" + eu.id).getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.send(new DatagramPacket(dados, dados.length, new InetSocketAddress(p.udp.host, p.udp.porta)));
            long limite = System.currentTimeMillis() + TIMEOUT_HEARTBEAT_MS;
            byte[] buf = new byte[1024];
            while (true) {
                long resta = limite - System.currentTimeMillis();
                if (resta <= 0) {
                    return null;
                }
                ds.setSoTimeout((int) resta);
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                ds.receive(resp);
                String txt = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                if (txt.endsWith("|SEQ=" + seq)) {
                    return txt;                 // respostas atrasadas de sondagens antigas são descartadas
                }
            }
        } catch (IOException e) {
            return null;                        // timeout ou ICMP "porta inalcancavel" = sem resposta
        }
    }

    /** Decide o papel desta réplica a partir do que o detector viu. */
    private synchronized void verificarPapel() {
        if (souPrimaria()) {
            // Proteção contra duas primárias (split-brain): a de menor id prevalece.
            for (Par p : outros) {
                if (p.viva && "PRIMARIA".equals(p.papel) && p.id < eu.id && p.statusPedidoEm > primariaDefinidaEm) {
                    Log.info("!! a " + p + " tambem e PRIMARIA e tem id menor -> esta replica volta a ser BACKUP");
                    definirPrimaria(p.id);
                    sincronizadoComPrimaria = false;
                    for (Par q : outros) {
                        q.naReplicacao = false;
                        q.fecharLink();
                    }
                    entrarNaReplicacao(p);
                    return;
                }
            }
            return;
        }
        Par prim = par(primariaId);
        if (prim == null || !prim.viva) {
            eleger("a primaria (replica " + primariaId + ") esta fora do ar", true);
            return;
        }
        boolean infoRecente = prim.statusPedidoEm > primariaDefinidaEm;
        if (infoRecente && "BACKUP".equals(prim.papel)) {
            // A réplica que eu considerava primária diz que é backup: procura quem é a primária.
            for (Par p : outros) {
                if (p.viva && "PRIMARIA".equals(p.papel)) {
                    Log.info("a " + prim + " nao e mais a primaria; a primaria atual e a " + p);
                    definirPrimaria(p.id);
                    sincronizadoComPrimaria = false;
                    entrarNaReplicacao(p);
                    return;
                }
            }
            eleger("nenhuma replica ativa se declara primaria", false);
            return;
        }
        boolean primariaMeEsqueceu = infoRecente && "PRIMARIA".equals(prim.papel)
                && !contemId(prim.replicasInformadas, eu.id);
        if (!sincronizadoComPrimaria || primariaMeEsqueceu) {
            entrarNaReplicacao(prim);
        }
    }

    /**
     * Failover: a réplica viva de menor id vira primária.
     * excluirAntiga = true quando a primária anterior caiu (ela não é candidata).
     */
    private synchronized void eleger(String motivo, boolean excluirAntiga) {
        int antiga = primariaId;
        int nova = eu.id;
        for (Par p : outros) {
            if (p.viva && !(excluirAntiga && p.id == antiga) && p.id < nova) {
                nova = p.id;
            }
        }
        if (nova == eu.id) {
            definirPrimaria(eu.id);
            sincronizadoComPrimaria = true;
            for (Par p : outros) {          // as outras backups precisarão fazer JOIN na nova primária
                p.naReplicacao = false;
                p.fecharLink();
            }
            Log.info("*** FAILOVER: " + motivo + " -> esta replica foi PROMOVIDA a PRIMARIA ***");
        } else {
            if (nova != antiga) {
                Log.info("FAILOVER: " + motivo + " -> nova primaria: " + par(nova));
                definirPrimaria(nova);
            }
            sincronizadoComPrimaria = false;
            entrarNaReplicacao(par(nova));  // se ela ainda não se promoveu, tenta de novo no próximo ciclo
        }
    }

    private void definirPrimaria(int id) {
        primariaId = id;
        primariaDefinidaEm = System.currentTimeMillis();
    }

    /** Chamado por um Atendente quando não consegue falar com a primária (detecção rápida). */
    synchronized void suspeitarDaPrimaria(int id, String motivo) {
        if (primariaId != id || souPrimaria()) {
            return;
        }
        Par p = par(id);
        if (p != null) {
            p.viva = false;
            p.papel = "?";
            p.falhasSeguidas = FALHAS_PARA_DECLARAR_QUEDA;
        }
        eleger(motivo, true);
    }

    // ---------------------------------------------------------------- util

    static Map<String, String> campos(String resposta) {
        Map<String, String> m = new HashMap<>();
        for (String parte : resposta.split("\\|")) {
            int i = parte.indexOf('=');
            if (i > 0) {
                m.put(parte.substring(0, i), parte.substring(i + 1));
            }
        }
        return m;
    }

    private static boolean contemId(String lista, int id) {
        if (lista == null) {
            return false;
        }
        for (String s : lista.split(",")) {
            if (s.trim().equals(String.valueOf(id))) {
                return true;
            }
        }
        return false;
    }

    private static int inteiro(String s, int padrao) {
        try {
            return s == null ? padrao : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return padrao;
        }
    }

    static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
