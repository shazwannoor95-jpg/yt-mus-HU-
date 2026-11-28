// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;
import android.os.Build;
import android.util.LruCache;

import net.crizin.KoreanRomanizer;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

final class LocalRomanizer {
    static final int MAX_LINE = 320;
    private static final String CYRILLIC = "абвгдежзийклмнопрстуфхцчшщъыьэюяёіїєґ";
    private static final String[] LATIN = {"a","b","v","g","d","e","zh","z","i","y","k","l","m","n","o","p","r","s","t",
            "u","f","kh","ts","ch","sh","shch","″","y","′","e","yu","ya","yo","i","yi","ye","g"};
    private static final Semaphore DICTIONARY_SLOT = new Semaphore(1);
    private final LruCache<String, Reading> cache = new LruCache<>(96);

    enum DictionaryState { READY, MISSING, MEMORY, ERROR }

    static final class Reading {
        static final Reading NONE = new Reading("", false);
        static final Reading ORIGINAL = new Reading("", true);
        final String text;
        final boolean complete;

        Reading(String text, boolean complete) {
            this.text = text;
            this.complete = complete;
        }
    }

    static String language(String reported, String title, List<Lyrics.Line> lines) {
        String source = reported.toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        if ("ja".equals(source) || hasKana(title)) return "ja";
        int kanaLines = 0;
        int koreanLines = 0;
        for (Lyrics.Line line : lines) {
            if (hasKana(line.text)) kanaLines++;
            if (line.text.codePoints().anyMatch(c -> script(c) == Character.UnicodeScript.HANGUL)) koreanLines++;
        }
        if (kanaLines > 0 && koreanLines == 0) return "ja";
        if (!source.isEmpty() && !"und".equals(source)) return source;
        if (koreanLines > 0 && kanaLines == 0) return "ko";
        return "";
    }

    private static boolean hasKana(String text) {
        return text.codePoints().anyMatch(c -> script(c) == Character.UnicodeScript.HIRAGANA
                || script(c) == Character.UnicodeScript.KATAKANA);
    }

    private static Character.UnicodeScript script(int point) { return Character.UnicodeScript.of(point); }

    static boolean japaneseHan(String text, String language) {
        return ("ja".equals(language) || hasKana(text)) && containsHan(text);
    }

    static boolean hasHan(List<Lyrics.Line> lines) {
        return lines.stream().anyMatch(line -> containsHan(line.text));
    }

    private static boolean containsHan(String text) {
        return text.codePoints().anyMatch(c -> script(c) == Character.UnicodeScript.HAN);
    }

    void clear() { cache.evictAll(); }

    final class Batch implements AutoCloseable {
        private final File dictionary;
        private final Context context;
        private DictionaryState dictionaryState;
        private JapaneseDictionary.Tokenizer tokenizer;
        private boolean dictionarySlot;

        Batch(Context context, JapaneseDictionary dictionary) {
            this.context = context;
            this.dictionary = dictionary.installed() ? dictionary.archive : null;
            dictionaryState = this.dictionary == null ? DictionaryState.MISSING : DictionaryState.READY;
        }

        DictionaryState dictionaryState() { return dictionaryState; }

        Reading read(String text, String language) {
            if (text.length() > MAX_LINE || Thread.currentThread().isInterrupted()) return Reading.NONE;
            if (!LyricExtras.nonLatin(text)) return Reading.ORIGINAL;
            boolean japanese = "ja".equals(language) || hasKana(text);
            String key = "hepburn-2:" + language + (japanese ? ":ja" : "") + '\n' + text;
            Reading cached = cache.get(key);
            if (cached != null) return cached;

            Reading reading = convert(text, language, japanese);
            if (reading.complete && !(japanese && dictionaryState != DictionaryState.READY)) cache.put(key, reading);
            return reading;
        }

