package io.micronaut.sourcegen.bytecode;

import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.jar.Manifest;

public final class DecompilerUtils {

    public static String decompileToJava(byte[] bytecode) {
        String[] result  = new String[1];
        Fernflower fernflower = new Fernflower((s, s1) -> bytecode, new IResultSaver() {
            @Override
            public void saveFolder(String s) {
                // only the decompiled class content is captured
            }

            @Override
            public void copyFile(String source, String path, String entryName) {
                try {
                    InterpreterUtil.copyFile(new File(source), new File(getAbsolutePath(path), entryName));
                }
                catch (IOException ex) {
                    DecompilerContext.getLogger().writeMessage("Cannot copy " + source + " to " + entryName, ex);
                }
            }

            private String getAbsolutePath(String path) {
                return new File(path).getAbsolutePath();
            }

            @Override
            public void saveClassFile(String s, String s1, String s2, String s3, int[] ints) {
                result[0] = s3.replace("\r\n", "\n");
            }

            @Override
            public void createArchive(String s, String s1, Manifest manifest) {
                // only the decompiled class content is captured
            }

            @Override
            public void saveDirEntry(String s, String s1, String s2) {
                // only the decompiled class content is captured
            }

            @Override
            public void copyEntry(String s, String s1, String s2, String s3) {
                // only the decompiled class content is captured
            }

            @Override
            public void saveClassEntry(String s, String s1, String s2, String s3, String s4) {
                // only the decompiled class content is captured
            }

            @Override
            public void closeArchive(String s, String s1) {
                // only the decompiled class content is captured
            }
        }, Map.of(), new PrintStreamLogger(System.out));
        try {
            File tempFile = File.createTempFile("ffffff", ".class");
            Files.write(tempFile.toPath(), bytecode);
            fernflower.addSource(tempFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        fernflower.decompileContext();
        return result[0];
    }

}
