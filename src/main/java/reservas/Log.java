package reservas;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Log simples: hora + nome da thread + mensagem, no console e em arquivo.
 *
 * Observação: o próprio log usa um lock (synchronized) só para não misturar linhas.
 * Ele NÃO protege a região crítica dos assentos, porque a leitura e a escrita dos
 * assentos acontecem fora dele.
 */
public final class Log {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static PrintWriter arquivo;
    private static String prefixo = "";

    private Log() { }

    public static synchronized void configurar(String prefixoProcesso, String caminhoArquivo) {
        prefixo = prefixoProcesso == null ? "" : prefixoProcesso;
        if (caminhoArquivo == null) {
            return;
        }
        try {
            File f = new File(caminhoArquivo);
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            arquivo = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            System.err.println("Nao foi possivel abrir o arquivo de log " + caminhoArquivo + ": " + e.getMessage());
        }
    }

    public static void info(String msg) {
        String linha = LocalTime.now().format(HORA) + " " + prefixo + "[" + Thread.currentThread().getName() + "] " + msg;
        synchronized (Log.class) {
            System.out.println(linha);
            if (arquivo != null) {
                arquivo.println(linha);
            }
        }
    }
}
