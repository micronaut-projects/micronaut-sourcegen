/*
 * Copyright 2017-2024 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Based on https://github.com/projectlombok/lombok/blob/eb4722c59ffc9e43e73bcf12fb52b2f8ad92ffef/src/core/lombok/core/handlers/Singulars.java.
 *
 * @since 1.2
 */
final class Singulars {
    private static final List<String> SINGULAR_STORE; // intended to be immutable.

    static {
        SINGULAR_STORE = new ArrayList<>();

        try (InputStream in = Singulars.class.getResourceAsStream("/singulars.txt")) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line = br.readLine(); line != null; line = br.readLine()) {
                    line = line.trim();
                    if (line.startsWith("#") || line.isEmpty()) {
                        continue;
                    }
                    if (line.endsWith(" =")) {
                        SINGULAR_STORE.add(line.substring(0, line.length() - 2));
                        SINGULAR_STORE.add("");
                        continue;
                    }

                    int idx = line.indexOf(" = ");
                    SINGULAR_STORE.add(line.substring(0, idx));
                    SINGULAR_STORE.add(line.substring(idx + 3));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Try to find singularize the name.
     *
     * @param name The property name
     * @return The singularize name
     */
    @Nullable
    public static String singularize(@NonNull String name) {
        final int inLen = name.length();
        for (int i = 0; i < SINGULAR_STORE.size(); i += 2) {
            final String lastPart = SINGULAR_STORE.get(i);
            final boolean wholeWord = Character.isUpperCase(lastPart.charAt(0));
            final int endingOnly = lastPart.charAt(0) == '-' ? 1 : 0;
            final int len = lastPart.length();
            if (inLen < len) {
                continue;
            }
            if (!name.regionMatches(true, inLen - len + endingOnly, lastPart, endingOnly, len - endingOnly)) {
                continue;
            }
            if (wholeWord && inLen != len && !Character.isUpperCase(name.charAt(inLen - len))) {
                continue;
            }

            String replacement = SINGULAR_STORE.get(i + 1);
            if (replacement.equals("!")) {
                return null;
            }

            boolean capitalizeFirst = !replacement.isEmpty() && Character.isUpperCase(name.charAt(inLen - len + endingOnly));
            String pre = name.substring(0, inLen - len + endingOnly);
            String post = capitalizeFirst ? Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1) : replacement;
            return pre + post;
        }

        return null;
    }
}
