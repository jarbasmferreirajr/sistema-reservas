package reservas;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cliente interativo (terminal).
 *
 * Uso: java -cp out reservas.Cliente --usuario ana [--inicio 2]
 *   --inicio N : conecta primeiro na réplica N (1 ou 2).
 *
 * Comandos: listar | reservar <n> | cancelar <n> | sair
 */
public final class Cliente {

    private static final String SERVIDORES = "127.0.0.1:5001,127.0.0.1:5002";
    private static final int ESPERA_MAX_S = 20;   // na partida, espera a réplica subir

    public static void main(String[] args) throws IOException {
        Map<String, String> a = Args.parse(args);
        List<Endereco> servidores = Endereco.parseLista(SERVIDORES);
        BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));

        String usuario = a.get("usuario");
        while (usuario == null || !usuario.matches("[A-Za-z0-9_.-]{1,32}")) {
            System.out.print("Nome de usuario (letras/numeros, sem espacos): ");
            usuario = teclado.readLine();
            if (usuario == null) {
                return;
            }
            usuario = usuario.trim();
        }
        int inicio = Args.inteiro(a, "inicio", 1) - 1;

        // Enquanto espera as réplicas subirem (tudo iniciado junto no VS Code), não polui a tela.
        boolean[] aguardando = {true};
        ClienteReservas cli = new ClienteReservas(servidores, usuario, inicio, m -> {
            if (!aguardando[0] || m.startsWith("[conectado]")) {
                System.out.println("   " + m);
            }
        });
        System.out.println("Cliente de reservas | usuario: " + usuario);
        System.out.println("Comandos: listar | reservar <n> | cancelar <n> | sair");
        boolean conectado = cli.conectarNaPreferida();
        if (!conectado) {
            System.out.println("   aguardando a replica " + (inicio + 1) + " ficar disponivel...");
        }
        for (int s = 1; !conectado && s <= ESPERA_MAX_S; s++) {
            Servidor.dormir(1000);
            // primeiros 10 s: insiste na réplica escolhida; depois aceita qualquer uma
            conectado = s <= 10 ? cli.conectarNaPreferida() : cli.conectar();
        }
        aguardando[0] = false;
        if (!conectado) {
            System.out.println("   nenhuma replica respondeu; os comandos vao tentar de novo");
        }
        System.out.println("   pronto: digite um comando e aperte Enter (ex.: listar)");

        while (true) {
            System.out.print(usuario + "> ");
            System.out.flush();
            String linha = teclado.readLine();
            if (linha == null) {
                break;
            }
            String[] t = linha.trim().split("\\s+");
            String pedido;
            switch (t[0].toLowerCase(Locale.ROOT)) {
                case "":
                    continue;
                case "listar":
                    pedido = "LISTAR";
                    break;
                case "reservar":
                case "cancelar":
                    if (t.length < 2) {
                        System.out.println("   uso: " + t[0] + " <numero do assento>");
                        continue;
                    }
                    pedido = t[0].toUpperCase(Locale.ROOT) + "|" + t[1] + "|" + usuario;
                    break;
                case "sair":
                    pedido = "SAIR";
                    break;
                default:
                    System.out.println("   comandos: listar | reservar <n> | cancelar <n> | sair");
                    continue;
            }
            System.out.println("-> " + pedido);                  // mostra a mensagem do protocolo enviada
            String resp = cli.pedir(pedido);
            String extra = resp.startsWith("LISTA|") ? "  (" + contarItens(resp.substring(6)) + " livres)" : "";
            System.out.println("<- " + resp + extra + "   [" + cli.servidorAtual() + "]");
            if ("SAIR".equals(pedido)) {
                break;
            }
        }
        cli.close();
    }

    private static int contarItens(String lista) {
        return lista.isEmpty() ? 0 : lista.split(",").length;
    }
}
