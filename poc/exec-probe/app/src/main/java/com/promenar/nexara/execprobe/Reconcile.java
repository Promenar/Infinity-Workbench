package com.promenar.nexara.execprobe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 模拟“命令结束后工作区索引对账”：生成 N 个文件，测元数据遍历、全量哈希、
 * 子进程改动后按 size+mtime 找差异并只对差异文件重新哈希的耗时。
 */
final class Reconcile {
    private Reconcile() {}

    static final class Entry {
        final long size;
        final long mtime;
        Entry(long size, long mtime) { this.size = size; this.mtime = mtime; }
    }

    static void run(File root, String probePath, int count, int size) {
        deleteTree(root);
        long t = System.nanoTime();
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        for (int i = 0; i < count; i++) {
            File file = new File(root, rel(i));
            file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(data);
            } catch (Exception e) {
                Probe.log("recon_gen", "exception=" + e);
                return;
            }
        }
        Probe.log("recon_gen", "files=" + count + " size=" + size + " ms=" + ms(t));

        t = System.nanoTime();
        Map<String, Entry> before = new HashMap<>();
        walk(root, root.getPath().length(), before);
        Probe.log("recon_stat_walk", "entries=" + before.size() + " ms=" + ms(t));

        t = System.nanoTime();
        long bytes = 0;
        for (String path : before.keySet()) bytes += hash(new File(root, path));
        Probe.log("recon_full_hash", "bytes=" + bytes + " ms=" + ms(t));

        // 子进程修改 20 个、新建 5 个、删除 5 个文件，模拟命令改写工作区。
        StringBuilder script = new StringBuilder("cd '" + root.getPath() + "' && sleep 1");
        for (int k = 0; k < 20; k++) script.append(" && echo changed >> ").append(rel(k * count / 20));
        for (int k = 0; k < 5; k++) script.append(" && echo new > d0/s0/new").append(k).append(".txt");
        for (int k = 0; k < 5; k++) script.append(" && rm -f ").append(rel(k * count / 20 + 1));
        Probe.log("recon_mutate", Probe.run("/system/bin/sh", "-c", script.toString()));

        t = System.nanoTime();
        Map<String, Entry> after = new HashMap<>();
        walk(root, root.getPath().length(), after);
        int changed = 0, added = 0, removed = 0;
        long rehashed = 0;
        for (Map.Entry<String, Entry> e : after.entrySet()) {
            Entry old = before.get(e.getKey());
            if (old == null) { added++; rehashed += hash(new File(root, e.getKey())); }
            else if (old.size != e.getValue().size || old.mtime != e.getValue().mtime) { changed++; rehashed += hash(new File(root, e.getKey())); }
        }
        for (String path : before.keySet()) if (!after.containsKey(path)) removed++;
        Probe.log("recon_diff", "changed=" + changed + " added=" + added + " removed=" + removed + " rehashedBytes=" + rehashed + " ms=" + ms(t));
        deleteTree(root);
    }

    private static String rel(int i) {
        return "d" + (i / 100) + "/s" + (i % 7) + "/f" + i + ".txt";
    }

    private static void walk(File dir, int prefix, Map<String, Entry> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) walk(child, prefix, out);
            else out.put(child.getPath().substring(prefix + 1), new Entry(child.length(), child.lastModified()));
        }
    }

    private static long hash(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) { digest.update(buffer, 0, n); total += n; }
            digest.digest();
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static long ms(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
