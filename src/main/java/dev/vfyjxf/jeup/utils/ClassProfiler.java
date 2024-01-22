package dev.vfyjxf.jeup.utils;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.vfyjxf.jeup.JustEnoughUpdate;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.MinecraftForge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public class ClassProfiler {

    private static final List<Analyzer> ANALYZERS = Lists.newArrayList(
            new Analyzer.Construct(Type.URL, "java/net/URL")
    );


    public static void runScan() {
        Path modPath = Launch.minecraftHome.toPath().resolve("mods");
        List<JarContainer> jars = new ArrayList<>();
        try {
            try (Stream<Path> stream = Files.walk(modPath)) {
                stream.forEach(path -> {
                    File file = path.toFile();
                    if (!file.isFile() || !file.getName().endsWith(".jar")) return;
                    try (ZipFile mod = new ZipFile(path.toFile())) {
                        JarContainer jar = scanJar(mod);
                        if (jar != null) jars.add(jar);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Thread t = new Thread(() -> {
            try (FileOutputStream fos = new FileOutputStream("logs/jeup-profiler.txt")) {
                OutputStreamWriter osw = new OutputStreamWriter(fos);
                osw.write(new GsonBuilder().setPrettyPrinting().create().toJson(jars));
                osw.flush();
            } catch (IOException e) {
                JustEnoughUpdate.LOG.error("Fail to write log file.", e);
            }
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    @Nullable
    private static JarContainer scanJar(ZipFile f) {
        JarContainer ret = new JarContainer();
        EnumMap<Type, Set<String>> data = new EnumMap<>(Type.class);
        for (Type value : Type.values()) {
            data.put(value, new TreeSet<>());
        }
        f.stream().forEach(entry -> {
            if (entry.getName().endsWith(".class")) {
                try (InputStream is = f.getInputStream(entry)) {
                    long size = entry.getSize() + 4;
                    if (size > Integer.MAX_VALUE) {
                        JustEnoughUpdate.LOG.info("Class file " + entry.getName()
                                + " in jar file " + f.getName() + " is too large, skip.");
                    } else {
                        scanClass(is, data);
                    }
                } catch (IOException e) {
                    JustEnoughUpdate.LOG.info("Fail to read file " + entry.getName()
                            + " in jar file " + f.getName() + ", skip.");
                }
            } else if (entry.getName().equals("mcmod.info")) {
                Gson gson = new Gson();
                try (InputStream is = f.getInputStream(entry)) {
                    try {
                        ret.mods = gson.fromJson(new InputStreamReader(is), ModContainer[].class);
                    } catch (Exception e) {
                        ret.mods = new ModContainer[]{gson.fromJson(new InputStreamReader(is), ModContainer.class)};
                    }
                } catch (Exception e) {
                    JustEnoughUpdate.LOG.info("Fail to read mod info in jar file " + f.getName() + ", skip.");
                }
            }
        });
        boolean nonEmpty = data.values().stream().anyMatch(it -> !it.isEmpty());
        if (nonEmpty) {
            ret.data = data;
            return ret;
        }
        return null;
    }

    private static void scanClass(InputStream is, EnumMap<Type, Set<String>> data) throws IOException {
        ClassNode classNode = new ClassNode();
        ClassReader classReader = new ClassReader(is);
        try {
            classReader.accept(classNode, 0);
        } catch (Exception e) {
            if (classNode.name != null) {
                JustEnoughUpdate.LOG.info("File decoding of class " + classNode.name + " failed. Try to continue.");
            } else {
                throw new IOException(e);
            }
        }

        classNode.methods.forEach(methodNode -> {
            Iterator<AbstractInsnNode> it = methodNode.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode node = it.next();
                ANALYZERS.forEach(analyzer -> analyzer.analyze(node, classNode, methodNode, data));
            }
        });
    }

    @SuppressWarnings("unused")
    public static class Report {
        String version = "@VERSION@";
        String mcversion = MinecraftForge.MC_VERSION;
        String date;
        JarContainer[] jars;

        {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
            date = sdf.format(new Date());
        }
    }

    private static class JarContainer {
        ModContainer[] mods;
        EnumMap<Type, Set<String>> data;
    }

    @SuppressWarnings("unused")
    private static class ModContainer {
        String modid;
        String name;
        String version;
        String mcversion;
        String url;
        String[] authorList;
    }


    private static abstract class Analyzer {
        Type type;

        public Analyzer(Type type) {
            this.type = type;
        }

        public void analyze(AbstractInsnNode insn, ClassNode clazz, MethodNode method,
                            EnumMap<Type, Set<String>> methods) {
            if (match(insn)) {
                methods.get(type).add(clazz.name.replace('/', '.') + ":" + method.name + method.desc);
            }
        }

        abstract boolean match(AbstractInsnNode insn);

        private static class Invoke extends Analyzer {
            String owner;
            String name;
            String desc;
            int op;
            int tag;

            public Invoke(Type type, boolean isStatic, String owner, String name, @Nullable String desc) {
                super(type);
                op = isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
                tag = isStatic ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL;
                this.owner = owner;
                this.name = name;
                this.desc = desc;
            }

            @Override
            boolean match(AbstractInsnNode insn) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode node = (MethodInsnNode) insn;
                    return node.getOpcode() == op && node.owner.equals(owner) &&
                            node.name.equals(name) && (desc == null || node.desc.equals(desc));
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode din = (InvokeDynamicInsnNode) insn;
                    if (din.bsmArgs.length != 3) return false;
                    Object arg = din.bsmArgs[1];
                    if (arg instanceof Handle) {
                        Handle handle = (Handle) arg;
                        return handle.getTag() == tag && handle.getOwner().equals(owner) &&
                                handle.getName().equals(name) && (desc == null || handle.getDesc().equals(desc));
                    }
                }
                return false;
            }
        }

        private static class Construct extends Analyzer {
            List<String> clazz;

            public Construct(Type type, String... clazz) {
                super(type);
                this.clazz = Arrays.asList(clazz);
            }

            @Override
            boolean match(AbstractInsnNode insn) {
                if (insn instanceof TypeInsnNode) {
                    TypeInsnNode tin = ((TypeInsnNode) insn);
                    return tin.getOpcode() == Opcodes.NEW && clazz.contains(tin.desc);
                } else return false;
            }
        }
    }

    enum Type {
        URL
    }

}
