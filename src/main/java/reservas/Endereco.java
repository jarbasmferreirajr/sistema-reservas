package reservas;

import java.util.ArrayList;
import java.util.List;

/** Um par host:porta. */
public final class Endereco {

    public final String host;
    public final int porta;

    public Endereco(String host, int porta) {
        this.host = host;
        this.porta = porta;
    }

    public static Endereco parse(String texto) {
        String t = texto.trim();
        int i = t.lastIndexOf(':');
        if (i <= 0) {
            throw new IllegalArgumentException("endereco invalido (use host:porta): " + texto);
        }
        return new Endereco(t.substring(0, i), Integer.parseInt(t.substring(i + 1)));
    }

    /** "127.0.0.1:5001,127.0.0.1:5002" -> lista de endereços. */
    public static List<Endereco> parseLista(String lista) {
        List<Endereco> r = new ArrayList<>();
        for (String s : lista.split(",")) {
            if (!s.isBlank()) {
                r.add(parse(s));
            }
        }
        return r;
    }

    @Override
    public String toString() {
        return host + ":" + porta;
    }
}
