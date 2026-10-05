package reservas;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * RECURSO COMPARTILHADO do sistema: o mapa de assentos e o contador de reservas.
 *
 * Existe UMA instância por réplica, acessada ao mesmo tempo por todas as threads
 * atendentes (uma thread por cliente conectado).
 *
 * A REGIÃO CRÍTICA são os métodos reservarRC() e cancelarRC(): eles LEEM o estado
 * (assento + contador), decidem e depois ESCREVEM com base no que leram
 * ("ler-decidir-escrever"). Se duas threads fizerem isso ao mesmo tempo, uma pode
 * sobrescrever o trabalho da outra.
 *
 * O mesmo código roda nos dois experimentos; a única diferença é se a chamada passa
 * ou não por synchronized (this) — ver reservar() e cancelar().
 */
public final class Assentos {

    private final String[] dono;           // dono[i] = usuário que reservou o assento i+1 (null = livre)
    private int reservasRegistradas = 0;   // contador de reservas ativas (o "registro" do sistema)

    private volatile boolean sincronizado; // true = região crítica protegida (experimento 2)
    private final int janelaMs;            // pausa entre LER e ESCREVER: amplia a janela da corrida
    private volatile Consumer<String> aposEscrita = op -> { };   // replicação (definida pelo Servidor)

    public Assentos(int quantidade, boolean sincronizado, int janelaMs) {
        this.dono = new String[quantidade];
        this.sincronizado = sincronizado;
        this.janelaMs = janelaMs;
    }

    public int quantidade() {
        return dono.length;
    }

    public boolean isSincronizado() {
        return sincronizado;
    }

    public void setSincronizado(boolean s) {
        sincronizado = s;
    }

    public int getJanelaMs() {
        return janelaMs;
    }

    void setAposEscrita(Consumer<String> c) {
        aposEscrita = c;
    }

    // =====================================================================
    //  Escritas feitas por clientes (executadas na réplica PRIMÁRIA)
    // =====================================================================

    public String reservar(int n, String usuario) {
        if (sincronizado) {
            synchronized (this) {                 // COM sincronização: uma thread por vez na RC
                return reservarRC(n, usuario, true);
            }
        }
        return reservarRC(n, usuario, true);      // SEM sincronização: várias threads ao mesmo tempo
    }

    public String cancelar(int n, String usuario) {
        if (sincronizado) {
            synchronized (this) {
                return cancelarRC(n, usuario, true);
            }
        }
        return cancelarRC(n, usuario, true);
    }

    /**
     * Escrita recebida da primária (acontece só na BACKUP). Chega por uma única
     * conexão, em ordem, e é aplicada uma por vez.
     */
    public synchronized String aplicarReplicada(String op, int n, String usuario) {
        return "RESERVAR".equals(op) ? reservarRC(n, usuario, false) : cancelarRC(n, usuario, false);
    }

    // =====================================================================
    //  REGIÃO CRÍTICA
    // =====================================================================

    private String reservarRC(int n, String usuario, boolean local) {
        // -------------------- INICIO DA REGIAO CRITICA --------------------
        String lido = dono[n - 1];                   // (1) LE o assento
        int antes = reservasRegistradas;             // (1) LE o contador
        if (local) {
            Log.info("RC RESERVAR assento=" + n + " user=" + usuario
                    + " | le: assento=" + (lido == null ? "livre" : lido) + " contador=" + antes);
            pausar();                                // outra thread pode entrar aqui se nao houver trava
        }
        if (lido != null) {                          // (2) DECIDE com base no que leu
            String r = lido.equals(usuario)
                    ? "OK|JA_ERA_SEU|" + n            // reenvio apos failover: idempotente, nao duplica
                    : "ERRO|ASSENTO_OCUPADO|" + n;
            if (local) {
                Log.info("RC RESERVAR assento=" + n + " user=" + usuario + " | " + r);
            }
            return r;
        }
        if (local) {
            detectarInterferencia(n, lido, antes, antes + 1);
        }
        dono[n - 1] = usuario;                       // (3) ESCREVE o assento
        reservasRegistradas = antes + 1;             // (3) ESCREVE o contador (valor lido + 1)
        if (local) {
            Log.info("RC RESERVAR assento=" + n + " user=" + usuario
                    + " | grava: contador " + antes + " -> " + (antes + 1) + " | OK");
            aposEscrita.accept("RESERVAR|" + n + "|" + usuario);   // replica para a(s) backup(s)
        } else {
            Log.info("REPL aplicada: RESERVAR assento=" + n + " user=" + usuario + " contador=" + reservasRegistradas);
        }
        return "OK|RESERVADO|" + n;
        // --------------------- FIM DA REGIAO CRITICA ----------------------
    }

