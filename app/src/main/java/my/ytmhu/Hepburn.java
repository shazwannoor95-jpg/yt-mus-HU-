// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

final class Hepburn {
    private static final String[] KANA = {
            "a","a","i","i","u","u","e","e","o","o","ka","ga","ki","gi","ku","gu","ke","ge","ko","go",
            "sa","za","shi","ji","su","zu","se","ze","so","zo","ta","da","chi","ji","","tsu","zu",
            "te","de","to","do","na","ni","nu","ne","no","ha","ba","pa","hi","bi","pi","fu","bu","pu",
            "he","be","pe","ho","bo","po","ma","mi","mu","me","mo","ya","ya","yu","yu","yo","yo",
            "ra","ri","ru","re","ro","wa","wa","i","e","o","n","vu","ka","ke"
    };

    private Hepburn() {}

    static String convert(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = hiragana(text.charAt(i));
            char next = i + 1 < text.length() ? hiragana(text.charAt(i + 1)) : 0;
            if (current == 'っ') {
                String follow = syllable(next);
                if (follow.startsWith("ch")) out.append('t');
                else if (!follow.isEmpty() && "aiueon".indexOf(follow.charAt(0)) < 0) out.append(follow.charAt(0));
                else out.append(text.charAt(i));
                continue;
            }
            if (current == 'ー') {
                if (!lengthen(out)) out.append(text.charAt(i));
                continue;
            }
            String first = syllable(current);
            if (first.isEmpty()) {
                out.append(text.charAt(i));
                continue;
            }
            String pair = pair(current, next, first);
            if (!pair.isEmpty()) {
                out.append(pair);
                i++;
            } else {
                out.append(first);
                if (current == 'ん' && "あいうえおやゆよ".indexOf(next) >= 0) out.append('\'');
            }
        }
        return out.toString();
    }

    private static String pair(char current, char next, String first) {
        int smallY = "ゃゅょ".indexOf(next);
        if (smallY >= 0 && first.endsWith("i") && first.length() >= 2) {
            String stem = first.substring(0, first.length() - 1);
            return stem + ("sh".equals(stem) || "ch".equals(stem) || "j".equals(stem) ? "" : "y")
                    + "auo".charAt(smallY);
        }
        if (next == 'ゅ') {
            switch (current) {
                case 'ふ': return "fyu";
                case 'て': return "tyu";
                case 'で': return "dyu";
                case 'ゔ': return "vyu";
                default: break;
            }
        }
        int smallVowel = "ぁぃぅぇぉ".indexOf(next);
        if (smallVowel < 0) return "";
        char vowel = "aiueo".charAt(smallVowel);
        switch (current) {
            case 'ふ': return "f" + vowel;
            case 'ゔ': return "v" + vowel;
            case 'う': return "w" + vowel;
            case 'い': return next == 'ぇ' ? "ye" : "";
            case 'く': return next == 'ぁ' ? "kwa" : "";
            case 'ぐ': return next == 'ぁ' ? "gwa" : "";
            case 'つ': return "ts" + vowel;
            case 'し': return next == 'ぇ' ? "she" : "";
            case 'じ': return next == 'ぇ' ? "je" : "";
            case 'ち': return next == 'ぇ' ? "che" : "";
            case 'て': return next == 'ぃ' ? "ti" : "";
            case 'で': return next == 'ぃ' ? "di" : "";
            case 'と': return next == 'ぅ' ? "tu" : "";
            case 'ど': return next == 'ぅ' ? "du" : "";
            default: return "";
        }
    }

    private static boolean lengthen(StringBuilder out) {
        if (out.length() == 0) return false;
        int last = out.length() - 1;
        int vowel = "aiueo".indexOf(out.charAt(last));
        if (vowel < 0) return false;
        out.setCharAt(last, "āīūēō".charAt(vowel));
        return true;
    }

    private static String syllable(char c) {
        int index = c - 'ぁ';
        return index < 0 || index >= KANA.length ? "" : KANA[index];
    }

    private static char hiragana(char c) { return c >= 'ァ' && c <= 'ヶ' ? (char) (c - 0x60) : c; }
}
