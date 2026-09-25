/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.sourcegen.bytecode.jdk.DiffBackends.Backend;
import io.micronaut.sourcegen.bytecode.jdk.DiffBackends.Model;
import io.micronaut.sourcegen.bytecode.jdk.DiffBackends.Result;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Differential test of the four backends: every model is written with the ASM bytecode writer, the
 * JDK ClassFile writer, the Java source generator (compiled with javac) and the Kotlin source
 * generator (compiled with kotlinc), and the written classes are run on the same inputs.
 *
 * <p>The Java source compiled with javac is the reference. When javac accepts a model, every other
 * backend that does not decline it must behave the same: the same result or exception for every
 * input. When javac rejects it the model is not valid Java, and a backend may reject it or write
 * what it makes of it, but when both bytecode writers write a class for it the two classes must
 * behave the same.</p>
 *
 * <p>Known disagreements are listed in {@code differential-allowlist.txt}, each with the verdicts
 * of the backends. A disagreement that is not listed fails, and so does a listed case that now
 * agrees or disagrees in another way, so that the list only shrinks.</p>
 *
 * <p>The default run is bounded and deterministic. System properties widen it for exploration:</p>
 * <ul>
 *     <li>{@code diff.families}: a comma separated list of the families to generate
 *     ({@code cast, math, compare, equality, unary, concat, cond, constant, stmt, random}), or
 *     {@code all} (the default)</li>
 *     <li>{@code diff.seed}: the seed of the random family and of the sample (default {@value DEFAULT_SEED})</li>
 *     <li>{@code diff.random}: the number of random models (default {@value DEFAULT_RANDOM})</li>
 *     <li>{@code diff.count}: the number of models sampled from the expression families and the random
 *     programs, {@code 0} for all of them (default {@value DEFAULT_COUNT}); the statement family always
 *     runs whole</li>
 * </ul>
 * <p>Every run writes the disagreements, with the sources and the outcomes of each backend, to
 * {@code build/differential-report.txt}; its first section lists them in the format of the
 * allowlist. The allowlist holds the cases of the default run, so a wider run also fails on the
 * known disagreements it adds.</p>
 */
class DifferentialHarnessTest {

    private static final String DEFAULT_FAMILIES = "all";
    private static final long DEFAULT_SEED = 1;
    private static final int DEFAULT_RANDOM = 100;
    private static final int DEFAULT_COUNT = 1500;
    private static final String ALLOWLIST = "differential-allowlist.txt";
    private static final String SEPARATOR = " :: ";

    @TestFactory
    Stream<DynamicTest> backendsAgree() throws IOException {
        String families = System.getProperty("diff.families", DEFAULT_FAMILIES);
        long seed = Long.getLong("diff.seed", DEFAULT_SEED);
        int randomCount = Integer.getInteger("diff.random", DEFAULT_RANDOM);
        int count = Integer.getInteger("diff.count", DEFAULT_COUNT);
        boolean defaultRun = DEFAULT_FAMILIES.equals(families) && seed == DEFAULT_SEED
            && randomCount == DEFAULT_RANDOM && count == DEFAULT_COUNT;

        DiffModels models = new DiffModels();
        int expressions = DiffFamilies.generate(models, families, seed, randomCount);
        // The statement family is small and each of its models is distinct, so it is not sampled
        List<Model> sample = new ArrayList<>(sample(models.models.subList(0, expressions), count, seed, models.rejected));
        sample.addAll(models.models.subList(expressions, models.models.size()));
        Map<Backend, Map<String, Result>> results = DiffBackends.runAll(sample);
        Map<String, String> allowlist = readAllowlist();

        List<DynamicTest> tests = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        StringBuilder details = new StringBuilder();
        Map<String, Integer> occurrences = new HashMap<>();
        Set<String> generated = new HashSet<>();
        List<String> stale = new ArrayList<>();
        int disagreements = 0;
        for (Model model : sample) {
            String description = escape(model.description());
            int occurrence = occurrences.merge(description, 1, Integer::sum);
            String id = occurrence > 1 ? description + " #" + occurrence : description;
            generated.add(id);
            Map<Backend, Result> byBackend = new LinkedHashMap<>();
            for (Backend backend : Backend.values()) {
                byBackend.put(backend, results.get(backend).get(model.name()));
            }
            String verdicts = verdicts(byBackend);
            String allowed = allowlist.get(id);
            if (verdicts != null) {
                disagreements++;
                summary.append(verdicts).append(SEPARATOR).append(id).append('\n');
                appendDetails(details, id, model, byBackend);
            }
            if (allowed != null && !allowed.equals(verdicts)) {
                stale.add(allowed + SEPARATOR + id + (verdicts == null ? " (now agrees)" : " (now " + verdicts + ")"));
            }
            if (verdicts == null || verdicts.equals(allowed)) {
                tests.add(DynamicTest.dynamicTest(id, () -> {
                }));
            } else {
                tests.add(DynamicTest.dynamicTest(id, () -> fail(id + " disagrees: " + verdicts
                    + "; see build/differential-report.txt, and fix it or add it to " + ALLOWLIST + " with the reason")));
            }
        }
        if (defaultRun) {
            allowlist.keySet().stream().filter(id -> !generated.contains(id))
                .forEach(id -> stale.add(allowlist.get(id) + SEPARATOR + id + " (not generated)"));
        }
        Path report = Path.of("build", "differential-report.txt");
        Files.createDirectories(report.getParent());
        String header = "models=" + sample.size() + " of " + models.models.size() + " rejected=" + models.rejected
            + " disagreements=" + disagreements + "\n\n";
        // A source may hold an unpaired surrogate, which getBytes replaces
        Files.write(report, (header + summary + "\n" + details).getBytes(StandardCharsets.UTF_8));

        tests.add(DynamicTest.dynamicTest("allowlist is up to date", () -> {
            if (!stale.isEmpty()) {
                fail("Update or remove these entries of " + ALLOWLIST + ":\n  " + String.join("\n  ", stale));
            }
        }));
        return tests.stream();
    }

