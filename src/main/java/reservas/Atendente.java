package reservas;

import java.io.IOException;
import java.net.Socket;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Thread que atende UMA conexão TCP (um cliente, ou uma outra réplica).
 * Lê uma linha, processa, responde; repete até SAIR ou até a conexão cair.
 */
final class Atendente implements Runnable {

    private static final Pattern USUARIO_VALIDO = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    private final Servidor srv;
    private final Socket socket;

    private boolean ehCliente = false;          // conta em CLIENTES só depois do OLA/1a operação
    private String nomeCliente;
    private Conexao encaminhamento;             // conexão desta thread com a primária (se esta é backup)
    private int encaminhamentoPara = -1;

    Atendente(Servidor srv, Socket socket) {
        this.srv = srv;
        this.socket = socket;
    }

    @Override
    public void run() {
        String remoto = String.valueOf(socket.getRemoteSocketAddress());
        boolean pediuSair = false;
        try (Conexao c = new Conexao(socket)) {
            String linha;
            while ((linha = c.receber()) != null) {
                linha = linha.trim();
                if (linha.isEmpty()) {
                    continue;
                }
                String resposta;
                try {
                    resposta = processar(linha);
                } catch (RuntimeException e) {
                    resposta = "ERRO|FALHA_INTERNA|" + e.getClass().getSimpleName();
                    Log.info("erro processando '" + linha + "': " + e);
                }
                c.enviar(resposta);
                if ("BYE".equals(resposta)) {
                    pediuSair = true;
                    break;
                }
            }
            if (ehCliente) {
                Log.info("cliente " + nomeCliente + (pediuSair ? " enviou SAIR" : " fechou a conexao"));
            }
        } catch (IOException e) {
            if (ehCliente) {
                Log.info("cliente " + nomeCliente + " (" + remoto + ") caiu: " + e.getMessage());
            }
        } finally {
            fecharEncaminhamento();
            if (ehCliente) {
                int n = srv.clientesAtivos.decrementAndGet();
                Log.info("clientes conectados agora: " + n + " (o servidor continua atendendo os demais)");
            }
        }
    }

    // ------------------------------------------------------------------------

    private String processar(String linha) {
        String[] p = linha.split("\\|", -1);
        String cmd = p[0].trim().toUpperCase(Locale.ROOT);
        switch (cmd) {
            // ---------- comandos de cliente ----------
            case "OLA":
                registrarCliente(p.length > 1 ? p[1].trim() : null);
                return "OLA|REPLICA=" + srv.eu.id + "|PAPEL=" + srv.papel()
                        + "|PRIMARIA=" + srv.primariaId + "|ASSENTOS=" + srv.assentos.quantidade();
            case "LISTAR":
                registrarCliente(null);
                if (!srv.pronto) {
                    return "ERRO|REPLICA_INICIALIZANDO";
                }
                srv.requisicoes.incrementAndGet();
                String livres = srv.assentos.listarLivres();
                Log.info("LISTAR (" + nomeCliente + ") -> " + (livres.isEmpty() ? 0 : livres.split(",").length) + " livres");
                return "LISTA|" + livres;
            case "RESERVAR":
            case "CANCELAR":
                return escrita(cmd, p);
            case "SAIR":
                return "BYE";

            // ---------- consultas / administração ----------
            case "STATUS":                          // também por TCP, só para comparar com o UDP
                return srv.status();
            case "CONTAGEM":
                return srv.assentos.contagem() + "|PAPEL=" + srv.papel();
            case "RESET": {
                String rotulo = p.length > 1 ? p[1].trim() : "";
                if (srv.souPrimaria()) {
                    srv.assentos.resetar(rotulo, true);
                    return "OK|RESET";
                }
                return encaminhar("RESET|" + rotulo, () -> {
                    srv.assentos.resetar(rotulo, true);
                    return "OK|RESET";
                });
            }
            case "MODO":
                return modo(p);

            // ---------- mensagens internas entre réplicas ----------
            case "JOIN":
                return srv.tratarJoin(Integer.parseInt(p[1].trim()));
            case "REPL":
                return aplicarReplicacao(p);
            case "FWD":
                return executarEncaminhada(linha.substring(linha.indexOf('|') + 1));

            default:
                return "ERRO|COMANDO_DESCONHECIDO|" + cmd;
        }
    }

    private void registrarCliente(String nome) {
        if (nome != null && !nome.isEmpty()) {
            nomeCliente = nome;
        }
        if (!ehCliente) {
            ehCliente = true;
            if (nomeCliente == null) {
                nomeCliente = String.valueOf(socket.getRemoteSocketAddress());
            }
            int n = srv.clientesAtivos.incrementAndGet();
            Log.info("cliente " + nomeCliente + " conectado de " + socket.getRemoteSocketAddress()
                    + " -> clientes conectados: " + n);
        }
    }

