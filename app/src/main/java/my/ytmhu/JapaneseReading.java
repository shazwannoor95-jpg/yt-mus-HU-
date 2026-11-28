// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import java.util.List;

final class JapaneseReading {
    private JapaneseReading() {}

    static String render(List<JapaneseDictionary.JapaneseToken> tokens) {
        StringBuilder output = new StringBuilder();
        StringBuilder phrase = new StringBuilder();
        boolean predicate = false;
        for (int i = 0; i < tokens.size(); i++) {
            if (Thread.currentThread().isInterrupted()) return "";
            JapaneseDictionary.JapaneseToken token = tokens.get(i);
            boolean afterPrefix = i > 0 && "接頭詞".equals(tokens.get(i - 1).partOfSpeech());
            String surface = token.getSurface();
            String reading = counter(surface);
            if (reading.isEmpty() && i + 1 < tokens.size() && "助数詞".equals(tokens.get(i + 1).subcategory())) {
                reading = counter(surface + tokens.get(i + 1).getSurface());
                if (!reading.isEmpty()) i++;
            }
            String pos = token.partOfSpeech();
            String kind = token.category();
            if (reading.isEmpty()) reading = pronunciation(token);
            boolean verb = "動詞".equals(pos) || "形容詞".equals(pos);
            boolean connective = "接続助詞".equals(kind) && ("て".equals(surface) || "で".equals(surface) || "ば".equals(surface));
            boolean attaches = "助動詞".equals(pos) || "接尾".equals(kind)
                    || afterPrefix
                    || (predicate && (connective || (verb && "非自立".equals(kind))));
            if (!attaches) flush(output, phrase);
            phrase.append(reading);
            predicate = verb || (attaches && predicate);
        }
        flush(output, phrase);
        return output.toString();
    }

    private static String counter(String surface) {
        switch (surface) {
            case "一人": return "ヒトリ";
            case "二人": return "フタリ";
            default: return "";
        }
    }

    private static String pronunciation(JapaneseDictionary.JapaneseToken token) {
        if ("助詞".equals(token.partOfSpeech())) {
            switch (token.getSurface()) {
                case "は": return "ワ";
                case "へ": return "エ";
                case "を": return "オ";
                default: break;
            }
        }
        String reading = token.isKnown() ? token.pronunciation() : "*";
        if ("*".equals(reading) || reading.isEmpty()) reading = token.isKnown() ? token.reading() : "*";
        return "*".equals(reading) || reading.isEmpty() ? token.getSurface() : reading;
    }

    private static void flush(StringBuilder output, StringBuilder phrase) {
        if (phrase.length() == 0) return;
        LocalRomanizer.appendWord(output, Hepburn.convert(phrase.toString()));
        phrase.setLength(0);
    }
}