    /**
     * The verdicts of the backends when they disagree (see the class documentation).
     *
     * @param byBackend The result of every backend
     * @return The verdict of every backend, or null when the backends agree
     */
    private static String verdicts(Map<Backend, Result> byBackend) {
        Result java = byBackend.get(Backend.JAVA);
        boolean validJava = java.failure == null;
        String reference = validJava ? outcomes(java) : null;
        Map<Backend, String> labels = new LinkedHashMap<>();
        boolean disagree = false;
        for (var entry : byBackend.entrySet()) {
            Result result = entry.getValue();
            boolean compared = validJava || entry.getKey() == Backend.ASM || entry.getKey() == Backend.JDK;
            String label;
            if (result.declined()) {
                label = "declined";
            } else if (result.failure != null) {
                label = result.failure.substring(0, Math.max(0, result.failure.indexOf(':')));
                disagree |= validJava;
            } else if (!compared) {
                label = "ok";
            } else if (reference == null) {
                reference = outcomes(result);
                label = "ok";
            } else if (reference.equals(outcomes(result))) {
                label = "ok";
            } else {
                label = "differs";
                disagree = true;
            }
            labels.put(entry.getKey(), label);
        }
        if (!disagree) {
            return null;
        }
        return labels.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" "));
    }

    private static String outcomes(Result result) {
        return String.join("|", result.outcomes);
    }

    /**
     * Samples about {@code count} models, each chosen by its seed and description alone: a model that stops being
     * generated - one the model rejects when it is built - leaves the others in or out of the sample as they were,
     * so the allowlist keeps naming the same cases.
     */
    private static List<Model> sample(List<Model> models, int count, long seed, int rejected) {
        int universe = models.size() + rejected;
        if (count <= 0 || count >= universe) {
            return models;
        }
        return models.stream()
            .filter(model -> Math.floorMod(mix(seed ^ model.description().hashCode()), (long) universe) < count)
            .toList();
    }

    private static long mix(long value) {
        // SplitMix64 finalizer: spreads the bits of a seed and a hash over the whole long
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static void appendDetails(StringBuilder details, String id, Model model, Map<Backend, Result> byBackend) {
        details.append("=== ").append(id).append(" (").append(model.name()).append(")\n");
        byBackend.forEach((backend, result) -> details.append("   ").append(backend).append(": ").append(truncate(result.summary(), 1500))
            .append(result.signature != null ? "   [descriptor " + result.signature + "]" : "").append('\n'));
        Result java = byBackend.get(Backend.JAVA);
        Result kotlin = byBackend.get(Backend.KOTLIN);
        if (java.source != null) {
            details.append("--- java\n").append(java.source);
        }
        if (kotlin.source != null) {
            details.append("--- kotlin\n").append(kotlin.source);
        }
        details.append('\n');
    }

    /**
     * Escapes the characters of a description outside printable ASCII (in the constants it names),
     * so that the case id fits on one line of the allowlist.
     *
     * @param description The description
     * @return The case id
     */
    private static String escape(String description) {
        StringBuilder id = new StringBuilder(description.length());
        for (char c : description.toCharArray()) {
            if (c < ' ' || c > '~' || c == '\\') {
                id.append(String.format("\\u%04x", (int) c));
            } else {
                id.append(c);
            }
        }
        return id.toString();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    /**
     * Reads the allowlist: a line {@code <verdicts> :: <case id>} per known disagreement, and
     * comments starting with {@code #}.
     *
     * @return The verdicts of each allowed case, by case id
     * @throws IOException When the allowlist cannot be read
     */
    private static Map<String, String> readAllowlist() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = DifferentialHarnessTest.class.getResourceAsStream("/" + ALLOWLIST)) {
            if (in == null) {
                throw new IOException(ALLOWLIST + " is missing");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.strip();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                int separator = entry.indexOf(SEPARATOR);
                if (separator < 0) {
                    throw new IOException("Not '<verdicts>" + SEPARATOR + "<case id>': " + entry);
                }
                if (entries.put(entry.substring(separator + SEPARATOR.length()), entry.substring(0, separator)) != null) {
                    throw new IOException("Listed twice: " + entry);
                }
            }
        }
        return entries;
    }
}
