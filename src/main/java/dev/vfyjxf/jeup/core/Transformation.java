package dev.vfyjxf.jeup.core;

import dev.vfyjxf.jeup.JustEnoughUpdate;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Optional;

public interface Transformation {

    static Optional<MethodNode> findMethod(ClassNode c, String name) {
        Optional<MethodNode> ret = c.methods.stream().filter(methodNode -> methodNode.name.equals(name))
                .findFirst();
        String s = ret.isPresent() ? "," : ", not";
        JustEnoughUpdate.LOG.info("Finding method " + name + " in class " + c.name + s + " found.");
        return ret;
    }

    static Optional<MethodNode> findMethod(ClassNode c, String name, String desc) {
        Optional<MethodNode> ret = c.methods.stream().filter(methodNode -> methodNode.name.equals(name))
                .filter(methodNode -> methodNode.desc.equals(desc)).findFirst();
        String s = ret.isPresent() ? "," : ", not";
        JustEnoughUpdate.LOG.info("Finding method " + name + desc + " in class " + c.name + s + " found.");
        return ret;
    }

    static boolean injectAfter(MethodNode methodNode, String constructor, AbstractInsnNode... nodes) {
        Iterator<AbstractInsnNode> i = methodNode.instructions.iterator();
        AbstractInsnNode target = null;
        while (i.hasNext()) {
            AbstractInsnNode node = i.next();
            if (node instanceof MethodInsnNode) {
                MethodInsnNode methodInsn = ((MethodInsnNode) node);
                if (methodInsn.owner.equals(constructor)) {
                    target = methodInsn;
                    break;
                }
            }
        }
        if (target != null) {
            InsnList list = new InsnList();
            for (AbstractInsnNode node : nodes) list.add(node);
            methodNode.instructions.insert(target, list);
            return true;
        }
        return false;
    }

    static boolean transformInvoke(
            MethodNode methodNode, String owner, String name, String newOwner, String newName,
            String id, boolean isInterface, int op, @Nullable String arg1, @Nullable String arg2) {
        JustEnoughUpdate.LOG.info("Transforming invoke of " + owner + "." + name +
                " to " + newOwner + "." + newName + " in method " + methodNode.name + ".");

        Iterator<AbstractInsnNode> iterator = methodNode.instructions.iterator();

        boolean ret = false;
        while (iterator.hasNext()) {
            AbstractInsnNode node = iterator.next();
            if (node instanceof MethodInsnNode && (node.getOpcode() == Opcodes.INVOKEVIRTUAL ||
                    node.getOpcode() == Opcodes.INVOKESPECIAL || node.getOpcode() == Opcodes.INVOKESTATIC)) {
                MethodInsnNode insnNode = ((MethodInsnNode) node);
                if (insnNode.owner.equals(owner) && insnNode.name.equals(name)) {
                    methodNode.instructions.set(insnNode, new MethodInsnNode(op, newOwner, newName, id, isInterface));
                    ret = true;
                }
            }
            if (node instanceof InvokeDynamicInsnNode && node.getOpcode() == Opcodes.INVOKEDYNAMIC
                    && arg1 != null && arg2 != null) {
                InvokeDynamicInsnNode insnNode = ((InvokeDynamicInsnNode) node);
                if (insnNode.bsmArgs[1] instanceof Handle) {
                    Handle h = ((Handle) insnNode.bsmArgs[1]);
                    if (h.getOwner().equals(owner) && h.getName().equals(name)) {
                        @SuppressWarnings("deprecation") Object[] args = {Type.getType(arg1),
                                new Handle(6, newOwner, newName, id), Type.getType(arg2)};
                        methodNode.instructions.set(insnNode,
                                new InvokeDynamicInsnNode(insnNode.name, insnNode.desc, insnNode.bsm, args));
                        ret = true;
                    }
                }
            }
        }
        return ret;
    }

    static boolean transformInvoke(
            MethodNode methodNode, String srcOwner, String srcName, String srcDesc,
            String dstOwner, String dstName, String dstDesc) {
        JustEnoughUpdate.LOG.info("Transforming invoke of " + srcOwner + "." + srcName +
                " to " + dstOwner + "." + dstName + " in method " + methodNode.name + ".");

        Iterator<AbstractInsnNode> iterator = methodNode.instructions.iterator();

        boolean ret = false;
        while (iterator.hasNext()) {
            AbstractInsnNode node = iterator.next();
            if (node instanceof MethodInsnNode && (node.getOpcode() == Opcodes.INVOKEVIRTUAL ||
                    node.getOpcode() == Opcodes.INVOKESPECIAL || node.getOpcode() == Opcodes.INVOKESTATIC)) {
                MethodInsnNode methodInsn = ((MethodInsnNode) node);
                if (methodInsn.owner.equals(srcOwner) && methodInsn.name.equals(srcName) && methodInsn.desc.equals(srcDesc)) {
                    methodInsn.setOpcode(Opcodes.INVOKESTATIC);
                    methodInsn.owner = dstOwner;
                    methodInsn.name = dstName;
                    methodInsn.desc = dstDesc;
                    ret = true;
                }
            } else if (node instanceof InvokeDynamicInsnNode && node.getOpcode() == Opcodes.INVOKEDYNAMIC) {
                InvokeDynamicInsnNode insnNode = ((InvokeDynamicInsnNode) node);
                if (insnNode.bsmArgs[1] instanceof Handle) {
                    Handle h = ((Handle) insnNode.bsmArgs[1]);
                    if (srcOwner.equals(h.getOwner()) && srcName.equals(h.getName()) && srcDesc.equals(h.getDesc()))
                        insnNode.bsmArgs[1] = new Handle(Opcodes.H_INVOKESTATIC, dstOwner, dstName, dstDesc, false);
                }
            }
        }
        return ret;
    }

    static void transformConstruct(MethodNode methodNode, String desc, String destNew) {
        JustEnoughUpdate.LOG.info("Transforming constructor of " + desc +
                " to " + destNew + " in method " + methodNode.name + ".");
        Iterator<AbstractInsnNode> i = methodNode.instructions.iterator();
        int cnt = 0;
        while (i.hasNext()) {
            AbstractInsnNode node = i.next();
            if (node.getOpcode() == Opcodes.NEW) {
                TypeInsnNode nodeNew = ((TypeInsnNode) node);
                if (nodeNew.desc.equals(desc)) {
                    // JechCore.LOG.info("Transforming new " + desc);
                    nodeNew.desc = destNew;
                    cnt++;
                }
            } else if (node.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode nodeNew = ((MethodInsnNode) node);
                if (nodeNew.owner.equals(desc)) {
                    // JechCore.LOG.info("Transforming constructor " + desc);
                    nodeNew.owner = destNew;
                }
            }
        }
        JustEnoughUpdate.LOG.info("Transformed " + cnt + " occurrences.");
    }

    static void transformHook(MethodNode methodNode, String owner, String name, String id) {
        Iterator<AbstractInsnNode> i = methodNode.instructions.iterator();
        while (i.hasNext()) {
            AbstractInsnNode node = i.next();
            if (node instanceof InsnNode && node.getOpcode() == Opcodes.RETURN) {
                methodNode.instructions.insertBefore(node, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        owner, name, id, false));
            }
        }
    }
}
