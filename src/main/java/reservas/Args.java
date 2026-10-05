package reservas;

import java.util.HashMap;
import java.util.Map;

/** Lê argumentos no formato --chave valor (ou --chave sozinho = "true"). */
final class Args {

    private Args() { }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                continue;
            }
            String chave = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(chave, args[++i]);
            } else {
                m.put(chave, "true");
            }
        }
        return m;
    }

    static int inteiro(Map<String, String> m, String chave, int padrao) {
        String v = m.get(chave);
        return v == null ? padrao : Integer.parseInt(v.trim());
    }
}
