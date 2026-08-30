package com.tencent.qqnt.patcher;

import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.baksmali.formatter.BaksmaliWriter;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DexPatcher {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: java DexPatcher <batch_config.txt>");
            System.exit(1);
        }

        String batchConfigFile = args[0];
        try {
            long t0 = System.currentTimeMillis();
            List<DexTask> tasks = loadBatchTasks(batchConfigFile);
            Opcodes opcodes = Opcodes.forApi(26);

            int threadCount = Math.min(tasks.size(), 3);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<?>> futures = new ArrayList<>();

            for (DexTask task : tasks) {
                futures.add(executor.submit(() -> {
                    try {
                        long tTask = System.currentTimeMillis();
                        patchSingleDex(task, opcodes);
                        System.out.println("[DexPatcher] " + new File(task.dexIn).getName() + " 处理完成，耗时: " + (System.currentTimeMillis() - tTask) + "ms");
                    } catch (Exception e) {
                        throw new RuntimeException("处理 " + task.dexIn + " 失败", e);
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get();
            }
            executor.shutdown();

            System.out.println("[DexPatcher] 分包处理完成，耗时: " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void patchSingleDex(DexTask task, Opcodes opcodes) throws Exception {
        if (task.rules.isEmpty()) {
            copyFile(new File(task.dexIn), new File(task.dexOut));
            return;
        }

        DexBackedDexFile dexFile;
        try (InputStream is = new BufferedInputStream(new FileInputStream(task.dexIn))) {
            dexFile = DexBackedDexFile.fromInputStream(opcodes, is);
        }

        Map<String, List<PatchRule>> ruleMap = new HashMap<>();
        for (PatchRule r : task.rules) {
            ruleMap.computeIfAbsent(r.targetClass, k -> new ArrayList<>()).add(r);
        }

        BaksmaliOptions baksmaliOptions = new BaksmaliOptions();
        Map<String, ClassDef> replacedClasses = new HashMap<>();

        for (ClassDef classDef : dexFile.getClasses()) {
            String clsType = classDef.getType();
            List<PatchRule> matchedRules = ruleMap.get(clsType);

            if (matchedRules != null && !matchedRules.isEmpty()) {
                String smaliCode = disassembleClass(classDef, baksmaliOptions);
                for (PatchRule r : matchedRules) {
                    smaliCode = applyRule(smaliCode, r);
                }
                ClassDef newClassDef = assembleSingleClass(smaliCode, opcodes);
                if (newClassDef != null) {
                    replacedClasses.put(clsType, newClassDef);
                }
            }
        }

        DexPool dexPool = new DexPool(opcodes);
        for (ClassDef classDef : dexFile.getClasses()) {
            if (replacedClasses.containsKey(classDef.getType())) {
                dexPool.internClass(replacedClasses.get(classDef.getType()));
            } else {
                dexPool.internClass(classDef);
            }
        }

        File outFile = new File(task.dexOut);
        if (outFile.exists()) outFile.delete();
        dexPool.writeTo(new FileDataStore(outFile));
    }

    private static String disassembleClass(ClassDef classDef, BaksmaliOptions options) throws Exception {
        StringWriter sw = new StringWriter();
        BaksmaliWriter bw = new BaksmaliWriter(sw, null);
        ClassDefinition cd = new ClassDefinition(options, classDef);
        cd.writeTo(bw);
        bw.close();
        return sw.toString();
    }

    private static ClassDef assembleSingleClass(String smaliCode, Opcodes opcodes) {
        File tempSmali = null;
        File tempDex = null;
        try {
            tempSmali = File.createTempFile("patch_temp_", ".smali");
            try (FileOutputStream fos = new FileOutputStream(tempSmali)) {
                fos.write(smaliCode.getBytes(StandardCharsets.UTF_8));
            }
            tempDex = File.createTempFile("patch_temp_", ".dex");

            SmaliOptions options = new SmaliOptions();
            options.outputDexFile = tempDex.getAbsolutePath();
            options.jobs = 1;

            boolean success = Smali.assemble(options, Collections.singletonList(tempSmali.getAbsolutePath()));
            if (!success || !tempDex.exists() || tempDex.length() == 0) return null;

            try (InputStream is = new BufferedInputStream(new FileInputStream(tempDex))) {
                DexBackedDexFile singleDex = DexBackedDexFile.fromInputStream(opcodes, is);
                Set<? extends ClassDef> classes = singleDex.getClasses();
                return classes.isEmpty() ? null : classes.iterator().next();
            }
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        } finally {
            if (tempSmali != null) tempSmali.delete();
            if (tempDex != null) tempDex.delete();
        }
    }

    private static String applyRule(String code, PatchRule rule) {
        String methodName = rule.targetMethod;
        Pattern pattern;

        if (methodName.contains("(")) {
            String escaped = Pattern.quote(methodName);
            pattern = Pattern.compile("(\\.method[^\\n]*\\s+" + escaped + "\\s*?\\n.*?\\.end method)", Pattern.DOTALL);
        } else if ("<init>".equals(methodName)) {
            pattern = Pattern.compile("(\\.method[^\\n]*\\s+<init>\\([^\\n]*\\)\\w*?\\s*?\\n.*?\\.end method)", Pattern.DOTALL);
        } else {
            String escaped = Pattern.quote(methodName);
            pattern = Pattern.compile("(\\.method[^\\n]*\\s+" + escaped + "\\b.*?\\.end method)", Pattern.DOTALL);
        }

        if ("REPLACE".equals(rule.type)) {
            return pattern.matcher(code).replaceAll(Matcher.quoteReplacement(rule.smali));
        } else if ("INSERT_BEFORE".equals(rule.type)) {
            Matcher m = pattern.matcher(code);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String mBody = m.group(1);
                Matcher headerMatcher = Pattern.compile("(\\.registers\\s+\\d+|\\.locals\\s+\\d+)").matcher(mBody);
                if (headerMatcher.find()) {
                    int idx = headerMatcher.end();
                    String newBody = mBody.substring(0, idx) + "\n" + rule.smali + "\n" + mBody.substring(idx);
                    m.appendReplacement(sb, Matcher.quoteReplacement(newBody));
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(mBody));
                }
            }
            m.appendTail(sb);
            return sb.toString();
        } else if ("REGEX_REPLACE".equals(rule.type)) {
            Matcher m = pattern.matcher(code);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String mBody = m.group(1);
                String replacedBody = Pattern.compile(rule.regex).matcher(mBody).replaceAll(Matcher.quoteReplacement(rule.smali));
                m.appendReplacement(sb, Matcher.quoteReplacement(replacedBody));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        return code;
    }

    private static List<DexTask> loadBatchTasks(String configFile) throws Exception {
        List<DexTask> taskList = new ArrayList<>();
        File file = new File(configFile);
        if (!file.exists()) return taskList;

        String raw = new String(readFile(file), StandardCharsets.UTF_8);
        String[] dexBlocks = raw.split("===DEX_TASK_SPLIT===");

        for (String dBlock : dexBlocks) {
            if (dBlock.trim().isEmpty()) continue;
            DexTask task = new DexTask();
            String[] ruleBlocks = dBlock.trim().split("===RULE_SPLIT===");

            for (int i = 0; i < ruleBlocks.length; i++) {
                String rBlock = ruleBlocks[i].trim();
                if (rBlock.isEmpty()) continue;

                if (i == 0) {
                    String[] lines = rBlock.split("\n");
                    for (String l : lines) {
                        if (l.startsWith("DEX_IN=")) task.dexIn = l.substring(7).trim();
                        else if (l.startsWith("DEX_OUT=")) task.dexOut = l.substring(8).trim();
                    }
                } else {
                    PatchRule r = new PatchRule();
                    String[] lines = rBlock.split("\n");
                    StringBuilder smaliSb = new StringBuilder();
                    boolean readingSmali = false;

                    for (String l : lines) {
                        if (!readingSmali) {
                            if (l.startsWith("TARGET_CLASS=")) r.targetClass = l.substring(13).trim();
                            else if (l.startsWith("TARGET_METHOD=")) r.targetMethod = l.substring(14).trim();
                            else if (l.startsWith("TYPE=")) r.type = l.substring(5).trim();
                            else if (l.startsWith("REGEX=")) r.regex = l.substring(6).trim();
                            else if (l.equals("---SMALI_START---")) readingSmali = true;
                        } else {
                            if (l.equals("---SMALI_END---")) {
                                readingSmali = false;
                            } else {
                                smaliSb.append(l).append("\n");
                            }
                        }
                    }
                    r.smali = smaliSb.toString().trim();
                    if (r.targetClass != null && r.targetMethod != null) {
                        task.rules.add(r);
                    }
                }
            }
            if (task.dexIn != null && task.dexOut != null) {
                taskList.add(task);
            }
        }
        return taskList;
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    static class DexTask {
        String dexIn;
        String dexOut;
        List<PatchRule> rules = new ArrayList<>();
    }

    static class PatchRule {
        String targetClass;
        String targetMethod;
        String type;
        String regex;
        String smali;
    }
}