    /** RESERVAR|<assento>|<usuario> e CANCELAR|<assento>|<usuario>. */
    private String escrita(String cmd, String[] p) {
        if (p.length != 3) {
            return "ERRO|FORMATO_INVALIDO|use " + cmd + "|<assento>|<usuario>";
        }
        int n;
        try {
            n = Integer.parseInt(p[1].trim());
        } catch (NumberFormatException e) {
            return "ERRO|ASSENTO_INVALIDO|" + p[1].trim();
        }
        String usuario = p[2].trim();
        if (n < 1 || n > srv.assentos.quantidade()) {
            return "ERRO|ASSENTO_INEXISTENTE|" + n;
        }
        if (!USUARIO_VALIDO.matcher(usuario).matches()) {
            return "ERRO|USUARIO_INVALIDO|use letras, numeros, . _ ou -";
        }
        registrarCliente(nomeCliente == null ? usuario : null);
        if (!srv.pronto) {
            return "ERRO|REPLICA_INICIALIZANDO";
        }
        srv.requisicoes.incrementAndGet();
        Supplier<String> local = () -> "RESERVAR".equals(cmd)
                ? srv.assentos.reservar(n, usuario)
                : srv.assentos.cancelar(n, usuario);
        if (srv.souPrimaria()) {
            return local.get();
        }
        return encaminhar(cmd + "|" + n + "|" + usuario, local);   // backup: quem escreve é a primária
    }

    /**
     * Backup -> primária. Se a primária não responder (nem depois de reconectar uma vez),
     * avisa o Servidor, que pode promover esta réplica; nesse caso executa localmente.
     */
    private String encaminhar(String linha, Supplier<String> seVirarPrimaria) {
        for (int tentativa = 1; tentativa <= 2; tentativa++) {
            if (srv.souPrimaria()) {
                return seVirarPrimaria.get();
            }
            Par prim = srv.par(srv.primariaId);
            if (prim == null) {
                break;
            }
            try {
                if (encaminhamento == null || encaminhamentoPara != prim.id) {
                    fecharEncaminhamento();
                    encaminhamento = Conexao.abrir(prim.tcp, 1000, 5000);
                    encaminhamentoPara = prim.id;
                }
                String r = encaminhamento.pedir("FWD|" + linha);
                if (r.startsWith("ERRO|NAO_SOU_PRIMARIA")) {
                    fecharEncaminhamento();
                    return "ERRO|SEM_PRIMARIA|failover em andamento, tente novamente";
                }
                Log.info(linha + " encaminhado a primaria (replica " + prim.id + ") -> " + r);
                return r;
            } catch (IOException e) {
                fecharEncaminhamento();
                Log.info("falha ao encaminhar para a " + prim + ": " + e.getMessage());
                if (tentativa == 2) {
                    srv.suspeitarDaPrimaria(prim.id, "encaminhamento para a primaria falhou");
                }
            }
        }
        if (srv.souPrimaria()) {
            return seVirarPrimaria.get();
        }
        return "ERRO|SEM_PRIMARIA|failover em andamento, tente novamente";
    }

    /** Na primária: escrita que chegou por outra réplica. */
    private String executarEncaminhada(String linha) {
        if (!srv.souPrimaria()) {
            return "ERRO|NAO_SOU_PRIMARIA|" + srv.primariaId;
        }
        String[] p = linha.split("\\|", -1);
        String cmd = p[0].toUpperCase(Locale.ROOT);
        if ("RESET".equals(cmd)) {
            srv.assentos.resetar(p.length > 1 ? p[1] : "", true);
            return "OK|RESET";
        }
        if (p.length != 3) {
            return "ERRO|FORMATO_INVALIDO";
        }
        int n = Integer.parseInt(p[1]);
        String usuario = p[2];
        Log.info("escrita recebida de outra replica: " + linha);
        return "RESERVAR".equals(cmd) ? srv.assentos.reservar(n, usuario) : srv.assentos.cancelar(n, usuario);
    }

    /** Na backup: aplica uma escrita enviada pela primária e confirma com ACK. */
    private String aplicarReplicacao(String[] p) {
        if (p.length >= 2 && "RESET".equals(p[1])) {
            srv.assentos.resetar(p.length > 2 ? p[2] : "", false);
            return "ACK|OK|RESET";
        }
        if (p.length != 4) {
            return "NACK|FORMATO_INVALIDO";
        }
        return "ACK|" + srv.assentos.aplicarReplicada(p[1], Integer.parseInt(p[2]), p[3]);
    }

    /** MODO|SYNC|ON ou MODO|SYNC|OFF: liga/desliga a proteção da região crítica nesta réplica. */
    private String modo(String[] p) {
        if (p.length != 3 || !"SYNC".equalsIgnoreCase(p[1].trim())) {
            return "ERRO|FORMATO_INVALIDO|use MODO|SYNC|ON ou MODO|SYNC|OFF";
        }
        boolean on = "ON".equalsIgnoreCase(p[2].trim());
        srv.assentos.setSincronizado(on);
        Log.info("########## MODO: sincronizacao " + (on
                ? "LIGADA (regiao critica protegida por synchronized)"
                : "DESLIGADA (regiao critica SEM protecao)") + " ##########");
        return "OK|MODO|SYNC=" + (on ? "ON" : "OFF");
    }

    private void fecharEncaminhamento() {
        if (encaminhamento != null) {
            encaminhamento.close();
            encaminhamento = null;
            encaminhamentoPara = -1;
        }
    }
}
