package org.Cry_Legende.sHItemTags;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mots interdits dans un nom d'item, comparés après normalisation (la même pour la liste et pour le nom) :
 * minuscules, sans accents, chiffres et symboles collés à une lettre lus comme des lettres (s@l0pe, m3rde), suites de
 * 3 lettres identiques ou plus réduites à une (meeerde), lettres isolées regroupées (f d p, f.u.c.k, f_d_p).
 * <p>
 * Deux listes : les <b>mots</b> ne sont trouvés qu'entiers (« con » ne touche ni « flacon » ni « second ») ; les
 * <b>morceaux</b> sont cherchés partout, y compris dans le nom écrit d'un seul tenant (« GrosConnard », « filsdepute »).
 * Les <b>exceptions</b> (« pique nique ») sont retirées du nom avant la recherche.
 * Construite une fois au chargement de la config : une vérification ne fait qu'un passage sur le nom.
 */
final class BannedWords {

    static final BannedWords EMPTY = new BannedWords(List.of(), List.of(), List.of());

    private final Set<String> words = new HashSet<>();
    private final List<String> pieces = new ArrayList<>();
    private final List<List<String>> exceptions = new ArrayList<>();

    BannedWords(Collection<String> words, Collection<String> pieces, Collection<String> exceptions) {
        for (String entry : exceptions) {
            List<String> tokens = tokens(entry);
            if (!tokens.isEmpty()) {
                this.exceptions.add(tokens);
            }
        }
        for (String entry : words) {
            // une entrée de plusieurs mots (« ta gueule ») est cherchée collée, comme un morceau
            List<String> tokens = tokens(entry);
            if (tokens.size() == 1) {
                this.words.add(tokens.getFirst());
            } else if (!tokens.isEmpty()) {
                this.pieces.add(String.join("", tokens));
            }
        }
        for (String entry : pieces) {
            String piece = String.join("", tokens(entry));
            if (!piece.isEmpty()) {
                this.pieces.add(piece);
            }
        }
    }

    boolean isEmpty() {
        return words.isEmpty() && pieces.isEmpty();
    }

    int size() {
        return words.size() + pieces.size();
    }

    /**
     * Premier mot interdit trouvé dans ce texte (sans codes couleur), sous sa forme normalisée ; null s'il n'y en a pas.
     */
    String find(String text) {
        if (isEmpty() || text == null || text.isBlank()) {
            return null;
        }
        List<String> tokens = withoutExceptions(tokens(text));
        StringBuilder compact = new StringBuilder();
        for (String token : tokens) {
            if (words.contains(token)) {
                return token;
            }
            compact.append(token);
        }
        for (String piece : pieces) {
            if (compact.indexOf(piece) >= 0) {
                return piece;
            }
        }
        return null;
    }

    /** Les mots du nom, sans les suites qui forment une exception. */
    private List<String> withoutExceptions(List<String> tokens) {
        if (exceptions.isEmpty()) {
            return tokens;
        }
        List<String> out = new ArrayList<>(tokens.size());
        int i = 0;
        outer:
        while (i < tokens.size()) {
            for (List<String> exception : exceptions) {
                int n = exception.size();
                if (i + n <= tokens.size() && tokens.subList(i, i + n).equals(exception)) {
                    i += n;
                    continue outer;
                }
            }
            out.add(tokens.get(i++));
        }
        return out;
    }

    /**
     * Mots normalisés du texte. Les lettres isolées qui se suivent forment un mot (« f d p » : fdp).
     */
    static List<String> tokens(String text) {
        String lower = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("œ", "oe").replace("æ", "ae").replace("ß", "ss");
        List<String> raw = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int length = lower.length();
        for (int i = 0; i < length; i++) {
            char c = lower.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            char letter = Character.isLetter(c) ? c : leet(c);
            // un chiffre ou @ $ € ne compte comme lettre que collé à une lettre (« m3rde », « $alope », pas « épée 4 »),
            // ! et | qu'entre deux lettres (« b!te », pas « con! »)
            boolean strict = c == '!' || c == '|';
            if (letter != 0 && !Character.isLetter(c) && !(strict ? betweenLetters(lower, i) : nextToLetter(lower, i))) {
                letter = 0;
            }
            if (letter == 0) {
                flush(word, raw);
                continue;
            }
            word.append(letter);
        }
        flush(word, raw);

        List<String> out = new ArrayList<>(raw.size());
        StringBuilder singles = new StringBuilder();
        for (String token : raw) {
            if (token.length() == 1) {
                singles.append(token);
                continue;
            }
            if (!singles.isEmpty()) {
                out.add(singles.toString());
                singles.setLength(0);
            }
            out.add(token);
        }
        if (!singles.isEmpty()) {
            out.add(singles.toString());
        }
        return out;
    }

    /** Ajoute le mot en cours, ses suites de 3 lettres identiques ou plus réduites à une (meeerde : merde). */
    private static void flush(StringBuilder word, List<String> out) {
        if (word.isEmpty()) {
            return;
        }
        StringBuilder collapsed = new StringBuilder(word.length());
        int i = 0;
        while (i < word.length()) {
            char c = word.charAt(i);
            int run = 1;
            while (i + run < word.length() && word.charAt(i + run) == c) {
                run++;
            }
            collapsed.append(String.valueOf(c).repeat(run >= 3 ? 1 : run));
            i += run;
        }
        out.add(collapsed.toString());
        word.setLength(0);
    }

    private static boolean nextToLetter(String text, int i) {
        return (i > 0 && isLetterLike(text.charAt(i - 1))) || (i + 1 < text.length() && isLetterLike(text.charAt(i + 1)));
    }

    private static boolean betweenLetters(String text, int i) {
        return i > 0 && i + 1 < text.length() && isLetterLike(text.charAt(i - 1)) && isLetterLike(text.charAt(i + 1));
    }

    private static boolean isLetterLike(char c) {
        return Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK;
    }

    /** Lettre qu'un chiffre ou un symbole remplace souvent, 0 sinon. */
    private static char leet(char c) {
        return switch (c) {
            case '0' -> 'o';
            case '1', '!', '|' -> 'i';
            case '3', '€' -> 'e';
            case '4', '@' -> 'a';
            case '5', '$' -> 's';
            case '7' -> 't';
            case '8' -> 'b';
            default -> 0;
        };
    }
}
