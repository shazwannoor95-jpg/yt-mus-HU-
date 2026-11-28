// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;

final class HanReadings {
    private static HanReadings table;
    private final String[] syllables;
    private final int[] characters;
    private final char[] readings;

    private HanReadings(Context context) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(context.getAssets().open("han-readings.bin")))) {
            syllables = new String[input.readUnsignedShort()];
            characters = new int[input.readUnsignedShort()];
            readings = new char[characters.length];
            for (int i = 0; i < syllables.length; i++) syllables[i] = input.readUTF();
            for (int i = 0; i < characters.length; i++) {
                characters[i] = input.readInt();
                readings[i] = input.readChar();
            }
        }
    }

    static synchronized HanReadings load(Context context) throws IOException {
        if (table == null) table = new HanReadings(context);
        return table;
    }

    String pinyin(int point) {
        int index = Arrays.binarySearch(characters, point);
        return index < 0 ? "" : syllables[readings[index]];
    }
}
