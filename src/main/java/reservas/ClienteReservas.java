package reservas;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lado cliente do protocolo, com FAILOVER: conhece a lista de réplicas; se a conexão
 * cair no meio de um pedido, conecta na próxima réplica ativa e reenvia o mesmo pedido.
 *
 * O reenvio não duplica reservas: se a reserva original chegou a ser gravada e
 * replicada, a réplica nova responde OK|JA_ERA_SEU (RESERVAR é idempotente por usuário).
 */
public final class ClienteReservas implements Closeable {

    private final List<Endereco> servidores;
    private final String usuario;
    private final Consumer<String> avisos;
    private int atual;
    private Conexao conexao;
    private String descricao = "desconectado";

    public ClienteReservas(List<Endereco> servidores, String usuario, int inicio, Consumer<String> avisos) {
        if (servidores.isEmpty()) {
            throw new IllegalArgumentException("lista de servidores vazia");
        }
        this.servidores = servidores;
        this.usuario = usuario;
        this.avisos = avisos;
        this.atual = Math.floorMod(inicio, servidores.size());
    }

    /** Tenta conectar começando pela réplica atual e seguindo a lista. */
    public boolean conectar() {
        for (int i = 0; i < servidores.size(); i++) {
            if (tentar((atual + i) % servidores.size())) {
                return true;
            }
        }
        return false;
    }

    /** Tenta só a réplica preferida (usada na partida, enquanto as réplicas ainda estão subindo). */
    public boolean conectarNaPreferida() {
        return tentar(atual);
    }

    private boolean tentar(int idx) {
        Endereco e = servidores.get(idx);
        try {
            Conexao c = Conexao.abrir(e, 1000, 15000);
            String ola = c.pedir("OLA|" + usuario);
            conexao = c;
            atual = idx;
            Map<String, String> info = Servidor.campos(ola);
            descricao = e + " replica " + info.getOrDefault("REPLICA", "?");
            avisos.accept("[conectado] " + descricao);
            return true;
        } catch (IOException ex) {
            avisos.accept("[indisponivel] " + e + ": " + ex.getMessage());
            return false;
        }
    }

    /** Envia um comando e devolve a resposta, trocando de réplica se preciso. */
    public String pedir(String linha) {
        int maxTentativas = 8 * servidores.size();
        for (int tentativa = 1; tentativa <= maxTentativas; tentativa++) {
            if (conexao == null && !conectar()) {
                Servidor.dormir(1000);
                continue;
            }
            try {
                String r = conexao.pedir(linha);
                if (r.startsWith("ERRO|SEM_PRIMARIA") || r.startsWith("ERRO|REPLICA_INICIALIZANDO")) {
                    avisos.accept("[aguardando] " + r + " -> repetindo em 1 s");
                    Servidor.dormir(1000);
                    continue;
                }
                return r;
            } catch (IOException e) {
                avisos.accept("[failover] conexao com " + descricao + " perdida (" + e.getMessage()
                        + "); reenviando '" + linha + "' para outra replica");
                fecharConexao();
                atual = (atual + 1) % servidores.size();
            }
        }
        return "ERRO|NENHUMA_REPLICA_DISPONIVEL";
    }

    public String servidorAtual() {
        return descricao;
    }

    private void fecharConexao() {
        if (conexao != null) {
            conexao.close();
            conexao = null;
        }
        descricao = "desconectado";
    }

    @Override
    public void close() {
        fecharConexao();
    }
}
