package reservas;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EXPERIMENTO DE CONCORRÊNCIA (TESTES 2, 3 e 4 do enunciado):
 * 3 clientes x 10 reservas disparadas ao mesmo tempo = 30 requisições concorrentes.
 *
 * Uso: java -cp out reservas.TesteCarga --sync off   (experimento 1: sem sincronização)
 *      java -cp out reservas.TesteCarga --sync on    (experimento 2: com sincronização)
 *
 * Cada cliente reserva 10 assentos só dele (cli1: 1-10, cli2: 11-20, cli3: 21-30).
 * Resultado correto: 30 reservas registradas no contador. Sem sincronização, o contador
 * da primária fica abaixo de 30 (atualizações perdidas).
 */
public final class TesteCarga {

    private static final int CLIENTES = 3;
    private static final int REQUISICOES = 10;
    private static final int PAUSA_MAX_MS = 30;   // cada cliente espera 0..30 ms (aleatório) entre um pedido e outro
    private static final String SERVIDORES = "127.0.0.1:5001,127.0.0.1:5002";

    public static void main(String[] args) throws Exception {
        Map<String, String> a = Args.parse(args);
        String sync = a.getOrDefault("sync", "").toLowerCase(Locale.ROOT);
        if (!"on".equals(sync) && !"off".equals(sync)) {
            System.out.println("Uso: TesteCarga --sync off   ou   TesteCarga --sync on");
            System.exit(1);
        }
        boolean comSync = "on".equals(sync);
        List<Endereco> servidores = Endereco.parseLista(SERVIDORES);
        int esperado = CLIENTES * REQUISICOES;

        String carimbo = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String arquivoLog = "logs/teste-" + (comSync ? "com" : "sem") + "-sync-" + carimbo + ".log";
        Log.configurar("", arquivoLog);
        Log.info("EXPERIMENTO " + (comSync ? "COM" : "SEM") + " SINCRONIZACAO: " + CLIENTES + " clientes x "
                + REQUISICOES + " reservas = " + esperado + " requisicoes concorrentes");

        if (!algumaReplicaNoAr(servidores)) {
            Log.info("Nenhuma replica respondeu em " + servidores + ". Suba as replicas antes (passo 1 do roteiro).");
            System.exit(1);
        }

        // 1) liga ou desliga a sincronização em todas as réplicas
        for (Endereco e : servidores) {
            try (Conexao c = Conexao.abrir(e, 1000, 5000)) {
                Log.info("replica " + e + " -> " + c.pedir("MODO|SYNC|" + (comSync ? "ON" : "OFF")));
            } catch (IOException ex) {
                Log.info("replica " + e + " -> fora do ar");
            }
        }

        // 2) libera todos os assentos (o RESET vai para a primária e é replicado)
        try (ClienteReservas admin = new ClienteReservas(servidores, "admin", 0, m -> { })) {
            Log.info("RESET -> " + admin.pedir("RESET|" + (comSync ? "com" : "sem") + "-sync"));
        }

        // 3) conecta os 3 clientes e dispara todos juntos (largada)
        CountDownLatch prontos = new CountDownLatch(CLIENTES);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger oks = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= CLIENTES; i++) {
            final int ci = i;
            Thread t = new Thread(() -> {
                String usuario = "cli" + ci;
                try (ClienteReservas cli = new ClienteReservas(servidores, usuario, 0, Log::info)) {
                    cli.conectar();
                    prontos.countDown();
                    largada.await();
                    Random rnd = new Random();
                    for (int j = 1; j <= REQUISICOES; j++) {
                        int assento = (ci - 1) * REQUISICOES + j;
                        String resp = cli.pedir("RESERVAR|" + assento + "|" + usuario);
                        if (resp.startsWith("OK")) {
                            oks.incrementAndGet();
                        }
                        Log.info("RESERVAR|" + assento + "|" + usuario + " -> " + resp);
                        Thread.sleep(rnd.nextInt(PAUSA_MAX_MS + 1));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "cli" + ci);
            threads.add(t);
            t.start();
        }
        prontos.await();
        Log.info(">>> " + CLIENTES + " clientes conectados. LARGADA: " + esperado + " requisicoes concorrentes");
        long t0 = System.nanoTime();
        largada.countDown();
        for (Thread t : threads) {
            t.join();
        }
        long tempoMs = (System.nanoTime() - t0) / 1_000_000;

        // 4) confere cada réplica: contador de reservas x assentos realmente ocupados
        Log.info("==================== RESULTADO ====================");
        Log.info("respostas OK recebidas pelos clientes: " + oks.get() + " de " + esperado);
        boolean consistente = oks.get() == esperado;
        int registradasPrimaria = -1;
        for (Endereco e : servidores) {
            try (Conexao c = Conexao.abrir(e, 1000, 5000)) {
                Map<String, String> ct = Servidor.campos(c.pedir("CONTAGEM"));
                int reg = Integer.parseInt(ct.get("REGISTRADAS"));
                int ocu = Integer.parseInt(ct.get("OCUPADOS"));
                String papel = ct.getOrDefault("PAPEL", "?");
                if ("PRIMARIA".equals(papel)) {
                    registradasPrimaria = reg;
                }
                boolean ok = reg == esperado && ocu == esperado;
                consistente &= ok;
                Log.info(String.format(Locale.ROOT, "replica %s (%s): contador REGISTRADAS=%d  assentos OCUPADOS=%d  %s",
                        e, papel, reg, ocu, ok ? "ok" : "<-- INCONSISTENTE"));
            } catch (IOException | RuntimeException ex) {
                Log.info("replica " + e + ": fora do ar");
            }
        }
        if (registradasPrimaria >= 0 && registradasPrimaria < esperado) {
            Log.info("atualizacoes perdidas no contador da primaria: " + (esperado - registradasPrimaria)
                    + " (" + registradasPrimaria + " de " + esperado + ")");
        }
        Log.info("tempo: " + tempoMs + " ms");
        Log.info("RESULTADO: " + (consistente
                ? "CONSISTENTE (" + esperado + "/" + esperado + ")"
                : "INCONSISTENTE (condicao de corrida)"));
        Log.info("log deste teste: " + arquivoLog);
    }

    private static boolean algumaReplicaNoAr(List<Endereco> servidores) {
        for (Endereco e : servidores) {
            try {
                Conexao.abrir(e, 1000, 2000).close();
                return true;
            } catch (IOException ex) {
                // tenta a próxima
            }
        }
        return false;
    }
}
