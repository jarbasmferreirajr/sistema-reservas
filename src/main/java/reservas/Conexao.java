package reservas;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Conexão TCP orientada a linhas: cada mensagem do protocolo é uma linha de texto
 * terminada em '\n'. Usada pelos clientes, pelo servidor e entre as réplicas.
 */
public final class Conexao implements Closeable {

    private final Socket socket;
    private final BufferedReader entrada;
    private final BufferedWriter saida;

    public Conexao(Socket socket) throws IOException {
        this.socket = socket;
        // Sem o algoritmo de Nagle: mensagens curtas saem na hora (importante no Windows,
        // onde Nagle + ACK atrasado pode somar ~200 ms por requisição).
        socket.setTcpNoDelay(true);
        this.entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.saida = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    /** Abre uma conexão com timeout de conexão e de leitura. */
    public static Conexao abrir(Endereco e, int timeoutConexaoMs, int timeoutLeituraMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(e.host, e.porta), timeoutConexaoMs);
            s.setSoTimeout(timeoutLeituraMs);
            return new Conexao(s);
        } catch (IOException ex) {
            try {
                s.close();
            } catch (IOException ignorada) {
                // nada a fazer
            }
            throw ex;
        }
    }

    public void enviar(String linha) throws IOException {
        saida.write(linha);
        saida.write('\n');
        saida.flush();
    }

    /** Retorna a próxima linha ou null se o outro lado fechou a conexão. */
    public String receber() throws IOException {
        return entrada.readLine();
    }

    /** Envia um comando e espera a resposta (requisição-resposta). */
    public String pedir(String linha) throws IOException {
        enviar(linha);
        String r = receber();
        if (r == null) {
            throw new EOFException("conexao encerrada pelo outro lado");
        }
        return r;
    }

    public String remoto() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignorada) {
            // nada a fazer
        }
    }
}