    private String cancelarRC(int n, String usuario, boolean local) {
        // -------------------- INICIO DA REGIAO CRITICA --------------------
        String lido = dono[n - 1];
        int antes = reservasRegistradas;
        if (local) {
            Log.info("RC CANCELAR assento=" + n + " user=" + usuario
                    + " | le: assento=" + (lido == null ? "livre" : lido) + " contador=" + antes);
            pausar();
        }
        String erro = null;
        if (lido == null) {
            erro = "ERRO|ASSENTO_NAO_RESERVADO|" + n;
        } else if (!lido.equals(usuario)) {
            erro = "ERRO|RESERVADO_POR_OUTRO_USUARIO|" + n;
        }
        if (erro != null) {
            if (local) {
                Log.info("RC CANCELAR assento=" + n + " user=" + usuario + " | " + erro);
            }
            return erro;
        }
        if (local) {
            detectarInterferencia(n, lido, antes, antes - 1);
        }
        dono[n - 1] = null;
        reservasRegistradas = antes - 1;
        if (local) {
            Log.info("RC CANCELAR assento=" + n + " user=" + usuario
                    + " | grava: contador " + antes + " -> " + (antes - 1) + " | OK");
            aposEscrita.accept("CANCELAR|" + n + "|" + usuario);
        } else {
            Log.info("REPL aplicada: CANCELAR assento=" + n + " user=" + usuario + " contador=" + reservasRegistradas);
        }
        return "OK|CANCELADO|" + n;
        // --------------------- FIM DA REGIAO CRITICA ----------------------
    }

    /**
     * Diagnóstico didático (não faz parte da lógica): confere, logo antes de gravar,
     * se outra thread alterou o estado durante a janela. Só dispara sem sincronização.
     * O número oficial de inconsistências vem do comando CONTAGEM.
     */
    private void detectarInterferencia(int n, String assentoLido, int contadorLido, int vaiGravar) {
        String assentoAgora = dono[n - 1];
        int contadorAgora = reservasRegistradas;
        if (contadorAgora != contadorLido) {
            Log.info("!! CORRIDA: contador mudou de " + contadorLido + " para " + contadorAgora
                    + " durante a janela; esta thread vai gravar " + vaiGravar + " por cima (atualizacao perdida)");
        }
        if (!Objects.equals(assentoAgora, assentoLido)) {
            Log.info("!! CORRIDA: assento " + n + " mudou de " + (assentoLido == null ? "livre" : assentoLido)
                    + " para " + (assentoAgora == null ? "livre" : assentoAgora) + " durante a janela (reserva duplicada/sobrescrita)");
        }
    }

    private void pausar() {
        int ms = janelaMs;
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================
    //  Leituras e operações administrativas
    // =====================================================================

    /** Lista de assentos livres, ex.: "1,4,7". */
    public String listarLivres() {
        if (sincronizado) {
            synchronized (this) {
                return livres();
            }
        }
        return livres();
    }

    private String livres() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dono.length; i++) {
            if (dono[i] == null) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(i + 1);
            }
        }
        return sb.toString();
    }

    /** Contagem aproximada (sem trava) usada só no STATUS. */
    public int contarLivres() {
        int c = 0;
        for (String d : dono) {
            if (d == null) {
                c++;
            }
        }
        return c;
    }

    /** Compara o contador com o que realmente está no vetor de assentos. */
    public synchronized String contagem() {
        int ocupados = dono.length - contarLivres();
        return "CONTAGEM|REGISTRADAS=" + reservasRegistradas + "|OCUPADOS=" + ocupados
                + "|LIVRES=" + (dono.length - ocupados)
                + "|CONSISTENTE=" + (ocupados == reservasRegistradas ? "SIM" : "NAO");
    }

    /** Zera todos os assentos. Na primária, a operação também é replicada. */
    public synchronized void resetar(String rotulo, boolean local) {
        Arrays.fill(dono, null);
        reservasRegistradas = 0;
        Log.info("==================== RESET " + rotulo + " (todos os assentos livres) ====================");
        if (local) {
            aposEscrita.accept("RESET|" + rotulo);
        }
    }

    /** Estado completo, ex.: "3:ana;7:bruno" ("-" se vazio). Enviado a uma backup que entra. */
    public synchronized String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dono.length; i++) {
            if (dono[i] != null) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(i + 1).append(':').append(dono[i]);
            }
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** Substitui o estado local pelo recebido da primária. */
    public synchronized void carregar(String snapshot) {
        Arrays.fill(dono, null);
        reservasRegistradas = 0;
        if (snapshot == null || snapshot.isEmpty() || "-".equals(snapshot)) {
            return;
        }
        for (String item : snapshot.split(";")) {
            String[] kv = item.split(":", 2);
            int n = Integer.parseInt(kv[0]);
            if (n >= 1 && n <= dono.length) {
                dono[n - 1] = kv[1];
                reservasRegistradas++;
            }
        }
    }
}
