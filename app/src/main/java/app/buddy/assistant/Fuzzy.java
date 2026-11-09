package app.buddy.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fuzzy matching of a spoken name against contacts and app names: Russian case endings ("маме" is "Мама"), small
 * recognition slips (Levenshtein) and Latin / Cyrillic spellings of the same name ("Alex" is "Алекс"). Plain Java,
 * unit-tested (FuzzyTest).
 */
final class Fuzzy {
    /** A score at least this high is a match. */
    static final double MATCH = 0.78;
    /** Matches this close to the best are a tie: the user picks. */
    static final double TIE = 0.06;

    private Fuzzy() {
    }

    static String clean(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static final String[] ENDINGS = {"ами", "ями", "ому", "ему", "ой", "ей", "ом", "ем", "ою", "ею", "ам", "ям",
            "ах", "ях", "ию", "ии", "ия", "а", "я", "у", "ю", "е", "и", "ы", "о", "ь", "й"};

    /** A Russian word without its case ending ("маме", "мама" → "мам"); Latin words stay. */
    static String stem(String w) {
        if (!w.matches(".*[а-я].*") || w.length() <= 3) return w;
        for (String e : ENDINGS) {
            if (w.endsWith(e) && w.length() - e.length() >= 3) return w.substring(0, w.length() - e.length());
        }
        return w;
    }

    private static final String[][] TRANSLIT = {{"щ", "shch"}, {"ж", "zh"}, {"ч", "ch"}, {"ш", "sh"}, {"ю", "yu"},
            {"я", "ya"}, {"х", "kh"}, {"ц", "ts"}, {"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"},
            {"е", "e"}, {"з", "z"}, {"и", "i"}, {"й", "y"}, {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"}, {"о", "o"},
            {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"}, {"у", "u"}, {"ф", "f"}, {"ы", "y"}, {"э", "e"}, {"ь", ""},
            {"ъ", ""}};

    /** Cyrillic to Latin, and Latin spellings that sound alike made the same (x → ks, ph → f, …). */
    static String latin(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            String r = null;
            for (String[] t : TRANSLIT) if (t[0].charAt(0) == ch) r = t[1];
            b.append(r != null ? r : String.valueOf(ch));
        }
        return b.toString().replace("x", "ks").replace("ph", "f").replace("w", "v").replace("kh", "h").replace("j", "y")
                .replace("c", "k").replace("q", "k").replaceAll("(.)\\1", "$1");
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** 1 for the same, down to 0. */
    static double similar(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        return 1.0 - (double) levenshtein(a, b) / Math.max(a.length(), b.length());
    }

    /** One word against another: by stems, and by their Latin spelling. */
    static double word(String spoken, String name) {
        String a = stem(spoken), b = stem(name);
        double s = similar(a, b);
        s = Math.max(s, similar(latin(a), latin(b)));
        // a short spoken word that starts the name ("саш" for "сашенька") is close too
        if (a.length() >= 3 && b.startsWith(a)) s = Math.max(s, 0.85);
        return s;
    }

    /**
     * How well what was said matches a name (0-1): every spoken word should match a different word of the name
     * ("ивану петровичу" ~ "Иван Петрович"); a single spoken word may match any word of it ("саше" ~ "Саша Петров").
     */
    static double score(String spoken, String name) {
        String[] sw = clean(spoken).split(" "), nw = clean(name).split(" ");
        if (sw.length == 0 || nw.length == 0 || sw[0].isEmpty() || nw[0].isEmpty()) return 0;
        double total = 0;
        boolean[] used = new boolean[nw.length];
        for (String w : sw) {
            double best = 0;
            int at = -1;
            for (int i = 0; i < nw.length; i++) {
                if (used[i]) continue;
                double s = word(w, nw[i]);
                if (s > best) {
                    best = s;
                    at = i;
                }
            }
            if (at >= 0) used[at] = true;
            total += best;
        }
        double s = total / sw.length;
        // the whole of it said as one, e.g. a name without spaces
        return Math.max(s, similar(latin(clean(spoken).replace(" ", "")), latin(clean(name).replace(" ", ""))));
    }

    /** The names that match best: one, several (a tie: ask which), or none. */
    static List<Integer> best(String spoken, List<String> names) {
        double top = 0;
        double[] sc = new double[names.size()];
        for (int i = 0; i < names.size(); i++) {
            sc[i] = score(spoken, names.get(i));
            top = Math.max(top, sc[i]);
        }
        List<Integer> out = new ArrayList<>();
        if (top < MATCH) return out;
        for (int i = 0; i < sc.length; i++) if (sc[i] >= MATCH && sc[i] >= top - TIE) out.add(i);
        return out;
    }
}
