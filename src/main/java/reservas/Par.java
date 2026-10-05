package reservas;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Uma réplica do servidor, vista a partir de outra réplica.
 * Guarda o endereço, o que o detector de falhas sabe sobre ela e, quando
 * esta réplica é a primária, a conexão usada para enviar as escritas replicadas.
 */
public final class Par {

    public final int id;
    public final Endereco tcp;
    public final Endereco udp;

    // ---- visão do detector de falhas (heartbeat = STATUS via UDP) ----
    volatile boolean viva = false;
    volatile int falhasSeguidas = 0;
    volatile String papel = "?";
    volatile int primariaInformada = -1;
    volatile String replicasInformadas = "";
    volatile long statusPedidoEm = 0;     // quando foi enviado o STATUS cuja resposta está acima

    // ---- replicação (só usado quando ESTA réplica é a primária) ----
    volatile boolean naReplicacao = false; // true depois que o par fez JOIN e recebeu o estado
    private Conexao linkReplicacao;         // conexão TCP persistente primária -> backup

    Par(int id, String host, int portaTcp, int portaUdp) {
        this.id = id;
        this.tcp = new Endereco(host, portaTcp);
        this.udp = new Endereco(host, portaUdp);
    }

    /**
     * Envia uma escrita à backup e espera o ACK (replicação SÍNCRONA).
     * O synchronized garante uma mensagem por vez na conexão, na ordem de chegada.
     */
    synchronized String replicar(String linha) throws IOException {
        if (linkReplicacao == null) {
            linkReplicacao = Conexao.abrir(tcp, 1000, 2000);
        }
        try {
            return linkReplicacao.pedir(linha);
        } catch (IOException e) {
            fecharLink();
            throw e;
        }
    }

    synchronized void fecharLink() {
        if (linkReplicacao != null) {
            linkReplicacao.close();
            linkReplicacao = null;
        }
    }

    /** "127.0.0.1:5001:6001,127.0.0.1:5002:6002" -> réplicas 1, 2, ... (id = posição na lista). */
    static List<Par> parseConfig(String config) {
        List<Par> lista = new ArrayList<>();
        int id = 1;
        for (String item : config.split(",")) {
            if (item.isBlank()) {
                continue;
            }
            String[] p = item.trim().split(":");
            if (p.length != 3) {
                throw new IllegalArgumentException("replica invalida '" + item + "' (use host:portaTcp:portaUdp)");
            }
            lista.add(new Par(id++, p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2])));
        }
        return lista;
    }

    @Override
    public String toString() {
        return "replica " + id + " (" + tcp + ")";
    }
}
