package cn.jiajixin.nuwa.util

import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.IOUtils
import org.objectweb.asm.*

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Created by jixin.jia on 15/11/10.
 */
class NuwaProcessor {
    public
    static void processDirPath(String dirName, File hashFile, File classPath, File patchDir, Map map, HashSet<String> includePackage, HashSet<String> excludeClass) {
        File[] classfiles = classPath.listFiles()
        classfiles.each { inputFile ->
            def path = inputFile.absolutePath
            path = path.split("${dirName}/")[1]
            if (inputFile.isDirectory()) {
                processDirPath(dirName, hashFile, inputFile, patchDir, map, includePackage, excludeClass)
            } else if (path.endsWith(".jar")) {
                NuwaProcessor.processJar(hashFile, inputFile, patchDir, map, includePackage, excludeClass)
            } else if (path.endsWith(".class") && !path.contains("/R\$") && !path.endsWith("/R.class") && !path.endsWith("/BuildConfig.class")) {
                if (NuwaSetUtils.isIncluded(path, includePackage)) {
                    if (!NuwaSetUtils.isExcluded(path, excludeClass)) {
                        def bytes = NuwaProcessor.processClass(inputFile)
                        def hash = DigestUtils.shaHex(bytes)
                        hashFile.append(NuwaMapUtils.format(path, hash))
                        if (NuwaMapUtils.notSame(map, path, hash)) {
                            println("patch class:" + path)
                            NuwaFileUtils.copyBytesToFile(inputFile.bytes, NuwaFileUtils.touchFile(patchDir, path))
                        }
                    }
                }
            }

        }
    }

    public
    static processJar(File hashFile, File jarFile, File patchDir, Map map, HashSet<String> includePackage, HashSet<String> excludeClass) {
        if (jarFile) {
            System.out.println("nuwa refer hack class:${jarFile.absolutePath}");
            def optJar = new File(jarFile.getParent(), jarFile.name + ".opt")

            def file = new JarFile(jarFile);
            Enumeration enumeration = file.entries();
            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(optJar));

            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumeration.nextElement();
                String entryName = jarEntry.getName();
                ZipEntry zipEntry = new ZipEntry(entryName);

                InputStream inputStream = file.getInputStream(jarEntry);
                jarOutputStream.putNextEntry(zipEntry);

                if (shouldProcessClassInJar(entryName, includePackage, excludeClass)) {
//                    System.out.println("hack:"+entryName);
                    def bytes = referHackWhenInit(inputStream);
                    jarOutputStream.write(bytes);

                    def hash = DigestUtils.shaHex(bytes)
                    hashFile.append(NuwaMapUtils.format(entryName, hash))

                    if (NuwaMapUtils.notSame(map, entryName, hash)) {
                        NuwaFileUtils.copyBytesToFile(bytes, NuwaFileUtils.touchFile(patchDir, entryName))
                    }
                } else {
                    System.out.println("nhack:"+entryName);
                    jarOutputStream.write(IOUtils.toByteArray(inputStream));
                }
                jarOutputStream.closeEntry();
            }
            jarOutputStream.close();
            file.close();

            if (jarFile.exists()) {
                jarFile.delete()
            }
            optJar.renameTo(jarFile)
        }

    }

    //refer hack class when object init
    private static byte[] referHackWhenInit(InputStream inputStream) {
        /*
        ClassReader cr = new ClassReader(inputStream);
        ClassWriter cw = new ClassWriter(cr, 0);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM4, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {

                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                mv = new MethodVisitor(Opcodes.ASM4, mv) {
                    @Override
                    void visitInsn(int opcode) {
                        if ("<init>".equals(name) && opcode == Opcodes.RETURN) {
                            super.visitLdcInsn(Type.getType("Lcn/jiajixin/nuwa/Hack;"));
                        }
                        super.visitInsn(opcode);
                    }
                }
                return mv;
            }

        };
        cr.accept(cv, 0);
        return cw.toByteArray();
        */
        return referHackWhenInitV2(inputStream);
    }


    private static byte[] referHackWhenInitV2(InputStream inputStream) {
        ClassReader cr = new ClassReader(inputStream);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM5, cw){};
        cr.accept(cv, Opcodes.ASM5);

        MethodVisitor mv= cw.visitMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC,
                "__nuwa_hack",
                "()V",
                null,
                null);
        /*
        mv.visitLdcInsn(Type.getType("Lcn/jiajixin/nuwa/Hack;"));//Class var = Hack.class;
        */
        //public static void __nuwa_hack() { System.out.println(Hack.class); }
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn(Type.getType("Lcn/jiajixin/nuwa/Hack;"));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();


        return cw.toByteArray();
    }

//    private
//    static byte[] referHackByJavassistWhenInit(InputStream inputStream) {
//        ClassPool classPool = ClassPool.getDefault();
//        CtClass clazz = classPool.makeClass(inputStream)
//        CtConstructor ctConstructor = clazz.makeClassInitializer()
//        ctConstructor.insertAfter("if(Boolean.FALSE.booleanValue()){System.out.println(cn.jiajixin.nuwa.Hack.class);}")
//        def bytes = clazz.toBytecode()
//        clazz.defrost()
//        return bytes
//    }

    public static boolean shouldProcessPreDexJar(String path) {
        return path.endsWith("classes.jar") && !path.contains("com.android.support") && !path.contains("/android/m2repository");
    }

    private
    static boolean shouldProcessClassInJar(String entryName, HashSet<String> includePackage, HashSet<String> excludeClass) {
        return entryName.endsWith(".class") && !entryName.contains("/R.class") && !entryName.contains("/R\$") && !entryName.startsWith("cn/jiajixin/nuwa/") && NuwaSetUtils.isIncluded(entryName, includePackage) && !NuwaSetUtils.isExcluded(entryName, excludeClass) && !entryName.contains("android/support/")
    }

    public static byte[] processClass(File file) {
        def optClass = new File(file.getParent(), file.name + ".opt")

        FileInputStream inputStream = new FileInputStream(file);
        FileOutputStream outputStream = new FileOutputStream(optClass)

        def bytes = referHackWhenInit(inputStream);
        outputStream.write(bytes)
        inputStream.close()
        outputStream.close()
        if (file.exists()) {
            file.delete()
        }
        optClass.renameTo(file)
        return bytes
    }
}
