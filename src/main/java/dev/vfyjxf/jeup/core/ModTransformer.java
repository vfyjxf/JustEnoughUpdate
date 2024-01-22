package dev.vfyjxf.jeup.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class ModTransformer implements IClassTransformer {

    private final static Logger logger = LogManager.getLogger();
    private final Map<String, List<Target>> targets = new HashMap<>();

    public ModTransformer() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("targets.json")) {
            if (is == null) {
                logger.warn("Failed to load targets.json");
                return;
            }
            JsonObject targets = new JsonParser().parse(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Type value : Type.values()) {
                if (targets.has(value.name)) {
                    JsonObject typed = targets.getAsJsonObject(value.name);
                    for (Operation operation : Operation.values()) {
                        if (!typed.has(operation.key)) continue;
                        for (JsonElement element : typed.get(operation.key).getAsJsonArray()) {
                            String token = element.getAsString();
                            Target target = Target.of(value, token);
                            this.targets.computeIfAbsent(target.owner, k -> new ArrayList<>()).add(target);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load targets.json", e);
        }
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        String internalName = name.replace('.', '/');
        List<Target> current = targets.get(internalName);
        boolean success = false;
        if (current != null && !current.isEmpty()) {
            ClassNode classNode = new ClassNode();
            ClassReader classReader = new ClassReader(basicClass);
            classReader.accept(classNode, 0);
            for (MethodNode method : classNode.methods) {
                for (Target target : current) {
                    if (!target.match(method)) continue;
                    Transformation.injectAfter(method, target.type.owner, target.type.method, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "dev/vfyjxf/jeup/JustEnoughUpdate",
                            "redirectUrl",
                            "(Ljava/net/URL;)Ljava/net/URL;", false));
                    success = true;
                }
            }
            if (success) {
                ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                classNode.accept(classWriter);
                return classWriter.toByteArray();
            }
        }
        return basicClass;
    }

    private static class Target {
        private final String owner;
        private final String methodName;
        private final String descriptor;
        private final Type type;

        public static Target of(Type type, @Nonnull String token) {
            String[] split = token.split(":");
            if (split.length < 2)
                throw new IllegalArgumentException("Invalid token: " + token);
            String owner = split[0].replace(".", "/");
            String methodToken = split[1];
            String methodName = methodToken.substring(0, methodToken.indexOf("("));
            String descriptor = methodToken.substring(methodToken.indexOf("("));
            return new Target(owner, methodName, descriptor, type);
        }

        private Target(String owner, String methodName, String descriptor, Type type) {
            this.owner = owner;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.type = type;
        }

        public String methodName() {
            return methodName;
        }

        public String desc() {
            return descriptor;
        }

        public boolean match(MethodNode methodNode) {
            return methodNode.name.equals(methodName) && methodNode.desc.equals(descriptor);
        }

    }

    enum Type {
        URL("url", "java/net/URL", "<init>"),
        ;

        public final String name;
        public final String owner;
        public final String method;

        Type(String name, String owner, String method) {
            this.name = name;
            this.owner = owner;
            this.method = method;
        }
    }

    enum Operation {
        CONSTRUCT("constructor"),
        ;

        private final String key;

        Operation(String key) {
            this.key = key;
        }
    }

}
