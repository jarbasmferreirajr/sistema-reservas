package reservas;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * TESTE 5 do enunciado: consulta STATUS via UDP e comparação TCP x UDP.
 *
 * Uso: java -cp out reservas.ConsultaStatus
 *
 *  1) Envia um datagrama STATUS para cada réplica e mostra a resposta.
 *  2) Na primeira réplica que respondeu, faz 300 consultas STATUS iguais por UDP, por TCP
 *     abrindo conexão a cada vez e por TCP com a conexão já aberta, e compara os tempos.
 *  3) Tenta falar com uma porta sem servidor: TCP avisa na hora, UDP só por timeout.
 */
public final class ConsultaStatus {

    private static final Endereco[] UDP = {new Endereco("127.0.0.1", 6001), new Endereco("127.0.0.1", 6002)};
    private static final Endereco[] TCP = {new Endereco("127.0.0.1", 5001), new Endereco("127.0.0.1", 5002)};
    private static final int CONSULTAS = 300;

    public static void main(String[] args) {
        System.out.println("=== 1) STATUS via UDP (um datagrama de pergunta, um de resposta) ===");
        int noAr = -1;
        for (int i = 0; i < UDP.length; i++) {
            Endereco e = UDP[i];
            long t0 = System.nanoTime();
            String r = consultarUdp(e, 1000);
            double ms = (System.nanoTime() - t0) / 1e6;
            if (r != null) {
                System.out.printf(Locale.ROOT, "UDP %-16s -> %s  (%.2f ms)%n", e, r, ms);
                if (noAr < 0) {
                    noAr = i;
                }
            } else {
                System.out.printf(Locale.ROOT, "UDP %-16s -> SEM RESPOSTA em 1000 ms (replica fora do ar?)%n", e);
            }
        }
        System.out.println();
        if (noAr < 0) {
            System.out.println("Nenhuma replica respondeu: suba as replicas antes (passo 1 do roteiro).");
            return;
        }
        comparar(TCP[noAr], UDP[noAr], CONSULTAS);
    }

    /** Envia um datagrama STATUS e espera um datagrama de resposta. null = sem resposta. */
    static String consultarUdp(Endereco e, int timeoutMs) {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setSoTimeout(timeoutMs);
            byte[] dados = "STATUS".getBytes(StandardCharsets.UTF_8);
            ds.send(new DatagramPacket(dados, dados.length, new InetSocketAddress(e.host, e.porta)));
            byte[] buf = new byte[1024];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            ds.receive(p);
            return new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------ comparação

    private static void comparar(Endereco tcp, Endereco udp, int n) {
        System.out.println("=== 2) Comparacao TCP x UDP: " + n + " consultas STATUS em " + tcp + " / " + udp + " (mesma resposta nos dois) ===");

        medirUdp(udp, 20);                       // aquecimento (JIT, caches)
        medirTcpNova(tcp, 20);
        medirTcpPersistente(tcp, 20);

        double[] u = medirUdp(udp, n);
        double[] tn = medirTcpNova(tcp, n);
        double[] tp = medirTcpPersistente(tcp, n);

        System.out.printf(Locale.ROOT, "%-36s | %-11s | %-9s | %-9s | %-9s%n", "metodo", "respondidas", "media ms", "mediana", "max ms");
        linha("UDP (1 datagrama ida + 1 volta)", u, n);
        linha("TCP abrindo conexao a cada consulta", tn, n);
        linha("TCP com conexao ja aberta", tp, n);
        System.out.println();

        System.out.println("=== 3) Servidor fora do ar (porta sem ninguem escutando) ===");
        Endereco fechadaTcp = new Endereco(tcp.host, 5999);
        Endereco fechadaUdp = new Endereco(udp.host, 6999);
        long t0 = System.nanoTime();
        try (Conexao c = Conexao.abrir(fechadaTcp, 1000, 1000)) {
            System.out.println("TCP " + fechadaTcp + ": conectou em " + c.remoto() + " (a porta 5999 esta em uso por outro programa)");
        } catch (IOException ex) {
            System.out.printf(Locale.ROOT, "TCP %s: erro imediato em %.2f ms -> %s%n", fechadaTcp, (System.nanoTime() - t0) / 1e6, ex.getMessage());
        }
        t0 = System.nanoTime();
        String r = consultarUdp(fechadaUdp, 1000);
        System.out.printf(Locale.ROOT, "UDP %s: %s apos %.2f ms (o envio 'funciona'; o cliente so percebe pelo timeout)%n",
                fechadaUdp, r == null ? "nenhuma resposta" : r, (System.nanoTime() - t0) / 1e6);
    }

    private static double[] medirUdp(Endereco udp, int n) {
        double[] t = new double[n];
        Arrays.fill(t, -1);
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setSoTimeout(1000);
            byte[] buf = new byte[1024];
            for (int i = 0; i < n; i++) {
                byte[] dados = ("STATUS|" + i + "|BENCH").getBytes(StandardCharsets.UTF_8);
                long t0 = System.nanoTime();
                try {
                    ds.send(new DatagramPacket(dados, dados.length, new InetSocketAddress(udp.host, udp.porta)));
                    while (true) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        String txt = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (txt.endsWith("|SEQ=" + i)) {
                            t[i] = (System.nanoTime() - t0) / 1e6;
                            break;
                        }
                    }
                } catch (IOException perdida) {
                    // datagrama perdido ou timeout: fica -1
                }
            }
        } catch (IOException e) {
            System.out.println("erro UDP: " + e.getMessage());
        }
        return t;
    }

    private static double[] medirTcpNova(Endereco tcp, int n) {
        double[] t = new double[n];
        Arrays.fill(t, -1);
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            try (Conexao c = Conexao.abrir(tcp, 1000, 1000)) {   // handshake de 3 vias a cada consulta
                c.pedir("STATUS");
                t[i] = (System.nanoTime() - t0) / 1e6;
            } catch (IOException e) {
                // falhou: fica -1
            }
        }
        return t;
    }

    private static double[] medirTcpPersistente(Endereco tcp, int n) {
        double[] t = new double[n];
        Arrays.fill(t, -1);
        try (Conexao c = Conexao.abrir(tcp, 1000, 1000)) {
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                c.pedir("STATUS");
                t[i] = (System.nanoTime() - t0) / 1e6;
            }
        } catch (IOException e) {
            System.out.println("erro TCP: " + e.getMessage());
        }
        return t;
    }

    private static void linha(String nome, double[] t, int n) {
        double[] ok = Arrays.stream(t).filter(x -> x >= 0).sorted().toArray();
        if (ok.length == 0) {
            System.out.printf(Locale.ROOT, "%-36s | %-11s | %-9s | %-9s | %-9s%n", nome, "0/" + n, "-", "-", "-");
            return;
        }
        double media = Arrays.stream(ok).average().orElse(0);
        System.out.printf(Locale.ROOT, "%-36s | %-11s | %-9.3f | %-9.3f | %-9.3f%n",
                nome, ok.length + "/" + n, media, ok[ok.length / 2], ok[ok.length - 1]);
    }
}