        private Reading convert(String text, String language, boolean japanese) {
            StringBuilder out = new StringBuilder();
            for (int start = 0; start < text.length(); ) {
                if (Thread.currentThread().isInterrupted()) return Reading.NONE;
                int code = text.codePointAt(start);
                int end = start + Character.charCount(code);
                Character.UnicodeScript writing = script(code);
                if (japanese && japaneseCharacter(code)) {
                    while (end < text.length() && japaneseCharacter(text.codePointAt(end))) {
                        end += Character.charCount(text.codePointAt(end));
                    }
                    String segment = text.substring(start, end);
                    appendWord(out, "ja".equals(language) || hasKana(segment) ? japanese(segment) : segment);
                } else if (writing == Character.UnicodeScript.HANGUL) {
                    while (end < text.length() && script(text.codePointAt(end)) == writing) {
                        end += Character.charCount(text.codePointAt(end));
                    }
                    String word = text.substring(start, end);
                    appendWord(out, KoreanRomanizer.romanize(Normalizer.normalize(word, Normalizer.Form.NFKC))
                            .toLowerCase(Locale.ROOT));
                } else if (writing == Character.UnicodeScript.HAN) {
                    String pinyin = "zh".equals(language) ? pinyin(code) : "";
                    if (pinyin.isEmpty()) out.appendCodePoint(code);
                    else appendWord(out, pinyin);
                } else if (Character.isLetter(code) && writing != Character.UnicodeScript.LATIN) {
                    while (end < text.length() && script(text.codePointAt(end)) == writing
                            && Character.isLetter(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end));
                    String word = text.substring(start, end);
                    out.append(writing == Character.UnicodeScript.CYRILLIC ? cyrillic(word)
                            : Build.VERSION.SDK_INT >= 29 ? Icu.romanize(word) : word);
                } else {
                    if (Character.isLetterOrDigit(code)) spaceAfterAsian(text, start, out);
                    out.appendCodePoint(code);
                }
                start = end;
            }
            String result = out.toString().trim();
            boolean changed = !result.replace(" ", "").equals(text.trim().replace(" ", ""));
            return changed ? new Reading(result, !LyricExtras.nonLatin(result)) : Reading.NONE;
        }

        private String japanese(String text) {
            String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
            if (dictionaryState == DictionaryState.READY) {
                try {
                    if (tokenizer == null) {
                        if (!DICTIONARY_SLOT.tryAcquire()) {
                            dictionaryState = DictionaryState.MEMORY;
                            return Hepburn.convert(normalized);
                        }
                        dictionarySlot = true;
                        if (!JapaneseDictionary.memoryAvailable(context)) {
                            dictionaryState = DictionaryState.MEMORY;
                            close();
                            return Hepburn.convert(normalized);
                        }
                        tokenizer = new JapaneseDictionary.Tokenizer(dictionary);
                    }
                    return JapaneseReading.render(tokenizer.tokenize(normalized));
                } catch (IOException | RuntimeException error) {
                    dictionaryState = DictionaryState.ERROR;
                    close();
                }
            }
            return Hepburn.convert(normalized);
        }
        private String pinyin(int code) {
            try {
                return HanReadings.load(context).pinyin(code);
            } catch (IOException unavailable) {
                return "";
            }
        }

        @Override public void close() {
            tokenizer = null;
            if (dictionarySlot) {
                dictionarySlot = false;
                DICTIONARY_SLOT.release();
            }
        }
    }

    private static boolean japaneseCharacter(int c) {
        Character.UnicodeScript s = script(c);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || c == 'ー' || c == 'ｰ' || c == '々';
    }

    private static void spaceAfterAsian(String text, int start, StringBuilder out) {
        if (start == 0) return;
        int before = text.codePointBefore(start);
        if (japaneseCharacter(before) || script(before) == Character.UnicodeScript.HANGUL) {
            if (out.length() > 0 && Character.isLetterOrDigit(out.codePointBefore(out.length()))) out.append(' ');
        }
    }

    static void appendWord(StringBuilder text, String word) {
        if (word.isEmpty()) return;
        if (text.length() > 0 && Character.isLetterOrDigit(text.codePointBefore(text.length()))
                && Character.isLetterOrDigit(word.codePointAt(0))) text.append(' ');
        text.append(word);
    }

    private static String cyrillic(String word) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            int index = CYRILLIC.indexOf(Character.toLowerCase(c));
            if (index < 0) result.append(c);
            else {
                String latin = LATIN[index];
                result.append(Character.isUpperCase(c) ? Character.toUpperCase(latin.charAt(0)) + latin.substring(1) : latin);
            }
        }
        return result.toString();
    }

    @android.annotation.TargetApi(29)
    private static final class Icu {
        private static final ThreadLocal<android.icu.text.Transliterator> LATIN =
                ThreadLocal.withInitial(() -> android.icu.text.Transliterator.getInstance("Any-Latin"));
        static String romanize(String text) { return LATIN.get().transliterate(text); }
    }
}
